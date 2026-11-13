package com.live.monitor.entity;

import java.time.LocalDateTime;

/**
 * One row per outbound alert notification attempt written by AlertService.
 */
public class AlertNotifyRecord {
    public Long id;
    public Long serviceId;
    public String serviceName;
    public String serviceType;
    public String clusterName;
    public String alertKey;
    public Long alertRecordId;
    public String alertType;
    public String notifyStatus;
    public String notifyMessage;
    public LocalDateTime createdAt;
}
