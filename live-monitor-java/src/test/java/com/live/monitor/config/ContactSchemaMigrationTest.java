package com.live.monitor.config;

import com.live.monitor.dto.AlertContactPayload;
import com.live.monitor.service.AlertContactService;
import java.lang.reflect.Method;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class ContactSchemaMigrationTest {
 @Test void upgradeIsIdempotentAndRetainsLegacyTasksWithoutChangingTheirRecipientMode() throws Exception {
  JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");JdbcTemplate jdbc=new JdbcTemplate(ds);
  jdbc.execute("CREATE TABLE alert_delivery_task(id VARCHAR(36) PRIMARY KEY,alert_content TEXT)");jdbc.update("INSERT INTO alert_delivery_task VALUES('legacy','keep this alert')");
  SchemaMigrationService migrations=new SchemaMigrationService(jdbc,new DatabaseDialect(jdbc));Method tables=SchemaMigrationService.class.getDeclaredMethod("createContactTables");tables.setAccessible(true);tables.invoke(migrations);tables.invoke(migrations);
  Method column=SchemaMigrationService.class.getDeclaredMethod("addColumnIfMissing",String.class,String.class,String.class);column.setAccessible(true);column.invoke(migrations,"alert_delivery_task","recipient_context_json","TEXT");column.invoke(migrations,"alert_delivery_task","recipient_context_json","TEXT");
  assertEquals("keep this alert",jdbc.queryForObject("SELECT alert_content FROM alert_delivery_task WHERE id='legacy'",String.class));assertNull(jdbc.queryForMap("SELECT recipient_context_json FROM alert_delivery_task").get("RECIPIENT_CONTEXT_JSON"));
  AlertContactService contacts=new AlertContactService(jdbc);AlertContactPayload person=new AlertContactPayload();person.name="Alice";person.email="alice@example.com";long id=((Number)contacts.save(null,person).get("id")).longValue();assertTrue(id>0);assertEquals("Alice",contacts.list().get(0).get("name"));
 }
}
