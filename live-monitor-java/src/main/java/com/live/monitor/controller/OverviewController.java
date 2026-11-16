package com.live.monitor.controller;

import com.live.monitor.service.LiveMonitorService;
import com.live.monitor.service.HostMonitorService;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.entity.HostConfig;
import com.live.monitor.mapper.AlertMapper;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Read-only projection of stored observations. Never invokes a collector. */
@RestController
public class OverviewController {
    private final LiveMonitorService services;
    private final HostMonitorService hosts;
    private final AlertMapper alerts;
    public OverviewController(LiveMonitorService services, HostMonitorService hosts, AlertMapper alerts) {
        this.services = services; this.hosts = hosts; this.alerts = alerts;
    }
    @GetMapping("/api/overview/summary")
    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        try {
            Map<String, Object> dashboard = services.dashboard();
            result.put("summary", dashboard.get("summary"));
            result.put("recent_alerts", dashboard.get("recent_alerts"));
            result.put("server_time", dashboard.get("server_time"));
            List<Map<String,Object>> rows = new ArrayList<>();
            for (MonitorService s : services.listServices(true)) {
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("id", s.id); row.put("service_name", s.serviceName);
                row.put("cluster_name", s.clusterName); row.put("service_type", s.serviceType);
                row.put("host_id", s.hostId); row.put("enabled", s.enabled);
                row.put("last_message", s.lastMessage); row.put("last_status", s.lastStatus); row.put("last_checked_at", s.lastCheckedAt);
                rows.add(row);
            }
            result.put("services", rows);
        } catch (Exception ex) { errors.put("services", "服务快照暂不可用"); }
        try {
            List<Map<String,Object>> rows = new ArrayList<>();
            for (HostConfig h : hosts.listHosts(true)) {
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("id", h.id); row.put("host_name", h.hostName); row.put("ip", h.ip);
                row.put("enabled", h.enabled); row.put("monitor_enabled", h.monitorEnabled);
                row.put("last_status", h.lastStatus); row.put("last_message", h.lastMessage);
                row.put("metric_checked_at", h.metricCheckedAt); row.put("check_interval", h.checkInterval);
                row.put("last_checked_at_epoch_ms", h.lastCheckedAtEpochMs);
                row.put("cpu_usage_percent", h.cpuUsagePercent);
                row.put("memory_used_percent", h.memoryUsedPercent); row.put("disk_used_percent", h.diskUsedPercent);
                rows.add(row);
            }
            result.put("hosts", rows);
        } catch (Exception ex) { errors.put("hosts", "主机快照暂不可用"); }
        try { result.put("active_service_alerts", alerts.countActiveServiceAlerts()); }
        catch (Exception ex) { errors.put("alerts", "活动服务告警暂不可用"); }
        result.put("errors", errors);
        return result;
    }
}
