package com.live.monitor.ops;
import org.springframework.stereotype.Service;
@Service
public class OperationAuditService {
    private final OpsRepository repo;
    public OperationAuditService(OpsRepository repo){this.repo=repo;}
    public void record(String actor,String action,String resource,String task,String outcome){repo.audit(actor,action,resource,task,outcome);}
}
