package com.live.monitor.alert;

import com.live.monitor.entity.AlertChannel;
import com.live.monitor.entity.AlertPolicy;
import com.live.monitor.entity.AlertRecord;
import com.live.monitor.entity.AlertState;
import com.live.monitor.entity.CheckEvent;
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
    private static final String HOST_RESOURCE_THRESHOLD_ALERT = "host_resource_threshold";
    private static final String AVAILABILITY_ALERT_KEY = "availability";
    private static final String STATE_NORMAL = "NORMAL";
    private static final String STATE_PENDING = "PENDING";
    private static final String STATE_ALERTING = "ALERTING";
    private static final String STATE_RECOVERED = "RECOVERED";
    private static final int DEFAULT_DOWN_CONFIRM_THRESHOLD = 3;
    private static final int DEFAULT_RECOVER_CONFIRM_THRESHOLD = 2;
    private static final Pattern CPU_PATTERN = Pattern.compile("CPU\\s+([0-9.]+)%", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEMORY_PATTERN = Pattern.compile("Memory\\s+([0-9.]+)%", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISK_PATTERN = Pattern.compile("Disk\\s+([0-9.]+)%", Pattern.CASE_INSENSITIVE);
    private static final Pattern CPU_THRESHOLD_PATTERN = Pattern.compile("CPU\\s+([0-9.]+)%\\s*/\\s*([0-9.]+%|disabled)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEMORY_THRESHOLD_PATTERN = Pattern.compile("Memory\\s+([0-9.]+)%\\s*/\\s*([0-9.]+%|disabled)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISK_THRESHOLD_PATTERN = Pattern.compile("Disk\\s+([0-9.]+)%\\s*/\\s*([0-9.]+%|disabled)", Pattern.CASE_INSENSITIVE);

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

    public void publishCheckEvent(MonitorService service, MonitorResult result) {
        CheckEvent event = checkEvent(result);
        alertMapper.insertCheckEvent(event);
        consumeCheckEvent(service, event);
    }

    public void evaluate(MonitorService service, MonitorResult result, String previousStatus) {
        publishCheckEvent(service, result);
    }

    public AlertRecord testAlert(MonitorService service) {
        List<AlertRecord> records = dispatch(
            service,
            "[Test Alert] Service " + service.serviceName + " alert delivery test.",
            "test",
            "test"
        );
        return records.isEmpty()
            ? record(service, "No alert record generated.", "test", "failed", "test")
            : records.get(0);
    }

    private CheckEvent checkEvent(MonitorResult result) {
        CheckEvent event = new CheckEvent();
        event.serviceId = result.serviceId;
        event.status = result.status;
        event.responseTimeMs = result.responseTimeMs;
        event.message = result.message;
        event.alertType = result.alertType;
        event.checkedAt = result.checkedAt;
        return event;
    }

    private MonitorResult monitorResult(CheckEvent event) {
        MonitorResult result = new MonitorResult();
        result.serviceId = event.serviceId;
        result.status = event.status;
        result.responseTimeMs = event.responseTimeMs;
        result.message = event.message;
        result.alertType = event.alertType;
        result.checkedAt = event.checkedAt;
        return result;
    }

    private AlertState state(Long serviceId, String alertKey) {
        AlertState existing = alertMapper.findAlertState(serviceId, alertKey);
        if (existing != null) {
            return existing;
        }
        AlertState state = new AlertState();
        state.serviceId = serviceId;
        state.alertKey = alertKey;
        state.state = STATE_NORMAL;
        state.failCount = 0;
        state.recoverCount = 0;
        return state;
    }

    private void applyState(
        AlertState state,
        String status,
        int failCount,
        int recoverCount,
        AlertPolicy activePolicy,
        MonitorResult result,
        CheckEvent event
    ) {
        state.state = status;
        state.failCount = failCount;
        state.recoverCount = recoverCount;
        state.activePolicyId = activePolicy == null ? null : activePolicy.id;
        state.activeTriggerType = activePolicy == null ? null : activePolicy.triggerType;
        state.lastStatus = result.status;
        state.lastMessage = result.message;
        state.lastEventAt = event.checkedAt;
        if (STATE_ALERTING.equals(status) && activePolicy != null) {
            state.lastAlertAt = event.checkedAt;
        }
        alertMapper.upsertAlertState(state);
    }

    private AlertPolicy firstEnabledPolicy(List<AlertPolicy> policies, String triggerType) {
        for (AlertPolicy policy : policies) {
            if (Boolean.TRUE.equals(policy.enabled) && triggerType.equals(policy.triggerType)) {
                return policy;
            }
        }
        return null;
    }

    private AlertPolicy defaultDownPolicy() {
        AlertPolicy policy = new AlertPolicy();
        policy.policyName = "DOWN consecutive " + DEFAULT_DOWN_CONFIRM_THRESHOLD + " times";
        policy.triggerType = "consecutive_down";
        policy.triggerValue = String.valueOf(DEFAULT_DOWN_CONFIRM_THRESHOLD);
        policy.enabled = true;
        return policy;
    }

    private void consumeCheckEvent(MonitorService service, CheckEvent event) {
        MonitorResult result = monitorResult(event);
        if (isHostResourceThresholdAlert(service, result)) {
            confirmHostResource(service, result, event, new ArrayList<AlertPolicy>());
            alertMapper.markCheckEventConsumed(event.id);
            return;
        }
        List<AlertPolicy> policies = service.alertGroupId == null || Boolean.FALSE.equals(service.alertGroupEnabled)
            ? new ArrayList<AlertPolicy>()
            : alertMapper.listPoliciesByGroup(service.alertGroupId);

        confirmAvailability(service, result, event, policies);
        confirmLatency(service, result, event, policies);
        confirmHostResource(service, result, event, policies);
        alertMapper.markCheckEventConsumed(event.id);
    }

    private void confirmAvailability(
        MonitorService service,
        MonitorResult result,
        CheckEvent event,
        List<AlertPolicy> policies
    ) {
        AlertPolicy downPolicy = firstEnabledPolicy(policies, "consecutive_down");
        AlertPolicy recoverPolicy = firstEnabledPolicy(policies, "recovered");
        boolean canNotify = service.alertGroupId != null && !Boolean.FALSE.equals(service.alertGroupEnabled) && downPolicy != null;
        if (downPolicy == null) {
            downPolicy = defaultDownPolicy();
        }

        AlertState state = state(service.id, AVAILABILITY_ALERT_KEY);
        if ("DOWN".equals(result.status)) {
            int failCount = intValue(state.failCount, 0) + 1;
            int threshold = intValue(downPolicy.triggerValue, DEFAULT_DOWN_CONFIRM_THRESHOLD);
            if (failCount >= threshold) {
                boolean enteringAlert = !STATE_ALERTING.equals(state.state);
                applyState(state, STATE_ALERTING, failCount, 0, downPolicy, result, event);
                if (enteringAlert) {
                    if (canNotify) {
                        dispatch(service, result, downPolicy, AVAILABILITY_ALERT_KEY);
                    } else if (service.alertGroupId == null) {
                        record(
                            service,
                            "Service confirmed DOWN after " + threshold + " checks: " + safe(result.message),
                            "record",
                            "success",
                            AVAILABILITY_ALERT_KEY
                        );
                    }
                }
                return;
            }
            applyState(state, STATE_PENDING, failCount, 0, downPolicy, result, event);
            return;
        }

        if ("UP".equals(result.status)) {
            if (STATE_ALERTING.equals(state.state)) {
                int recoverCount = intValue(state.recoverCount, 0) + 1;
                if (recoverCount >= DEFAULT_RECOVER_CONFIRM_THRESHOLD) {
                    if (canNotify && recoverPolicy != null) {
                        dispatch(service, result, recoverPolicy, AVAILABILITY_ALERT_KEY);
                    }
                    applyState(state, STATE_RECOVERED, 0, recoverCount, null, result, event);
                    return;
                }
                applyState(state, STATE_ALERTING, 0, recoverCount, downPolicy, result, event);
                return;
            }
            applyState(state, STATE_RECOVERED.equals(state.state) ? STATE_NORMAL : STATE_NORMAL, 0, 0, null, result, event);
        }
    }

    private void confirmLatency(
        MonitorService service,
        MonitorResult result,
        CheckEvent event,
        List<AlertPolicy> policies
    ) {
        if (service.alertGroupId == null || Boolean.FALSE.equals(service.alertGroupEnabled)) {
            return;
        }
        for (AlertPolicy policy : policies) {
            if (!Boolean.TRUE.equals(policy.enabled) || !"latency_gt_ms".equals(policy.triggerType)) {
                continue;
            }
            String alertKey = "latency_gt_ms:" + policy.id;
            AlertState state = state(service.id, alertKey);
            boolean slow = "UP".equals(result.status)
                && result.responseTimeMs != null
                && result.responseTimeMs > intValue(policy.triggerValue, 3000);
            if (slow) {
                int failCount = intValue(state.failCount, 0) + 1;
                boolean enteringAlert = !STATE_ALERTING.equals(state.state);
                applyState(state, STATE_ALERTING, failCount, 0, policy, result, event);
                if (enteringAlert) {
                    dispatch(service, result, policy, alertKey);
                }
            } else if (STATE_ALERTING.equals(state.state) || STATE_PENDING.equals(state.state)) {
                applyState(state, STATE_NORMAL, 0, 0, null, result, event);
            }
        }
    }

    private void confirmHostResource(
        MonitorService service,
        MonitorResult result,
        CheckEvent event,
        List<AlertPolicy> policies
    ) {
        if (!"host".equals(normalize(service.serviceType))) {
            return;
        }
        AlertState state = state(service.id, HOST_RESOURCE_THRESHOLD_ALERT);
        boolean thresholdExceeded = isHostResourceThresholdAlert(service, result);
        AlertPolicy thresholdPolicy = hostResourceThresholdPolicy();
        if (thresholdExceeded) {
            int failCount = intValue(state.failCount, 0) + 1;
            boolean enteringAlert = !STATE_ALERTING.equals(state.state);
            applyState(state, STATE_ALERTING, failCount, 0, thresholdPolicy, result, event);
            if (enteringAlert && service.alertGroupId != null && !Boolean.FALSE.equals(service.alertGroupEnabled)) {
                dispatch(service, result, thresholdPolicy, HOST_RESOURCE_THRESHOLD_ALERT);
            }
            return;
        }
        if (STATE_ALERTING.equals(state.state)) {
            int recoverCount = intValue(state.recoverCount, 0) + 1;
            if (recoverCount >= DEFAULT_RECOVER_CONFIRM_THRESHOLD) {
                AlertPolicy recoverPolicy = firstEnabledPolicy(policies, "recovered");
                if (service.alertGroupId != null && !Boolean.FALSE.equals(service.alertGroupEnabled) && recoverPolicy != null) {
                    dispatch(service, result, recoverPolicy, HOST_RESOURCE_THRESHOLD_ALERT);
                }
                applyState(state, STATE_RECOVERED, 0, recoverCount, null, result, event);
                return;
            }
            applyState(state, STATE_ALERTING, 0, recoverCount, thresholdPolicy, result, event);
        } else if (STATE_RECOVERED.equals(state.state)) {
            applyState(state, STATE_NORMAL, 0, 0, null, result, event);
        }
    }

    private List<AlertRecord> dispatch(MonitorService service, MonitorResult result, AlertPolicy policy, String alertKey) {
        String fallbackContent = defaultContent(service, result, policy);
        List<AlertRecord> records = new ArrayList<AlertRecord>();
        if (service.alertGroupId == null) {
            records.add(record(service, fallbackContent + " Delivery skipped: no alert group bound.", policy.triggerType, "failed", alertKey));
            return records;
        }
        if (Boolean.FALSE.equals(service.alertGroupEnabled)) {
            records.add(record(service, fallbackContent + " Delivery skipped: alert group is disabled.", policy.triggerType, "failed", alertKey));
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
                delivery.success ? "success" : "failed",
                alertKey
            ));
        }
        if (enabledChannels == 0) {
            records.add(record(service, fallbackContent + " Delivery skipped: no enabled alert channels.", policy.triggerType, "failed", alertKey));
        }
        return records;
    }

    private boolean triggered(MonitorService service, MonitorResult result, String previousStatus, AlertPolicy policy) {
        if ("consecutive_down".equals(policy.triggerType)) {
            if (!"DOWN".equals(result.status)) {
                return false;
            }
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
        if (HOST_RESOURCE_THRESHOLD_ALERT.equals(policy.triggerType)) {
            return isHostResourceThresholdAlert(service, result);
        }
        return false;
    }

    private boolean isHostResourceThresholdAlert(MonitorService service, MonitorResult result) {
        return service != null
            && result != null
            && "host".equals(normalize(service.serviceType))
            && HOST_RESOURCE_THRESHOLD_ALERT.equals(result.alertType);
    }

    private AlertPolicy hostResourceThresholdPolicy() {
        AlertPolicy policy = new AlertPolicy();
        policy.policyName = "Host resource threshold exceeded";
        policy.triggerType = HOST_RESOURCE_THRESHOLD_ALERT;
        policy.enabled = true;
        return policy;
    }

    private List<AlertRecord> dispatch(MonitorService service, String content, String fallbackType, String alertKey) {
        List<AlertRecord> records = new ArrayList<AlertRecord>();
        if (service.alertGroupId == null) {
            records.add(record(service, content + " Delivery skipped: no alert group bound.", fallbackType, "failed", alertKey));
            return records;
        }
        if (Boolean.FALSE.equals(service.alertGroupEnabled)) {
            records.add(record(service, content + " Delivery skipped: alert group is disabled.", fallbackType, "failed", alertKey));
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
                delivery.success ? "success" : "failed",
                alertKey
            ));
        }
        if (enabledChannels == 0) {
            records.add(record(service, content + " Delivery skipped: no enabled alert channels.", fallbackType, "failed", alertKey));
        }
        return records;
    }

    private AlertRecord record(MonitorService service, String content, String type, String status, String alertKey) {
        AlertRecord record = new AlertRecord();
        record.serviceId = service.id;
        record.alertType = type;
        record.alertContent = content;
        record.alertStatus = status;
        record.serviceName = service.serviceName;
        record.serviceType = service.serviceType;
        record.clusterName = service.clusterName;
        AlertRecord saved = historyRepository.saveAlertRecord(record);
        alertMapper.insertNotifyRecord(service.id, alertKey, saved.id, type, status, content);
        return saved;
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
        variables.put("alertReason", alertReason(service, result));
        variables.put("recoverReason", recoverReason(service, result, policy));
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

    private String alertReason(MonitorService service, MonitorResult result) {
        if ("host".equals(normalize(service.serviceType))) {
            return hostAlertReason(result.message);
        }
        return safe(result.message);
    }

    private String recoverReason(MonitorService service, MonitorResult result, AlertPolicy policy) {
        if ("host".equals(normalize(service.serviceType))) {
            return hostRecoverReason(result.message);
        }
        if ("recovered".equals(policy.triggerType)) {
            return "本次检测状态恢复为 UP";
        }
        return "-";
    }

    private String hostAlertReason(String message) {
        List<String> reasons = new ArrayList<String>();
        addExceededReason(reasons, hostThreshold(CPU_THRESHOLD_PATTERN, message, "CPU使用率"));
        addExceededReason(reasons, hostThreshold(MEMORY_THRESHOLD_PATTERN, message, "内存使用率"));
        addExceededReason(reasons, hostThreshold(DISK_THRESHOLD_PATTERN, message, "磁盘使用率"));
        return reasons.isEmpty() ? safe(message) : String.join("；", reasons);
    }

    private String hostRecoverReason(String message) {
        List<String> reasons = new ArrayList<String>();
        addRecoverReason(reasons, hostThreshold(CPU_THRESHOLD_PATTERN, message, "CPU使用率"));
        addRecoverReason(reasons, hostThreshold(MEMORY_THRESHOLD_PATTERN, message, "内存使用率"));
        addRecoverReason(reasons, hostThreshold(DISK_THRESHOLD_PATTERN, message, "磁盘使用率"));
        return reasons.isEmpty() ? "-" : "所有已启用指标低于告警阈值（" + String.join("；", reasons) + "）";
    }

    private HostThreshold hostThreshold(Pattern pattern, String message, String label) {
        Matcher matcher = pattern.matcher(message == null ? "" : message);
        if (!matcher.find()) {
            return null;
        }
        Double value = doubleValue(matcher.group(1));
        String thresholdText = matcher.group(2);
        if (value == null || "disabled".equalsIgnoreCase(thresholdText)) {
            return null;
        }
        Double threshold = doubleValue(thresholdText.replace("%", ""));
        return threshold == null ? null : new HostThreshold(label, matcher.group(1), value, thresholdText, threshold);
    }

    private void addExceededReason(List<String> reasons, HostThreshold metric) {
        if (metric != null && metric.value >= metric.threshold) {
            reasons.add(metric.label + " " + metric.valueText + "% >= 告警阈值 " + metric.thresholdText);
        }
    }

    private void addRecoverReason(List<String> reasons, HostThreshold metric) {
        if (metric != null) {
            reasons.add(metric.label + " < " + metric.thresholdText);
        }
    }

    private Double doubleValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
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
        if ("latency_gt_ms".equals(policy.triggerType) || HOST_RESOURCE_THRESHOLD_ALERT.equals(policy.triggerType)) {
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

    private int intValue(Integer value, int defaultValue) {
        return value == null ? defaultValue : Math.max(0, value);
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private static class HostThreshold {
        final String label;
        final String valueText;
        final double value;
        final String thresholdText;
        final double threshold;

        HostThreshold(String label, String valueText, double value, String thresholdText, double threshold) {
            this.label = label;
            this.valueText = valueText;
            this.value = value;
            this.thresholdText = thresholdText;
            this.threshold = threshold;
        }
    }
}
