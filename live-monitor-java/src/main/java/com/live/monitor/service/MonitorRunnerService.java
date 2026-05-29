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

    public MonitorRunnerService(
        LiveMonitorProperties properties,
        WebMonitorService webMonitorService,
        RedisMonitorService redisMonitorService,
        ZookeeperMonitorService zookeeperMonitorService
    ) {
        this.properties = properties;
        this.webMonitorService = webMonitorService;
        this.redisMonitorService = redisMonitorService;
        this.zookeeperMonitorService = zookeeperMonitorService;
    }

    public CheckResult run(MonitorService service) {
        double timeout = service.checkTimeoutSeconds == null ? properties.getDefaultTimeoutSeconds() : service.checkTimeoutSeconds;
        String type = service.serviceType == null ? "" : service.serviceType;
        if ("web".equals(type)) {
            return webMonitorService.check(
                service.url,
                service.httpMethod,
                service.expectedStatusCode,
                service.responseKeyword,
                timeout
            );
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
        return new CheckResult("UNKNOWN", null, "Unsupported service type: " + type);
    }
}
