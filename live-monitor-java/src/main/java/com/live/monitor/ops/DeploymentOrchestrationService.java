package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DeploymentOrchestrationService {
    final OpsRepository repo; final OpsAgentGateway agent; final DeploymentPolicy policy;
    public DeploymentOrchestrationService(OpsRepository repo,OpsAgentGateway agent,DeploymentPolicy policy){this.repo=repo;this.agent=agent;this.policy=policy;}
    public Map<String,Object> execute(String action,Map<String,Object> app,Map<String,Object> input,String task) {
        policy.validate(app);Map<String,Object> host=repo.get("host",text(app,"host_id"));
        require(!flag(host,"maintenance"),"Host is under maintenance");
        Map<String,Object> spec=new LinkedHashMap<>(app);
        if("rollback".equals(action)) {
            Map<String,Object> version=repo.get("version",text(input,"version_id"));
            require(text(app,"id").equals(text(version,"app_id")),"Version belongs to another application");
            require("stateless".equals(text(app,"data_kind")) || flag(input,"data_compatible"),"Explicit data compatibility confirmation required");
            spec=object(version,"spec");policy.validate(spec);
        }
        // Approved registry metadata is only a reference; the host-local allowlist remains authoritative.
        String registry=text(spec,"image").split("/")[0];
        Map<String,Object> registration=text(spec,"image").matches("sha256:[a-f0-9]{64}")?map():repo.list("registry").stream().filter(r->registry.equals(text(r,"address"))).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Image registry has not been approved"));
        Map<String,Object> result=agent.call(host,action,map("spec",spec,"app_id",text(app,"id"),
            "credential_ref",text(registration,"credential_ref"),"data_compatible",flag(input,"data_compatible")),task);
        if(!"validate".equals(action)) {
            String versionId=UUID.randomUUID().toString();
            repo.save("version",map("id",versionId,"app_id",text(app,"id"),"host_id",text(app,"host_id"),
                "environment",text(app,"environment"),"spec",spec,"task_id",task,"created_at",System.currentTimeMillis()));
            Map<String,Object> current=repo.get("application",text(app,"id"));
            current.put("active_version",versionId);current.put("active_image",text(spec,"image"));
            current.put("desired_state","RUNNING");repo.save("application",current);
        }
        return result;
    }
}
