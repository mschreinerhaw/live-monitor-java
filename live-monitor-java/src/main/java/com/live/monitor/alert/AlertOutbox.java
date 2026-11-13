package com.live.monitor.alert;

import com.live.monitor.entity.AlertChannel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Durable channel tasks. All mutations participate in the caller's JDBC transaction. */
@Service
public class AlertOutbox {
    private final JdbcTemplate jdbc;
    private final int maxRetries;
    private long leaseMillis = 300000L;

    @Value("${live-monitor.alert-lease-ms:300000}")
    public void setLeaseMillis(long millis) { leaseMillis = Math.max(180000L, millis); }

    public AlertOutbox(JdbcTemplate jdbc, @Value("${live-monitor.alert-max-retries:5}") int maxRetries) {
        this.jdbc = jdbc;
        this.maxRetries = Math.max(0, Math.min(20, maxRetries));
    }

    public void enqueue(Long eventId, Long serviceId, AlertChannel channel, String content, String alertKey) {
        jdbc.update("INSERT INTO alert_delivery_task (id, event_id, service_id, channel_id, alert_content, alert_key, delivery_status, retry_count, max_retries, next_retry_at) SELECT ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, 0 WHERE NOT EXISTS (SELECT 1 FROM alert_delivery_task WHERE event_id=? AND channel_id=? AND alert_key=?)",
            UUID.randomUUID().toString(), eventId, serviceId, channel.id, content, alertKey, maxRetries, eventId, channel.id, alertKey);
    }

    public void enqueueHistory(Long eventId) {
        jdbc.update("INSERT INTO monitor_history_projection(event_id) VALUES (?)", eventId);
    }

    public List<Map<String, Object>> historyDue() {
        return jdbc.queryForList("SELECT e.* FROM monitor_history_projection p JOIN monitor_check_event e ON e.id = p.event_id ORDER BY e.id LIMIT 32");
    }

    public void historyComplete(Long eventId) {
        jdbc.update("DELETE FROM monitor_history_projection WHERE event_id = ?", eventId);
    }

    public List<Map<String, Object>> due(long now) {
        return jdbc.queryForList("SELECT * FROM alert_delivery_task WHERE (delivery_status IN ('PENDING','FAILED') AND next_retry_at <= ?) OR (delivery_status = 'SENDING' AND next_retry_at <= ?) ORDER BY next_retry_at, created_at LIMIT 32", now, now);
    }

    public boolean claim(String id, String token, long now) {
        return jdbc.update("UPDATE alert_delivery_task SET delivery_status = 'SENDING', claim_token = ?, next_retry_at = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND ((delivery_status IN ('PENDING','FAILED') AND next_retry_at <= ?) OR (delivery_status = 'SENDING' AND next_retry_at <= ?))",
            token, now + leaseMillis, id, now, now) == 1;
    }

    public Map<String, Object> owned(String id, String token) {
        return jdbc.queryForMap("SELECT * FROM alert_delivery_task WHERE id=? AND claim_token=? AND delivery_status='SENDING'", id, token);
    }

    @org.springframework.transaction.annotation.Transactional
    public void complete(String id, String token, boolean success, int retries, int limit, String error, long now) {
        List<Map<String, Object>> owned = jdbc.queryForList("SELECT * FROM alert_delivery_task WHERE id=? AND claim_token=? AND delivery_status='SENDING' FOR UPDATE", id, token);
        if (owned.isEmpty()) return;
        retries = ((Number) owned.get(0).get("retry_count")).intValue();
        limit = ((Number) owned.get(0).get("max_retries")).intValue();
        error = error == null ? null : com.live.monitor.service.MonitorCheckExecution.safe(error);
        String status = success ? "SUCCESS" : retries >= limit ? "FINAL_FAILED" : "FAILED";
        long next = now + 30000L * (1L << Math.min(retries, 20));
        jdbc.update("INSERT INTO alert_notify_record (service_id, alert_key, alert_type, notify_status, notify_message) SELECT service_id, alert_key, 'delivery_attempt', ?, ? FROM alert_delivery_task WHERE id = ? AND claim_token = ? AND delivery_status = 'SENDING'",
            success ? "success" : "failed", "Task " + id + ": " + status + (error == null ? "" : "; " + error), id, token);
        jdbc.update("UPDATE alert_delivery_task SET delivery_status = ?, retry_count = ?, next_retry_at = ?, last_error = ?, claim_token = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND claim_token = ? AND delivery_status = 'SENDING'",
            status, success ? retries : Math.min(limit, retries + 1), next, error, id, token);
        jdbc.update("INSERT INTO alert_delivery_attempt(id,task_id,delivery_status,error_message) VALUES (?,?,?,?)", UUID.randomUUID().toString(), id, status, error);
    }
}
