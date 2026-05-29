package com.live.monitor.entity;

public class HostConfig {
    public Long id;
    public String hostName;
    public String ip;
    public Integer sshPort;
    public String sshUser;
    public String sshPasswordCipher;
    public String privateKeyCipher;
    public Long monitorServiceId;
    public String clusterName;
    public Double cpuThresholdPercent;
    public Double diskThresholdPercent;
    public Integer checkInterval;
    public Long alertGroupId;
    public Double cpuUsagePercent;
    public Double loadAverage;
    public Double memoryUsedPercent;
    public Double diskUsedPercent;
    public String metricCheckedAt;
    public Boolean enabled;
    public String createdAt;
}
