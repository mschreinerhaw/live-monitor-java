package com.live.monitor.controller;

import com.live.monitor.alert.AlertService;
import com.live.monitor.dto.CheckResult;
import com.live.monitor.dto.DatabasePreviewPayload;
import com.live.monitor.dto.RuleTestPayload;
import com.live.monitor.dto.ServiceAlertGroupPayload;
import com.live.monitor.dto.ServicePayload;
import com.live.monitor.entity.AlertRecord;
import com.live.monitor.entity.MonitorResult;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.MonitorServiceMapper;
import com.live.monitor.rule.ApiRuleEvaluator;
import com.live.monitor.service.DatabaseMonitorService;
import com.live.monitor.service.LiveMonitorService;
import com.live.monitor.util.CheckIntervals;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MonitorController {
    private final LiveMonitorService liveMonitorService;
    private final MonitorServiceMapper serviceMapper;
    private final AlertService alertService;
    private final ApiRuleEvaluator apiRuleEvaluator;
    private final DatabaseMonitorService databaseMonitorService;

    public MonitorController(
        LiveMonitorService liveMonitorService,
        MonitorServiceMapper serviceMapper,
        AlertService alertService,
        ApiRuleEvaluator apiRuleEvaluator,
        DatabaseMonitorService databaseMonitorService
    ) {
        this.liveMonitorService = liveMonitorService;
        this.serviceMapper = serviceMapper;
        this.alertService = alertService;
        this.apiRuleEvaluator = apiRuleEvaluator;
        this.databaseMonitorService = databaseMonitorService;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        Map<String, String> result = new HashMap<String, String>();
        result.put("status", "ok");
        result.put("time", OffsetDateTime.now().toString());
        return result;
    }

    @GetMapping("/api/dashboard")
    public Map<String, Object> dashboard() {
        return liveMonitorService.dashboard();
    }

    @GetMapping("/api/services")
    public List<MonitorService> services(@RequestParam(name = "include_disabled", defaultValue = "false") boolean includeDisabled) {
        return liveMonitorService.listServices(includeDisabled);
    }

    @GetMapping("/api/database/connections")
    public List<MonitorService> databaseConnections(@RequestParam(name = "include_disabled", defaultValue = "true") boolean includeDisabled) {
        return liveMonitorService.databaseConnections(includeDisabled);
    }

    @PostMapping("/api/services")
    @ResponseStatus(HttpStatus.CREATED)
    public MonitorService createService(@Valid @RequestBody ServicePayload payload) {
        return liveMonitorService.create(payload);
    }

    @PostMapping("/api/services/test")
    public CheckResult testService(@Valid @RequestBody ServicePayload payload) {
        return liveMonitorService.test(payload);
    }

    @PostMapping("/api/services/{serviceId}/test")
    public CheckResult testExistingService(@PathVariable Long serviceId, @Valid @RequestBody ServicePayload payload) {
        return liveMonitorService.test(serviceId, payload);
    }

    @PostMapping("/api/rules/test")
    public ApiRuleEvaluator.Evaluation testRule(@Valid @RequestBody RuleTestPayload payload) {
        return apiRuleEvaluator.evaluate(
            payload.expression,
            new ApiRuleEvaluator.ResponseContext(
                payload.statusCode == null ? 200 : payload.statusCode,
                payload.responseTimeMs == null ? 0 : payload.responseTimeMs,
                payload.body
            )
        );
    }

    @PostMapping("/api/database/preview")
    public DatabaseMonitorService.PreviewResult databasePreview(@Valid @RequestBody DatabasePreviewPayload payload) {
        return databasePreview(payload, payload.databasePassword);
    }

    @PostMapping("/api/services/{serviceId}/database/preview")
    public DatabaseMonitorService.PreviewResult existingServiceDatabasePreview(
        @PathVariable Long serviceId,
        @Valid @RequestBody DatabasePreviewPayload payload
    ) {
        String password = liveMonitorService.databasePasswordForPreview(
            serviceId,
            payload.serviceType,
            payload.databaseConnectionServiceId,
            payload.databasePassword
        );
        return databasePreview(payload, password);
    }

    private DatabaseMonitorService.PreviewResult databasePreview(DatabasePreviewPayload payload, String databasePassword) {
        MonitorService connection = payload.databaseConnectionServiceId == null
            ? null
            : liveMonitorService.databaseConnectionForUse(payload.serviceType, payload.databaseConnectionServiceId);
        String host = connection == null ? payload.host : connection.host;
        Integer port = connection == null ? payload.port : connection.port;
        String databaseName = connection == null ? payload.databaseName : connection.databaseName;
        String databaseUsername = connection == null ? payload.databaseUsername : connection.databaseUsername;
        String password = connection == null ? databasePassword : connection.databasePassword;
        String jdbcDriverClass = connection == null ? payload.jdbcDriverClass : connection.jdbcDriverClass;
        String jdbcUrl = connection == null ? payload.jdbcUrl : connection.jdbcUrl;
        try {
            return databaseMonitorService.preview(
                payload.serviceType,
                host,
                port,
                databaseName,
                databaseUsername,
                password,
                payload.databaseQuery,
                jdbcDriverClass,
                jdbcUrl,
                payload.checkTimeoutSeconds == null ? 3D : payload.checkTimeoutSeconds
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        }
    }

    @GetMapping("/api/services/{serviceId}")
    public MonitorService service(@PathVariable Long serviceId) {
        return liveMonitorService.getService(serviceId);
    }

    @PutMapping("/api/services/{serviceId}")
    public MonitorService updateService(@PathVariable Long serviceId, @Valid @RequestBody ServicePayload payload) {
        return liveMonitorService.update(serviceId, payload);
    }

    @DeleteMapping("/api/services/{serviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteService(@PathVariable Long serviceId) {
        if (!liveMonitorService.delete(serviceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "service not found");
        }
    }

    @PostMapping("/api/services/{serviceId}/check")
    public MonitorResult check(@PathVariable Long serviceId) {
        return liveMonitorService.checkAndStore(serviceId);
    }

    @GetMapping("/api/services/{serviceId}/results")
    public List<MonitorResult> results(
        @PathVariable Long serviceId,
        @RequestParam(defaultValue = "100") int limit
    ) {
        return liveMonitorService.results(serviceId, limit);
    }

    @GetMapping("/api/services/{serviceId}/alerts")
    public List<AlertRecord> serviceAlerts(
        @PathVariable Long serviceId,
        @RequestParam(defaultValue = "50") int limit
    ) {
        return liveMonitorService.alerts(serviceId, limit);
    }

    @GetMapping("/api/alerts")
    public List<AlertRecord> alerts(@RequestParam(defaultValue = "50") int limit) {
        return liveMonitorService.alerts(null, limit);
    }

    @DeleteMapping("/api/alerts")
    public Map<String, Object> clearAlerts() {
        int deleted = liveMonitorService.clearAlerts();
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("success", true);
        result.put("deleted", deleted);
        return result;
    }

    @PutMapping("/api/services/{serviceId}/alert-group")
    public MonitorService bindAlertGroup(@PathVariable Long serviceId, @RequestBody ServiceAlertGroupPayload payload) {
        if (serviceMapper.findById(serviceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "service not found");
        }
        if (payload.alertGroupId == null) {
            serviceMapper.unbindAlertGroup(serviceId);
        } else {
            serviceMapper.bindAlertGroup(serviceId, payload.alertGroupId);
        }
        return liveMonitorService.getService(serviceId);
    }

    @PutMapping("/api/services/{serviceId}/alert-config")
    public MonitorService bindAlertConfig(@PathVariable Long serviceId, @RequestBody Map<String, Object> payload) {
        return liveMonitorService.getService(serviceId);
    }

    @PutMapping("/api/services/{serviceId}/alert-settings")
    public MonitorService alertSettings(@PathVariable Long serviceId, @RequestBody Map<String, Object> payload) {
        if (serviceMapper.findById(serviceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "service not found");
        }
        if (containsAny(payload, "alert_group_id", "alertGroupId")) {
            Long groupId = longPayload(payload, "alert_group_id", "alertGroupId");
            if (groupId == null) {
                serviceMapper.unbindAlertGroup(serviceId);
            } else {
                serviceMapper.bindAlertGroup(serviceId, groupId);
            }
        }
        if (containsAny(payload, "check_interval", "checkInterval", "check_interval_value", "checkIntervalValue")) {
            Integer seconds = intPayload(payload, "check_interval", "checkInterval");
            Integer value = intPayload(payload, "check_interval_value", "checkIntervalValue");
            String unit = stringPayload(payload, "check_interval_unit", "checkIntervalUnit");
            try {
                serviceMapper.updateCheckInterval(
                    serviceId,
                    CheckIntervals.fromValueAndUnit(value, unit, seconds == null ? CheckIntervals.DEFAULT_SECONDS : seconds)
                );
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
            }
        }
        return liveMonitorService.getService(serviceId);
    }

    @PostMapping("/api/services/{serviceId}/alert-test")
    public Map<String, Object> alertTest(@PathVariable Long serviceId) {
        MonitorService service = liveMonitorService.getService(serviceId);
        AlertRecord record = alertService.testAlert(service);
        Map<String, Object> result = new HashMap<String, Object>();
        boolean success = "success".equals(record.alertStatus);
        result.put("service_id", service.id);
        result.put("service_name", service.serviceName);
        result.put("alert_group_id", service.alertGroupId);
        result.put("record", record);
        result.put("success", success);
        if (!success) {
            result.put("error", record.alertContent);
        }
        return result;
    }

    private boolean containsAny(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            if (payload.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private Long longPayload(Map<String, Object> payload, String... keys) {
        Object value = payloadValue(payload, keys);
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid id value");
        }
    }

    private Integer intPayload(Map<String, Object> payload, String... keys) {
        Object value = payloadValue(payload, keys);
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid interval value");
        }
    }

    private String stringPayload(Map<String, Object> payload, String... keys) {
        Object value = payloadValue(payload, keys);
        return value == null ? null : String.valueOf(value);
    }

    private Object payloadValue(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            if (payload.containsKey(key)) {
                return payload.get(key);
            }
        }
        return null;
    }
}
