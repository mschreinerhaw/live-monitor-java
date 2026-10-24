package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class BackupRestoreService {
    final OpsRepository repo;final OpsAgentGateway agent;
    public BackupRestoreService(OpsRepository repo,OpsAgentGateway agent){this.repo=repo;this.agent=agent;}
    public Map<String,Object> execute(String action,Map<String,Object> app,Map<String,Object> input,String task) {
        Map<String,Object> host=repo.get("host",text(app,"host_id"));
        String backupId="backup".equals(action)?UUID.randomUUID().toString():text(input,"backup_id");
        if(!"backup".equals(action)) {
            Map<String,Object> backup=repo.get("backup",backupId);
            require(text(backup,"app_id").equals(text(app,"id"))&&text(backup,"host_id").equals(text(app,"host_id")),"Backup is outside application/host scope");
            if("restore".equals(action))require(flag(backup,"restore_tested"),"Restore test must succeed before recovery");
        }
        Map<String,Object> result=agent.call(host,action,map("spec",app,"app_id",text(app,"id"),"backup_id",backupId,
            "target_suffix",text(input,"target_suffix")),task);
        if("backup".equals(action)) repo.save("backup",map("id",backupId,"host_id",text(app,"host_id"),"app_id",text(app,"id"),
            "environment",text(app,"environment"),"kind",text(app,"data_kind"),"created_at",System.currentTimeMillis(),
            "retention_days",number(app,"backup_retention_days",30),"verified",true,"restore_tested",false,"result",result));
        else if("restore_test".equals(action)) {Map<String,Object> backup=repo.get("backup",backupId);backup.put("restore_tested",true);repo.save("backup",backup);}
        return result;
    }
}
