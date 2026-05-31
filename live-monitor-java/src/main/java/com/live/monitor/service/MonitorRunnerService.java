package com.live.monitor.service;

import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.dto.CheckResult;
import com.live.monitor.entity.MonitorService;
import org.springframework.stereotype.Service;

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
        if ("web".equals(type) || "nginx".equals(type)) {
            return webMonitorService.check(
                service.url,
                service.httpMethod,
                service.expectedStatusCode,
                service.responseKeyword,
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
                service.jdbcDriverClass,
                service.jdbcUrl,
                timeout
            );
        }
        return new CheckResult("UNKNOWN", null, "Unsupported service type: " + type);
    }
}
