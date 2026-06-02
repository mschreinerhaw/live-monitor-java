package com.live.monitor.entity;

public class CheckEvent {
    public Long id;
    public Long serviceId;
    public String status;
    public Integer responseTimeMs;
    public String message;
    public String alertType;
    public String checkedAt;
    public Boolean consumed;
    public String consumedAt;
    public String createdAt;
}
