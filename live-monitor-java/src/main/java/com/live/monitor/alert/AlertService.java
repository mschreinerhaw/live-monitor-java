package com.live.monitor.alert;

import com.live.monitor.entity.AlertChannel;
import com.live.monitor.entity.AlertPolicy;
import com.live.monitor.entity.AlertRecord;
import com.live.monitor.entity.MonitorResult;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.AlertMapper;
import com.live.monitor.store.RocksDbHistoryRepository;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AlertService {
    private static final Pattern CPU_PATTERN = Pattern.compile("CPU\\s+([0-9.]+)%", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEMORY_PATTERN = Pattern.compile("Memory\\s+([0-9.]+)%", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISK_PATTERN = Pattern.compile("Disk\\s+([0-9.]+)%", Pattern.CASE_INSENSITIVE);

    private final AlertMapper alertMapper;
    private final RocksDbHistoryRepository historyRepository;
    private final AlertDeliveryService deliveryService;

    public AlertService(
        AlertMapper alertMapper,
        RocksDbHistoryRepository historyRepository,
        AlertDeliveryService deliveryService
    ) {
        this.alertMapper = alertMapper;
        this.historyRepository = historyRepository;
        this.deliveryService = deliveryService;
    }

    public void evaluate(MonitorService service, MonitorResult result, String previousStatus) {
        if (service.alertGroupId == null) {
            if ("DOWN".equals(result.status) && "UP".equals(previousStatus)) {
                record(service, "Service changed from UP to DOWN: " + safe(result.message), "record", "success");
            }
            return;
        }
        if (Boolean.FALSE.equals(service.alertGroupEnabled)) {
            return;
        }
        List<AlertPolicy> policies = alertMapper.listPoliciesByGroup(service.alertGroupId);
        for (AlertPolicy policy : policies) {
            if (!Boolean.TRUE.equals(policy.enabled) || !triggered(service, result, previousStatus, policy)) {
                continue;
            }
            dispatch(service, result, policy);
        }
    }

    public AlertRecord testAlert(MonitorService service) {
        List<AlertRecord> records = dispatch(
            service,
            "[Test Alert] Service " + service.serviceName + " alert delivery test.",
            "test"
        );
        return records.isEmpty()
            ? record(service, "No alert record generated.", "test", "failed")
            : records.get(0);
    }

    private List<AlertRecord> dispatch(MonitorService service, MonitorResult result, AlertPolicy policy) {
        String fallbackContent = defaultContent(service, result, policy);
        List<AlertRecord> records = new ArrayList<AlertRecord>();
        if (service.alertGroupId == null) {
            records.add(record(service, fallbackContent + " Delivery skipped: no alert group bound.", policy.triggerType, "failed"));
            return records;
        }
        if (Boolean.FALSE.equals(service.alertGroupEnabled)) {
            records.add(record(service, fallbackContent + " Delivery skipped: alert group is disabled.", policy.triggerType, "failed"));
            return records;
        }

        List<AlertChannel> channels = alertMapper.listChannelsByGroup(service.alertGroupId);
        int enabledChannels = 0;
        for (AlertChannel channel : channels) {
            if (!Boolean.TRUE.equals(channel.enabled)) {
                continue;
            }
            enabledChannels++;
            String content = content(service, result, policy, channel, fallbackContent);
            AlertDeliveryService.DeliveryResult delivery = deliveryService.send(channel, content);
            String recordContent = delivery.success || !StringUtils.hasText(delivery.message)
                ? content
                : content + " Delivery error: " + delivery.message;
            records.add(record(
                service,
                recordContent,
                StringUtils.hasText(channel.channelType) ? channel.channelType : policy.triggerType,
                delivery.success ? "success" : "failed"
            ));
        }
        if (enabledChannels == 0) {
            records.add(record(service, fallbackContent + " Delivery skipped: no enabled alert channels.", policy.triggerType, "failed"));
        }
        return records;
    }

    private boolean triggered(MonitorService service, MonitorResult result, String previousStatus, AlertPolicy policy) {
        if ("consecutive_down".equals(policy.triggerType)) {
            int threshold = intValue(policy.triggerValue, 3);
            List<MonitorResult> rows = historyRepository.listMonitorResults(service.id, threshold + 1);
            if (rows.size() < threshold) {
                return false;
            }
            for (int i = 0; i < threshold; i++) {
                if (!"DOWN".equals(rows.get(i).status)) {
                    return false;
                }
            }
            return rows.size() <= threshold || !"DOWN".equals(rows.get(threshold).status);
        }
        if ("latency_gt_ms".equals(policy.triggerType)) {
            if (result.responseTimeMs == null) {
                return false;
            }
            int threshold = intValue(policy.triggerValue, 3000);
            return "UP".equals(result.status) && result.responseTimeMs > threshold;
        }
        if ("recovered".equals(policy.triggerType)) {
            return "UP".equals(result.status) && "DOWN".equals(previousStatus);
        }
        return false;
    }

    private List<AlertRecord> dispatch(MonitorService service, String content, String fallbackType) {
        List<AlertRecord> records = new ArrayList<AlertRecord>();
        if (service.alertGroupId == null) {
            records.add(record(service, content + " Delivery skipped: no alert group bound.", fallbackType, "failed"));
            return records;
        }
        if (Boolean.FALSE.equals(service.alertGroupEnabled)) {
            records.add(record(service, content + " Delivery skipped: alert group is disabled.", fallbackType, "failed"));
            return records;
        }

        List<AlertChannel> channels = alertMapper.listChannelsByGroup(service.alertGroupId);
        int enabledChannels = 0;
        for (AlertChannel channel : channels) {
            if (!Boolean.TRUE.equals(channel.enabled)) {
                continue;
            }
            enabledChannels++;
            AlertDeliveryService.DeliveryResult delivery = deliveryService.send(channel, content);
            String recordContent = delivery.success || !StringUtils.hasText(delivery.message)
                ? content
                : content + " Delivery error: " + delivery.message;
            records.add(record(
                service,
                recordContent,
                StringUtils.hasText(channel.channelType) ? channel.channelType : fallbackType,
                delivery.success ? "success" : "failed"
            ));
        }
        if (enabledChannels == 0) {
            records.add(record(service, content + " Delivery skipped: no enabled alert channels.", fallbackType, "failed"));
        }
        return records;
    }

    private AlertRecord record(MonitorService service, String content, String type, String status) {
        AlertRecord record = new AlertRecord();
        record.serviceId = service.id;
        record.alertType = type;
        record.alertContent = content;
        record.alertStatus = status;
        record.serviceName = service.serviceName;
        record.serviceType = service.serviceType;
        record.clusterName = service.clusterName;
        return historyRepository.saveAlertRecord(record);
    }

    private String content(
        MonitorService service,
        MonitorResult result,
        AlertPolicy policy,
        AlertChannel channel,
        String fallbackContent
    ) {
        String templateName = templateName(service, policy, channel);
        return deliveryService.renderTemplate(templateName, templateVariables(service, result, policy), fallbackContent);
    }

    private String defaultContent(MonitorService service, MonitorResult result, AlertPolicy policy) {
        return "Service " + service.serviceName + " triggered " + policy.policyName +
            ". Status: " + result.status +
            ". Type: " + service.serviceType +
            ". Response: " + (result.responseTimeMs == null ? "-" : result.responseTimeMs) + " ms" +
            ". Message: " + safe(result.message);
    }

    private String templateName(MonitorService service, AlertPolicy policy, AlertChannel channel) {
        if ("recovered".equals(policy.triggerType)) {
            return "sms_service_recover.j2";
        }
        if ("host".equals(normalize(service.serviceType))) {
            return "alert_host_resource.j2";
        }
        if ("email".equals(normalize(channel.channelType))) {
            return "email_service_alert.j2";
        }
        return "sms_service_alert.j2";
    }

    private Map<String, Object> templateVariables(MonitorService service, MonitorResult result, AlertPolicy policy) {
        Map<String, Object> variables = new LinkedHashMap<String, Object>();
        Map<String, String> hostMetrics = hostMetrics(result.message);
        variables.put("serviceName", safe(service.serviceName));
        variables.put("instanceName", instanceName(service));
        variables.put("host", firstNonBlank(service.host, service.endpoint, service.url, service.clusterName, "-"));
        variables.put("level", alertLevel(policy, result));
        variables.put("alertTime", eventTime(result));
        variables.put("recoverTime", eventTime(result));
        variables.put("duration", "-");
        variables.put("responseTime", result.responseTimeMs == null ? "-" : result.responseTimeMs);
        variables.put("errorMsg", safe(result.message));
        variables.put("cpu", hostMetrics.get("cpu"));
        variables.put("memory", hostMetrics.get("memory"));
        variables.put("disk", hostMetrics.get("disk"));
        return variables;
    }

    private Map<String, String> hostMetrics(String message) {
        Map<String, String> metrics = new LinkedHashMap<String, String>();
        metrics.put("cpu", firstMatch(CPU_PATTERN, message));
        metrics.put("memory", firstMatch(MEMORY_PATTERN, message));
        metrics.put("disk", firstMatch(DISK_PATTERN, message));
        return metrics;
    }

    private String firstMatch(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : "-";
    }

    private String instanceName(MonitorService service) {
        if (StringUtils.hasText(service.endpoint)) {
            return service.endpoint.trim();
        }
        if (StringUtils.hasText(service.url)) {
            return service.url.trim();
        }
        if (StringUtils.hasText(service.host) && service.port != null) {
            return service.host.trim() + ":" + service.port;
        }
        return firstNonBlank(service.host, service.clusterName, service.id == null ? null : String.valueOf(service.id), "-");
    }

    private String alertLevel(AlertPolicy policy, MonitorResult result) {
        if ("DOWN".equals(result.status) || "consecutive_down".equals(policy.triggerType)) {
            return "CRITICAL";
        }
        if ("latency_gt_ms".equals(policy.triggerType)) {
            return "WARNING";
        }
        return "INFO";
    }

    private String eventTime(MonitorResult result) {
        if (StringUtils.hasText(result.checkedAt)) {
            return result.checkedAt.trim();
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private int intValue(String value, int defaultValue) {
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }
}
