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
    static Map<String,Object> stack(){return map("id","stack-a","name","Example release","host_id","host-a","environment","test","package_ref","approved-release","package_sha256",String.join("",Collections.nCopies(64,"a")),"desired_state","RUNNING");}
    @Test void samePhysicalServerCanHaveExplicitlySeparatedAgentEnvironments(){
        HostMapper monitors=mock(HostMapper.class);when(monitors.findHost(1L)).thenReturn(new com.live.monitor.entity.HostConfig());
        HostManagementService service=new HostManagementService(repo,monitors);
        Map<String,Object> input=map("id","host-a-staging","environment","staging","monitor_host_id",1L,"ssh_host_key","ssh-ed25519 AAAA");
        assertEquals("staging",service.register(input).get("environment"));
        input.put("id","duplicate");assertThrows(ResponseStatusException.class,()->service.register(input));
    }
    @Test void registeredEnvironmentCannotBeReassigned(){
        HostMapper monitors=mock(HostMapper.class);when(monitors.findHost(1L)).thenReturn(new com.live.monitor.entity.HostConfig());
        HostManagementService service=new HostManagementService(repo,monitors);
        Map<String,Object> input=repo.get("host","host-a");input.put("environment","changed");input.put("ssh_host_key","ssh-ed25519 AAAA");
        assertThrows(ResponseStatusException.class,()->service.register(input));
    }
    @Test void overviewScopesHostsAndDoesNotExposeEnvironmentSecrets(){
        repo.save("application",app());repo.save("grant",map("id","viewer-a","user_id","viewer","host_id","host-a","environment","test","actions","read","enabled",true));
        Map<String,Object> result=new OpsOverviewService(repo,auth).read("viewer",System.currentTimeMillis());
        assertEquals(1,rows(result,"hosts").size());assertEquals(1,rows(result,"applications").size());
        assertEquals("NOT_DEPLOYED",rows(result,"applications").get(0).get("status"));assertFalse(json(result).contains("environment_variables"));
    }
    @Test void overviewIncludesStacksAndDeduplicatesSharedDockerDependencies(){
        for(String id:Arrays.asList("stack-a","stack-c")){
            Map<String,Object>s=stack();s.put("id",id);s.put("active_version","v-"+id);repo.save("stack",s);
            repo.save("stack_version",map("id","v-"+id,"stack_id",id,"host_id","host-a","spec",s,"created_at",10L));
            repo.save("stack_snapshot",map("id",id,"stack_id",id,"host_id","host-a","observed_at",100000L,"data",map("services",Arrays.asList(map("service","web","container_id",id,"status","running","healthy",true)),"dependencies",Arrays.asList(map("name","database","container_id","shared","running",true,"healthy",true),map("name","external","container_id","external:license","running",true,"healthy",true)))));
        }
        Map<String,Object> result=new OpsOverviewService(repo,auth).read("admin",100001L);
        assertEquals(2,rows(result,"applications").size());assertEquals(3,rows(result,"containers").size());assertEquals(3L,object(result,"counts").get("running"));
        assertEquals(2,rows(result,"recent_deployments").size());
    }
    @Test void overviewDoesNotReportStaleContainersAsRunningHealthy(){
        Map<String,Object>s=stack();s.put("active_version","v");repo.save("stack",s);
        repo.save("stack_snapshot",map("id","stack-a","stack_id","stack-a","host_id","host-a","observed_at",1L,"data",map("services",Arrays.asList(map("service","web","status","running","healthy",true)))));
        Map<String,Object> result=new OpsOverviewService(repo,auth).read("admin",100000L);
        assertEquals("UNKNOWN",rows(result,"applications").get(0).get("status"));assertEquals(0L,object(result,"counts").get("running"));
    }
    @Test void manuallyStoppedStacksAreStillSampledWithoutAutoRecovery()throws Exception{
        Map<String,Object>s=stack();s.put("active_version","published");s.put("desired_state","STOPPED");repo.save("stack",s);
        repo.save("stack_snapshot",map("id","stack-a","stack_id","stack-a","host_id","host-a","observed_at",System.currentTimeMillis(),"data",map("recovery_candidates",Arrays.asList("web"))));
        HealthDetectionService detector=new HealthDetectionService(repo,tasks,new AutoRecoveryService(),new AlertEventService(repo));java.lang.reflect.Field started=HealthDetectionService.class.getDeclaredField("started");started.setAccessible(true);started.setLong(detector,0L);detector.tick();
        assertTrue(repo.tasks().stream().anyMatch(t->"stack_probe".equals(text(t,"action"))));assertFalse(repo.tasks().stream().anyMatch(t->"stack_recover".equals(text(t,"action"))));
    }
    @Test void stackConfigurationRejectsUnsealedPackages(){StackManagementService.validate(stack());Map<String,Object>s=stack();s.put("package_sha256","latest");assertThrows(ResponseStatusException.class,()->StackManagementService.validate(s));}
    @Test void stackStopPersistsIntentBeforeAsyncExecution(){Map<String,Object>s=repo.save("stack",stack());tasks.submit("admin","stack_stop",s,map(),"stack-stop-key");assertEquals("STOPPED",repo.get("stack","stack-a").get("desired_state"));}
    @Test void stackNeverForwardsBrowserYamlOrShell()throws Exception{
        Map<String,Object>s=repo.save("stack",stack());when(agent.call(anyMap(),eq("stack_plan"),anyMap(),anyString())).thenAnswer(call->{Map<String,Object> p=call.getArgument(2);assertFalse(p.containsKey("compose"));assertFalse(p.containsKey("command"));assertEquals("approved-release",p.get("package_ref"));return map("project","example");});
        String id=text(tasks.submit("admin","stack_plan",s,map("compose","untrusted yaml","command","rm -rf /"),"stack-plan-key"),"id");tasks.dispatch();await(id);assertEquals("SUCCEEDED",repo.task(id).get("status"));
    }
    @Test void stackProbeUsesPublishedPackageRatherThanEditedDraft(){
        Map<String,Object> published=stack();repo.save("stack_version",map("id","published-stack","spec",published));Map<String,Object>draft=stack();draft.put("active_version","published-stack");draft.put("package_ref","unpublished");repo.save("stack",draft);
        when(agent.call(anyMap(),eq("stack_probe"),anyMap(),anyString())).thenAnswer(call->{assertEquals("approved-release",((Map<?,?>)call.getArgument(2)).get("package_ref"));return map("services",new ArrayList<>(),"healthy",true);});new StackManagementService(repo,agent).execute("stack_probe",draft,map(),"test-probe");assertEquals(1,repo.list("stack_snapshot").size());
    }
    @Test void stackRecoveryCannotOverrideStoppedIntent(){Map<String,Object>s=stack();s.put("desired_state","STOPPED");repo.save("stack",s);assertThrows(ResponseStatusException.class,()->new StackManagementService(repo,agent).execute("stack_recover",s,map(),"recover"));verifyNoInteractions(agent);}
    @Test void stackDeploymentRequiresDataAcknowledgement(){Map<String,Object>s=repo.save("stack",stack());assertThrows(ResponseStatusException.class,()->new StackManagementService(repo,agent).execute("stack_deploy",s,map(),"deploy"));verifyNoInteractions(agent);}
    @Test void queuedReadOnlyLogsDoNotStarveHealthSampling()throws Exception{
        tasks.submit("admin","logs",app(),map(),"queued-log-reader");
        HealthDetectionService detector=new HealthDetectionService(repo,tasks,new AutoRecoveryService(),new AlertEventService(repo));
        java.lang.reflect.Field started=HealthDetectionService.class.getDeclaredField("started");started.setAccessible(true);started.setLong(detector,0L);detector.tick();
        assertTrue(repo.tasks().stream().anyMatch(t->"probe".equals(text(t,"action"))&&"host-a".equals(text(t,"host_id"))));
    }
    @Test void recentReadOnlyLogsDoNotInvalidateRecoveryEvidence()throws Exception{
        Map<String,Object>s=stack();s.put("active_version","published");repo.save("stack",s);
        repo.save("stack_snapshot",map("id","stack-a","stack_id","stack-a","host_id","host-a","observed_at",System.currentTimeMillis()-1000,"data",map("recovery_candidates",Arrays.asList("web"))));
        String id=text(tasks.submit("admin","stack_logs",s,map("service","web"),"completed-log-reader"),"id");repo.jdbc().update("UPDATE ops_task SET status='SUCCEEDED',updated_at=? WHERE id=?",System.currentTimeMillis(),id);
        HealthDetectionService detector=new HealthDetectionService(repo,tasks,new AutoRecoveryService(),new AlertEventService(repo));java.lang.reflect.Field started=HealthDetectionService.class.getDeclaredField("started");started.setAccessible(true);started.setLong(detector,0L);detector.tick();
        assertTrue(repo.tasks().stream().anyMatch(t->"stack_recover".equals(text(t,"action"))));
    }
    @Test void authorizedStackDeploymentRunsInMaintenanceButAutomaticRecoveryDoesNot()throws Exception{
        Map<String,Object>h=repo.get("host","host-a");h.put("maintenance",true);repo.save("host",h);Map<String,Object>s=repo.save("stack",stack());
        when(agent.call(anyMap(),eq("stack_deploy"),anyMap(),anyString())).thenReturn(map("services",new ArrayList<>(),"healthy",true));
        String id=text(tasks.submit("admin","stack_deploy",s,map("data_compatible",true),"stack-maintenance-deploy"),"id");tasks.dispatch();await(id);assertEquals("SUCCEEDED",repo.task(id).get("status"));
        id=text(tasks.submitSystem("stack_recover",repo.get("stack","stack-a"),map(),"stack-maintenance-recover"),"id");tasks.dispatch();await(id);assertEquals("FAILED",repo.task(id).get("status"));verify(agent,never()).call(anyMap(),eq("stack_recover"),anyMap(),anyString());
    }
    @Test void disabledGrantDoesNotRestoreLegacyInterfaceAccess(){
        Map<String,Object> g=repo.save("grant",map("id","isolated","user_id","viewer","ops_only",true,"enabled",true));
        assertTrue(auth.operationsOnly("viewer"));g.put("enabled",false);repo.save("grant",g);assertTrue(auth.operationsOnly("viewer"));
        assertFalse(auth.legacyInterfaceAllowed("viewer","/api/hosts"));
    }
    @Test void historicalGrantPreservesExistingMonitorUser(){repo.save("grant",map("id","old","user_id","operator","enabled",true));assertFalse(auth.operationsOnly("operator"));assertTrue(auth.legacyInterfaceAllowed("operator","/api/hosts"));}
    @Test void siteAdministratorCannotBeLockedOutByGrant(){repo.save("grant",map("id","admin-mode","user_id","admin","ops_only",true));assertFalse(auth.operationsOnly("admin"));}
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
    @Test void multibyteDocumentsCannotOverflowMysqlTextStorage(){Map<String,Object> doc=map("id","oversize","name",String.join("",Collections.nCopies(20000,"中")));assertThrows(ResponseStatusException.class,()->repo.save("host",doc));}
    @Test void deploymentByteLimitIncludesUnicodeEnvironmentValues(){Map<String,Object>a=app();a.put("environment_variables",map("DESCRIPTION",String.join("",Collections.nCopies(16000,"中"))));assertThrows(ResponseStatusException.class,()->policy.validate(a));}
    @Test void probeResultsCannotMixApplicationsAssignedToOtherLogicalHosts()throws Exception{
        repo.save("application",app());Map<String,Object> other=app();other.put("id","app-b");other.put("host_id","host-b");repo.save("application",other);
        when(agent.call(anyMap(),eq("probe"),anyMap(),anyString())).thenReturn(map("containers",Arrays.asList(map("app_id","app-a"),map("app_id","app-b"))));
        String id=text(tasks.submit("admin","probe",repo.get("host","host-a"),map(),"scoped-probe"),"id");tasks.dispatch();await(id);
        List<Map<String,Object>> containers=rows(object(repo.get("snapshot","host-a"),"data"),"containers");assertEquals(1,containers.size());assertEquals("app-a",containers.get(0).get("app_id"));
        assertEquals(1,rows(parse(text(repo.task(id),"result_json")),"containers").size());
    }
    @Test void historicalTaskResultsAreScopedWithoutMutatingTheirOriginalReceipt(){
        repo.save("application",app());String id=text(tasks.submit("admin","probe",repo.get("host","host-a"),map(),"history-probe"),"id");
        repo.jdbc().update("UPDATE ops_task SET status='SUCCEEDED',result_json=? WHERE id=?",json(map("containers",Arrays.asList(map("app_id","app-a"),map("app_id","another-host-app")))),id);
        assertEquals(1,rows(parse(text(tasks.publicTask(repo.task(id)),"result_json")),"containers").size());
        assertEquals(2,rows(parse(text(repo.task(id),"result_json")),"containers").size());
    }
    @Test void aCompletedRecoveryInvalidatesOlderSnapshotsBeforeAnotherDecision(){
        Map<String,Object>a=app();a.put("restart_policy","no");repo.save("application",a);
        repo.save("snapshot",map("id","host-a","host_id","host-a","observed_at",System.currentTimeMillis()-1000,"data",map("containers",Arrays.asList(map("app_id","app-a","failure_since",1,"status","exited","exit_code",137)))));
        String previous=text(tasks.submitSystem("recover",a,map(),"last-recovery"),"id");repo.jdbc().update("UPDATE ops_task SET status='SUCCEEDED',updated_at=? WHERE id=?",System.currentTimeMillis(),previous);
        HealthDetectionService health=new HealthDetectionService(repo,tasks,new AutoRecoveryService(),new AlertEventService(repo));
        org.springframework.test.util.ReflectionTestUtils.setField(health,"started",System.currentTimeMillis()-61000);health.tick();
        assertEquals(1,repo.jdbc().queryForObject("SELECT COUNT(*) FROM ops_task WHERE action='recover'",Integer.class));
        assertEquals(1,repo.jdbc().queryForObject("SELECT COUNT(*) FROM ops_task WHERE host_id='host-a' AND action='probe' AND status='QUEUED'",Integer.class));
    }
    @Test void stabilizationBackoffPrecedesCircuitEscalationAfterRestart(){AutoRecoveryService p=new AutoRecoveryService();assertEquals("BACKOFF",p.decision(app(),map("failure_since",1,"recovery_attempts",3,"next_recovery_at",200000,"health","starting"),false,100000));}
    @Test void dockerHealthStartupIsNotACompletedRecoveryFailure(){AutoRecoveryService p=new AutoRecoveryService();assertEquals("DEBOUNCE",p.decision(app(),map("failure_since",1,"status","running","health","starting","recovery_attempts",3),false,100000));}
    void await(String id) throws Exception {long until=System.currentTimeMillis()+5000;while(System.currentTimeMillis()<until&&!Arrays.asList("SUCCEEDED","FAILED").contains(text(repo.task(id),"status")))Thread.sleep(20);assertTrue(Arrays.asList("SUCCEEDED","FAILED").contains(text(repo.task(id),"status")));}
    MockHttpServletRequest request(String id){MockHttpServletRequest r=new MockHttpServletRequest();TUser u=new TUser();u.userId=id;r.getSession().setAttribute(AuthController.SESSION_USER,u);return r;}
}
