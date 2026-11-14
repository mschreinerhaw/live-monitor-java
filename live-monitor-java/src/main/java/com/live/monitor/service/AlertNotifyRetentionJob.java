package com.live.monitor.service;

import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.mapper.AlertMapper;
import com.live.monitor.util.MonitorTime;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically purge alert_notify_record rows older than the configured
 * retention window, so long-running deployments don't grow this audit table
 * unbounded.
 */
@Component
public class AlertNotifyRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(AlertNotifyRetentionJob.class);

    private final LiveMonitorProperties properties;
    private final AlertMapper alertMapper;

    public AlertNotifyRetentionJob(LiveMonitorProperties properties, AlertMapper alertMapper) {
        this.properties = properties;
        this.alertMapper = alertMapper;
    }

    @Scheduled(initialDelay = 120_000L, fixedDelay = Long.MAX_VALUE)
    public void runOnStartup() {
        purge("startup");
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void runDaily() {
        purge("daily");
    }

    private void purge(String trigger) {
        int days = Math.max(1, properties.getAlertNotifyRetentionDays());
        LocalDateTime cutoff = MonitorTime.now().minusDays(days);
        String cutoffText = MonitorTime.formatText(cutoff);
        try {
            int removed = alertMapper.deleteNotifyRecordsBefore(cutoffText);
            log.info("Alert notify retention purge ({}): removed {} rows older than {} ({} days)",
                trigger, removed, cutoffText, days);
        } catch (Exception ex) {
            log.warn("Alert notify retention purge ({}) failed", trigger, ex);
        }
    }
}
