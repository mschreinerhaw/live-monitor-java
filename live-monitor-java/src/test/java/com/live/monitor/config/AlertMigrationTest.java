package com.live.monitor.config;

import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class AlertMigrationTest {
    @Test void relationalMigrationCopiesPendingTasksAndEventStateAndMultiGroupBindings() throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:migration-source;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        DriverManagerDataSource target=new DriverManagerDataSource("jdbc:h2:mem:migration-target;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        for(DriverManagerDataSource database:Arrays.asList(source,target))
            new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql")).execute(database);
        JdbcTemplate from=new JdbcTemplate(source), to=new JdbcTemplate(target);
        from.update("INSERT INTO monitor_service(id,service_name,service_type) VALUES(10,'monitor','web')");
        from.update("INSERT INTO alert_group(id,group_name) VALUES(20,'first'),(21,'second')");
        from.update("INSERT INTO alert_channel(id,channel_name,channel_type) VALUES(30,'channel','http')");
        from.update("INSERT INTO service_alert_group(service_id,group_id) VALUES(10,20),(10,21)");
        from.update("INSERT INTO monitor_check_event(id,service_id,status,checked_at) VALUES(40,10,'DOWN',CURRENT_TIMESTAMP)");
        from.update("INSERT INTO alert_state(service_id,alert_key,state,fail_count,recover_count) VALUES(10,'availability','ALERTING',3,0)");
        from.update("INSERT INTO monitor_history_projection(event_id) VALUES(40)");
        from.update("INSERT INTO alert_delivery_task(id,event_id,service_id,channel_id,alert_content,delivery_status,next_retry_at) VALUES('task',40,10,30,'DOWN','PENDING',0)");
        DatabaseDialect dialect=mock(DatabaseDialect.class);when(dialect.isMysql()).thenReturn(true);
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(target));
        H2ToMysqlMigrationService migration=new H2ToMysqlMigrationService(new LiveMonitorProperties(),to,tx,dialect);
        String[][] tables=(String[][])ReflectionTestUtils.getField(H2ToMysqlMigrationService.class,"TABLES");
        try(Connection connection=source.getConnection()) {
            tx.execute(status->{
                for(String[] table:tables) ReflectionTestUtils.invokeMethod(migration,"copyTable",connection,table[0],Arrays.asList(table[1].split(",")));
                return null;
            });
        }
        assertEquals(2,to.queryForObject("SELECT COUNT(*) FROM service_alert_group WHERE service_id=10",Integer.class));
        assertEquals("PENDING",to.queryForObject("SELECT delivery_status FROM alert_delivery_task WHERE id='task'",String.class));
        assertEquals(1,to.queryForObject("SELECT COUNT(*) FROM monitor_check_event WHERE id=40",Integer.class));
        assertEquals(1,to.queryForObject("SELECT COUNT(*) FROM monitor_history_projection WHERE event_id=40",Integer.class));
        assertEquals("ALERTING",to.queryForObject("SELECT state FROM alert_state WHERE service_id=10",String.class));
    }
}
