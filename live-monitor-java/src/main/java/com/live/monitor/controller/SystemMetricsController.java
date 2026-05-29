package com.live.monitor.controller;

import com.live.monitor.service.SystemMetricsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemMetricsController {
    private final SystemMetricsService systemMetricsService;

    public SystemMetricsController(SystemMetricsService systemMetricsService) {
        this.systemMetricsService = systemMetricsService;
    }

    @GetMapping("/api/system-metrics")
    public Map<String, Object> metrics() {
        return systemMetricsService.snapshot();
    }
}
