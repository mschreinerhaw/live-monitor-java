package com.live.monitor.scheduler;

import com.live.monitor.entity.MonitorService;
import com.live.monitor.service.LiveMonitorService;
import com.live.monitor.util.CheckIntervals;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class MonitorScheduler {
    private static final Logger log = LoggerFactory.getLogger(MonitorScheduler.class);
    private static final DateTimeFormatter SQLITE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LiveMonitorService liveMonitorService;
    private final ThreadPoolTaskExecutor monitorCheckExecutor;
    private final Set<Long> runningServiceIds = ConcurrentHashMap.newKeySet();

    public MonitorScheduler(
        LiveMonitorService liveMonitorService,
        ThreadPoolTaskExecutor monitorCheckExecutor
    ) {
        this.liveMonitorService = liveMonitorService;
        this.monitorCheckExecutor = monitorCheckExecutor;
    }

    @Scheduled(fixedDelayString = "#{@liveMonitorProperties.getSchedulerTickSeconds() * 1000}")
    public void tick() {
        try {
            List<MonitorService> services = liveMonitorService.listServices(false);
            for (MonitorService service : services) {
                if (isDue(service)) {
                    submitCheck(service);
                }
            }
        } catch (Exception ex) {
            log.warn("scheduler tick failed", ex);
        }
    }

    private void submitCheck(MonitorService service) {
        if (service.id == null || !runningServiceIds.add(service.id)) {
            return;
        }
        try {
            monitorCheckExecutor.execute(() -> runCheck(service));
        } catch (RejectedExecutionException ex) {
            runningServiceIds.remove(service.id);
            log.warn("monitor worker pool is full, skip service {} this tick", service.id);
        }
    }

    private void runCheck(MonitorService service) {
        try {
            liveMonitorService.checkAndStore(service.id);
        } catch (Exception ex) {
            log.warn("monitor check failed for service {} ({})", service.id, service.serviceName, ex);
        } finally {
            runningServiceIds.remove(service.id);
        }
    }

    private boolean isDue(MonitorService service) {
        if (service.lastCheckedAt == null || service.lastCheckedAt.trim().isEmpty()) {
            return true;
        }
        try {
            LocalDateTime last = LocalDateTime.parse(service.lastCheckedAt.substring(0, 19), SQLITE_TIME);
            int interval = CheckIntervals.normalizeSeconds(service.checkInterval);
            return !last.plusSeconds(interval).isAfter(LocalDateTime.now());
        } catch (Exception ex) {
            return true;
        }
    }
}
