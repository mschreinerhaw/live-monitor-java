package com.live.monitor.dto;

import com.live.monitor.entity.AlertNotifyRecord;
import java.util.List;

public class AlertNotifyRecordPageResponse {
    public List<AlertNotifyRecord> items;
    public int page;
    public int pageSize;
    public long total;
    public int totalPages;
    public long todayTotal;
    public long todaySuccess;
    public long todayFailed;

    public AlertNotifyRecordPageResponse(
        List<AlertNotifyRecord> items,
        int page,
        int pageSize,
        long total,
        int totalPages,
        long todayTotal,
        long todaySuccess,
        long todayFailed
    ) {
        this.items = items;
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
        this.totalPages = totalPages;
        this.todayTotal = todayTotal;
        this.todaySuccess = todaySuccess;
        this.todayFailed = todayFailed;
    }
}
