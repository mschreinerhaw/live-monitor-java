package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ContainerManagementService {
    final OpsRepository repo;
    final OpsAgentGateway agent;
    public ContainerManagementService(OpsRepository repo,OpsAgentGateway agent){this.repo=repo;this.agent=agent;}
    public Map<String,Object> scopedSnapshot(String host,Map<String,Object> source){
        Set<String> owned=new HashSet<>();
        for(Map<String,Object> app:repo.list("application"))if(host.equals(text(app,"host_id")))owned.add(text(app,"id"));
        Map<String,Object> result=new LinkedHashMap<>(source);List<Map<String,Object>> scoped=new ArrayList<>();
        for(Map<String,Object> container:rows(source,"containers"))if(owned.contains(text(container,"app_id")))scoped.add(container);
        result.put("containers",scoped);return result;
    }
    public Map<String,Object> operate(String action,Map<String,Object> app,String task) {
        Map<String,Object> host=repo.get("host",text(app,"host_id"));
        Map<String,Object> result=agent.call(host,action,map("app_id",text(app,"id"),"spec",app),task);
        if(Arrays.asList("start","stop","restart").contains(action)) {
            Map<String,Object> current=repo.get("application",text(app,"id"));
            current.put("desired_state","stop".equals(action)?"STOPPED":"RUNNING");repo.save("application",current);
        }
        return result;
    }
}
