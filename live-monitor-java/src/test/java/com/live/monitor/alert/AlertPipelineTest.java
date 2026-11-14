package com.live.monitor.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.entity.*;
import com.live.monitor.mapper.AlertMapper;
import com.live.monitor.store.RocksDbHistoryRepository;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class AlertPipelineTest {
    JdbcTemplate jdbc;
    AlertMapper mapper;
    AlertService alerts;
    AlertOutbox outbox;
    AlertDeliveryService delivery;
    TransactionTemplate tx;
    com.live.monitor.dolphin.DolphinStore dolphinStore;

    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=LEGACY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("ALTER TABLE monitor_check_event ADD COLUMN IF NOT EXISTS event_type VARCHAR(64)");
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(AlertMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(config);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AlertMapper.class);
        outbox = new AlertOutbox(jdbc, 5);
        delivery = mock(AlertDeliveryService.class);
        when(delivery.renderTemplate(any(), any(), any())).thenAnswer(call -> call.getArgument(2));
        alerts = new AlertService(mapper, mock(RocksDbHistoryRepository.class), delivery, new ObjectMapper());
        alerts.setOutbox(outbox);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.update("INSERT INTO alert_group(id,group_name,enabled) VALUES (2,'production',1)");
        jdbc.update("INSERT INTO alert_channel(id,channel_name,channel_type,enabled) VALUES(7,'broken','http',1),(8,'healthy','http',1)");
        jdbc.update("INSERT INTO group_channel_rel(group_id,channel_id) VALUES(2,7),(2,8)");
        dolphinStore=new com.live.monitor.dolphin.DolphinStore(jdbc,new ObjectMapper(),tx.getTransactionManager());dolphinStore.migrate();
    }

    MonitorService service(long id) {
        jdbc.update("INSERT INTO monitor_service(id,service_name,service_type) VALUES(?,?,'api')",id,"service-"+id);
        MonitorService service = new MonitorService(); service.id=id; service.serviceName="service-"+id;
        service.serviceType="api"; service.alertGroupId=2L; service.alertGroupEnabled=true;
        service.serviceConsecutiveFailures=2; service.serviceRecoverSuccesses=2; service.configJson="{}";
        return service;
    }

    void check(MonitorService service, String status, String at) {
        MonitorResult result = new MonitorResult(); result.serviceId=service.id; result.status=status;
        result.responseTimeMs=1; result.message="reason-"+status; result.checkedAt=at;
        tx.execute(transaction -> { alerts.publishCheckEvent(service,result); return null; });
    }

    com.live.monitor.dolphin.DolphinAlerts enterpriseAlerts(MonitorService service,List<Long> fallback) {
        com.live.monitor.service.DolphinSchedulerService api=mock(com.live.monitor.service.DolphinSchedulerService.class);
        Map<String,Object> config=new HashMap<>();config.put("enterprise_alerts_enabled",true);config.put("default_ops_group_ids",fallback);when(api.config(service)).thenReturn(config);when(api.sourceNow(service)).thenAnswer(call->com.live.monitor.util.MonitorTime.now());
        com.live.monitor.mapper.MonitorServiceMapper services=mock(com.live.monitor.mapper.MonitorServiceMapper.class);when(services.listServiceAlertGroups(any())).thenReturn(Collections.emptyList());
        return new com.live.monitor.dolphin.DolphinAlerts(dolphinStore,api,alerts,services,mapper,new ObjectMapper());
    }
    void task(long cluster,String project,String id,String state) {
        Map<String,Object> row=new HashMap<>();row.put("id",id);row.put("project_code",project);row.put("definition_code","800");row.put("task_code",id);row.put("state",state);row.put("etl_date","2026-11-13");row.put("retry_times",0);row.put("max_retry_times",0);
        dolphinStore.save(cluster,"task_instance",row);dolphinStore.success(cluster,"runtime:"+project,com.live.monitor.util.MonitorTime.nowText());dolphinStore.success(cluster,"runtime",com.live.monitor.util.MonitorTime.nowText());
    }
    @Test void historicalTimeoutsAreAggregatedUntilEveryMemberHasBeenObservedTerminal() throws Exception {
        MonitorService cluster=service(910);cluster.serviceType="dolphinscheduler";
        com.live.monitor.dolphin.DolphinAlerts engine=enterpriseAlerts(cluster,Arrays.asList(2L));
        task(910,"101","9001","RUNNING_EXECUTION");task(910,"101","9002","RUNNING_EXECUTION");
        for(Map<String,Object> row:dolphinStore.entities(910,"task_instance",null)){row.put("start_time","2020-01-01 00:00:00");dolphinStore.save(910,"task_instance",row);}
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");StringBuilder hex=new StringBuilder();for(byte b:digest.digest("TIMEOUT:task:9001".getBytes(java.nio.charset.StandardCharsets.UTF_8)))hex.append(String.format("%02x",b&255));String legacy=hex.toString();
        jdbc.update("INSERT INTO ds_incident(cluster_id,incident_key,project_code,category,message,group_ids,active,opened_at) VALUES(910,?,'101','TIMEOUT','previous single event','[2]',1,?)",legacy,com.live.monitor.util.MonitorTime.nowText());
        jdbc.update("INSERT INTO alert_state(service_id,alert_key,state,fail_count) VALUES(910,?,'ALERTING',1)","group:2:ds:"+legacy+":availability");
        engine.evaluate(cluster,"runtime");engine.evaluate(cluster,"runtime");
        assertEquals(2,jdbc.queryForObject("SELECT active FROM ds_incident WHERE cluster_id=910 AND incident_key=?",Integer.class,legacy));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=910",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM ds_incident_member WHERE cluster_id=910",Integer.class));
        task(910,"101","9001","SUCCESS");engine.evaluate(cluster,"runtime");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=910",Integer.class));
        task(910,"101","9002","SUCCESS");engine.evaluate(cluster,"runtime");
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=910",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ds_incident WHERE cluster_id=910 AND active<>0",Integer.class));
        assertEquals("RECOVERED",jdbc.queryForObject("SELECT state FROM alert_state WHERE service_id=910 AND alert_key=?",String.class,"group:2:ds:"+legacy+":availability"));
    }
    @Test void enterpriseProjectsRouteToDifferentGroupsDeduplicateAndRecoverThroughOutbox() {
        MonitorService cluster=service(904);cluster.serviceType="dolphinscheduler";
        jdbc.update("INSERT INTO alert_group(id,group_name,enabled) VALUES(3,'project-b',1)");jdbc.update("INSERT INTO group_channel_rel(group_id,channel_id) VALUES(3,8)");
        dolphinStore.saveConfig(904,"101","alice","backup-a",Arrays.asList(2L),true);dolphinStore.saveConfig(904,"102","bob","backup-b",Arrays.asList(3L),true);
        com.live.monitor.dolphin.DolphinAlerts engine=enterpriseAlerts(cluster,Arrays.asList(2L));
        task(904,"101","9001","FAILURE");task(904,"102","9002","FAILURE");engine.evaluate(cluster,"runtime");
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=904",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(DISTINCT alert_key) FROM alert_delivery_task WHERE service_id=904",Integer.class));
        engine.evaluate(cluster,"runtime");assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=904",Integer.class));
        task(904,"101","9001","SUCCESS");task(904,"102","9002","SUCCESS");engine.evaluate(cluster,"runtime");
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=904",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ds_incident WHERE cluster_id=904 AND active=1",Integer.class));
        verify(delivery,never()).send(any(),any());
    }
    @Test void enterpriseMissingOwnerFallsBackAndMissingRouteIsAuditedThenRetried() {
        MonitorService cluster=service(905);cluster.serviceType="dolphinscheduler";
        task(905,"101","9001","FAILURE");com.live.monitor.dolphin.DolphinAlerts unconfigured=enterpriseAlerts(cluster,Collections.emptyList());unconfigured.evaluate(cluster,"runtime");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=905",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM alert_notify_record WHERE service_id=905 AND notify_message LIKE '%no alert group%'",Integer.class));
        com.live.monitor.dolphin.DolphinAlerts repaired=enterpriseAlerts(cluster,Arrays.asList(2L));repaired.evaluate(cluster,"runtime");assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=905",Integer.class));
    }
    @Test void enterpriseRulePriorityAndDisabledTaskOverrideAreRespected() {
        MonitorService cluster=service(906);com.live.monitor.dolphin.DolphinAlerts engine=enterpriseAlerts(cluster,Arrays.asList(2L));
        for(String[] scope:new String[][]{{"platform","*","10"},{"project","101","20"},{"workflow","800","30"},{"task","9001","40"}}){Map<String,Object> r=new HashMap<>();r.put("scope_type",scope[0]);r.put("scope_id",scope[1]);r.put("rule_type","TIMEOUT");r.put("threshold_value",Double.valueOf(scope[2]));r.put("enabled",true);dolphinStore.saveRule(906,r);}
        assertEquals(40,engine.threshold(906,"101","800","9001","TIMEOUT",100));assertEquals(30,engine.threshold(906,"101","800","9002","TIMEOUT",100));
        Map<String,Object> off=new HashMap<>();off.put("scope_type","task");off.put("scope_id","9001");off.put("rule_type","TIMEOUT");off.put("enabled",false);dolphinStore.saveRule(906,off);assertEquals(-1,engine.threshold(906,"101","800","9001","TIMEOUT",100));
    }
    @Test void enterpriseOfflineNodeRequiresConfirmedObservationAndRecoversDurably() {
        MonitorService cluster=service(907);com.live.monitor.dolphin.DolphinAlerts engine=enterpriseAlerts(cluster,Arrays.asList(2L));Map<String,Object> node=new HashMap<>();node.put("id","masters:node:5678");node.put("role","masters");node.put("host","node");node.put("online",false);node.put("offline_confirmations",1);dolphinStore.save(907,"node",node);dolphinStore.success(907,"resources",com.live.monitor.util.MonitorTime.nowText());engine.evaluate(cluster,"resources");assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=907",Integer.class));
        node.put("offline_confirmations",2);dolphinStore.save(907,"node",node);engine.evaluate(cluster,"resources");assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=907",Integer.class));node.put("online",true);node.put("offline_confirmations",0);dolphinStore.save(907,"node",node);engine.evaluate(cluster,"resources");assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=907",Integer.class));
    }
    @Test void enterpriseMissingInstanceNeverCountsAsSuccessfulRecovery() {
        MonitorService cluster=service(908);com.live.monitor.dolphin.DolphinAlerts engine=enterpriseAlerts(cluster,Arrays.asList(2L));task(908,"101","9001","FAILURE");engine.evaluate(cluster,"runtime");jdbc.update("DELETE FROM ds_entity WHERE cluster_id=908 AND kind='task_instance'");engine.evaluate(cluster,"runtime");assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=908",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ds_incident WHERE cluster_id=908 AND active=1",Integer.class));
    }
    @Test void enterpriseCollectionFailuresAreConfirmedAndRestoredThroughExistingOutbox() {
        MonitorService cluster=service(909);com.live.monitor.dolphin.DolphinAlerts engine=enterpriseAlerts(cluster,Arrays.asList(2L));dolphinStore.failure(909,"runtime","HTTP 503");engine.evaluate(cluster,"runtime");assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=909",Integer.class));dolphinStore.failure(909,"runtime","HTTP 503");engine.evaluate(cluster,"runtime");assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=909",Integer.class));dolphinStore.success(909,"runtime",com.live.monitor.util.MonitorTime.nowText());engine.evaluate(cluster,"runtime");assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=909",Integer.class));
    }

    @Test void oneHundredServicesFollowThresholdSuppressDuplicatesRecoverAndIsolateChannels() throws Exception {
        List<MonitorService> services = new ArrayList<>();
        for(int i=1;i<=100;i++) {
            MonitorService service=service(i); services.add(service);
            check(service,"DOWN","2026-11-13 10:00:00");
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task",Integer.class));
        java.util.concurrent.ExecutorService checks=java.util.concurrent.Executors.newFixedThreadPool(16);
        try {
            List<java.util.concurrent.Callable<Void>> tasks=new ArrayList<>();
            for(MonitorService service:services) tasks.add(()->{
                check(service,"DOWN","2026-11-13 10:01:00");
                check(service,"DOWN","2026-11-13 10:02:00");
                return null;
            });
            for(java.util.concurrent.Future<Void> task:checks.invokeAll(tasks)) task.get();
        } finally {checks.shutdownNow();}
        assertEquals(200,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task",Integer.class));
        verify(delivery,never()).send(any(),any());
        when(delivery.send(any(),any())).thenAnswer(call -> ((AlertChannel)call.getArgument(0)).id==7L
            ? AlertDeliveryService.DeliveryResult.failed("gateway down") : AlertDeliveryService.DeliveryResult.success());
        AlertWorker worker=new AlertWorker(outbox,mapper,delivery,mock(RocksDbHistoryRepository.class));
        try { for(int i=0;i<10;i++) worker.poll(); } finally {worker.stop();}
        assertEquals(100,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE delivery_status='SUCCESS'",Integer.class));
        assertEquals(100,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE delivery_status='FAILED'",Integer.class));
        for(MonitorService service:services) {
            check(service,"UP","2026-11-13 10:03:00"); check(service,"UP","2026-11-13 10:04:00");
        }
        assertEquals(400,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task",Integer.class));
    }

    @Test void dolphinSchedulerFailuresUseDurableTasksAndPreserveInstanceDetails() {
        MonitorService service=service(901);service.serviceType="dolphinscheduler";service.serviceConsecutiveFailures=1;
        MonitorResult result=new MonitorResult();result.serviceId=service.id;result.status="DOWN";result.checkedAt="2026-11-13 10:00:00";
        result.message="DolphinScheduler project-123 workflow-101 FAILURE start 2026-11-13 09:00:00";
        tx.execute(transaction->{alerts.publishCheckEvent(service,result);return null;});
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=901 AND delivery_status='PENDING'",Integer.class));
        String content=jdbc.queryForList("SELECT alert_content FROM alert_delivery_task WHERE service_id=901",String.class).get(0);
        assertTrue(content.contains("workflow-101"));assertTrue(content.contains("FAILURE"));
        check(service,"DOWN","2026-11-13 10:01:00");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=901",Integer.class));
    }

    @Test void hostCollectionLatencyNeverCreatesResourceAlarmAndClearsLegacyState() {
        MonitorService service=service(902);service.serviceType="host";
        jdbc.update("INSERT INTO alert_policy(id,policy_name,trigger_type,trigger_value,enabled) VALUES(92,'Latency > 3 seconds','latency_gt_ms','3000',1)");
        jdbc.update("INSERT INTO group_policy_rel(group_id,policy_id) VALUES(2,92)");
        jdbc.update("INSERT INTO alert_state(service_id,alert_key,state,fail_count,recover_count,active_trigger_type) VALUES(902,'latency_gt_ms:92','ALERTING',1,0,'latency_gt_ms')");
        MonitorResult result=new MonitorResult();result.serviceId=902L;result.status="UP";result.responseTimeMs=3427;
        result.checkedAt="2026-11-14 00:13:39";result.message="CPU 0.3% / 85.0%, Memory 64.1% / 85.0%, Disk 83.0% / 85.0%";
        tx.execute(transaction->{alerts.publishCheckEvent(service,result);return null;});
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=902",Integer.class));
        assertEquals("NORMAL",jdbc.queryForObject("SELECT state FROM alert_state WHERE service_id=902 AND alert_key='latency_gt_ms:92'",String.class));
        verify(delivery,never()).send(any(),any());
    }

    @Test void hostAvailabilityFailureUsesServiceTemplateWithActualCollectionError() {
        MonitorService service=service(903);service.serviceType="host";service.serviceConsecutiveFailures=1;
        MonitorResult result=new MonitorResult();result.serviceId=903L;result.status="UNKNOWN";
        result.checkedAt="2026-11-14 01:00:00";result.message="SSH connection timed out";
        tx.execute(transaction->{alerts.publishCheckEvent(service,result);return null;});
        verify(delivery,times(2)).renderTemplate(eq("http_service_alert.j2"),any(),any());
        String content=jdbc.queryForList("SELECT alert_content FROM alert_delivery_task WHERE service_id=903",String.class).get(0);
        assertTrue(content.contains("SSH connection timed out"));
    }

    @Test void rollbackRemovesEventStateProjectionAndTasksTogether() {
        MonitorService service=service(1);
        service.serviceConsecutiveFailures=1;
        tx.execute(transaction -> {
            check(service,"DOWN","2026-11-13 10:00:00"); transaction.setRollbackOnly(); return null;
        });
        for(String table:Arrays.asList("monitor_check_event","alert_state","monitor_history_projection","alert_delivery_task","alert_notify_record"))
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class),table);
    }

    @Test void unknownIsIndependentAndRecoveryCreatesNotification() {
        MonitorService service=service(1);
        service.configJson="{\"unknown_consecutive_failures\":2}";
        check(service,"DOWN","2026-11-13 10:00:00");
        check(service,"UNKNOWN","2026-11-13 10:01:00");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task",Integer.class));
        check(service,"UNKNOWN","2026-11-13 10:02:00");
        check(service,"UNKNOWN","2026-11-13 10:03:00");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task",Integer.class));
        check(service,"UP","2026-11-13 10:04:00"); check(service,"UP","2026-11-13 10:05:00");
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task",Integer.class));
    }

    @Test void taskPagesUseRequestedSizeAndClampAfterResultsShrink() {
        for (int i=1; i<=12; i++) { MonitorService service=service(i); service.serviceConsecutiveFailures=1; check(service,"DOWN","2026-11-13 10:00:00"); }
        AlertTaskAdmin admin=new AlertTaskAdmin(jdbc,mapper,delivery);
        Map<String,Object> first=admin.list(null,7L,null,null,null,1,10);
        Map<String,Object> second=admin.list(null,7L,null,null,null,2,10);
        assertEquals(12L,first.get("total"));
        assertEquals(10,((List<?>)first.get("items")).size());
        assertEquals(2,((List<?>)second.get("items")).size());
        assertEquals(2,admin.list(null,7L,null,null,null,99,10).get("page"));
        jdbc.update("DELETE FROM alert_delivery_task");
        Map<String,Object> empty=admin.list(null,7L,null,null,null,99,10);
        assertEquals(1,empty.get("page"));
        assertTrue(((List<?>)empty.get("items")).isEmpty());
    }

    @Test void manualRetryPreservesOriginalAndActorAndRevalidatesChannel() {
        MonitorService service=service(1); service.serviceConsecutiveFailures=1;
        check(service,"DOWN","2026-11-13 10:00:00");
        String id=jdbc.queryForObject("SELECT id FROM alert_delivery_task WHERE channel_id=7",String.class);
        jdbc.update("UPDATE alert_delivery_task SET delivery_status='FINAL_FAILED' WHERE id=?",id);
        AlertTaskAdmin admin=new AlertTaskAdmin(jdbc,mapper,delivery);
        String next=tx.execute(transaction -> admin.retry(id,"admin"));
        verify(delivery).configurationError(any());
        assertEquals("FINAL_FAILED",jdbc.queryForObject("SELECT delivery_status FROM alert_delivery_task WHERE id=?",String.class,id));
        assertEquals("admin",jdbc.queryForObject("SELECT actor FROM alert_manual_retry WHERE new_task_id=?",String.class,next));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->tx.execute(transaction->admin.retry(id,"admin")));
        assertEquals(3L,admin.list(service.id,null,null,null,null,1).get("total"));
    }

    @Test void transientUpDoesNotCloseIncidentOrGenerateDuplicateAlert() {
        MonitorService service=service(1);
        check(service,"DOWN","2026-11-13 10:00:00"); check(service,"DOWN","2026-11-13 10:01:00");
        check(service,"UP","2026-11-13 10:02:00");
        check(service,"DOWN","2026-11-13 10:03:00"); check(service,"DOWN","2026-11-13 10:04:00");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task",Integer.class));
    }

    @Test void remindersFollowIntervalAndRepairedConfigurationResumesDelivery() {
        MonitorService service=service(1); service.serviceConsecutiveFailures=1;
        service.configJson="{\"alert_repeat_interval_seconds\":60}";
        jdbc.update("UPDATE alert_channel SET enabled=0");
        check(service,"DOWN","2026-11-13 10:00:00");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM alert_notify_record WHERE notify_status='failed'",Integer.class));
        check(service,"DOWN","2026-11-13 10:00:00");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM alert_notify_record WHERE notify_status='failed'",Integer.class));
        jdbc.update("UPDATE alert_channel SET enabled=1");
        check(service,"DOWN","2026-11-13 10:00:01");
        check(service,"DOWN","2026-11-13 10:00:30");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task",Integer.class));
        check(service,"DOWN","2026-11-13 10:01:01");
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task",Integer.class));
    }

    @Test void eachBoundGroupUsesItsOwnLatencyPolicy() {
        MonitorService service=service(1); service.alertGroupIds=Arrays.asList(2L,3L);
        jdbc.update("INSERT INTO alert_group(id,group_name,enabled) VALUES(3,'slow threshold',1)");
        jdbc.update("INSERT INTO alert_channel(id,channel_name,channel_type,enabled) VALUES(9,'third','http',1)");
        jdbc.update("INSERT INTO group_channel_rel(group_id,channel_id) VALUES(3,9)");
        jdbc.update("INSERT INTO alert_policy(id,policy_name,trigger_type,trigger_value,enabled) VALUES(10,'fast','latency_gt_ms','1',1),(11,'slow','latency_gt_ms','1000',1)");
        jdbc.update("INSERT INTO group_policy_rel(group_id,policy_id) VALUES(2,10),(3,11)");
        MonitorResult result=new MonitorResult(); result.serviceId=1L; result.status="UP"; result.responseTimeMs=500;result.checkedAt="2026-11-13 10:00:00";
        tx.execute(status->{alerts.publishCheckEvent(service,result);return null;});
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE channel_id=9",Integer.class));
    }

    @Test void failedHistoryProjectionRetainsDurableReplayAndThenRecovers() {
        MonitorService service=service(1); check(service,"UP","2026-11-13 10:00:00");
        RocksDbHistoryRepository history=mock(RocksDbHistoryRepository.class);
        when(history.saveMonitorResult(any())).thenThrow(new IllegalStateException("disk unavailable")).thenAnswer(call->call.getArgument(0));
        AlertWorker worker=new AlertWorker(outbox,mapper,delivery,history);
        try {
            worker.projectHistory();
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM monitor_history_projection",Integer.class));
            worker.projectHistory();
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM monitor_history_projection",Integer.class));
            org.mockito.ArgumentCaptor<MonitorResult> records=org.mockito.ArgumentCaptor.forClass(MonitorResult.class);
            verify(history,times(2)).saveMonitorResult(records.capture());
            assertEquals(records.getAllValues().get(0).id,records.getAllValues().get(1).id);
            assertEquals(records.getAllValues().get(0).checkedAt,records.getAllValues().get(1).checkedAt);
        } finally {worker.stop();}
    }

    @Test void disabledUnknownBreaksDownSequenceAndHostUnknownCanRecover() {
        MonitorService service=service(1);service.configJson="{\"unknown_alert_enabled\":false}";
        check(service,"DOWN","2026-11-13 10:00:00");check(service,"UNKNOWN","2026-11-13 10:01:00");check(service,"DOWN","2026-11-13 10:02:00");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task",Integer.class));
        MonitorService host=service(2);host.serviceType="host";
        check(host,"UNKNOWN","2026-11-13 10:03:00");
        check(host,"UP","2026-11-13 10:04:00");check(host,"UP","2026-11-13 10:05:00");
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=2",Integer.class));
    }

    @Test void retentionNeverRemovesPendingDeliveriesOrUnprojectedSnapshots() {
        MonitorService service=service(1);service.serviceConsecutiveFailures=1;
        check(service,"DOWN","2020-01-01 00:00:00");
        jdbc.update("UPDATE alert_delivery_task SET created_at='2020-01-01',updated_at='2020-01-01'");
        tx.execute(status->{new AlertTaskMaintenance(jdbc,7).purge();return null;});
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM monitor_check_event",Integer.class));
        jdbc.update("UPDATE alert_delivery_task SET delivery_status='SUCCESS'");
        tx.execute(status->{new AlertTaskMaintenance(jdbc,7).purge();return null;});
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM monitor_check_event",Integer.class));
    }
}
