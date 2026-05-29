package com.live.monitor.alert;

import com.live.monitor.entity.AlertPolicy;
import com.live.monitor.entity.AlertRecord;
import com.live.monitor.entity.MonitorResult;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.AlertMapper;
import com.live.monitor.mapper.MonitorResultMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AlertService {
    private final AlertMapper alertMapper;
    private final MonitorResultMapper resultMapper;

    public AlertService(AlertMapper alertMapper, MonitorResultMapper resultMapper) {
        this.alertMapper = alertMapper;
        this.resultMapper = resultMapper;
    }

    public void evaluate(MonitorService service, MonitorResult result, String previousStatus) {
        if (service.alertGroupId == null) {
            if ("DOWN".equals(result.status) && "UP".equals(previousStatus)) {
                record(service, "服务从 UP 变为 DOWN: " + safe(result.message), "record");
            }
            return;
        }
        List<AlertPolicy> policies = alertMapper.listPoliciesByGroup(service.alertGroupId);
        for (AlertPolicy policy : policies) {
            if (!Boolean.TRUE.equals(policy.enabled) || !triggered(service, result, previousStatus, policy)) {
                continue;
            }
            record(service, content(service, result, policy), policy.triggerType);
        }
    }

    public AlertRecord testAlert(MonitorService service) {
        return record(service, "[测试告警] 服务 " + service.serviceName + " 的告警绑定测试。", "test");
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

    private AlertRecord record(MonitorService service, String content, String type) {
        AlertRecord record = new AlertRecord();
        record.serviceId = service.id;
        record.alertType = type;
        record.alertContent = content;
        record.alertStatus = "success";
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
