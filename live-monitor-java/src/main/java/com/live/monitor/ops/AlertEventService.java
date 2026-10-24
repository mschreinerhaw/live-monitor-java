package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AlertEventService {
    public static final List<String> CODES=Arrays.asList("*","HOST_OFFLINE","TASK_FAILED","RESOURCE_ALERT","CONTAINER_MISSING","CIRCUIT_OPEN","NATIVE_LIMIT","ALERT_ONLY","DOCKER_OWNS_RESTART","RECOVER","STACK_UNHEALTHY","STACK_RECOVERY_EXHAUSTED");
    final OpsRepository repo;
    public AlertEventService(OpsRepository repo){this.repo=repo;}
    public synchronized void open(String host,String app,String code,String severity) {
        Map<String,Object> selected=null;int priority=-1;
        for(Map<String,Object> rule:repo.list("alert_rule")){
            if(!host.equals(text(rule,"host_id"))||(!text(rule,"app_id").isEmpty()&&!app.equals(text(rule,"app_id")))||(!"*".equals(text(rule,"code"))&&!code.equals(text(rule,"code"))))continue;
            int score=(text(rule,"app_id").isEmpty()?0:2)+("*".equals(text(rule,"code"))?0:1);
            if(score>priority){selected=rule;priority=score;}
        }
        if(selected!=null){if(Boolean.FALSE.equals(selected.get("enabled")))return;severity=text(selected,"severity");}
        String id=UUID.nameUUIDFromBytes((host+":"+app+":"+code).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        Map<String,Object> previous=null;
        for(Map<String,Object> a:repo.list("alert"))if(id.equals(text(a,"id")))previous=a;
        if(previous!=null && "OPEN".equals(text(previous,"status")))return;
        Map<String,Object> value=map("id",id,"host_id",host,"app_id",app,"code",code,"severity",severity,
            "status","OPEN","created_at",System.currentTimeMillis(),"revision",previous==null?0:previous.get("revision"));
        value.put("environment",text(repo.get("host",host),"environment"));
        repo.save("alert",value);
        repo.audit("system",code,app,"","ALERT");
    }
    public synchronized void recovered(String app) {
        for(Map<String,Object> alert:repo.list("alert"))if(app.equals(text(alert,"app_id")) && !"TASK_FAILED".equals(text(alert,"code")) && !"RECOVERED".equals(text(alert,"status"))){
            alert.put("status","RECOVERED");alert.put("recovered_at",System.currentTimeMillis());repo.save("alert",alert);
            repo.audit("system","RECOVERED",app,"","RECOVERED");
        }
    }
}
