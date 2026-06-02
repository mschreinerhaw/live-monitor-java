package com.live.monitor.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class MonitorResult {
    public Long id;
    public Long serviceId;
    public String serviceName;
    public String serviceType;
    public String clusterName;
    public String status;
    public Integer responseTimeMs;
    public String message;
    public String checkedAt;
    @JsonIgnore
    public String alertType;
}
