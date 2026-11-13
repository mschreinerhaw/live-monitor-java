package com.live.monitor.store;

import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.util.MonitorTime;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly job that purges host metric samples older than
 * {@code live-monitor.metric-retention-days} from both RocksDB and the Lucene
 * secondary index, so long-lived deployments don't accumulate metric history
 * indefinitely.
 */
@Component
public class MetricRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(MetricRetentionJob.class);

    private final LiveMonitorProperties properties;
    private final RocksDbHistoryRepository historyRepository;

    public MetricRetentionJob(LiveMonitorProperties properties, RocksDbHistoryRepository historyRepository) {
        this.properties = properties;
        this.historyRepository = historyRepository;
    }

    /** Run 90s after startup so we get an initial cleanup on every boot. */
    @Scheduled(initialDelay = 90_000L, fixedDelay = Long.MAX_VALUE)
    public void runOnStartup() {
        purge("startup");
    }

    /** Run daily at 03:15 local time. */
    @Scheduled(cron = "0 15 3 * * *")
    public void runDaily() {
        purge("daily");
    }

    private void purge(String trigger) {
        int days = Math.max(1, properties.getMetricRetentionDays());
        LocalDateTime cutoff = MonitorTime.now().minusDays(days);
        try {
            int removed = historyRepository.purgeMetricsBefore(cutoff);
            log.info("Metric retention purge ({}): removed {} RocksDB keys older than {} ({} days)",
                trigger, removed, cutoff, days);
        } catch (Exception ex) {
            log.warn("Metric retention purge ({}) failed", trigger, ex);
        }
    }
}
