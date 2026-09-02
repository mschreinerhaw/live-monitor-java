package com.live.monitor.controller;

import com.live.monitor.dto.AlertNotifyRecordPageResponse;
import com.live.monitor.service.AlertNotifyAuditService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AlertNotifyAuditController {
    private final AlertNotifyAuditService auditService;

    public AlertNotifyAuditController(AlertNotifyAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/api/alert-notifications")
    public AlertNotifyRecordPageResponse list(
        @RequestParam(defaultValue = "1") Integer page,
        @RequestParam(name = "page_size", defaultValue = "20") Integer pageSize,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String query,
        @RequestParam(name = "start_time", required = false) String startTime,
        @RequestParam(name = "end_time", required = false) String endTime
    ) {
        return auditService.listPage(page, pageSize, status, query, startTime, endTime);
    }

    @GetMapping("/api/alert-notifications/trend")
    public List<Map<String, Object>> trend(@RequestParam(defaultValue = "14") Integer days) {
        return auditService.dailyTrend(days == null ? 14 : days);
    }
}
