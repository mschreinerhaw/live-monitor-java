package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import com.live.monitor.mapper.HostMapper;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class HostManagementService {
    final OpsRepository repo;
    final HostMapper monitors;
    public HostManagementService(OpsRepository repo,HostMapper monitors){this.repo=repo;this.monitors=monitors;}
    public Map<String,Object> register(Map<String,Object> value) {
        identifier(text(value,"id"));identifier(text(value,"environment"));
        require(value.get("monitor_host_id") instanceof Number,"Select an existing monitoring host");
        require(monitors.findHost(((Number)value.get("monitor_host_id")).longValue())!=null,"Unknown monitoring host");
        require(text(value,"ssh_host_key").matches("(ssh-ed25519|ecdsa-sha2-nistp256|ssh-rsa) [A-Za-z0-9+/=]+"),"Pinned SSH host key required");
        for(Map<String,Object> h:repo.list("host"))
            require(text(h,"id").equals(text(value,"id")) || !h.get("monitor_host_id").equals(value.get("monitor_host_id")),"Monitoring host already registered");
        boolean maintenance=false;
        for(Map<String,Object> h:repo.list("host"))if(text(h,"id").equals(text(value,"id"))){
            require(h.get("monitor_host_id").equals(value.get("monitor_host_id"))&&text(h,"environment").equals(text(value,"environment")),"Registered host identity/environment cannot be reassigned");
            maintenance=flag(h,"maintenance");
        }
        return repo.save("host",map("id",text(value,"id"),"name",text(value,"name"),"environment",text(value,"environment"),
            "group",text(value,"group"),"monitor_host_id",value.get("monitor_host_id"),"ssh_host_key",text(value,"ssh_host_key"),
            "maintenance",maintenance,"revision",value.getOrDefault("revision",0)));
    }
}
