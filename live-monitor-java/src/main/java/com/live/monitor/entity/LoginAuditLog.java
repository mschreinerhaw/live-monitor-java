package com.live.monitor.entity;

import java.time.LocalDateTime;

public class LoginAuditLog {
    public Long id;
    public String userId;
    public String userName;
    public String action;
    public String ipAddress;
    public LocalDateTime eventTime;
}
