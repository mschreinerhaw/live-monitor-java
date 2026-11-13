package com.live.monitor.service;

import com.live.monitor.dto.AlertNotifyRecordPageResponse;
import com.live.monitor.entity.AlertNotifyRecord;
import com.live.monitor.util.MonitorTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Serves alert notification audit queries used by the "告警审计" front-end page.
 */
@Service
public class AlertNotifyAuditService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_TREND_DAYS = 90;

    private final AlertAuditRepository audit;

    public AlertNotifyAuditService(AlertAuditRepository audit) {
        this.audit = audit;
    }

    public AlertNotifyRecordPageResponse listPage(
        Integer page,
        Integer pageSize,
        String status,
        String query,
        String startTime,
        String endTime
    ) {
        int currentPage = (page == null || page < 1) ? 1 : page;
        int size = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        String normalizedStatus = isBlank(status) ? null : status;
        String normalizedQuery = isBlank(query) ? null : query.trim();
        String normalizedStart = isBlank(startTime) ? null : startTime.trim();
        String normalizedEnd = isBlank(endTime) ? null : endTime.trim();

        long total = audit.count(normalizedStatus, normalizedQuery, normalizedStart, normalizedEnd);
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        if (totalPages > 0 && currentPage > totalPages) {
            currentPage = totalPages;
        }
        int offset = (currentPage - 1) * size;
        List<AlertNotifyRecord> items = audit.list(
            normalizedStatus, normalizedQuery, normalizedStart, normalizedEnd, size, offset
        );

        LocalDate todayDate = MonitorTime.today();
        String todayStart = MonitorTime.formatText(todayDate.atStartOfDay());
        String tomorrowStart = MonitorTime.formatText(todayDate.plusDays(1).atStartOfDay());
        long todayTotal = audit.count(null, null, todayStart, tomorrowStart);
        long todaySuccess = audit.count("success", null, todayStart, tomorrowStart);
        long todayFailed = audit.count("failed", null, todayStart, tomorrowStart);

        AlertNotifyRecordPageResponse response = new AlertNotifyRecordPageResponse(
            items, currentPage, size, total, totalPages,
            todayTotal, todaySuccess, todayFailed
        );
        response.todayPending = audit.count("pending", null, todayStart, tomorrowStart) + audit.count("retrying", null, todayStart, tomorrowStart) + audit.count("sending", null, todayStart, tomorrowStart);
        response.todayBlocked = audit.count("blocked", null, todayStart, tomorrowStart);
        return response;
    }

    /**
     * Return per-day counts (success / failed / total) for the last {@code days} days,
     * ordered oldest to newest.
     */
    public List<Map<String, Object>> dailyTrend(int days) {
        int span = Math.max(1, Math.min(days, MAX_TREND_DAYS));
        LocalDate today = MonitorTime.today();
        List<Map<String, Object>> series = new ArrayList<>(span);
        for (int i = span - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            String start = MonitorTime.formatText(day.atStartOfDay());
            String end = MonitorTime.formatText(day.plusDays(1).atStartOfDay());
            long success = audit.count("success", null, start, end);
            long failed = audit.count("failed", null, start, end);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day.toString());
            row.put("success", success);
            row.put("failed", failed);
            row.put("total", audit.count(null, null, start, end));
            series.add(row);
        }
        return series;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
