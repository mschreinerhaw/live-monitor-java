package com.live.monitor.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

public class HostPayload {
    @NotBlank
    public String hostName;
    @NotBlank
    public String ip;
    @Min(1)
    @Max(65535)
    public Integer sshPort = 22;
    public String sshUser;
    public String sshPassword;
    public String privateKey;
    public String clusterName;
    @Min(1)
    @Max(100)
    public Double cpuThresholdPercent = 85D;
    @Min(1)
    @Max(100)
    public Double diskThresholdPercent = 85D;
    @Min(5)
    public Integer checkInterval = 60;
    public Long alertGroupId;
    public Boolean enabled = true;
}
