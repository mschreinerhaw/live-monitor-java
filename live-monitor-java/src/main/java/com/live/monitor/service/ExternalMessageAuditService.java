package com.live.monitor.service;

import com.live.monitor.entity.ExternalMessageAuditRecord;
import com.live.monitor.mapper.ExternalMessageAuditMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExternalMessageAuditService {
    private static final Logger log = LoggerFactory.getLogger(ExternalMessageAuditService.class);
    private final ExternalMessageAuditMapper mapper;

    public ExternalMessageAuditService(ExternalMessageAuditMapper mapper) {
        this.mapper = mapper;
    }

    public void record(
        Long serviceId,
        String clientIp,
        String contentType,
        String message,
        String status,
        int deliveries,
        int succeeded,
        int failed,
        String detail
    ) {
        record(serviceId, clientIp, contentType, null, message, status, deliveries, succeeded, failed, detail);
    }

    public void record(
        Long serviceId,
        String clientIp,
        String contentType,
        String projectName,
        String message,
        String status,
        int deliveries,
        int succeeded,
        int failed,
        String detail
    ) {
        ExternalMessageAuditRecord record = new ExternalMessageAuditRecord();
        record.serviceId = serviceId;
        record.clientIp = truncate(clientIp, 64);
        record.contentType = truncate(contentType, 255);
        record.projectName = truncate(projectName, 255);
        record.messageSummary = truncate(summary(message), 1000);
        record.messageLength = message == null ? 0 : message.length();
        record.requestStatus = truncate(status, 32);
        record.deliveryCount = deliveries;
        record.successCount = succeeded;
        record.failedCount = failed;
        record.detail = truncate(detail, 2000);
        try {
            mapper.insert(record);
        } catch (RuntimeException ex) {
            log.warn("Failed to write external message audit: {}", ex.getMessage());
        }
    }

    public Map<String, Object> listPage(Integer page, Integer pageSize, String status, String query) {
        int currentPage = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        String normalizedStatus = hasText(status) ? status.trim() : null;
        String normalizedQuery = hasText(query) ? query.trim() : null;
        long total = mapper.count(normalizedStatus, normalizedQuery);
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        if (totalPages > 0 && currentPage > totalPages) currentPage = totalPages;
        List<ExternalMessageAuditRecord> items = mapper.listPage(
            normalizedStatus, normalizedQuery, size, (currentPage - 1) * size
        );
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("page", currentPage);
        result.put("page_size", size);
        result.put("total", total);
        result.put("total_pages", totalPages);
        return result;
    }

    private String summary(String message) {
        return message == null ? null : message.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
