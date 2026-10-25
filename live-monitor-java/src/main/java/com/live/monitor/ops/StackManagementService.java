package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import java.util.*;

/** Deploy sealed host-approved application stacks without accepting shell/YAML from a browser. */
public class StackManagementService {
    public static final Set<String> ACTIONS=new LinkedHashSet<>(Arrays.asList("stack_plan","stack_deploy","stack_rollback","stack_probe","stack_logs","stack_backup","stack_start","stack_stop","stack_service_start","stack_service_stop","stack_service_restart","stack_recover"));
    private final OpsRepository repo;private final OpsAgentGateway agent;
    public StackManagementService(OpsRepository repo,OpsAgentGateway agent){this.repo=repo;this.agent=agent;}
    public static void validate(Map<String,Object> value){
        identifier(text(value,"id"));identifier(text(value,"host_id"));identifier(text(value,"environment"));identifier(text(value,"package_ref"));
        require(text(value,"package_sha256").matches("[a-f0-9]{64}"),"Approved package SHA256 required");
        require(!text(value,"name").isEmpty()&&text(value,"name").length()<=120,"Stack name required (up to 120 characters)");
    }
    public Map<String,Object> execute(String action,Map<String,Object> resource,Map<String,Object> input,String task){
        require(ACTIONS.contains(action),"Unsupported stack action");
        Map<String,Object> current=repo.get("stack",text(resource,"id"));Map<String,Object> spec=resource;
        if(!Arrays.asList("stack_deploy","stack_plan").contains(action)&&!text(current,"active_version").isEmpty())
            spec=object(repo.get("stack_version",text(current,"active_version")),"spec");
        validate(spec);
        if("stack_recover".equals(action))require("RUNNING".equals(text(current,"desired_state")),"Stack recovery suppressed by manual stop");
        if(Arrays.asList("stack_deploy","stack_rollback","stack_backup").contains(action))require(flag(input,"data_compatible"),"Explicit data compatibility and backup acknowledgement required");
        Map<String,Object> payload=map("stack_id",text(spec,"id"),"package_ref",text(spec,"package_ref"),"package_sha256",text(spec,"package_sha256"),"data_compatible",flag(input,"data_compatible"));
        if(action.startsWith("stack_service_")||"stack_logs".equals(action)){identifier(text(input,"service"));payload.put("service",text(input,"service"));}
        Map<String,Object> host=repo.get("host",text(spec,"host_id"));
        Map<String,Object> result=agent.call(host,action,payload,task);
        if(Arrays.asList("stack_deploy","stack_rollback").contains(action)){
            String id=UUID.randomUUID().toString();
            repo.save("stack_version",map("id",id,"stack_id",text(spec,"id"),"host_id",text(spec,"host_id"),"environment",text(spec,"environment"),"spec",spec,"action",action,"task_id",task,"created_at",System.currentTimeMillis()));
            current=repo.get("stack",text(spec,"id"));current.put("active_version",id);current.put("desired_state","RUNNING");repo.save("stack",current);
        }else if(Arrays.asList("stack_start","stack_service_start","stack_service_restart").contains(action)){current=repo.get("stack",text(spec,"id"));current.put("desired_state","RUNNING");repo.save("stack",current);}
        if(result.containsKey("services")){
            Map<String,Object> snapshot=map("id",text(spec,"id"),"stack_id",text(spec,"id"),"host_id",text(spec,"host_id"),"environment",text(spec,"environment"),"observed_at",System.currentTimeMillis(),"data",result);
            for(Map<String,Object> old:repo.list("stack_snapshot"))if(text(old,"id").equals(text(spec,"id")))snapshot.put("revision",old.get("revision"));
            repo.save("stack_snapshot",snapshot);
            AlertEventService alerts=new AlertEventService(repo);
            if(!flag(host,"maintenance")&&!flag(result,"healthy")&&"RUNNING".equals(text(repo.get("stack",text(spec,"id")),"desired_state")))alerts.open(text(spec,"host_id"),text(spec,"id"),"STACK_UNHEALTHY","ERROR");
            else if(flag(result,"healthy"))alerts.recovered(text(spec,"id"));
            if(result.get("circuits") instanceof List&&!((List<?>)result.get("circuits")).isEmpty())alerts.open(text(spec,"host_id"),text(spec,"id"),"STACK_RECOVERY_EXHAUSTED","CRITICAL");
        }
        return result;
    }
}
