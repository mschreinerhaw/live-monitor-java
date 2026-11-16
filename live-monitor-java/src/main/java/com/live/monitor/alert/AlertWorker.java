package com.live.monitor.alert;

import com.live.monitor.entity.AlertChannel;
import com.live.monitor.mapper.AlertMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Separate lifecycle and threads from Spring's monitor scheduler. No network IO in a DB transaction. */
@Component
@org.springframework.context.annotation.DependsOn({"h2ToMysqlMigrationService", "sqliteToH2RocksMigrationService"})
public class AlertWorker {
    private static final Logger log = LoggerFactory.getLogger(AlertWorker.class);
    private final AlertOutbox outbox;
    private final AlertMapper mapper;
    private final AlertDeliveryService delivery;
    private final com.live.monitor.store.RocksDbHistoryRepository history;
    private final java.util.List<java.util.concurrent.ScheduledFuture<?>> consumers = new java.util.ArrayList<>();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(3, r -> {
        Thread t = new Thread(r, "alert-delivery-worker");
        t.setDaemon(true);
        return t;
    });

    public AlertWorker(AlertOutbox outbox, AlertMapper mapper, AlertDeliveryService delivery,
        com.live.monitor.store.RocksDbHistoryRepository history) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.delivery = delivery;
        this.history = history;
    }

    @PostConstruct
    public void start() {
        consumers.add(executor.scheduleWithFixedDelay(this::projectHistory, 1, 1, TimeUnit.SECONDS));
        for (int i = 0; i < 2; i++) consumers.add(executor.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS));
        executor.scheduleWithFixedDelay(this::supervise, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized void supervise() {
        if (executor.isShutdown()) return;
        for (int i = 0; i < consumers.size(); i++) {
            if (consumers.get(i).isDone()) {
                log.error("Alert background task {} exited unexpectedly; restarting it", i);
                consumers.set(i, executor.scheduleWithFixedDelay(i == 0 ? this::projectHistory : this::poll, 0, 1, TimeUnit.SECONDS));
            }
        }
    }

    public void projectHistory() {
        try {
            for (Map<String, Object> event : outbox.historyDue()) {
                com.live.monitor.entity.MonitorResult result = new com.live.monitor.entity.MonitorResult();
                Long eventId = ((Number) event.get("id")).longValue();
                // Negative IDs reserve a separate namespace from legacy RocksDB sequence IDs.
                result.id = -eventId;
                result.serviceId = ((Number) event.get("service_id")).longValue();
                result.status = (String) event.get("status");
                Number responseTime = (Number) event.get("response_time_ms");
                result.responseTimeMs = responseTime == null ? null : responseTime.intValue();
                result.message = (String) event.get("message");
                result.checkedAt = event.get("checked_at").toString().replace('T', ' ');
                history.saveMonitorResult(result);
                outbox.historyComplete(eventId);
            }
        } catch (Exception ex) {
            log.error("Monitor history projection failed; committed H2 events remain available for retry", ex);
        }
    }

    public void poll() {
        try {
            for (Map<String, Object> task : outbox.due(System.currentTimeMillis())) {
                String id = (String) task.get("id");
                String token = UUID.randomUUID().toString();
                if (!outbox.claim(id, token, System.currentTimeMillis())) continue;
                task = outbox.owned(id, token);
                boolean success = false;
                String error = null;
                try {
                    AlertChannel channel = mapper.findChannel(((Number) task.get("channel_id")).longValue());
                    if (channel == null || !Boolean.TRUE.equals(channel.enabled)) {
                        error = "Alert channel missing or disabled";
                    } else {
                        channel.recipientContextJson=(String)task.get("recipient_context_json");
                        AlertDeliveryService.DeliveryResult result = delivery.send(channel, (String) task.get("alert_content"));
                        success = result.success;
                        error = result.message;
                    }
                } catch (Exception ex) {
                    // Channel exception messages can contain credentials or URLs.
                    error = "Channel exception: " + ex.getClass().getSimpleName();
                }
                outbox.complete(id, token, success, ((Number) task.get("retry_count")).intValue(),
                    ((Number) task.get("max_retries")).intValue(), error, System.currentTimeMillis());
            }
        } catch (Exception ex) {
            log.error("Alert worker poll failed; durable tasks will be retried", ex);
        }
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }
}
