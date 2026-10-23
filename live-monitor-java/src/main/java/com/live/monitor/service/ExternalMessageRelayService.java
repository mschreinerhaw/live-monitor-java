package com.live.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.alert.AlertService;
import com.live.monitor.entity.AlertRecord;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.MonitorServiceMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExternalMessageRelayService {
    public static final String SERVICE_TYPE = "external_message";
    private static final int MAX_MESSAGE_LENGTH = 100000;

    private final MonitorServiceMapper serviceMapper;
    private final LiveMonitorService liveMonitorService;
    private final AlertService alertService;
    private final ObjectMapper objectMapper;
    private final ExternalMessageAuditService auditService;
    private final ExternalProjectService projectService;

    public ExternalMessageRelayService(
        MonitorServiceMapper serviceMapper,
        LiveMonitorService liveMonitorService,
        AlertService alertService,
        ObjectMapper objectMapper,
        ExternalMessageAuditService auditService,
        ExternalProjectService projectService
    ) {
        this.serviceMapper = serviceMapper;
        this.liveMonitorService = liveMonitorService;
        this.alertService = alertService;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.projectService = projectService;
    }

    public Map<String, Object> forward(String requestBody, String contentType) {
        return forward(requestBody, contentType, null);
    }

    public Map<String, Object> forward(String requestBody, String contentType, String clientIp) {
        Long serviceId = serviceMapper.findFirstIdByServiceType(SERVICE_TYPE);
        if (serviceId == null) {
            auditService.record(null, clientIp, contentType, requestBody, "REJECTED", 0, 0, 0, "forwarding service unavailable");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "external message forwarding service is unavailable");
        }

        MonitorService service = liveMonitorService.getService(serviceId);
        if (!Boolean.TRUE.equals(service.enabled)) {
            auditService.record(serviceId, clientIp, contentType, requestBody, "REJECTED", 0, 0, 0, "channel disabled");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "external message forwarding is disabled");
        }

        String message;
        ExternalProjectService.RoutedProject route;
        try {
            message = extractMessage(requestBody, contentType);
            route = projectService.route(StringUtils.hasText(requestBody) ? requestBody : message, service);
        } catch (ResponseStatusException ex) {
            auditService.record(serviceId, clientIp, contentType, requestBody, "REJECTED", 0, 0, 0, ex.getReason());
            throw ex;
        }
        MonitorService routedService = route.service;

        List<AlertRecord> records;
        try {
            records = alertService.forwardText(routedService, message);
        } catch (RuntimeException ex) {
            auditService.record(serviceId, clientIp, contentType, route.project.projectName, message, "FAILED", 0, 0, 0, ex.getMessage());
            throw ex;
        }
        int succeeded = 0;
        for (AlertRecord record : records) {
            if (record != null && "success".equalsIgnoreCase(record.alertStatus)) {
                succeeded++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        boolean success = !records.isEmpty() && succeeded == records.size();
        result.put("success", success);
        result.put("service_id", service.id);
        result.put("service_name", service.serviceName);
        result.put("project_id", route.project.id);
        result.put("project_name", route.project.projectName);
        result.put("alert_group_ids", routedService.alertGroupIds);
        result.put("delivery_count", records.size());
        result.put("success_count", succeeded);
        result.put("failed_count", records.size() - succeeded);
        result.put("records", records);
        auditService.record(
            serviceId,
            clientIp,
            contentType,
            route.project.projectName,
            message,
            success ? "SUCCESS" : "FAILED",
            records.size(),
            succeeded,
            records.size() - succeeded,
            (success ? "forwarded" : "one or more deliveries failed") + "; project=" + route.project.projectName
        );
        return result;
    }

    public Map<String, Object> setEnabled(boolean enabled, String clientIp) {
        Long serviceId = serviceMapper.findFirstIdByServiceType(SERVICE_TYPE);
        if (serviceId == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "external message forwarding service is unavailable");
        }
        serviceMapper.updateEnabled(serviceId, enabled);
        MonitorService service = liveMonitorService.getService(serviceId);
        auditService.record(
            serviceId, clientIp, null, null, enabled ? "CONFIG_ENABLED" : "CONFIG_DISABLED",
            0, 0, 0, enabled ? "channel enabled" : "channel disabled"
        );
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("service_id", service.id);
        result.put("enabled", Boolean.TRUE.equals(service.enabled));
        return result;
    }

    public Map<String, Object> auditPage(Integer page, Integer pageSize, String status, String query) {
        return auditService.listPage(page, pageSize, status, query);
    }

    String extractMessage(String requestBody, String contentType) {
        String message = requestBody;
        if (contentType != null && contentType.toLowerCase().contains("json")) {
            message = messageFromJson(requestBody);
        }
        if (!StringUtils.hasText(message)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be blank");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "message must not exceed 100000 characters");
        }
        return message;
    }

    private String messageFromJson(String requestBody) {
        if (!StringUtils.hasText(requestBody)) {
            return requestBody;
        }
        try {
            JsonNode root = objectMapper.readTree(requestBody);
            if (root != null && root.isTextual()) {
                return root.textValue();
            }
            if (root != null && root.isObject()) {
                for (String field : new String[] {"message", "text", "content"}) {
                    JsonNode value = root.get(field);
                    if (value != null && value.isTextual()) {
                        return value.textValue();
                    }
                }
            }
            if (root != null) {
                return objectMapper.writeValueAsString(root);
            }
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is not valid JSON");
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is not valid JSON");
    }
}
