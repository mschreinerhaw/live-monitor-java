package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OpsTaskService {
    @org.springframework.beans.factory.annotation.Autowired private InstallationService installations;
    private final OpsRepository repo;private final OpsAgentGateway agent;
    private final DeploymentOrchestrationService deployments;private final ContainerManagementService containers;
    private final BackupRestoreService backups;private final AlertEventService alerts;
    private final OpsAuthorization authorization;
    private final StackManagementService stacks;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(6,6,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(24),r->{Thread t=new Thread(r,"container-ops");t.setDaemon(true);return t;});
    private final Map<SseEmitter,String[]> subscribers=new ConcurrentHashMap<>();
    public OpsTaskService(OpsRepository repo,OpsAgentGateway agent,DeploymentOrchestrationService deployments,
            ContainerManagementService containers,BackupRestoreService backups,AlertEventService alerts,OpsAuthorization authorization){
        this.repo=repo;this.agent=agent;this.deployments=deployments;this.containers=containers;this.backups=backups;this.alerts=alerts;
        this.authorization=authorization;
        this.stacks=new StackManagementService(repo,agent);
    }
    public Map<String,Object> submit(String actor,String action,Map<String,Object> resource,Map<String,Object> input,String key) {
        return submit(actor,action,resource,input,key,false);
    }
    public Map<String,Object> submitSystem(String action,Map<String,Object> resource,Map<String,Object> input,String key){return submit("system",action,resource,input,key,true);}
    private Map<String,Object> submit(String actor,String action,Map<String,Object> resource,Map<String,Object> input,String key,boolean internal) {
        require(key.matches("[a-zA-Z0-9_.:-]{8,128}"),"An idempotency key of 8..128 safe characters is required");
        String resourceId=text(resource,"id"),hostId=Arrays.asList("install_upload","import_image","probe","images","registry_catalog","resolve_image","pull","cleanup","maintenance_on","maintenance_off").contains(action)?resourceId:text(resource,"host_id");
        String env=text(resource,"environment");
        Map<String,Object> request=map("resource",resource,"input",input,"internal",internal);String encoded=json(request);
        require(utf8Length(encoded)<48000,"Task request exceeds UTF-8 byte limit");
        Map<String,Object> fingerprint=new LinkedHashMap<>(resource);
        for(String field:Arrays.asList("revision","desired_state","active_version","active_image"))fingerprint.remove(field);
        Map<String,Object> hashedInput=new LinkedHashMap<>(input);if(Arrays.asList("import_image","install_upload").contains(action))hashedInput.remove("archive_path");hashedInput.remove("encrypted_parameters");
        String hash=hash(json(map("action",action,"resource",fingerprint,"input",hashedInput)));
        List<Map<String,Object>> existing=repo.jdbc().queryForList("SELECT * FROM ops_task WHERE actor=? AND idempotency_key=?",actor,key);
        if(!existing.isEmpty()){require(hash.equals(text(existing.get(0),"request_hash")),"Idempotency key reused with different request");return existing.get(0);}
        long now=System.currentTimeMillis();String id=UUID.randomUUID().toString();
        try {
            repo.transaction().execute(status->{
                repo.jdbc().update("INSERT INTO ops_task(id,actor,idempotency_key,request_hash,host_id,environment,action,resource_id,status,progress,request_json,result_json,message,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,0,?,'{}','QUEUED',?,?)",
                    id,actor,key,hash,hostId,env,action,resourceId,"QUEUED",encoded,now,now);
                if("stop".equals(action)){
                    Map<String,Object> app=repo.get("application",resourceId);
                    if(!"STOPPED".equals(text(app,"desired_state"))){app.put("desired_state","STOPPED");repo.save("application",app);}
                }
                if("stack_stop".equals(action)){
                    Map<String,Object> stack=repo.get("stack",resourceId);stack.put("desired_state","STOPPED");repo.save("stack",stack);
                }
                repo.audit(actor,action,resourceId,id,"ACCEPTED");return null;
            });
        }catch(DuplicateKeyException e){return submit(actor,action,resource,input,key,internal);}
        return repo.task(id);
    }
    static String hash(String value) {
        try{byte[] data=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:data)s.append(String.format("%02x",b));return s.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public void dispatch() {
        // Bounded independent workers isolate slow/offline hosts from legacy monitoring.
        if(workers.getQueue().size()<12)
            for(Map<String,Object> task:repo.jdbc().queryForList("SELECT id FROM ops_task WHERE status='QUEUED' ORDER BY created_at LIMIT 6"))
                try{workers.execute(()->execute(text(task,"id")));}catch(RejectedExecutionException ignored){break;}
    }
    public void publishEvents(){
        for(Map.Entry<SseEmitter,String[]> s:subscribers.entrySet())try{
            Map<String,Object> t=publicTask(repo.task(s.getValue()[0]));
            authorization.check(s.getValue()[1],text(t,"host_id"),text(t,"environment"),"read");
            if("logs".equals(text(t,"action")))authorization.check(s.getValue()[1],text(t,"host_id"),text(t,"environment"),"logs");
            s.getKey().send(SseEmitter.event().name("task").data(t));
            if(Arrays.asList("SUCCEEDED","FAILED","INTERRUPTED").contains(text(t,"status"))){s.getKey().complete();subscribers.remove(s.getKey());}
        }catch(Exception e){subscribers.remove(s.getKey());s.getKey().complete();}
    }
    private void execute(String id) {
        Map<String,Object> task=repo.task(id);String host=text(task,"host_id");
        if(!repo.lock("host:"+host,id))return;
        try {
            if(repo.jdbc().update("UPDATE ops_task SET status='RUNNING',progress=5,message='CONNECTING',updated_at=? WHERE id=? AND status='QUEUED'",System.currentTimeMillis(),id)!=1)return;
            repo.taskEvent(id,5,"CONNECTING");
            String action=text(task,"action");Map<String,Object> request=parse(text(task,"request_json"));
            if(flag(request,"internal"))require(Arrays.asList("probe","recover","circuit_break","backup","stack_probe","stack_recover").contains(action),"Invalid system operation");
            else authorization.check(text(task,"actor"),host,text(task,"environment"),action);
            Map<String,Object> resource=object(request,"resource"),input=object(request,"input"),result;
            // Re-read desired state and host maintenance immediately before every privileged action.
            Map<String,Object> currentHost=repo.get("host",host);
            require(!flag(currentHost,"maintenance")||Arrays.asList("probe","images","logs","stop","stack_probe","stack_stop","stack_service_stop","maintenance_off","maintenance_on").contains(action)||(StackManagementService.ACTIONS.contains(action)&&!"stack_recover".equals(action)),"Host entered maintenance");
            if("install_upload".equals(action)){
                result=agent.upload(currentHost,input,id,action);Map<String,Object> installation=new LinkedHashMap<>(result);
                installation.putAll(map("id",text(result,"installation_id"),"host_id",host,"environment",text(currentHost,"environment")));repo.save("installer",installation);
            }
            else if(Arrays.asList("install_plan","install_apply").contains(action))result=installations.execute(agent,action,resource,input,id);
            else if("import_image".equals(action))result=agent.upload(currentHost,input,id);
            else if(StackManagementService.ACTIONS.contains(action))result=stacks.execute(action,resource,input,id);
            else if(Arrays.asList("deploy","validate","rollback").contains(action))result=deployments.execute(action,resource,input,id);
            else if(Arrays.asList("backup","restore","restore_test").contains(action))result=backups.execute(action,resource,input,id);
            else if(Arrays.asList("start","stop","restart","logs").contains(action))result=containers.operate(action,resource,id);
            else if("recover".equals(action)||"circuit_break".equals(action)) {
                Map<String,Object> current=repo.get("application",text(resource,"id"));
                require("RUNNING".equals(text(current,"desired_state")),"Recovery suppressed by manual stop");
                result=agent.call(currentHost,action,map("app_id",text(current,"id"),"spec",current),id);
            } else if(Arrays.asList("probe","images","registry_catalog","resolve_image","pull","cleanup","maintenance_on","maintenance_off").contains(action)) {
                result=agent.call(currentHost,action,input,id);
                if("probe".equals(action))result=containers.scopedSnapshot(host,result);
                if(action.startsWith("maintenance_")){currentHost.put("maintenance","maintenance_on".equals(action));repo.save("host",currentHost);}
                if("probe".equals(action))repo.save("snapshot",mergeRevision("snapshot",map("id",host,"host_id",host,
                    "environment",text(currentHost,"environment"),"observed_at",System.currentTimeMillis(),"data",result)));
                if("probe".equals(action))alerts.recovered(host);
            } else throw new IllegalArgumentException("Unknown operation");
            String encoded=json(result);require(utf8Length(encoded)<48000,"Task output exceeds persistence UTF-8 byte limit");
            repo.transaction().execute(status->{
                repo.jdbc().update("UPDATE ops_task SET status='SUCCEEDED',progress=100,message='COMPLETED',result_json=?,updated_at=? WHERE id=?",encoded,System.currentTimeMillis(),id);
                repo.audit(text(task,"actor"),action,text(task,"resource_id"),id,"SUCCEEDED");
                repo.taskEvent(id,100,"COMPLETED");return null;
            });
        }catch(Exception e){
            // Only our sanitized exception messages enter the task, never SSH stderr/configuration.
            String message=e instanceof IllegalStateException||e instanceof ResponseStatusException||e instanceof IllegalArgumentException?Objects.toString(e.getMessage(),"Operation failed"):"Operation failed; inspect host diagnostics";
            message=message.substring(0,Math.min(message.length(),1800));
            final String failureMessage=message;
            repo.transaction().execute(status->{
                repo.jdbc().update("UPDATE ops_task SET status='FAILED',message=?,updated_at=? WHERE id=?",failureMessage,System.currentTimeMillis(),id);
                repo.audit(text(task,"actor"),text(task,"action"),text(task,"resource_id"),id,"FAILED");
                repo.taskEvent(id,number(repo.task(id),"progress",5),"FAILED");
                alerts.open(host,text(task,"resource_id"),"probe".equals(text(task,"action"))?"HOST_OFFLINE":"TASK_FAILED","ERROR");return null;
            });
        }finally{
            if(Arrays.asList("import_image","install_upload").contains(text(task,"action")))agent.discardUpload(object(parse(text(task,"request_json")),"input"));
            repo.unlock("host:"+host,id);
        }
    }
    Map<String,Object> mergeRevision(String kind,Map<String,Object> value){
        for(Map<String,Object> old:repo.list(kind))if(text(old,"id").equals(text(value,"id")))value.put("revision",old.get("revision"));return value;
    }
    public Map<String,Object> publicTask(Map<String,Object> task){Map<String,Object> value=new LinkedHashMap<>(task);value.remove("request_json");value.remove("request_hash");
        if("probe".equals(text(task,"action"))&&!text(task,"result_json").isEmpty()&&!"{}".equals(text(task,"result_json")))
            value.put("result_json",json(containers.scopedSnapshot(text(task,"host_id"),parse(text(task,"result_json")))));
        value.put("events",repo.jdbc().queryForList("SELECT progress,message,created_at FROM ops_task_event WHERE task_id=? ORDER BY created_at LIMIT 100",text(task,"id")));return value;}
    public SseEmitter stream(String id,String actor){
        if(subscribers.size()>=100)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many event streams");
        SseEmitter s=new SseEmitter(1500000L);subscribers.put(s,new String[]{id,actor});s.onCompletion(()->subscribers.remove(s));s.onTimeout(()->subscribers.remove(s));return s;
    }
    public void markInterrupted(){
        // Never retry an unknown remote outcome. Agent task journal is the recovery authority.
        for(Map<String,Object> t:repo.jdbc().queryForList("SELECT * FROM ops_task WHERE status='RUNNING' AND updated_at<?",System.currentTimeMillis()-1500000L)){
            String id=text(t,"id");repo.jdbc().update("UPDATE ops_task SET status='INTERRUPTED',message='Reconcile agent task journal before retry',updated_at=? WHERE id=? AND status='RUNNING'",System.currentTimeMillis(),id);
            repo.unlock("host:"+text(t,"host_id"),id);repo.audit("system","INTERRUPTED",text(t,"resource_id"),id,"UNKNOWN_REMOTE_OUTCOME");
        }
    }
    @PreDestroy public void shutdown(){workers.shutdownNow();subscribers.keySet().forEach(SseEmitter::complete);}
}
