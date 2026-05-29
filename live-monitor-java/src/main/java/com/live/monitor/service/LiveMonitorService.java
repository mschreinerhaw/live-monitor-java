package com.live.monitor.service;

import com.live.monitor.alert.AlertService;
import com.live.monitor.dto.CheckResult;
import com.live.monitor.dto.ServicePayload;
import com.live.monitor.entity.MonitorResult;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.AlertMapper;
import com.live.monitor.mapper.MonitorResultMapper;
import com.live.monitor.mapper.MonitorServiceMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LiveMonitorService {
    private final MonitorServiceMapper serviceMapper;
    private final MonitorResultMapper resultMapper;
    private final AlertMapper alertMapper;
    private final MonitorRunnerService runnerService;
    private final AlertService alertService;

    public LiveMonitorService(
        MonitorServiceMapper serviceMapper,
        MonitorResultMapper resultMapper,
        AlertMapper alertMapper,
        MonitorRunnerService runnerService,
        AlertService alertService
    ) {
        this.serviceMapper = serviceMapper;
        this.resultMapper = resultMapper;
        this.alertMapper = alertMapper;
        this.runnerService = runnerService;
        this.alertService = alertService;
    }

    public List<MonitorService> listServices(boolean includeDisabled) {
        List<MonitorService> rows = serviceMapper.list(includeDisabled ? 1 : 0);
        for (MonitorService row : rows) {
            maskSecrets(row);
        }
        return rows;
    }

    public MonitorService getService(Long id) {
        MonitorService service = requireService(id);
        maskSecrets(service);
        return service;
    }

    @Transactional
    public MonitorService create(ServicePayload payload) {
        validatePayload(payload);
        MonitorService service = fromPayload(payload);
        serviceMapper.insert(service);
        syncAlertGroup(service.id, payload.alertGroupId);
        return getService(service.id);
    }

    @Transactional
    public MonitorService update(Long id, ServicePayload payload) {
        validatePayload(payload);
        MonitorService service = fromPayload(payload);
        service.id = id;
        if (serviceMapper.update(service) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "service not found");
        }
        syncAlertGroup(id, payload.alertGroupId);
        return getService(id);
    }

    public boolean delete(Long id) {
        return serviceMapper.delete(id) > 0;
    }

    public CheckResult test(ServicePayload payload) {
        validatePayload(payload);
        return runnerService.run(fromPayload(payload));
    }

    @Transactional
    public MonitorResult checkAndStore(Long id) {
        MonitorService service = requireService(id);
        String previousStatus = serviceMapper.latestStatus(id);
        CheckResult check = runnerService.run(service);
        MonitorResult result = new MonitorResult();
        result.serviceId = id;
        result.status = check.status == null ? "UNKNOWN" : check.status;
        result.responseTimeMs = check.responseTimeMs;
        result.message = check.message;
        resultMapper.insert(result);
        MonitorResult stored = resultMapper.findById(result.id);
        alertService.evaluate(service, stored, previousStatus);
        return stored;
    }

    public Map<String, Object> dashboard() {
        List<MonitorService> services = listServices(false);
        Map<String, Object> summary = new HashMap<String, Object>();
        int up = 0;
        int down = 0;
        int unknown = 0;
        for (MonitorService service : services) {
            if ("UP".equals(service.lastStatus)) {
                up++;
            } else if ("DOWN".equals(service.lastStatus)) {
                down++;
            } else {
                unknown++;
            }
        }
        summary.put("total", services.size());
        summary.put("up", up);
        summary.put("down", down);
        summary.put("unknown", unknown);

        Map<String, Object> dashboard = new HashMap<String, Object>();
        dashboard.put("summary", summary);
        dashboard.put("services", services);
        dashboard.put("recent_alerts", alertMapper.listRecentAlerts(10));
        dashboard.put("recent_results", resultMapper.listRecent(10));
        dashboard.put("server_time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return dashboard;
    }

    public List<MonitorResult> results(Long serviceId, int limit) {
        requireService(serviceId);
        return resultMapper.listByService(serviceId, Math.max(1, Math.min(limit, 500)));
    }

    private MonitorService requireService(Long id) {
        MonitorService service = serviceMapper.findById(id);
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "service not found");
        }
        return service;
    }

    private void syncAlertGroup(Long serviceId, Long groupId) {
        if (groupId == null) {
            serviceMapper.unbindAlertGroup(serviceId);
        } else {
            serviceMapper.bindAlertGroup(serviceId, groupId);
        }
    }

    private MonitorService fromPayload(ServicePayload payload) {
        MonitorService service = new MonitorService();
        service.serviceName = payload.serviceName;
        service.serviceType = payload.serviceType;
        service.clusterName = emptyToNull(payload.clusterName);
        service.host = emptyToNull(payload.host);
        service.port = payload.port;
        service.url = emptyToNull(payload.url);
        service.httpMethod = StringUtils.hasText(payload.httpMethod) ? payload.httpMethod.toUpperCase() : "GET";
        service.expectedStatusCode = payload.expectedStatusCode;
        service.responseKeyword = emptyToNull(payload.responseKeyword);
        service.checkTimeoutSeconds = payload.checkTimeoutSeconds;
        service.redisUsername = emptyToNull(payload.redisUsername);
        service.redisPassword = emptyToNull(payload.redisPassword);
        service.redisClusterMode = Boolean.TRUE.equals(payload.redisClusterMode);
        service.zookeeperCheckMode = StringUtils.hasText(payload.zookeeperCheckMode) ? payload.zookeeperCheckMode : "ruok";
        service.zookeeperCheckCommand = StringUtils.hasText(payload.zookeeperCheckCommand) ? payload.zookeeperCheckCommand : "ruok";
        service.zookeeperExpectedNodes = payload.zookeeperExpectedNodes;
        service.checkInterval = payload.checkInterval == null ? 60 : payload.checkInterval;
        service.alertConfigId = payload.alertConfigId;
        service.enabled = payload.enabled == null || payload.enabled;
        return service;
    }

    private void validatePayload(ServicePayload payload) {
        if (!"web".equals(payload.serviceType) && !"redis".equals(payload.serviceType) && !"zookeeper".equals(payload.serviceType)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "service_type must be web, redis, or zookeeper");
        }
        if ("web".equals(payload.serviceType) && !StringUtils.hasText(payload.url)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "url is required for web services");
        }
        if (("redis".equals(payload.serviceType) || "zookeeper".equals(payload.serviceType))
            && (!StringUtils.hasText(payload.host) || payload.port == null)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "host and port are required for middleware services");
        }
    }

    private void maskSecrets(MonitorService service) {
        service.redisPassword = null;
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
