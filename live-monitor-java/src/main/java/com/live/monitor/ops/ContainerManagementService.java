package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ContainerManagementService {
    final OpsRepository repo;
    final OpsAgentGateway agent;
    public ContainerManagementService(OpsRepository repo,OpsAgentGateway agent){this.repo=repo;this.agent=agent;}
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
