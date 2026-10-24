package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Validation is duplicated at the privileged agent boundary, where host-local policy wins. */
@Component
public class DeploymentPolicy {
    public void validate(Map<String,Object> app) {
        identifier(text(app,"id"));identifier(text(app,"host_id"));identifier(text(app,"environment"));
        String image=text(app,"image");
        require(image.matches("[a-zA-Z0-9][a-zA-Z0-9.:-]+/[a-zA-Z0-9_./-]+@sha256:[a-f0-9]{64}"),"Use an explicit registry/repository@sha256 digest");
        require(number(app,"memory_mb",0)>=16 && number(app,"memory_mb",0)<=1048576,"Memory limit required (16..1048576 MiB)");
        require(app.get("cpus") instanceof Number && ((Number)app.get("cpus")).doubleValue()>0 && ((Number)app.get("cpus")).doubleValue()<=256,"CPU limit required");
        require(Arrays.asList("no","on-failure","unless-stopped").contains(text(app,"restart_policy")),"Unsupported Docker restart policy");
        require(number(app,"restart_limit",3)>=1 && number(app,"restart_limit",3)<=20,"Restart limit must be 1..20");
        require(number(app,"failure_window_seconds",30)>=1&&number(app,"failure_window_seconds",30)<=3600,"Failure window must be 1..3600 seconds");
        require(number(app,"backup_interval_hours",0)>=0&&number(app,"backup_interval_hours",0)<=8760,"Invalid backup interval");
        require(number(app,"backup_retention_days",30)>=1&&number(app,"backup_retention_days",30)<=3650,"Invalid backup retention");
        require(Arrays.asList("stateless","volume","postgres").contains(text(app,"data_kind")),"Declare persistent data kind");
        require(text(app,"network").isEmpty()||text(app,"network").matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}"),"Invalid Docker network");
        require(!Arrays.asList("host","container","none").contains(text(app,"network")),"Isolated application network required");
        Map<String,Object> health=object(app,"health");
        require(health.get("command") instanceof List && !((List<?>)health.get("command")).isEmpty(),"Health check argv required");
        require(number(health,"interval_seconds",10)>=1 && number(health,"timeout_seconds",3)>=1,"Invalid health timings");
        require(number(app,"health_deadline_seconds",90)>=5 && number(app,"health_deadline_seconds",90)<=600,"Health deadline must be 5..600 seconds");
        for(Map.Entry<String,Object> env:object(app,"environment_variables").entrySet()) {
            require(env.getKey().matches("[A-Za-z_][A-Za-z0-9_]*"),"Invalid environment key");
            require(!env.getKey().toLowerCase(Locale.ROOT).matches(".*(password|secret|token|credential|private_key).*"),"Sensitive variables must use secret_refs");
        }
        for(Object secret:object(app,"secret_refs").values())require(secret instanceof String && ((String)secret).matches("[a-zA-Z0-9_.-]{1,64}"),"Secret must reference an agent-local file");
        Set<Integer> ports=new HashSet<>();
        for(Map<String,Object> p:rows(app,"ports")){
            int external=number(p,"host",0),internal=number(p,"container",0);
            require(external>=1024&&external<=65535&&internal>=1&&internal<=65535&&ports.add(external),"Invalid or duplicate port mapping");
            require(Arrays.asList("tcp","udp").contains(text(p,"protocol")),"Port protocol required");
        }
        for(Map<String,Object> m:rows(app,"mounts")) {
            require(Arrays.asList("volume","bind").contains(text(m,"type")),"Unsupported mount type");
            require(text(m,"target").startsWith("/")&&!text(m,"target").contains(".."),"Absolute mount destination required");
            require(!text(m,"target").contains("docker.sock"),"Docker socket mounts forbidden");
            if("volume".equals(text(m,"type")))identifier(text(m,"source"));
            else require(flag(m,"read_only"),"Bind mounts must be read-only configuration mounts");
        }
        require(json(app).length()<32000,"Deployment specification too large");
    }
}
