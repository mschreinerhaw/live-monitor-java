package com.live.monitor.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.service.AlertAuditRepository;
import com.live.monitor.service.AlertNotifyAuditService;
import com.live.monitor.util.MonitorTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

class AlertAuditTest {
    JdbcTemplate jdbc;
    AlertAuditRepository audit;
    AlertNotifyAuditService service;
    com.live.monitor.service.CryptoService crypto;
    @BeforeEach void setup() {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=LEGACY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql")).execute(source);
        crypto=new com.live.monitor.service.CryptoService(new com.live.monitor.config.LiveMonitorProperties());
        jdbc=new JdbcTemplate(source); audit=new AlertAuditRepository(jdbc,new ObjectMapper(),crypto); service=new AlertNotifyAuditService(audit);
        jdbc.update("INSERT INTO monitor_service(id,service_name,service_type) VALUES(1,'production-host','host')");
        jdbc.update("INSERT INTO alert_channel(id,channel_name,channel_type,config_json) VALUES(7,'production-mail','email',?)", "{\"alert_email\":\"ops@example.com\",\"smtp_password\":\"secret-must-never-leak\"}");
    }
    @Test void oneTaskRemainsOneNotificationAcrossQueueRetriesAndSuccess() throws Exception {
        jdbc.update("INSERT INTO alert_delivery_task(id,event_id,service_id,channel_id,alert_content,alert_key,delivery_status,next_retry_at) VALUES('task-1',1,1,7,'host down','availability','PENDING',0)");
        jdbc.update("INSERT INTO alert_notify_record(service_id,alert_key,alert_type,notify_status,notify_message) VALUES(1,'availability','email','pending','host down')");
        assertEquals(1,audit.count(null,null,null,null));
        assertEquals("pending",audit.list(null,null,null,null,20,0).get(0).notifyStatus);
        for(int i=0;i<2;i++) {
            jdbc.update("INSERT INTO alert_delivery_attempt(id,task_id,delivery_status,error_message) VALUES(?,'task-1','FAILED','SMTP timeout')", "attempt-"+i);
            jdbc.update("INSERT INTO alert_notify_record(service_id,alert_key,alert_type,notify_status,notify_message) VALUES(1,'availability','delivery_attempt','failed','SMTP timeout')");
        }
        jdbc.update("UPDATE alert_delivery_task SET delivery_status='FAILED',last_error='SMTP timeout',next_retry_at=30000");
        assertEquals(1,audit.count(null,null,null,null));
        assertEquals(0,audit.count("failed",null,null,null));
        assertEquals(1,audit.count("retrying",null,null,null));
        com.live.monitor.entity.AlertNotifyRecord row=audit.list(null,"SMTP timeout",null,null,20,0).get(0);
        assertEquals(2,row.attemptCount); assertEquals("task-1",row.taskId);
        assertEquals("ops@example.com",row.currentRecipients);
        assertFalse(new ObjectMapper().findAndRegisterModules().writeValueAsString(row).contains("secret-must-never-leak"));
        jdbc.update("UPDATE alert_delivery_task SET delivery_status='SUCCESS',last_error=NULL");
        assertEquals(1,audit.count("success",null,null,null));
        assertEquals(0,audit.count("retrying",null,null,null));
        assertEquals(1,service.listPage(1,20,null,null,null,null).todaySuccess);
        assertEquals(1L,service.dailyTrend(1).get(0).get("total"));
    }
    @Test void configurationBlockIsNotASendFailureAndLegacyRecordsRemainVisible() {
        jdbc.update("INSERT INTO alert_notify_record(service_id,alert_key,alert_type,notify_status,notify_message) VALUES(1,'availability','consecutive_unknown','failed','UNKNOWN Delivery skipped: no alert group bound.')");
        jdbc.update("INSERT INTO alert_notify_record(service_id,alert_key,alert_type,notify_status,notify_message) VALUES(1,'test','email','success','legacy test accepted')");
        assertEquals(2,audit.count(null,null,null,null));
        assertEquals(1,audit.count("blocked",null,null,null));
        assertEquals(0,audit.count("failed",null,null,null));
        assertEquals(1,service.listPage(1,20,null,null,null,null).todayBlocked);
        String today=MonitorTime.formatText(MonitorTime.today().atStartOfDay());
        assertEquals(2,audit.count(null,"production-host",today,null));
        assertTrue(audit.list(null,null,null,today,20,0).isEmpty());
    }
    @Test void encryptedChannelConfigurationExposesOnlyItsRecipient() throws Exception {
        jdbc.update("UPDATE alert_channel SET config_json=? WHERE id=7",crypto.encrypt("{\"alert_email\":\"ops@example.com\",\"smtp_password\":\"private-password\"}"));
        jdbc.update("INSERT INTO alert_delivery_task(id,event_id,service_id,channel_id,alert_content,alert_key,delivery_status,next_retry_at) VALUES('encrypted-task',1,1,7,'alert','availability','SUCCESS',0)");
        com.live.monitor.entity.AlertNotifyRecord row=audit.list(null,null,null,null,20,0).get(0);
        assertEquals("ops@example.com",row.currentRecipients);
        assertFalse(new ObjectMapper().findAndRegisterModules().writeValueAsString(row).contains("private-password"));
    }
    @Test void identicalConfigurationBlocksAreSuppressedButRepairReopensDelivery() {
        AlertOutbox outbox=new AlertOutbox(jdbc,5);
        outbox.recordBlocked(1L,"availability","consecutive_unknown","Delivery skipped: no alert group bound.");
        outbox.recordBlocked(1L,"availability","consecutive_unknown","Delivery skipped: no alert group bound.");
        assertEquals(1,audit.count("blocked",null,null,null));
        jdbc.update("INSERT INTO alert_notify_record(service_id,alert_key,alert_type,notify_status,notify_message) VALUES(1,'availability','email','pending','queued')");
        outbox.recordBlocked(1L,"availability","consecutive_unknown","Delivery skipped: no alert group bound.");
        assertEquals(2,audit.count("blocked",null,null,null));
    }
}
