package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.live.monitor.mapper.HostMapper;
import com.live.monitor.mapper.UserMapper;
import com.live.monitor.entity.TUser;
import com.live.monitor.controller.AuthController;
import com.live.monitor.config.AuthInterceptor;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class ContainerOpsTest {
    OpsRepository repo; OpsAuthorization auth; OpsAgentGateway agent; OpsTaskService tasks;
    DeploymentPolicy policy=new DeploymentPolicy();
    @BeforeEach void setup(){
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=LEGACY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        repo=new OpsRepository(new JdbcTemplate(ds),new TransactionTemplate(new DataSourceTransactionManager(ds)));repo.migrate();
        UserMapper users=mock(UserMapper.class);for(String name:Arrays.asList("admin","viewer","operator")){TUser u=new TUser();u.userId=name;u.status=1;when(users.findByUserId(name)).thenReturn(u);}
        auth=new OpsAuthorization(repo,users);agent=mock(OpsAgentGateway.class);
        AlertEventService alerts=new AlertEventService(repo);
        tasks=new OpsTaskService(repo,agent,new DeploymentOrchestrationService(repo,agent,policy),new ContainerManagementService(repo,agent),new BackupRestoreService(repo,agent),alerts,auth);
        repo.save("host",map("id","host-a","environment","test","monitor_host_id",1,"maintenance",false));
        repo.save("host",map("id","host-b","environment","prod","monitor_host_id",2,"maintenance",false));
    }
    @AfterEach void stop(){tasks.shutdown();}
    static Map<String,Object> app(){return map("id","app-a","host_id","host-a","environment","test","image","docker.io/library/nginx@sha256:"+String.join("",Collections.nCopies(64,"a")),
        "cpus",1,"memory_mb",64,"restart_policy","on-failure","restart_limit",3,"health_deadline_seconds",30,
        "health",map("command",Arrays.asList("true")),"data_kind","stateless","desired_state","RUNNING");}
    @Test void immutableImageAndResourceValidation(){policy.validate(app());Map<String,Object> a=app();a.put("image","nginx:latest");assertThrows(ResponseStatusException.class,()->policy.validate(a));}
    @Test void secretsCannotBePlainEnvironment(){Map<String,Object>a=app();a.put("environment_variables",map("DB_PASSWORD","sensitive"));assertThrows(ResponseStatusException.class,()->policy.validate(a));}
    @Test void dockerSocketAndWritableBindRejected(){Map<String,Object>a=app();a.put("mounts",Arrays.asList(map("type","bind","source","/var/run/docker.sock","target","/var/run/docker.sock","read_only",false)));assertThrows(ResponseStatusException.class,()->policy.validate(a));}
    @Test void duplicatePortsRejected(){Map<String,Object>a=app();a.put("ports",Arrays.asList(map("host",8080,"container",80,"protocol","tcp"),map("host",8080,"container",81,"protocol","tcp")));assertThrows(ResponseStatusException.class,()->policy.validate(a));}
    @Test void optimisticRevisionProtectsConcurrentEdits(){Map<String,Object>a=repo.get("host","host-a"),b=new LinkedHashMap<>(a);a.put("name","first");repo.save("host",a);assertThrows(ResponseStatusException.class,()->repo.save("host",b));}
    @Test void resourceKindsHaveIndependentIdentity(){repo.save("snapshot",map("id","host-a","data",map()));assertEquals(1,repo.list("snapshot").size());assertEquals(2,repo.list("host").size());}
    @Test void sameHostOperationsAreSerializedDifferentHostsIndependent(){assertTrue(repo.lock("host:host-a","one"));assertFalse(repo.lock("host:host-a","two"));assertTrue(repo.lock("host:host-b","two"));repo.unlock("host:host-a","wrong");assertFalse(repo.lock("host:host-a","three"));repo.unlock("host:host-a","one");assertTrue(repo.lock("host:host-a","three"));}
    @Test void scopedGrantDoesNotAuthorizeAnotherHostOrEnvironment(){repo.save("grant",map("id","g","user_id","operator","host_id","host-a","environment","test","actions","read,start"));auth.check("operator","host-a","test","start");assertThrows(ResponseStatusException.class,()->auth.check("operator","host-b","test","start"));assertThrows(ResponseStatusException.class,()->auth.check("operator","host-a","prod","start"));assertThrows(ResponseStatusException.class,()->auth.check("operator","host-a","test","restore"));}
    @Test void embeddedAdminCannotOperate(){MockHttpServletRequest r=request("admin");r.getSession().setAttribute(AuthInterceptor.SESSION_EMBED_EXPIRES_AT,Long.MAX_VALUE);assertThrows(ResponseStatusException.class,()->auth.actor(r,true));}
    @Test void crossSiteFormCannotMutate(){MockHttpServletRequest r=request("admin");assertThrows(ResponseStatusException.class,()->auth.actor(r,true));r.addHeader("X-Ops-Request","1");assertEquals("admin",auth.actor(r,true));}
    @Test void confirmationBindsActionResourceAndEnvironment(){auth.confirm(map("confirmation","deploy:app-a:test"),"deploy","app-a","test");assertThrows(ResponseStatusException.class,()->auth.confirm(map("confirmation","deploy:app-a:test"),"restore","app-a","test"));}
    @Test void idempotencyReturnsSameTaskAndRejectsChangedPayload(){Map<String,Object>h=repo.get("host","host-a");Map<String,Object>a=tasks.submit("admin","probe",h,map(),"same-key-1");Map<String,Object>b=tasks.submit("admin","probe",h,map(),"same-key-1");assertEquals(a.get("id"),b.get("id"));assertThrows(ResponseStatusException.class,()->tasks.submit("admin","probe",h,map("different",true),"same-key-1"));assertEquals(1,repo.tasks().size());}
    @Test void acceptedTaskSurvivesServiceRestartBeforeDispatch(){tasks.submit("admin","probe",repo.get("host","host-a"),map(),"durable-key");assertEquals("QUEUED",repo.tasks().get(0).get("status"));repo.migrate();assertEquals(1,repo.tasks().size());}
    @Test void offlineHostProducesExplicitFailureAndAudit() throws Exception{
        when(agent.call(anyMap(),eq("probe"),anyMap(),anyString())).thenThrow(new IllegalStateException("Agent connection/identity/timeout failure"));
        String id=text(tasks.submit("admin","probe",repo.get("host","host-a"),map(),"offline-key"),"id");tasks.dispatch();await(id);
        assertEquals("FAILED",repo.task(id).get("status"));assertEquals(1,repo.list("alert").size());assertTrue(repo.jdbc().queryForObject("SELECT COUNT(*) FROM ops_audit WHERE outcome='FAILED'",Integer.class)>0);
    }
    @Test void successStoresSnapshotAndTaskResult() throws Exception{
        when(agent.call(anyMap(),eq("probe"),anyMap(),anyString())).thenReturn(map("docker_version","test","containers",new ArrayList<>()));
        String id=text(tasks.submit("admin","probe",repo.get("host","host-a"),map(),"success-key"),"id");tasks.dispatch();await(id);assertEquals("SUCCEEDED",repo.task(id).get("status"));assertEquals("test",object(repo.get("snapshot","host-a"),"data").get("docker_version"));
    }
    @Test void stoppedIntentSuppressesRecovery(){AutoRecoveryService p=new AutoRecoveryService();Map<String,Object>a=app();a.put("desired_state","STOPPED");assertEquals("SUPPRESSED",p.decision(a,map("status","exited","exit_code",1),false,100000));}
    @Test void agentManualStopAndMaintenanceSuppressRecovery(){AutoRecoveryService p=new AutoRecoveryService();assertEquals("SUPPRESSED",p.decision(app(),map("manual_stop",true),false,100000));assertEquals("SUPPRESSED",p.decision(app(),map(),true,100000));}
    @Test void debounceBackoffAndCircuitBreak(){AutoRecoveryService p=new AutoRecoveryService();Map<String,Object>a=app();a.put("recover_unhealthy",true);assertEquals("DEBOUNCE",p.decision(a,map("health","unhealthy","failure_since",99999),false,100000));assertEquals("CIRCUIT_OPEN",p.decision(a,map("failure_since",1,"recovery_attempts",3),false,100000));assertEquals("BACKOFF",p.decision(a,map("failure_since",1,"next_recovery_at",200000),false,100000));assertEquals("RECOVER",p.decision(a,map("failure_since",1,"health","unhealthy"),false,100000));}
    @Test void nativeRestartControllerIsNotCompetedWith(){AutoRecoveryService p=new AutoRecoveryService();assertEquals("DOCKER_OWNS_RESTART",p.decision(app(),map("status","exited","exit_code",1,"failure_since",1),false,100000));}
    @Test void restoreCannotSkipSuccessfulRestoreTest(){Map<String,Object>a=app();repo.save("backup",map("id","backup-a","app_id","app-a","host_id","host-a","restore_tested",false));BackupRestoreService b=new BackupRestoreService(repo,agent);assertThrows(ResponseStatusException.class,()->b.execute("restore",a,map("backup_id","backup-a"),"task"));verifyNoInteractions(agent);}
    @Test void stoppedIntentAndAcceptedTaskAreAtomicAndIdempotent(){Map<String,Object>a=repo.save("application",app());Map<String,Object> t=tasks.submit("admin","stop",a,map(),"stop-key-1");assertEquals("STOPPED",repo.get("application","app-a").get("desired_state"));assertEquals(t.get("id"),tasks.submit("admin","stop",repo.get("application","app-a"),map(),"stop-key-1").get("id"));}
    @Test void rejectedStopDoesNotChangeDesiredState(){Map<String,Object>a=repo.save("application",app());assertThrows(ResponseStatusException.class,()->tasks.submit("admin","stop",a,map(),"bad"));assertEquals("RUNNING",repo.get("application","app-a").get("desired_state"));}
    @Test void queuedOperationsRecheckRevokedPermissions()throws Exception{
        Map<String,Object>g=repo.save("grant",map("id","operator-grant","user_id","operator","host_id","host-a","environment","test","actions","read,start"));
        Map<String,Object>a=repo.save("application",app());String id=text(tasks.submit("operator","start",a,map(),"revoked-key"),"id");
        g.put("actions","read");repo.save("grant",g);tasks.dispatch();await(id);assertEquals("FAILED",repo.task(id).get("status"));verifyNoInteractions(agent);
    }
    @Test void interruptedRemoteOutcomesAreNotAutomaticallyRetried(){
        String id=text(tasks.submit("admin","probe",repo.get("host","host-a"),map(),"crash-key-1"),"id");
        repo.jdbc().update("UPDATE ops_task SET status='RUNNING',updated_at=? WHERE id=?",System.currentTimeMillis()-1600000L,id);
        repo.lock("host:host-a",id);tasks.markInterrupted();assertEquals("INTERRUPTED",repo.task(id).get("status"));assertTrue(repo.lock("host:host-a","new-owner"));verifyNoInteractions(agent);
    }
    @Test void resourceFaultsAlertWithoutBlindRestart(){AutoRecoveryService p=new AutoRecoveryService();assertEquals("RESOURCE_ALERT",p.decision(app(),map("healthy",true,"resource_abnormal",true,"failure_since",1),false,100000));}
    @Test void nativeRestartLimitEscalatesInsteadOfStartingCompetingController(){AutoRecoveryService p=new AutoRecoveryService();assertEquals("NATIVE_LIMIT",p.decision(app(),map("failure_since",1,"native_restarts",3,"status","restarting"),false,100000));}
    @Test void entireOperationsGrantCanBeRevokedWithoutDisablingMonitorUser(){Map<String,Object>g=repo.save("grant",map("id","revokable","user_id","operator","host_id","host-a","environment","test","actions","read,start","enabled",true));assertTrue(auth.allowed("operator","host-a","test","start"));g.put("enabled",false);repo.save("grant",g);assertFalse(auth.allowed("operator","host-a","test","start"));assertFalse(auth.allowed("operator","host-a","test","read"));}
    @Test void alertRulesRespectScopePriorityAndSeverity(){repo.save("alert_rule",map("id","host-default","host_id","host-a","app_id","","code","*","severity","WARNING","enabled",true));repo.save("alert_rule",map("id","app-specific","host_id","host-a","app_id","app-a","code","HOST_OFFLINE","severity","CRITICAL","enabled",true));new AlertEventService(repo).open("host-a","app-a","HOST_OFFLINE","ERROR");assertEquals("CRITICAL",repo.list("alert").get(0).get("severity"));}
    @Test void mutedRuleDoesNotDeleteExistingEventsOrAffectAnotherHost(){repo.save("alert_rule",map("id","muted","host_id","host-a","app_id","","code","*","severity","WARNING","enabled",false));AlertEventService events=new AlertEventService(repo);events.open("host-a","app-a","TASK_FAILED","ERROR");assertTrue(repo.list("alert").isEmpty());events.open("host-b","app-b","TASK_FAILED","ERROR");assertEquals(1,repo.list("alert").size());}
    void await(String id) throws Exception {long until=System.currentTimeMillis()+5000;while(System.currentTimeMillis()<until&&!Arrays.asList("SUCCEEDED","FAILED").contains(text(repo.task(id),"status")))Thread.sleep(20);assertTrue(Arrays.asList("SUCCEEDED","FAILED").contains(text(repo.task(id),"status")));}
    MockHttpServletRequest request(String id){MockHttpServletRequest r=new MockHttpServletRequest();TUser u=new TUser();u.userId=id;r.getSession().setAttribute(AuthController.SESSION_USER,u);return r;}
}
