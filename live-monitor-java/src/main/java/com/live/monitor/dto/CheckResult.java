package com.live.monitor.dto;

public class CheckResult {
    public String status;
    public Integer responseTimeMs;
    public String message;
    public String alertType;

    public CheckResult() {
    }

    public CheckResult(String status, Integer responseTimeMs, String message) {
        this.status = status;
        this.responseTimeMs = responseTimeMs;
        this.message = message;
    }

    public CheckResult(String status, Integer responseTimeMs, String message, String alertType) {
        this.status = status;
        this.responseTimeMs = responseTimeMs;
        this.message = message;
        this.alertType = alertType;
    }
}
