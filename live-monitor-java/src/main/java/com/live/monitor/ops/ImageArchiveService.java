package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Staged files never become user-selectable paths or shell commands. */
@Service
public class ImageArchiveService {
    private final Path root;
    private final long maxBytes;
    private final OpsTaskService tasks;
    @Value("${live-monitor.ops.max-install-archive-bytes:34359738368}") private long installMaxBytes=34359738368L;
    public ImageArchiveService(OpsTaskService tasks,
            @Value("${live-monitor.ops.upload-directory:./data/image-uploads}") String directory,
            @Value("${live-monitor.ops.max-image-archive-bytes:4294967296}") long maxBytes) {
        this.tasks=tasks;this.root=Paths.get(directory).toAbsolutePath().normalize();this.maxBytes=maxBytes;
    }
    public synchronized Map<String,Object> submit(String actor,Map<String,Object> host,MultipartFile file,String key) throws IOException {
        return submit(actor,host,file,key,"import_image");
    }
    public synchronized Map<String,Object> submit(String actor,Map<String,Object> host,MultipartFile file,String key,String action) throws IOException {
        require(Arrays.asList("import_image","install_upload").contains(action),"Invalid archive action");
        long limit="install_upload".equals(action)?installMaxBytes:maxBytes;
        require(file.getSize()>0&&file.getSize()<=limit,"Archive exceeds configured size limit");
        String name=Objects.toString(file.getOriginalFilename(),"");
        require(name.toLowerCase(Locale.ROOT).matches(".*\\.(tar|tar\\.gz|tgz)"),"Upload a docker save .tar, .tar.gz or .tgz archive");
        Files.createDirectories(root);
        require(Files.getFileStore(root).getUsableSpace()>file.getSize()+268435456L,"Insufficient upload staging disk space");
        try(java.util.stream.Stream<Path> files=Files.list(root)) {require(files.count()<24,"Upload queue full; wait for imports to finish");}
        Path staged=Files.createTempFile(root,"image-",".archive");
        boolean submitted=false;
        try {
            try {Files.setPosixFilePermissions(staged,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}
            catch(UnsupportedOperationException ignored){/* Windows inherits the protected data directory ACL. */}
            MessageDigest digest;
            try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            long copied=0;byte[] block=new byte[1024*1024];
            try(InputStream in=file.getInputStream();OutputStream out=Files.newOutputStream(staged)) {
                int n;while((n=in.read(block))!=-1){copied+=n;require(copied<=limit,"Archive exceeds configured size limit");out.write(block,0,n);digest.update(block,0,n);}
            }
            require(copied==file.getSize(),"Incomplete image upload");
            StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format("%02x",b));
            Map<String,Object> input=map("archive_path",staged.toString(),"archive_bytes",copied,"archive_sha256",sha.toString());
            if("install_upload".equals(action))input.put("installation_id","install-"+OpsTaskService.hash(actor+":"+text(host,"id")+":"+key).substring(0,20));
            Map<String,Object> task=tasks.submit(actor,action,host,input,key);
            // An idempotent repeat may return an existing task referencing another staged file.
            submitted=staged.toString().equals(text(object(parse(text(task,"request_json")),"input"),"archive_path"));
            return tasks.publicTask(task);
        } finally {if(!submitted)Files.deleteIfExists(staged);}
    }
}
