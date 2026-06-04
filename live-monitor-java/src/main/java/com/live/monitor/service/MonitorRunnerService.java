package com.live.monitor.service;

import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.dto.CheckResult;
import com.live.monitor.entity.MonitorService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MonitorRunnerService {
    private final LiveMonitorProperties properties;
    private final WebMonitorService webMonitorService;
    private final RedisMonitorService redisMonitorService;
    private final ZookeeperMonitorService zookeeperMonitorService;
    private final PortMonitorService portMonitorService;
    private final DatabaseMonitorService databaseMonitorService;
    private final ProcessMonitorService processMonitorService;
    private final HostResourceMonitorService hostResourceMonitorService;

    public MonitorRunnerService(
        LiveMonitorProperties properties,
        WebMonitorService webMonitorService,
        RedisMonitorService redisMonitorService,
        ZookeeperMonitorService zookeeperMonitorService,
        PortMonitorService portMonitorService,
        DatabaseMonitorService databaseMonitorService,
        ProcessMonitorService processMonitorService,
        HostResourceMonitorService hostResourceMonitorService
    ) {
        this.properties = properties;
        this.webMonitorService = webMonitorService;
        this.redisMonitorService = redisMonitorService;
        this.zookeeperMonitorService = zookeeperMonitorService;
        this.portMonitorService = portMonitorService;
        this.databaseMonitorService = databaseMonitorService;
        this.processMonitorService = processMonitorService;
        this.hostResourceMonitorService = hostResourceMonitorService;
    }

    public CheckResult run(MonitorService service) {
        double timeout = service.checkTimeoutSeconds == null ? properties.getDefaultTimeoutSeconds() : service.checkTimeoutSeconds;
        String type = service.serviceType == null ? "" : service.serviceType;
        if ("api".equals(type)) {
            WebMonitorService.RequestOptions options = WebMonitorService.RequestOptions.api();
            options.headers = requestHeaders(service);
            options.body = service.apiRequestBody;
            options.contentType = service.apiContentType;
            return webMonitorService.checkApi(
                service.url,
                service.httpMethod,
                service.expectedStatusCode,
                service.responseKeyword,
                service.apiAssertionExpression,
                Boolean.TRUE.equals(service.ignoreSslVerification),
                timeout,
                options
            );
        }
        if ("web".equals(type) || "nginx".equals(type)) {
            return webMonitorService.checkWeb(
                service.url,
                service.httpMethod,
                service.expectedStatusCode,
                service.responseKeyword,
                service.apiAssertionExpression,
                Boolean.TRUE.equals(service.ignoreSslVerification),
                timeout
            );
        }
        if ("process".equals(type)) {
            return processMonitorService.check(service, timeout);
        }
        if ("host".equals(type)) {
            return hostResourceMonitorService.check(service, timeout);
        }
        if ("redis".equals(type)) {
            return redisMonitorService.check(
                service.host,
                service.port,
                service.redisUsername,
                service.redisPassword,
                service.redisClusterMode,
                timeout
            );
        }
        if ("zookeeper".equals(type)) {
            return zookeeperMonitorService.check(
                service.host,
                service.port,
                service.zookeeperCheckMode,
                service.zookeeperCheckCommand,
                service.zookeeperExpectedNodes,
                timeout
            );
        }
        if ("port".equals(type) || "tcp".equals(type)) {
            return portMonitorService.check(service.host, service.port, timeout);
        }
        if ("cross_database".equals(type)) {
            return databaseMonitorService.checkCrossDatabase(
                service.crossDatabaseQueries,
                service.apiAssertionExpression,
                timeout
            );
        }
        if ("mysql".equals(type) || "oracle".equals(type) || "postgresql".equals(type) || "postgres".equals(type)
            || "jdbc".equals(type)) {
            return databaseMonitorService.check(
                type,
                service.host,
                service.port,
                service.databaseName,
                service.databaseUsername,
                service.databasePassword,
                service.databaseQuery,
                service.expectedResult,
                service.databaseResultOperator,
                service.apiAssertionExpression,
                service.databaseAssertionFields,
                service.jdbcDriverClass,
                service.jdbcUrl,
                timeout
            );
        }
        return new CheckResult("UNKNOWN", null, "Unsupported service type: " + type);
    }

    private Map<String, String> requestHeaders(MonitorService service) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        if (service.apiHeaders != null) {
            for (Map<String, String> header : service.apiHeaders) {
                if (header == null || !StringUtils.hasText(header.get("name"))) {
                    continue;
                }
                headers.put(header.get("name").trim(), header.get("value") == null ? "" : header.get("value"));
            }
        }
        String authType = service.apiAuthType == null ? "none" : service.apiAuthType;
        if ("basic".equals(authType) && StringUtils.hasText(service.apiBasicUsername)) {
            String raw = service.apiBasicUsername + ":" + (service.apiBasicPassword == null ? "" : service.apiBasicPassword);
            headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        } else if ("bearer".equals(authType) && StringUtils.hasText(service.apiBearerToken)) {
            headers.put("Authorization", "Bearer " + service.apiBearerToken.trim());
        } else if ("custom_header".equals(authType)) {
            if (StringUtils.hasText(service.apiAuthAppId)) {
                headers.put("AppId", service.apiAuthAppId.trim());
            }
            if (StringUtils.hasText(service.apiAuthAppSecret)) {
                headers.put("AppSecret", service.apiAuthAppSecret.trim());
            }
        }
        return headers;
    }
}
