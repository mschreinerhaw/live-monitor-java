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
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/alert-delivery-dedup.sql")).execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        String database=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)connection->connection.getMetaData().getDatabaseProductName());
        String seed="SELECT DISTINCT event_id,channel_id,COALESCE(alert_key,'') FROM alert_delivery_task";
        if(database!=null&&database.toLowerCase(java.util.Locale.ROOT).contains("mysql"))jdbc.update("INSERT IGNORE INTO alert_delivery_dedup(event_id,channel_id,alert_key) "+seed);
        else jdbc.update("MERGE INTO alert_delivery_dedup(event_id,channel_id,alert_key) KEY(event_id,channel_id,alert_key) "+seed);
    }

    @org.springframework.transaction.annotation.Transactional
    public void enqueue(Long eventId, Long serviceId, AlertChannel channel, String content, String alertKey) {
        String key=alertKey==null?"":alertKey;
        try{jdbc.update("INSERT INTO alert_delivery_dedup(event_id,channel_id,alert_key) VALUES(?,?,?)",eventId,channel.id,key);}
        catch(org.springframework.dao.DuplicateKeyException duplicate){
            for(Map<String,Object> task:jdbc.queryForList("SELECT id,recipient_context_json FROM alert_delivery_task WHERE event_id=? AND channel_id=? AND alert_key=? AND delivery_status='PENDING' FOR UPDATE",eventId,channel.id,key)){
                String merged=com.live.monitor.service.AlertContactService.mergeContexts((String)task.get("recipient_context_json"),channel.recipientContextJson);
                jdbc.update("UPDATE alert_delivery_task SET recipient_context_json=? WHERE id=?",merged,task.get("id"));
            }
            return;
        }
        // No INSERT ... SELECT / gap-lock scan. Manual retry tasks deliberately bypass this ledger.
        jdbc.update("INSERT INTO alert_delivery_task (id, event_id, service_id, channel_id, alert_content, alert_key, recipient_context_json, delivery_status, retry_count, max_retries, next_retry_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, 0)",
            UUID.randomUUID().toString(), eventId, serviceId, channel.id, content, key, channel.recipientContextJson, maxRetries);
    }

    public void enqueueHistory(Long eventId) {
        jdbc.update("INSERT INTO monitor_history_projection(event_id) VALUES (?)", eventId);
    }

    public void recordBlocked(Long serviceId, String alertKey, String type, String content) {
        // Re-evaluate configuration every check, but do not flood the audit with the
        // same unsent incident. A later queued/sent record opens a new audit episode.
        jdbc.update("INSERT INTO alert_notify_record(service_id,alert_key,alert_type,notify_status,notify_message) SELECT ?,?,?,'failed',? WHERE NOT EXISTS (SELECT 1 FROM alert_notify_record r WHERE r.id=(SELECT MAX(n.id) FROM alert_notify_record n WHERE n.service_id=? AND n.alert_key=?) AND r.notify_message=?)",
            serviceId, alertKey, type, content, serviceId, alertKey, content);
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
