package com.live.monitor.alert;

import com.live.monitor.entity.AlertChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;
import static org.junit.jupiter.api.Assertions.*;

class AlertOutboxTest {
    private JdbcTemplate jdbc;
    private AlertOutbox outbox;
    private DriverManagerDataSource source;

    @BeforeEach
    void setup() throws Exception {
        source = new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE alert_notify_record(service_id BIGINT, alert_key VARCHAR(100), alert_type VARCHAR(100), notify_status VARCHAR(20), notify_message TEXT)");
        String schema = StreamUtils.copyToString(new ClassPathResource("schema-h2.sql").getInputStream(), StandardCharsets.UTF_8);
        for (String statement : schema.substring(schema.indexOf("CREATE TABLE IF NOT EXISTS alert_delivery_task")).split(";")) {
            if (!statement.trim().isEmpty()) jdbc.execute(statement);
        }
        outbox = new AlertOutbox(jdbc, 5);
    }

    private void enqueue() {
        AlertChannel channel = new AlertChannel();
        channel.id = 7L;
        outbox.enqueue(9L, 3L, channel, "DOWN", "availability");
    }

    @Test
    void rollbackDoesNotLeaveRunnableTask() {
        new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> {
            enqueue();
            status.setRollbackOnly();
            return null;
        });
        assertTrue(outbox.due(0).isEmpty());
    }

    @Test
    void restartRecoversPendingAndExpiredClaimsButNeverSuccess() {
        enqueue();
        AlertOutbox restarted = new AlertOutbox(new JdbcTemplate(source), 5);
        String id = (String) restarted.due(0).get(0).get("id");
        assertTrue(restarted.claim(id, "owner1", 0));
        assertFalse(outbox.claim(id, "owner2", 0));
        assertTrue(restarted.due(299999).isEmpty());
        assertTrue(restarted.claim(id, "owner2", 300000));
        restarted.complete(id, "owner1", true, 0, 5, null, 3600001);
        assertEquals("SENDING", jdbc.queryForObject("SELECT delivery_status FROM alert_delivery_task", String.class));
        restarted.complete(id, "owner2", true, 0, 5, null, 3600001);
        assertTrue(new AlertOutbox(jdbc, 5).due(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void exponentialRetriesStopAfterFiveRetries() {
        enqueue();
        long now = 0;
        for (int attempt = 0; attempt <= 5; attempt++) {
            Map<String, Object> task = outbox.due(now).get(0);
            String id = (String) task.get("id");
            assertTrue(outbox.claim(id, "owner", now));
            outbox.complete(id, "owner", false, attempt, 5, "gateway unavailable", now);
            if (attempt < 5) {
                long delay = 30000L * (1L << attempt);
                assertTrue(outbox.due(now + delay - 1).isEmpty());
                now += delay;
            }
        }
        assertEquals("FINAL_FAILED", jdbc.queryForObject("SELECT delivery_status FROM alert_delivery_task", String.class));
        assertTrue(outbox.due(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void failedChannelDoesNotPreventOtherChannelAndAttemptsRemainQueryable() {
        enqueue();
        AlertChannel second = new AlertChannel();
        second.id = 8L;
        second.enabled = true;
        outbox.enqueue(9L, 3L, second, "DOWN", "availability");
        com.live.monitor.mapper.AlertMapper mapper = org.mockito.Mockito.mock(com.live.monitor.mapper.AlertMapper.class);
        AlertDeliveryService delivery = org.mockito.Mockito.mock(AlertDeliveryService.class);
        AlertChannel first = new AlertChannel();
        first.id = 7L;
        first.enabled = true;
        org.mockito.Mockito.when(mapper.findChannel(7L)).thenReturn(first);
        org.mockito.Mockito.when(mapper.findChannel(8L)).thenReturn(second);
        org.mockito.Mockito.when(delivery.send(first, "DOWN")).thenReturn(AlertDeliveryService.DeliveryResult.failed("gateway unavailable"));
        org.mockito.Mockito.when(delivery.send(second, "DOWN")).thenReturn(AlertDeliveryService.DeliveryResult.success());
        AlertWorker worker = new AlertWorker(outbox, mapper, delivery,
            org.mockito.Mockito.mock(com.live.monitor.store.RocksDbHistoryRepository.class));
        try {
            worker.poll();
            assertEquals("FAILED", jdbc.queryForObject("SELECT delivery_status FROM alert_delivery_task WHERE channel_id=7", String.class));
            assertEquals("SUCCESS", jdbc.queryForObject("SELECT delivery_status FROM alert_delivery_task WHERE channel_id=8", String.class));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM alert_notify_record", Integer.class));
            worker.poll();
            org.mockito.Mockito.verify(delivery, org.mockito.Mockito.times(1)).send(second, "DOWN");
        } finally {
            worker.stop();
        }
    }

    @Test
    void concurrentWorkersHaveOneOwner() throws Exception {
        enqueue();
        String id = (String) outbox.due(0).get(0).get("id");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            java.util.List<java.util.concurrent.Callable<Boolean>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) calls.add(() -> outbox.claim(id, java.util.UUID.randomUUID().toString(), 0));
            int owners = 0;
            for (java.util.concurrent.Future<Boolean> result : pool.invokeAll(calls)) if (result.get()) owners++;
            assertEquals(1, owners);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void supervisorRestartsConsumerAfterUnexpectedError() throws Exception {
        enqueue();
        com.live.monitor.mapper.AlertMapper mapper = org.mockito.Mockito.mock(com.live.monitor.mapper.AlertMapper.class);
        AlertChannel channel = new AlertChannel(); channel.id=7L; channel.enabled=true;
        org.mockito.Mockito.when(mapper.findChannel(7L)).thenReturn(channel);
        AlertDeliveryService delivery=org.mockito.Mockito.mock(AlertDeliveryService.class);
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(delivery.send(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            if(calls.incrementAndGet()==1) throw new AssertionError("simulated consumer crash");
            return AlertDeliveryService.DeliveryResult.success();
        });
        AlertWorker worker=new AlertWorker(outbox,mapper,delivery,org.mockito.Mockito.mock(com.live.monitor.store.RocksDbHistoryRepository.class));
        worker.start();
        try {
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(calls.get()==0 && System.nanoTime()<deadline) Thread.sleep(20);
            assertEquals(1,calls.get());
            jdbc.update("UPDATE alert_delivery_task SET next_retry_at=0 WHERE delivery_status='SENDING'");
            worker.supervise();
            while(jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE delivery_status='SUCCESS'",Integer.class)==0 && System.nanoTime()<deadline) Thread.sleep(20);
            assertEquals("SUCCESS",jdbc.queryForObject("SELECT delivery_status FROM alert_delivery_task",String.class));
        } finally {worker.stop();}
    }
}
