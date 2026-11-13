package com.live.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.entity.AlertNotifyRecord;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Task outcomes are notifications; retries remain in the separate attempt history. */
@Repository
public class AlertAuditRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CryptoService crypto;
    private static final String SOURCE = "(SELECT NULL AS id,t.service_id,t.alert_key,NULL AS alert_record_id," +
        "c.channel_type AS alert_type,CASE t.delivery_status WHEN 'SUCCESS' THEN 'success' WHEN 'FINAL_FAILED' THEN 'failed' WHEN 'FAILED' THEN 'retrying' WHEN 'SENDING' THEN 'sending' ELSE 'pending' END AS notify_status," +
        "t.alert_content AS notify_message,t.created_at,t.id AS task_id,t.channel_id,c.channel_name,c.config_json,t.last_error,t.delivery_status,t.next_retry_at," +
        "(SELECT COUNT(*) FROM alert_delivery_attempt a WHERE a.task_id=t.id) AS attempt_count " +
        "FROM alert_delivery_task t LEFT JOIN alert_channel c ON c.id=t.channel_id UNION ALL " +
        "SELECT n.id,n.service_id,n.alert_key,n.alert_record_id,n.alert_type," +
        "CASE WHEN n.notify_message LIKE '%Delivery skipped:%' THEN 'blocked' ELSE n.notify_status END," +
        "n.notify_message,n.created_at,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0 FROM alert_notify_record n " +
        "WHERE COALESCE(n.alert_type,'') <> 'delivery_attempt' AND COALESCE(n.notify_status,'') <> 'pending') r";

    public AlertAuditRepository(JdbcTemplate jdbc, ObjectMapper json, CryptoService crypto) { this.jdbc=jdbc; this.json=json; this.crypto=crypto; }

    private String where(String status, String query, String start, String end, List<Object> args) {
        StringBuilder sql=new StringBuilder(" WHERE 1=1");
        if(status!=null) { sql.append(" AND r.notify_status=?"); args.add(status); }
        if(start!=null) { sql.append(" AND r.created_at>=?"); args.add(start); }
        if(end!=null) { sql.append(" AND r.created_at<?"); args.add(end); }
        if(query!=null) {
            sql.append(" AND (LOWER(COALESCE(s.service_name,'')) LIKE ? OR LOWER(COALESCE(r.channel_name,'')) LIKE ? OR LOWER(COALESCE(r.notify_message,'')) LIKE ? OR LOWER(COALESCE(r.last_error,'')) LIKE ?)");
            for(int i=0;i<4;i++) args.add("%"+query.toLowerCase(Locale.ROOT)+"%");
        }
        return sql.toString();
    }

    public long count(String status, String query, String start, String end) {
        List<Object> args=new ArrayList<>();
        String filter=where(status,query,start,end,args);
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+SOURCE+" LEFT JOIN monitor_service s ON s.id=r.service_id"+filter,Long.class,args.toArray());
    }

    public List<AlertNotifyRecord> list(String status, String query, String start, String end, int limit, int offset) {
        List<Object> args=new ArrayList<>(); String filter=where(status,query,start,end,args);
        args.add(limit); args.add(offset);
        return jdbc.query("SELECT r.*,s.service_name,s.service_type,s.cluster_name FROM "+SOURCE+" LEFT JOIN monitor_service s ON s.id=r.service_id"+filter+" ORDER BY r.created_at DESC,r.task_id DESC,r.id DESC LIMIT ? OFFSET ?",(row,index)->{
            AlertNotifyRecord record=new AlertNotifyRecord();
            record.id=(Long)row.getObject("id"); record.serviceId=row.getLong("service_id");
            record.serviceName=row.getString("service_name"); record.serviceType=row.getString("service_type"); record.clusterName=row.getString("cluster_name");
            record.alertKey=row.getString("alert_key"); record.alertRecordId=(Long)row.getObject("alert_record_id");
            record.alertType=row.getString("alert_type"); record.notifyStatus=row.getString("notify_status"); record.notifyMessage=row.getString("notify_message");
            record.createdAt=row.getTimestamp("created_at").toLocalDateTime(); record.taskId=row.getString("task_id");
            record.channelId=(Long)row.getObject("channel_id"); record.channelName=row.getString("channel_name");
            record.currentRecipients=targets(row.getString("config_json"),record.alertType);
            record.lastError=row.getString("last_error"); record.deliveryStatus=row.getString("delivery_status");
            record.attemptCount=row.getInt("attempt_count"); record.nextRetryAt=(Long)row.getObject("next_retry_at");
            return record;
        },args.toArray());
    }

    // Current configuration is explicitly labelled; historical recipient snapshots do not exist.
    private String targets(String config,String type) {
        try {
            JsonNode node=json.readTree(config==null?"{}":crypto.decryptIfEncrypted(config));
            if("email".equals(type)) return node.path("alert_email").asText("")+(node.path("alert_cc").asText("").isEmpty()?"":"；抄送："+node.path("alert_cc").asText());
            if("sms".equals(type)) return node.path("alert_mobile").asText("");
            String url=node.path("http_url").asText(node.path("webhook_url").asText(""));
            java.net.URI uri=java.net.URI.create(url);
            return uri.getHost()==null?"":uri.getScheme()+"://"+uri.getHost()+(uri.getPort()<0?"":":"+uri.getPort());
        } catch(Exception ignored) { return ""; }
    }
}
