package com.live.monitor.alert;

import com.live.monitor.entity.AlertChannel;
import com.live.monitor.mapper.AlertMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertTaskAdmin {
    private final JdbcTemplate jdbc;
    private final AlertMapper mapper;
    private final AlertDeliveryService delivery;
    public AlertTaskAdmin(JdbcTemplate jdbc, AlertMapper mapper, AlertDeliveryService delivery) { this.jdbc = jdbc; this.mapper = mapper; this.delivery = delivery; }

    public Map<String, Object> list(Long service, Long channel, String status, String start, String end, int page) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (service != null) { where.append(" AND t.service_id = ?"); args.add(service); }
        if (channel != null) { where.append(" AND t.channel_id = ?"); args.add(channel); }
        if (status != null && !status.isEmpty()) { where.append(" AND t.delivery_status = ?"); args.add(status); }
        if (start != null && !start.isEmpty()) { where.append(" AND t.created_at >= ?"); args.add(start); }
        if (end != null && !end.isEmpty()) { where.append(" AND t.created_at < ?"); args.add(end); }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task t" + where, Long.class, args.toArray()));
        args.add(50); args.add((Math.max(1, Math.min(1000000, page)) - 1) * 50);
        result.put("items", jdbc.queryForList("SELECT t.*, s.service_name, c.channel_name, (SELECT COUNT(*) FROM alert_delivery_attempt a WHERE a.task_id=t.id) AS attempt_count, (SELECT MAX(a.created_at) FROM alert_delivery_attempt a WHERE a.task_id=t.id) AS last_attempt_at FROM alert_delivery_task t LEFT JOIN monitor_service s ON s.id=t.service_id LEFT JOIN alert_channel c ON c.id=t.channel_id" + where + " ORDER BY t.created_at DESC, t.id LIMIT ? OFFSET ?", args.toArray()));
        result.put("counts", jdbc.queryForList("SELECT delivery_status, COUNT(*) AS total FROM alert_delivery_task GROUP BY delivery_status"));
        result.put("oldest_pending_at", jdbc.queryForObject("SELECT MIN(created_at) FROM alert_delivery_task WHERE delivery_status IN ('PENDING','FAILED','SENDING')", java.sql.Timestamp.class));
        return result;
    }

    public List<Map<String, Object>> attempts(String id) {
        return jdbc.queryForList("SELECT * FROM alert_delivery_attempt WHERE task_id = ? ORDER BY created_at, id", id);
    }

    public List<com.live.monitor.entity.AlertRecord> recentAlerts(Long service, int limit) {
        String filter = service == null ? "" : " AND t.service_id = ?";
        List<Object> args = new ArrayList<>();
        if (service != null) args.add(service);
        args.add(limit);
        return jdbc.query("SELECT t.*, c.channel_type FROM alert_delivery_task t LEFT JOIN alert_channel c ON c.id=t.channel_id WHERE 1=1"+filter+" ORDER BY t.created_at DESC LIMIT ?", (row, index) -> {
            com.live.monitor.entity.AlertRecord record = new com.live.monitor.entity.AlertRecord();
            record.id=row.getLong("event_id"); record.taskId=row.getString("id");
            record.serviceId=row.getLong("service_id"); record.alertType=row.getString("channel_type");
            record.alertContent=row.getString("alert_content");
            String status=row.getString("delivery_status");
            record.alertStatus="SUCCESS".equals(status)?"success":("FINAL_FAILED".equals(status)||"FAILED".equals(status))?"failed":"pending";
            record.createdAt=row.getTimestamp("created_at").toString();
            return record;
        },args.toArray());
    }

    @Transactional
    public String retry(String id, String actor) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM alert_delivery_task WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert task not found");
        Map<String, Object> task = rows.get(0);
        if (!"FINAL_FAILED".equals(task.get("delivery_status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only final failed tasks can be retried");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM alert_manual_retry r JOIN alert_delivery_task t ON t.id=r.new_task_id WHERE r.original_task_id=? AND t.delivery_status IN ('PENDING','SENDING','FAILED')", Integer.class, id) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Manual retry already active");
        AlertChannel channel = mapper.findChannel(((Number) task.get("channel_id")).longValue());
        if (channel == null || !Boolean.TRUE.equals(channel.enabled)) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Channel missing or disabled; repair channel configuration first");
        String configurationError = delivery.configurationError(channel);
        if (configurationError != null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, configurationError);
        String next = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO alert_delivery_task(id,event_id,service_id,channel_id,alert_content,alert_key,delivery_status,retry_count,max_retries,next_retry_at) SELECT ?,event_id,service_id,channel_id,alert_content,alert_key,'PENDING',0,max_retries,0 FROM alert_delivery_task WHERE id=?", next, id);
        jdbc.update("INSERT INTO alert_manual_retry(original_task_id,new_task_id,actor) VALUES (?,?,?)", id, next, actor);
        return next;
    }
}
