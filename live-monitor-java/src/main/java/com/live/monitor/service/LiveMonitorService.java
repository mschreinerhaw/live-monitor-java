package com.live.monitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.alert.AlertService;
import com.live.monitor.dto.CheckResult;
import com.live.monitor.dto.ServicePayload;
import com.live.monitor.entity.MonitorResult;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.MonitorServiceMapper;
import com.live.monitor.store.RocksDbHistoryRepository;
import com.live.monitor.util.CheckIntervals;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LiveMonitorService {
    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP =
        new TypeReference<Map<String, Object>>() {};
    private static final DateTimeFormatter TEXT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final MonitorServiceMapper serviceMapper;
    private final RocksDbHistoryRepository historyRepository;
    private final MonitorRunnerService runnerService;
    private final AlertService alertService;
    private final ObjectMapper objectMapper;
    private final CryptoService cryptoService;
    private final TransactionTemplate transactionTemplate;

    public LiveMonitorService(
        MonitorServiceMapper serviceMapper,
        RocksDbHistoryRepository historyRepository,
        MonitorRunnerService runnerService,
        AlertService alertService,
        ObjectMapper objectMapper,
        CryptoService cryptoService,
        TransactionTemplate transactionTemplate
    ) {
        this.serviceMapper = serviceMapper;
        this.historyRepository = historyRepository;
        this.runnerService = runnerService;
        this.alertService = alertService;
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.transactionTemplate = transactionTemplate;
    }

    public List<MonitorService> listServices(boolean includeDisabled) {
        List<MonitorService> rows = serviceMapper.list(includeDisabled ? 1 : 0);
        for (MonitorService row : rows) {
            hydrateTypedFields(row);
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
        MonitorService existing = requireService(id);
        MonitorService service = fromPayload(payload);
        service.id = id;
        if (!hasSecretPayload(payload) && sameType(existing.serviceType, service.serviceType)) {
            service.secretConfigJson = existing.secretConfigJson;
        }
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

    public MonitorResult checkAndStore(Long id) {
        MonitorService service = requireService(id);
        String previousStatus = serviceMapper.latestStatus(id);
        CheckResult check = runnerService.run(service);
        return transactionTemplate.execute(status -> {
            MonitorResult result = new MonitorResult();
            result.serviceId = id;
            result.status = check.status == null ? "UNKNOWN" : check.status;
            result.responseTimeMs = check.responseTimeMs;
            result.message = check.message;
            result.alertType = check.alertType;
            MonitorResult stored = historyRepository.saveMonitorResult(result);
            serviceMapper.upsertLatestStatus(
                stored.serviceId,
                stored.status,
                stored.responseTimeMs,
                stored.message,
                stored.checkedAt
            );
            alertService.evaluate(service, stored, previousStatus);
            return stored;
        });
    }

    public Map<String, Object> dashboard() {
        List<MonitorService> services = listServices(false);
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        int serviceTotal = serviceGroupCount(services, null);
        int yesterdayServiceTotal = serviceGroupCount(services, todayStart);
        int instanceTotal = services.size();
        int yesterdayInstanceTotal = serviceCountBefore(services, todayStart);
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
        Map<String, Integer> yesterdayStatus = statusCountBefore(services, todayStart);
        int todayAlerts = historyRepository.countAlertsBetween(formatTime(todayStart), formatTime(todayStart.plusDays(1)));
        int yesterdayAlerts = historyRepository.countAlertsBetween(formatTime(yesterdayStart), formatTime(todayStart));
        Map<String, Object> trends = new HashMap<String, Object>();
        trends.put("total", serviceTotal - yesterdayServiceTotal);
        trends.put("instances", instanceTotal - yesterdayInstanceTotal);
        trends.put("up", up - yesterdayStatus.get("up"));
        trends.put("down", down - yesterdayStatus.get("down"));
        trends.put("alerts", todayAlerts - yesterdayAlerts);

        summary.put("total", serviceTotal);
        summary.put("instances", instanceTotal);
        summary.put("up", up);
        summary.put("down", down);
        summary.put("unknown", unknown);
        summary.put("today_alerts", todayAlerts);
        summary.put("yesterday_alerts", yesterdayAlerts);
        summary.put("trends", trends);

        Map<String, Object> dashboard = new HashMap<String, Object>();
        dashboard.put("summary", summary);
        dashboard.put("services", services);
        dashboard.put("recent_alerts", enrichAlerts(historyRepository.listAlerts(null, 10), services));
        dashboard.put("recent_results", enrichResults(historyRepository.listRecentMonitorResults(10), services));
        dashboard.put("server_time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return dashboard;
    }

    private int serviceGroupCount(List<MonitorService> services, LocalDateTime before) {
        Map<String, Boolean> groups = new HashMap<String, Boolean>();
        for (MonitorService service : services) {
            if (before != null && !serviceExistedBefore(service, before)) {
                continue;
            }
            String clusterName = emptyToNull(service.clusterName);
            String key = clusterName == null ? "service:" + service.id : "cluster:" + clusterName;
            groups.put(key, Boolean.TRUE);
        }
        return groups.size();
    }

    private int serviceCountBefore(List<MonitorService> services, LocalDateTime before) {
        int count = 0;
        for (MonitorService service : services) {
            if (serviceExistedBefore(service, before)) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Integer> statusCountBefore(List<MonitorService> services, LocalDateTime before) {
        Map<Long, MonitorResult> latestByService = historyRepository.latestMonitorResultsBefore(formatTime(before));
        Map<String, Integer> counts = new HashMap<String, Integer>();
        counts.put("up", 0);
        counts.put("down", 0);
        counts.put("unknown", 0);
        for (MonitorService service : services) {
            if (!serviceExistedBefore(service, before)) {
                continue;
            }
            MonitorResult latest = latestByService.get(service.id);
            String status = latest == null ? "UNKNOWN" : latest.status;
            if ("UP".equals(status)) {
                counts.put("up", counts.get("up") + 1);
            } else if ("DOWN".equals(status)) {
                counts.put("down", counts.get("down") + 1);
            } else {
                counts.put("unknown", counts.get("unknown") + 1);
            }
        }
        return counts;
    }

    private boolean serviceExistedBefore(MonitorService service, LocalDateTime before) {
        if (!StringUtils.hasText(service.createdAt)) {
            return true;
        }
        return parseTime(service.createdAt).isBefore(before);
    }

    private LocalDateTime parseTime(String value) {
        if (!StringUtils.hasText(value)) {
            return LocalDateTime.now();
        }
        String text = value.trim().replace('T', ' ');
        if (text.length() == 19) {
            text = text + ".000";
        }
        if (text.length() > 23) {
            text = text.substring(0, 23);
        }
        try {
            return LocalDateTime.parse(text, TEXT_TIME);
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (Exception ignoredAgain) {
                return LocalDateTime.now();
            }
        }
    }

    private String formatTime(LocalDateTime value) {
        return TEXT_TIME.format(value);
    }

    public List<MonitorResult> results(Long serviceId, int limit) {
        MonitorService service = requireService(serviceId);
        List<MonitorResult> rows = historyRepository.listMonitorResults(serviceId, Math.max(1, Math.min(limit, 500)));
        for (MonitorResult row : rows) {
            enrichResult(row, service);
        }
        return rows;
    }

    public List<com.live.monitor.entity.AlertRecord> alerts(Long serviceId, int limit) {
        List<com.live.monitor.entity.AlertRecord> rows = historyRepository.listAlerts(serviceId, Math.max(1, Math.min(limit, 200)));
        return enrichAlerts(rows, listServices(true));
    }

    public int clearAlerts() {
        return historyRepository.deleteAllAlerts();
    }

    private MonitorService requireService(Long id) {
        MonitorService service = serviceMapper.findById(id);
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "service not found");
        }
        hydrateTypedFields(service);
        return service;
    }

    private List<MonitorResult> enrichResults(List<MonitorResult> rows, List<MonitorService> services) {
        Map<Long, MonitorService> serviceById = serviceById(services);
        for (MonitorResult row : rows) {
            enrichResult(row, serviceById.get(row.serviceId));
        }
        return rows;
    }

    private List<com.live.monitor.entity.AlertRecord> enrichAlerts(
        List<com.live.monitor.entity.AlertRecord> rows,
        List<MonitorService> services
    ) {
        Map<Long, MonitorService> serviceById = serviceById(services);
        for (com.live.monitor.entity.AlertRecord row : rows) {
            MonitorService service = serviceById.get(row.serviceId);
            if (service != null) {
                row.serviceName = service.serviceName;
                row.serviceType = service.serviceType;
                row.clusterName = service.clusterName;
            }
        }
        return rows;
    }

    private Map<Long, MonitorService> serviceById(List<MonitorService> services) {
        Map<Long, MonitorService> result = new HashMap<Long, MonitorService>();
        for (MonitorService service : services) {
            result.put(service.id, service);
        }
        return result;
    }

    private void enrichResult(MonitorResult row, MonitorService service) {
        if (service == null) {
            return;
        }
        row.serviceName = service.serviceName;
        row.serviceType = service.serviceType;
        row.clusterName = service.clusterName;
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
        service.serviceName = payload.serviceName == null ? null : payload.serviceName.trim();
        service.serviceType = normalizeType(payload.serviceType);
        service.serviceCategory = StringUtils.hasText(payload.serviceCategory)
            ? normalizeType(payload.serviceCategory)
            : inferCategory(service.serviceType);
        service.clusterName = emptyToNull(payload.clusterName);
        service.monitorReason = emptyToNull(payload.monitorReason);
        service.host = emptyToNull(payload.host);
        service.port = payload.port == null ? defaultPort(service.serviceType) : payload.port;
        service.endpoint = preferredEndpoint(payload, service.port);
        service.checkMode = defaultCheckMode(payload, service.serviceType);
        service.checkCommand = emptyToNull(payload.checkCommand);
        service.expectedResult = emptyToNull(payload.expectedResult);
        service.checkTimeoutSeconds = payload.checkTimeoutSeconds;
        service.checkInterval = resolveCheckInterval(payload.checkIntervalValue, payload.checkIntervalUnit, payload.checkInterval);
        service.alertConfigId = payload.alertConfigId;
        service.enabled = payload.enabled == null || payload.enabled;

        Map<String, Object> config = copyMap(payload.config);
        Map<String, Object> secretConfig = copyMap(payload.secretConfig);
        applyLegacyPayloadConfig(payload, service, config, secretConfig);
        service.configJson = toJson(config);
        service.secretConfigJson = toEncryptedJson(secretConfig);
        hydrateTypedFields(service);
        return service;
    }

    private void validatePayload(ServicePayload payload) {
        String type = normalizeType(payload.serviceType);
        if (!StringUtils.hasText(type) || !type.matches("[a-z0-9_.-]+")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "service_type must contain only letters, numbers, dot, dash, or underscore");
        }
        if (isWebUrlType(type) && !StringUtils.hasText(payload.url)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "url is required for web services");
        }
        if (isWebUrlType(type) && !isHttpUrl(payload.url)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "web service url must start with http:// or https://");
        }
        if ("process".equals(type)
            && (!StringUtils.hasText(payload.host) && payload.hostId == null)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "host or host_id is required for process checks");
        }
        if ("process".equals(type)
            && !StringUtils.hasText(payload.processName)
            && !StringUtils.hasText(payload.checkCommand)
            && !StringUtils.hasText(payload.processCheckCommand)
            && !StringUtils.hasText(payload.processMatchKeyword)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "process_name or check_command is required for process checks");
        }
        if (("redis".equals(type) || "zookeeper".equals(type))
            && (!StringUtils.hasText(payload.host) || payload.port == null)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "host and port are required for middleware services");
        }
        if (("port".equals(type) || "tcp".equals(type)) && (!StringUtils.hasText(payload.host) || payload.port == null)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "host and port are required for port checks");
        }
        if (isDatabaseType(type) && !"jdbc".equals(type) && !StringUtils.hasText(payload.host)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "host is required for database services");
        }
        if ("oracle".equals(type) && !StringUtils.hasText(payload.databaseName)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "database_name is required for Oracle services");
        }
        if ("jdbc".equals(type) && !StringUtils.hasText(payload.jdbcDriverClass)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "jdbc_driver_class is required for generic JDBC services");
        }
        if ("jdbc".equals(type) && !StringUtils.hasText(payload.jdbcUrl)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "jdbc_url is required for generic JDBC services");
        }
    }

    private void maskSecrets(MonitorService service) {
        service.redisPassword = null;
        service.databasePassword = null;
        service.secretConfigJson = null;
    }

    private void applyLegacyPayloadConfig(
        ServicePayload payload,
        MonitorService service,
        Map<String, Object> config,
        Map<String, Object> secretConfig
    ) {
        if (service.host != null) {
            config.put("host", service.host);
        }
        if (service.port != null) {
            config.put("port", service.port);
        }

        if (isWebUrlType(service.serviceType)) {
            service.url = emptyToNull(payload.url);
            service.httpMethod = StringUtils.hasText(payload.httpMethod) ? payload.httpMethod.toUpperCase(Locale.ROOT) : "GET";
            service.expectedStatusCode = payload.expectedStatusCode;
            service.responseKeyword = emptyToNull(payload.responseKeyword);
            service.ignoreSslVerification = Boolean.TRUE.equals(payload.ignoreSslVerification);
            service.endpoint = service.endpoint == null ? service.url : service.endpoint;
            service.expectedResult = service.expectedResult == null && service.expectedStatusCode != null
                ? String.valueOf(service.expectedStatusCode)
                : service.expectedResult;
            config.put("url", service.url);
            config.put("http_method", service.httpMethod);
            putIfNotNull(config, "expected_status_code", service.expectedStatusCode);
            putIfNotNull(config, "response_keyword", service.responseKeyword);
            config.put("ignore_ssl_verification", service.ignoreSslVerification);
            return;
        }

        if ("redis".equals(service.serviceType)) {
            service.redisUsername = emptyToNull(payload.redisUsername);
            service.redisPassword = emptyToNull(payload.redisPassword);
            service.redisClusterMode = Boolean.TRUE.equals(payload.redisClusterMode);
            putIfNotNull(config, "redis_username", service.redisUsername);
            config.put("redis_cluster_mode", service.redisClusterMode);
            putIfNotNull(secretConfig, "redis_password", service.redisPassword);
            return;
        }

        if ("zookeeper".equals(service.serviceType)) {
            service.zookeeperCheckMode = StringUtils.hasText(payload.zookeeperCheckMode) ? payload.zookeeperCheckMode : "ruok";
            service.zookeeperCheckCommand = StringUtils.hasText(payload.zookeeperCheckCommand) ? payload.zookeeperCheckCommand : "ruok";
            service.zookeeperExpectedNodes = payload.zookeeperExpectedNodes;
            service.checkMode = service.zookeeperCheckMode;
            service.checkCommand = service.zookeeperCheckCommand;
            config.put("zookeeper_check_mode", service.zookeeperCheckMode);
            config.put("zookeeper_check_command", service.zookeeperCheckCommand);
            putIfNotNull(config, "zookeeper_expected_nodes", service.zookeeperExpectedNodes);
            return;
        }

        if ("process".equals(service.serviceType)) {
            service.hostId = payload.hostId;
            service.processName = emptyToNull(payload.processName);
            service.processMatchKeyword = StringUtils.hasText(payload.processMatchKeyword)
                ? payload.processMatchKeyword.trim()
                : service.processName;
            service.processMatchMode = "exact".equals(normalizeType(payload.processMatchMode)) ? "exact" : "fuzzy";
            service.processCheckCommand = StringUtils.hasText(payload.processCheckCommand)
                ? payload.processCheckCommand.trim()
                : emptyToNull(payload.checkCommand);
            service.processMinInstances = payload.processMinInstances == null
                ? 1
                : Math.max(1, payload.processMinInstances);
            if (StringUtils.hasText(service.processCheckCommand)) {
                service.checkMode = "shell_command";
                service.checkCommand = service.processCheckCommand;
            } else {
                service.checkMode = "process_count";
                service.checkCommand = service.processMatchKeyword;
                service.expectedResult = String.valueOf(service.processMinInstances);
            }
            putIfNotNull(config, "host_id", service.hostId);
            putIfNotNull(config, "process_name", service.processName);
            putIfNotNull(config, "process_match_keyword", service.processMatchKeyword);
            putIfNotNull(config, "process_check_command", service.processCheckCommand);
            config.put("process_match_mode", service.processMatchMode);
            config.put("process_min_instances", service.processMinInstances);
            return;
        }

        if (isDatabaseType(service.serviceType)) {
            service.databaseName = emptyToNull(payload.databaseName);
            service.databaseUsername = emptyToNull(payload.databaseUsername);
            service.databasePassword = emptyToNull(payload.databasePassword);
            service.databaseQuery = emptyToNull(payload.databaseQuery);
            service.databaseResultOperator = databaseResultOperator(payload.databaseResultOperator);
            service.jdbcDriverClass = emptyToNull(payload.jdbcDriverClass);
            service.jdbcUrl = emptyToNull(payload.jdbcUrl);
            service.checkMode = "jdbc_query";
            service.checkCommand = service.databaseQuery;
            putIfNotNull(config, "database_name", service.databaseName);
            putIfNotNull(config, "database_username", service.databaseUsername);
            putIfNotNull(config, "database_query", service.databaseQuery);
            config.put("database_result_operator", service.databaseResultOperator);
            putIfNotNull(config, "jdbc_driver_class", service.jdbcDriverClass);
            putIfNotNull(config, "jdbc_url", service.jdbcUrl);
            putIfNotNull(secretConfig, "database_password", service.databasePassword);
            return;
        }

        if ("port".equals(service.serviceType) || "tcp".equals(service.serviceType)) {
            service.checkMode = "tcp_connect";
        }
    }

    private void hydrateTypedFields(MonitorService service) {
        if (service == null) {
            return;
        }
        Map<String, Object> config = parseJsonMap(service.configJson);
        Map<String, Object> secretConfig = parseSecretJsonMap(service.secretConfigJson);
        applyCheckIntervalDisplay(service);

        service.host = stringValue(config, "host", service.host);
        service.port = intValue(config, "port", service.port);
        if (service.endpoint == null) {
            service.endpoint = endpointFromHostPort(service.host, service.port);
        }

        if (isWebUrlType(service.serviceType)) {
            service.url = stringValue(config, "url", service.endpoint);
            service.httpMethod = stringValue(config, "http_method", "GET");
            service.expectedStatusCode = intValue(config, "expected_status_code", null);
            service.responseKeyword = stringValue(config, "response_keyword", null);
            service.ignoreSslVerification = booleanValue(config, "ignore_ssl_verification", false);
        } else if ("redis".equals(service.serviceType)) {
            service.redisUsername = stringValue(config, "redis_username", null);
            service.redisPassword = stringValue(secretConfig, "redis_password", null);
            service.redisClusterMode = booleanValue(config, "redis_cluster_mode", false);
        } else if ("zookeeper".equals(service.serviceType)) {
            service.zookeeperCheckMode = stringValue(config, "zookeeper_check_mode", service.checkMode == null ? "ruok" : service.checkMode);
            service.zookeeperCheckCommand = stringValue(config, "zookeeper_check_command", service.checkCommand == null ? "ruok" : service.checkCommand);
            service.zookeeperExpectedNodes = intValue(config, "zookeeper_expected_nodes", null);
        } else if ("process".equals(service.serviceType)) {
            service.hostId = longValue(config, "host_id", service.hostId);
            service.processName = stringValue(config, "process_name", null);
            service.processMatchKeyword = stringValue(config, "process_match_keyword", service.checkCommand);
            service.processCheckCommand = stringValue(config, "process_check_command", "shell_command".equals(service.checkMode) ? service.checkCommand : null);
            service.processMatchMode = stringValue(config, "process_match_mode", "fuzzy");
            service.processMinInstances = intValue(config, "process_min_instances", 1);
        } else if ("host".equals(service.serviceType)) {
            service.hostId = longValue(config, "host_id", service.hostId);
            service.cpuThresholdPercent = doubleValue(config, "cpu_threshold_percent", null);
            service.memoryThresholdPercent = doubleValue(config, "memory_threshold_percent", null);
            service.diskThresholdPercent = doubleValue(config, "disk_threshold_percent", null);
            service.cpuAlertEnabled = booleanValue(config, "cpu_alert_enabled", true);
            service.memoryAlertEnabled = booleanValue(config, "memory_alert_enabled", true);
            service.diskAlertEnabled = booleanValue(config, "disk_alert_enabled", true);
        } else if (isDatabaseType(service.serviceType)) {
            service.databaseName = stringValue(config, "database_name", null);
            service.databaseUsername = stringValue(config, "database_username", null);
            service.databasePassword = stringValue(secretConfig, "database_password", null);
            service.databaseQuery = stringValue(config, "database_query", service.checkCommand);
            service.databaseResultOperator = databaseResultOperator(stringValue(config, "database_result_operator", "fuzzy"));
            service.jdbcDriverClass = stringValue(config, "jdbc_driver_class", null);
            service.jdbcUrl = stringValue(config, "jdbc_url", null);
        }
    }

    private String preferredEndpoint(ServicePayload payload, Integer port) {
        String endpoint = emptyToNull(payload.endpoint);
        if (endpoint != null) {
            return endpoint;
        }
        if (isWebUrlType(normalizeType(payload.serviceType))) {
            return emptyToNull(payload.url);
        }
        if ("jdbc".equals(normalizeType(payload.serviceType))) {
            return emptyToNull(payload.jdbcUrl);
        }
        return endpointFromHostPort(emptyToNull(payload.host), port);
    }

    private String endpointFromHostPort(String host, Integer port) {
        if (!StringUtils.hasText(host)) {
            return null;
        }
        return port == null ? host.trim() : host.trim() + ":" + port;
    }

    private String defaultCheckMode(ServicePayload payload, String type) {
        if (StringUtils.hasText(payload.checkMode)) {
            return payload.checkMode.trim();
        }
        if (isWebUrlType(type)) {
            return "http";
        }
        if ("redis".equals(type)) {
            return "redis_ping";
        }
        if ("zookeeper".equals(type)) {
            return StringUtils.hasText(payload.zookeeperCheckMode) ? payload.zookeeperCheckMode.trim() : "ruok";
        }
        if ("port".equals(type) || "tcp".equals(type)) {
            return "tcp_connect";
        }
        if (isDatabaseType(type)) {
            return "jdbc_query";
        }
        return "ping";
    }

    private String inferCategory(String type) {
        if ("web".equals(type) || "http".equals(type) || "https".equals(type) || "nginx".equals(type)) {
            return "web";
        }
        if ("mysql".equals(type) || "postgresql".equals(type) || "postgres".equals(type)
            || "oracle".equals(type) || "jdbc".equals(type) || "sqlserver".equals(type) || "mongodb".equals(type)) {
            return "database";
        }
        if ("port".equals(type) || "tcp".equals(type)) {
            return "network";
        }
        if ("host".equals(type) || "server".equals(type)) {
            return "host";
        }
        if ("java".equals(type) || "jvm".equals(type) || "java_process".equals(type) || "process".equals(type)) {
            return "process";
        }
        if ("redis".equals(type) || "zookeeper".equals(type) || "kafka".equals(type) || "rabbitmq".equals(type)) {
            return "middleware";
        }
        return "custom";
    }

    private boolean hasSecretPayload(ServicePayload payload) {
        return !copyMap(payload.secretConfig).isEmpty()
            || StringUtils.hasText(payload.redisPassword)
            || StringUtils.hasText(payload.databasePassword);
    }

    private boolean sameType(String left, String right) {
        return normalizeType(left).equals(normalizeType(right));
    }

    private String normalizeType(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String type = value.trim().toLowerCase(Locale.ROOT);
        return "http".equals(type) || "https".equals(type) ? "web" : type;
    }

    private boolean isHttpUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    private boolean isWebUrlType(String type) {
        return "web".equals(type) || "nginx".equals(type);
    }

    private boolean isDatabaseType(String type) {
        return "mysql".equals(type) || "oracle".equals(type) || "postgresql".equals(type) || "postgres".equals(type)
            || "jdbc".equals(type);
    }

    private String databaseResultOperator(String value) {
        String normalized = normalizeType(value);
        if ("gt".equals(normalized) || "gte".equals(normalized) || "lt".equals(normalized)
            || "lte".equals(normalized) || "eq".equals(normalized) || "exact".equals(normalized)) {
            return normalized;
        }
        return "fuzzy";
    }

    private Integer defaultPort(String type) {
        if ("mysql".equals(type)) {
            return 3306;
        }
        if ("oracle".equals(type)) {
            return 1521;
        }
        if ("postgresql".equals(type) || "postgres".equals(type)) {
            return 5432;
        }
        return null;
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(source);
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return objectMapper.readValue(json, STRING_OBJECT_MAP);
        } catch (Exception ex) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private Map<String, Object> parseSecretJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<String, Object>();
        }
        Map<String, Object> plain = parseJsonMap(json);
        if (!plain.isEmpty() || json.trim().startsWith("{")) {
            return plain;
        }
        return parseJsonMap(cryptoService.decryptIfEncrypted(json));
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? new LinkedHashMap<String, Object>() : value);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid monitor service config");
        }
    }

    private String toEncryptedJson(Map<String, Object> value) {
        Map<String, Object> normalized = value == null ? new LinkedHashMap<String, Object>() : value;
        if (normalized.isEmpty()) {
            return "{}";
        }
        return cryptoService.encrypt(toJson(normalized));
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private String stringValue(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private Integer intValue(Map<String, Object> map, String key, Integer fallback) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            try {
                return Integer.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private Long longValue(Map<String, Object> map, String key, Long fallback) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            try {
                return Long.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private Double doubleValue(Map<String, Object> map, String key, Double fallback) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            try {
                return Double.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private Boolean booleanValue(Map<String, Object> map, String key, Boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            return Boolean.valueOf(String.valueOf(value));
        }
        return fallback;
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private int resolveCheckInterval(Integer value, String unit, Integer fallbackSeconds) {
        try {
            return CheckIntervals.fromValueAndUnit(value, unit, fallbackSeconds);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        }
    }

    private void applyCheckIntervalDisplay(MonitorService service) {
        try {
            service.checkInterval = CheckIntervals.normalizeSeconds(service.checkInterval);
        } catch (IllegalArgumentException ex) {
            service.checkInterval = CheckIntervals.DEFAULT_SECONDS;
        }
        service.checkIntervalValue = CheckIntervals.displayValue(service.checkInterval);
        service.checkIntervalUnit = CheckIntervals.displayUnit(service.checkInterval);
    }
}
