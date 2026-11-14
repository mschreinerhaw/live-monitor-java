package com.live.monitor.entity;

public class AlertState {
    public Long serviceId;
    public String alertKey;
    public String state;
    public Integer failCount;
    public Integer recoverCount;
    public Long activePolicyId;
    public String activeTriggerType;
    public String lastStatus;
    public String lastMessage;
    public String lastEventAt;
    public String lastAlertAt;
    public String updatedAt;
}
