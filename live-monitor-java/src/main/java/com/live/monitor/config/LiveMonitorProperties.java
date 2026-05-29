package com.live.monitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "live-monitor")
public class LiveMonitorProperties {
    private int schedulerTickSeconds = 5;
    private double defaultTimeoutSeconds = 3;
    private String secretKey = "change-this-dev-key";

    public int getSchedulerTickSeconds() {
        return schedulerTickSeconds;
    }

    public void setSchedulerTickSeconds(int schedulerTickSeconds) {
        this.schedulerTickSeconds = schedulerTickSeconds;
    }

    public double getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(double defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }
}
