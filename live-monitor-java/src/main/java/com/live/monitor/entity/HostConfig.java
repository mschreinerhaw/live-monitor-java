package com.live.monitor.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Map;

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
    public Integer checkIntervalValue;
    public String checkIntervalUnit;
    public Long alertGroupId;
    public Double cpuUsagePercent;
    public Double loadAverage;
    public Double memoryUsedPercent;
    public Double diskUsedPercent;
    public Integer cpuCoreCount;
    public Double memoryTotalMb;
    public Integer diskMountCount;
    @JsonIgnore
    public String diskMetricsJson;
    public List<Map<String, Object>> diskMetrics;
    public String metricCheckedAt;
    public Boolean enabled;
    public String createdAt;
}
