package com.live.monitor.alert;

import com.live.monitor.entity.AlertChannel;
import java.nio.file.*;
import java.sql.Connection;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;

class AlertCrashRecoveryTest {
    @TempDir Path directory;

    static DriverManagerDataSource source(String path) {
        return new DriverManagerDataSource("jdbc:h2:file:"+path+";DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
    }

    @Test void forceKilledProcessRecoversOnlyCommittedUnfinishedTasks() throws Exception {
        String database=directory.resolve("alerts").toString();
        Path ready=directory.resolve("ready");
        String java=Paths.get(System.getProperty("java.home"),"bin","java").toString();
        Process child=new ProcessBuilder(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
            CrashWriter.class.getName(),database,ready.toString())
            .redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile()).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!Files.exists(ready) && child.isAlive() && System.nanoTime()<deadline) Thread.sleep(50);
            assertTrue(Files.exists(ready),"Child did not reach committed state: "+new String(Files.readAllBytes(directory.resolve("child.log")),StandardCharsets.UTF_8));
        } finally {child.destroyForcibly(); assertTrue(child.waitFor(10,TimeUnit.SECONDS));}
        JdbcTemplate jdbc=new JdbcTemplate(source(database));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task",Integer.class));
        AlertOutbox outbox=new AlertOutbox(jdbc,5);
        assertEquals(2,outbox.due(System.currentTimeMillis()).size());
        com.live.monitor.mapper.AlertMapper mapper=mock(com.live.monitor.mapper.AlertMapper.class);
        AlertChannel channel=new AlertChannel();channel.id=7L;channel.enabled=true;
        when(mapper.findChannel(7L)).thenReturn(channel);
        AlertDeliveryService delivery=mock(AlertDeliveryService.class);
        when(delivery.send(any(),any())).thenReturn(AlertDeliveryService.DeliveryResult.success());
        AlertWorker worker=new AlertWorker(outbox,mapper,delivery,mock(com.live.monitor.store.RocksDbHistoryRepository.class));
        try {worker.poll();worker.poll();} finally {worker.stop();}
        verify(delivery,times(2)).send(any(),any());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE delivery_status='SUCCESS'",Integer.class));
    }

    public static class CrashWriter {
        public static void main(String[] args) throws Exception {
            DriverManagerDataSource source=source(args[0]); JdbcTemplate jdbc=new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE alert_notify_record(service_id BIGINT,alert_key VARCHAR(100),alert_type VARCHAR(100),notify_status VARCHAR(20),notify_message TEXT)");
            String ddl=StreamUtils.copyToString(new ClassPathResource("schema-h2.sql").getInputStream(),StandardCharsets.UTF_8);
            for(String statement:ddl.substring(ddl.indexOf("CREATE TABLE IF NOT EXISTS alert_delivery_task")).split(";"))
                if(!statement.trim().isEmpty()) jdbc.execute(statement);
            AlertOutbox outbox=new AlertOutbox(jdbc,5); AlertChannel channel=new AlertChannel();channel.id=7L;
            for(int i=0;i<3;i++) outbox.enqueue((long)i+1,1L,channel,"DOWN","availability");
            java.util.List<java.util.Map<String,Object>> tasks=outbox.due(0);
            String success=(String)tasks.get(0).get("id");
            outbox.claim(success,"success",0);outbox.complete(success,"success",true,0,5,null,0);
            outbox.claim((String)tasks.get(1).get("id"),"abandoned",System.currentTimeMillis()-3600001L);
            try(Connection connection=source.getConnection()) {
                connection.setAutoCommit(false);
                connection.createStatement().execute("INSERT INTO alert_delivery_task(id,event_id,service_id,channel_id,alert_content,delivery_status,next_retry_at) VALUES('uncommitted',1,1,7,'not committed','PENDING',0)");
                Files.write(Paths.get(args[1]),new byte[]{1});
                Thread.sleep(TimeUnit.MINUTES.toMillis(2));
            }
        }
    }
}
