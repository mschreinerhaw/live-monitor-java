package com.live.monitor.dto;

import com.live.monitor.entity.LoginAuditLog;
import java.util.List;

public class AuditLogPageResponse {
    public List<LoginAuditLog> items;
    public int page;
    public int pageSize;
    public long total;
    public int totalPages;

    public AuditLogPageResponse(List<LoginAuditLog> items, int page, int pageSize, long total, int totalPages) {
        this.items = items;
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
        this.totalPages = totalPages;
    }
}
