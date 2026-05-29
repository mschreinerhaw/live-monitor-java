package com.live.monitor.controller;

import com.live.monitor.alert.AlertService;
import com.live.monitor.dto.CheckResult;
import com.live.monitor.dto.ServiceAlertGroupPayload;
import com.live.monitor.dto.ServicePayload;
import com.live.monitor.entity.AlertRecord;
import com.live.monitor.entity.MonitorResult;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.MonitorServiceMapper;
import com.live.monitor.service.LiveMonitorService;
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

    public MonitorController(
        LiveMonitorService liveMonitorService,
        MonitorServiceMapper serviceMapper,
        AlertService alertService
    ) {
        this.liveMonitorService = liveMonitorService;
        this.serviceMapper = serviceMapper;
        this.alertService = alertService;
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

    @PostMapping("/api/services")
    @ResponseStatus(HttpStatus.CREATED)
    public MonitorService createService(@Valid @RequestBody ServicePayload payload) {
        return liveMonitorService.create(payload);
    }

    @PostMapping("/api/services/test")
    public CheckResult testService(@Valid @RequestBody ServicePayload payload) {
        return liveMonitorService.test(payload);
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
}
