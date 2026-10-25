package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import com.jcraft.jsch.*;
import com.live.monitor.entity.HostConfig;
import com.live.monitor.mapper.HostMapper;
import com.live.monitor.service.CryptoService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** SSH is only transport: stdin JSON invokes a fixed, allowlisted agent, never a user command. */
@Service
public class OpsAgentGateway {
    private final HostMapper hosts;
    private final CryptoService crypto;
    private final int timeout;
    private final OpsRepository repo;
    @Value("${live-monitor.ops.upload-directory:./data/image-uploads}") private String uploadDirectory="./data/image-uploads";
    public OpsAgentGateway(HostMapper hosts,CryptoService crypto,OpsRepository repo,
            @Value("${live-monitor.ops.agent-timeout-seconds:1200}") int timeout) {
        this.hosts=hosts;this.crypto=crypto;this.repo=repo;this.timeout=timeout;
    }
    public Map<String,Object> call(Map<String,Object> host,String action,Map<String,Object> payload,String task) {
        return call(host,action,payload,task,null);
    }
    public Map<String,Object> upload(Map<String,Object> host,Map<String,Object> input,String task) {
        java.nio.file.Path path=java.nio.file.Paths.get(text(input,"archive_path")).toAbsolutePath().normalize();
        java.nio.file.Path root=java.nio.file.Paths.get(uploadDirectory).toAbsolutePath().normalize();
        require(root.equals(path.getParent())&&!java.nio.file.Files.isSymbolicLink(path),"Invalid staged archive");
        try {return call(host,"import_image",map("archive_bytes",input.get("archive_bytes"),"archive_sha256",text(input,"archive_sha256")),task,path);}
        finally {try{java.nio.file.Files.deleteIfExists(path);}catch(IOException ignored){}}
    }
    public void discardUpload(Map<String,Object> input) {
        java.nio.file.Path path=java.nio.file.Paths.get(text(input,"archive_path")).toAbsolutePath().normalize();
        java.nio.file.Path root=java.nio.file.Paths.get(uploadDirectory).toAbsolutePath().normalize();
        if(root.equals(path.getParent())&&!java.nio.file.Files.isSymbolicLink(path))try{java.nio.file.Files.deleteIfExists(path);}catch(IOException ignored){}
    }
    private Map<String,Object> call(Map<String,Object> host,String action,Map<String,Object> payload,String task,java.nio.file.Path archive) {
        HostConfig credentials=hosts.findHost(((Number)host.get("monitor_host_id")).longValue());
        require(credentials!=null && Boolean.TRUE.equals(credentials.enabled),"Monitor host is missing or disabled");
        String key=text(host,"ssh_host_key");
        require(key.matches("(ssh-ed25519|ecdsa-sha2-nistp256|ssh-rsa) [A-Za-z0-9+/=]+"),"Pinned SSH host key required");
        Session session=null;ChannelExec channel=null;InputStream transmitted=null;
        try {
            JSch jsch=new JSch();int port=credentials.sshPort==null?22:credentials.sshPort;
            String hostName=port==22?credentials.ip:"["+credentials.ip+"]:"+port;
            jsch.setKnownHosts(new ByteArrayInputStream((hostName+" "+key+"\n").getBytes(StandardCharsets.UTF_8)));
            String privateKey=crypto.decrypt(credentials.privateKeyCipher);
            if(privateKey!=null)jsch.addIdentity("ops-"+credentials.id,privateKey.getBytes(StandardCharsets.UTF_8),null,null);
            session=jsch.getSession(credentials.sshUser,credentials.ip,port);
            String password=crypto.decrypt(credentials.sshPasswordCipher);if(password!=null)session.setPassword(password);
            session.setConfig("StrictHostKeyChecking","yes");session.setConfig("PreferredAuthentications","publickey,password");
            session.setServerAliveInterval(15000);session.setServerAliveCountMax(80);
            session.setTimeout(15000);session.connect(8000);
            channel=(ChannelExec)session.openChannel("exec");channel.setCommand("python3 /opt/live-monitor-agent/agent.py");
            byte[] input=(json(map("protocol",1,"action",action,"payload",payload,"task_id",task,
                "environment",text(host,"environment")))+(archive==null?"":"\n")).getBytes(StandardCharsets.UTF_8);
            require(input.length<=65536,"Agent input exceeds limit");
            transmitted=archive==null?new ByteArrayInputStream(input):new SequenceInputStream(new ByteArrayInputStream(input),java.nio.file.Files.newInputStream(archive));
            channel.setInputStream(transmitted);
            // stderr is deliberately not forwarded: external tooling may print credentials.
            channel.setErrStream(new OutputStream(){public void write(int ignored){}});
            InputStream output=channel.getInputStream();channel.connect(8000);
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[8192];
            int processed=0;Map<String,Object> result=null;
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(timeout);
            while(true) {
                while(output.available()>0){int count=output.read(chunk);if(count<0)break;
                    if(buffer.size()+count>524288)throw new IOException("Agent response limit");buffer.write(chunk,0,count);}
                byte[] accumulated=buffer.toByteArray();
                for(int i=processed;i<accumulated.length;i++)if(accumulated[i]=='\n'){
                    String line=new String(accumulated,processed,i-processed,StandardCharsets.UTF_8).trim();processed=i+1;
                    if(!line.isEmpty()){
                        Map<String,Object> event=parse(line);
                        if(event.containsKey("ok"))result=event;
                        else if(event.get("progress") instanceof Number && text(event,"message").matches("[A-Z_]{1,64}")){
                            repo.jdbc().update("UPDATE ops_task SET progress=?,message=?,updated_at=? WHERE id=? AND status='RUNNING'",
                                Math.min(95,Math.max(5,number(event,"progress",5))),text(event,"message"),System.currentTimeMillis(),task);
                            repo.taskEvent(task,number(event,"progress",5),text(event,"message"));
                        }
                    }
                }
                if(channel.isClosed() && output.available()==0)break;
                if(System.nanoTime()>deadline)throw new IOException("Agent deadline exceeded");
                Thread.sleep(25);
            }
            if(result==null)throw new IOException("Agent did not provide a terminal result");
            if(channel.getExitStatus()!=0 || !flag(result,"ok")) {
                String code=text(result,"code");
                throw new IllegalStateException("Agent operation failed ["+(code.matches("[A-Z_]{1,64}")?code:"UNKNOWN")+"]; inspect task diagnostics on the host");
            }
            return object(result,"data");
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Agent interrupted");}
        catch(IOException|JSchException e){throw new IllegalStateException("Agent connection/identity/timeout failure; verify pinned key, connectivity and agent installation");}
        finally{if(channel!=null)channel.disconnect();if(session!=null)session.disconnect();if(transmitted!=null)try{transmitted.close();}catch(IOException ignored){}}
    }
}
