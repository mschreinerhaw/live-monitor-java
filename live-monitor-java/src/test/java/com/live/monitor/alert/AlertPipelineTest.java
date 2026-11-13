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
