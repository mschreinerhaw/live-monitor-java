package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class HealthDetectionService {
    final OpsRepository repo;final OpsTaskService tasks;final AutoRecoveryService recovery;final AlertEventService alerts;
    private final long started=System.currentTimeMillis();
    public HealthDetectionService(OpsRepository repo,OpsTaskService tasks,AutoRecoveryService recovery,AlertEventService alerts){this.repo=repo;this.tasks=tasks;this.recovery=recovery;this.alerts=alerts;}
    public void tick() {
        long now=System.currentTimeMillis();if(now-started<60000)return;
        for(Map<String,Object> host:repo.list("host"))try {
            String id=text(host,"id");
            boolean busy=repo.jdbc().queryForObject("SELECT COUNT(*) FROM ops_task WHERE host_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,id)>0;
            if(busy)continue;
            if(!flag(host,"maintenance"))tasks.submitSystem("probe",host,map(),"probe:"+id+":"+(now/30000));
            Map<String,Object> snapshot=null;for(Map<String,Object> s:repo.list("snapshot"))if(id.equals(text(s,"host_id")))snapshot=s;
            if(snapshot==null||now-((Number)snapshot.get("observed_at")).longValue()>90000)continue;
            for(Map<String,Object> state:rows(object(snapshot,"data"),"containers")) {
                String appId=text(state,"app_id");Map<String,Object> app;
                try{app=repo.get("application",appId);}catch(Exception missing){continue;}
                if(!id.equals(text(app,"host_id")))continue;
                Long changed=repo.jdbc().queryForObject("SELECT COALESCE(MAX(updated_at),0) FROM ops_task WHERE host_id=? AND resource_id=? AND action IN ('deploy','rollback','start','stop','restart','recover','circuit_break') AND status IN ('SUCCEEDED','FAILED','INTERRUPTED')",Long.class,id,appId);
                // A completed runtime mutation invalidates every earlier sample, even after a failed request.
                // Wait for the probe already queued above instead of issuing recovery from stale evidence.
                if(changed!=null&&changed>=((Number)snapshot.get("observed_at")).longValue())continue;
                if(!text(app,"active_version").isEmpty()){
                    Map<String,Object> version=repo.get("version",text(app,"active_version"));
                    if(((Number)snapshot.get("observed_at")).longValue()<((Number)version.get("created_at")).longValue())continue;
                    Map<String,Object> active=object(version,"spec");
                    active.put("desired_state",text(app,"desired_state"));app=active;
                }
                String decision=recovery.decision(app,state,flag(host,"maintenance"),now);
                if("HEALTHY".equals(decision)){alerts.recovered(appId);continue;}
                if(Arrays.asList("SUPPRESSED","DEBOUNCE","BACKOFF").contains(decision))continue;
                alerts.open(id,appId,decision,decision.contains("LIMIT")||"CIRCUIT_OPEN".equals(decision)?"CRITICAL":"WARNING");
                if("RECOVER".equals(decision))tasks.submitSystem("recover",app,map(),"recover:"+id+":"+appId+":"+(now/30000));
                if("NATIVE_LIMIT".equals(decision))tasks.submitSystem("circuit_break",app,map(),"circuit:"+id+":"+appId+":"+(now/30000));
            }
            for(Map<String,Object> app:repo.list("application"))if(id.equals(text(app,"host_id"))&&!flag(host,"maintenance")){
                boolean present=rows(object(snapshot,"data"),"containers").stream().anyMatch(c->text(app,"id").equals(text(c,"app_id")));
                if("RUNNING".equals(text(app,"desired_state"))&&!present&&!text(app,"active_version").isEmpty()){
                    long deployed=((Number)repo.get("version",text(app,"active_version")).get("created_at")).longValue();
                    if(((Number)snapshot.get("observed_at")).longValue()>=deployed && now-deployed>number(app,"failure_window_seconds",30)*1000L)
                        alerts.open(id,text(app,"id"),"CONTAINER_MISSING","CRITICAL");
                }
                int hours=number(app,"backup_interval_hours",0);
                if(hours>0 && !text(app,"active_version").isEmpty())
                    tasks.submitSystem("backup",app,map(),"scheduled-backup:"+id+":"+text(app,"id")+":"+(now/(hours*3600000L)));
            }
        }catch(Exception offline){/* One host/record never prevents checking the next host. Failures are durable task alerts. */}
    }
}
