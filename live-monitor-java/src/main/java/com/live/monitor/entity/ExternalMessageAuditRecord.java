package com.live.monitor.entity;

public class ExternalMessageAuditRecord {
    public Long id;
    public Long serviceId;
    public String clientIp;
    public String contentType;
    public String projectName;
    public String messageSummary;
    public Integer messageLength;
    public String requestStatus;
    public Integer deliveryCount;
    public Integer successCount;
    public Integer failedCount;
    public String detail;
    public String createdAt;
}
