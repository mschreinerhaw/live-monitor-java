package com.live.monitor.alert;

import com.live.monitor.entity.AlertChannel;
import com.live.monitor.entity.AlertPolicy;
import com.live.monitor.entity.AlertRecord;
import com.live.monitor.entity.MonitorResult;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.AlertMapper;
import com.live.monitor.mapper.MonitorResultMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AlertService {
    private final AlertMapper alertMapper;
    private final MonitorResultMapper resultMapper;
    private final AlertDeliveryService deliveryService;

    public AlertService(
        AlertMapper alertMapper,
        MonitorResultMapper resultMapper,
        AlertDeliveryService deliveryService
    ) {
        this.alertMapper = alertMapper;
        this.resultMapper = resultMapper;
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
            dispatch(service, content(service, result, policy), policy.triggerType);
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

    private boolean triggered(MonitorService service, MonitorResult result, String previousStatus, AlertPolicy policy) {
        if ("consecutive_down".equals(policy.triggerType)) {
            int threshold = intValue(policy.triggerValue, 3);
            List<MonitorResult> rows = resultMapper.listByService(service.id, threshold + 1);
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
            return result.responseTimeMs > threshold;
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
        alertMapper.insertAlertRecord(record);
        return record;
    }

    private String content(MonitorService service, MonitorResult result, AlertPolicy policy) {
        return "Service " + service.serviceName + " triggered " + policy.policyName +
            ". Status: " + result.status +
            ". Type: " + service.serviceType +
            ". Response: " + (result.responseTimeMs == null ? "-" : result.responseTimeMs) + " ms" +
            ". Message: " + safe(result.message);
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
