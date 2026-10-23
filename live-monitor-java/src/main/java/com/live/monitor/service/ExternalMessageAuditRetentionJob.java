package com.live.monitor.service;

import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.mapper.ExternalMessageAuditMapper;
import com.live.monitor.util.MonitorTime;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps the external request audit table within its configured retention window. */
@Component
public class ExternalMessageAuditRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(ExternalMessageAuditRetentionJob.class);

    private final LiveMonitorProperties properties;
    private final ExternalMessageAuditMapper mapper;

    public ExternalMessageAuditRetentionJob(
        LiveMonitorProperties properties,
        ExternalMessageAuditMapper mapper
    ) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Scheduled(initialDelay = 120_000L, fixedDelay = Long.MAX_VALUE)
    public void runOnStartup() {
        purge("startup");
    }

    @Scheduled(cron = "0 40 3 * * *")
    public void runDaily() {
        purge("daily");
    }

    private void purge(String trigger) {
        int days = Math.max(1, properties.getExternalMessageAuditRetentionDays());
        LocalDateTime cutoff = MonitorTime.now().minusDays(days);
        String cutoffText = MonitorTime.formatText(cutoff);
        try {
            int removed = mapper.deleteBefore(cutoffText);
            log.info("External message audit retention purge ({}): removed {} rows older than {} ({} days)",
                trigger, removed, cutoffText, days);
        } catch (Exception ex) {
            log.warn("External message audit retention purge ({}) failed", trigger, ex);
        }
    }
}
