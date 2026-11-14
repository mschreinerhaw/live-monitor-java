package com.live.monitor.alert;

import com.live.monitor.util.MonitorTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class AlertTaskMaintenance {
    private static final Logger log = LoggerFactory.getLogger(AlertTaskMaintenance.class);
    private final JdbcTemplate jdbc;
    private final int retention;
    public AlertTaskMaintenance(JdbcTemplate jdbc, @Value("${live-monitor.alert-retention-days:90}") int retention) {
        this.jdbc=jdbc; this.retention=Math.max(7,retention);
    }

    @Scheduled(fixedDelay=60000)
    public void backlog() {
        Long pending=jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE delivery_status IN ('PENDING','SENDING','FAILED')",Long.class);
        Long projection=jdbc.queryForObject("SELECT COUNT(*) FROM monitor_history_projection",Long.class);
        if(pending>1000 || projection>1000) log.warn("Alert backlog: {} unfinished deliveries, {} history projections",pending,projection);
    }

    @Transactional
    @Scheduled(cron="0 30 3 * * *",zone="Asia/Shanghai")
    public void purge() {
        String cutoff=MonitorTime.formatText(MonitorTime.now().minusDays(retention));
        // Never discard unfinished deliveries or the check snapshots they reference.
        String terminal="SELECT id FROM alert_delivery_task WHERE delivery_status IN ('SUCCESS','FINAL_FAILED') AND updated_at < ?";
        jdbc.update("DELETE FROM alert_delivery_attempt WHERE task_id IN ("+terminal+")",cutoff);
        jdbc.update("DELETE FROM alert_manual_retry WHERE original_task_id IN ("+terminal+") AND new_task_id IN ("+terminal+")",cutoff,cutoff);
        jdbc.update("DELETE FROM alert_delivery_task WHERE delivery_status IN ('SUCCESS','FINAL_FAILED') AND updated_at < ? AND id NOT IN (SELECT original_task_id FROM alert_manual_retry) AND id NOT IN (SELECT new_task_id FROM alert_manual_retry)",cutoff);
        jdbc.update("DELETE FROM monitor_check_event WHERE checked_at < ? AND id NOT IN (SELECT event_id FROM monitor_history_projection) AND id NOT IN (SELECT event_id FROM alert_delivery_task)",cutoff);
        jdbc.update("DELETE FROM alert_delivery_dedup WHERE event_id NOT IN (SELECT id FROM monitor_check_event)");
    }
}
