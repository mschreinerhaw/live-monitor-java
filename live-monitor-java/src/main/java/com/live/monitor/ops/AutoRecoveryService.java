package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Pure policy decision; the agent independently enforces stop/maintenance markers and limits. */
@Service
public class AutoRecoveryService {
    public String decision(Map<String,Object> app,Map<String,Object> state,boolean maintenance,long now) {
        if(maintenance||!"RUNNING".equals(text(app,"desired_state"))||flag(state,"manual_stop")||flag(state,"maintenance"))return "SUPPRESSED";
        if(flag(state,"healthy")&&!flag(state,"resource_abnormal"))return "HEALTHY";
        long failureSince=((Number)state.getOrDefault("failure_since",now)).longValue();
        if(now-failureSince<number(app,"failure_window_seconds",30)*1000L)return "DEBOUNCE";
        if(flag(state,"resource_abnormal"))return "RESOURCE_ALERT";
        if(flag(state,"circuit_open"))return "CIRCUIT_OPEN";
        long next=((Number)state.getOrDefault("next_recovery_at",0)).longValue();if(now<next)return "BACKOFF";
        if("running".equals(text(state,"status"))&&"starting".equals(text(state,"health")))return "DEBOUNCE";
        if(number(state,"recovery_attempts",0)>=number(app,"restart_limit",3))return "CIRCUIT_OPEN";
        if(number(state,"native_restarts",0)>=number(app,"restart_limit",3))return "NATIVE_LIMIT";
        if("unhealthy".equals(text(state,"health")))return flag(app,"recover_unhealthy")?"RECOVER":"ALERT_ONLY";
        if("exited".equals(text(state,"status"))&&number(state,"exit_code",0)!=0)
            return "no".equals(text(app,"restart_policy"))?"RECOVER":"DOCKER_OWNS_RESTART";
        return "ALERT_ONLY";
    }
}
