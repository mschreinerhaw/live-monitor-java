package com.live.monitor.service;

import com.live.monitor.config.DatabaseDialect;
import com.live.monitor.entity.TUser;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(CalendarServiceTest.Config.class)
class CalendarServiceTest {
 @Configuration @EnableTransactionManagement static class Config {
  @Bean DataSource dataSource(){JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:business-calendars;MODE=LEGACY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");return ds;}
  @Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}
  @Bean DatabaseDialect dialect(JdbcTemplate j){return new DatabaseDialect(j);}
  @Bean LoginAuditLogService audit(){return mock(LoginAuditLogService.class);}
  @Bean CalendarService service(JdbcTemplate j,DatabaseDialect d,LoginAuditLogService a){return new CalendarService(j,d,a);}
  @Bean DataSourceTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
 }
 @Autowired CalendarService service;@Autowired JdbcTemplate jdbc;@Autowired LoginAuditLogService audit;
 final TUser admin=new TUser();
 @BeforeEach void prepare(){admin.userId="admin";reset(audit);for(String kind:Arrays.asList("trading","working")){String field=kind.equals("trading")?"JYR":"GZR";jdbc.execute("CREATE TABLE IF NOT EXISTS t_ld_"+kind+"_calendar (ZRR INT NOT NULL,"+field+" INT NOT NULL,ND INT,JD INT,YF_XH INT,XQ INT,NY INT,NZ INT,XQ_XH INT,PRIMARY KEY(ZRR,"+field+"))");jdbc.update("DELETE FROM t_ld_"+kind+"_calendar");}}
 String sql(String kind){String field=kind.equals("trading")?"JYR":"GZR";return "-- delete data\nDELETE FROM `t_ld_"+kind+"_calendar` WHERE ND=2026;\n-- insert data\nINSERT INTO `t_ld_"+kind+"_calendar` (ZRR,"+field+",ND,JD,YF_XH,XQ,NY,NZ,XQ_XH) VALUES (20260101,20251231,2026,1,1,4,202601,202601,1);";}
 @Test void previewIsReadOnlyAndCrossYearMappingsResolve(){jdbc.update("INSERT INTO t_ld_working_calendar(ZRR,GZR,ND) VALUES(20260102,20251231,2026)");Map<?,?> p=(Map<?,?>)service.previewFile(sql("working")+sql("trading")).get("working");assertEquals(1L,p.get("delete_count"));assertEquals(1,p.get("row_count"));assertEquals(1L,service.list("working",null,null,1,20).get("total"));verifyNoInteractions(audit);service.importFile(sql("working")+sql("trading"),admin,"test");assertEquals("2025-12-31",service.resolve("working",20260101).get("etl_date"));assertEquals("2025-12-31",service.resolve("trading",20260101).get("etl_date"));assertEquals("MISSING",service.resolve("working",20260102).get("status"));}
 @Test void invalidStatementNeverDeletesExistingYear(){service.importFile(sql("working"),admin,"test");assertThrows(RuntimeException.class,()->service.importFile(sql("working")+"DROP TABLE monitor_service;",admin,"test"));assertEquals("RESOLVED",service.resolve("working",20260101).get("status"));for(String text:Arrays.asList("DELETE FROM t_ld_working_calendar;","DELETE FROM t_ld_working_calendar WHERE ND=2026 OR 1=1;","INSERT INTO t_ld_working_calendar(ZRR,GZR) VALUES(20260101,1+2);"))assertThrows(RuntimeException.class,()->service.previewFile(text));}
 @Test void auditFailureRollsBackBothCalendarsAndAnnualDelete(){jdbc.update("INSERT INTO t_ld_trading_calendar(ZRR,JYR,ND) VALUES(20260102,20251231,2026)");doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(),anyString(),anyString());assertThrows(RuntimeException.class,()->service.importFile(sql("trading")+sql("working"),admin,"test"));assertEquals("RESOLVED",service.resolve("trading",20260102).get("status"));assertEquals("MISSING",service.resolve("trading",20260101).get("status"));assertEquals(0L,service.list("working",null,null,1,20).get("total"));}
 @Test void compositeKeyAndUnrelatedYearsArePreserved(){service.importFile(sql("trading"),admin,"test");Map<String,Object> row=new HashMap<>();row.put("zrr",20260101);row.put("jyr",20251230);row.put("nd",2026);service.save("trading",row,admin,"test");assertEquals("AMBIGUOUS",service.resolve("trading",20260101).get("status"));service.delete("trading",20260101,20251230,admin,"test");assertEquals("2025-12-31",service.resolve("trading",20260101).get("etl_date"));jdbc.update("INSERT INTO t_ld_trading_calendar(ZRR,JYR,ND) VALUES(20250101,20241231,2025)");service.importFile(sql("trading"),admin,"test");assertEquals("2024-12-31",service.resolve("trading",20250101).get("etl_date"));}
 @Test void rejectsInvalidDatesDuplicateKeysAndWrongYear(){for(String text:Arrays.asList("INSERT INTO t_ld_trading_calendar(ZRR,JYR) VALUES(20260229,20251231);","INSERT INTO t_ld_trading_calendar(ZRR,JYR) VALUES(20260101,0);","INSERT INTO t_ld_trading_calendar(ZRR,JYR,ND) VALUES(20260101,20251231,2025);",sql("trading")+"INSERT INTO t_ld_trading_calendar(ZRR,JYR) VALUES(20260101,20251231);"))assertThrows(RuntimeException.class,()->service.previewFile(text));assertThrows(RuntimeException.class,()->service.list("trading;DROP TABLE x",null,null,1,20));}
}
