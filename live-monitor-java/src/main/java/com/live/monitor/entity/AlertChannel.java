package com.live.monitor.entity;

public class AlertChannel {
    public Long id;
    /** Transient contact IDs for this delivery; no transport credentials are copied into tasks. */
    public String recipientContextJson;
    public String channelName;
    public String channelType;
    public String configJson;
    public Boolean enabled;
    public String createdAt;
}
