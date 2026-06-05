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
    public String remark;
    @Min(1)
    @Max(100)
    public Double cpuThresholdPercent = 85D;
    @Min(1)
    @Max(100)
    public Double memoryThresholdPercent = 85D;
    @Min(1)
    @Max(100)
    public Double diskThresholdPercent = 85D;
    public Boolean cpuAlertEnabled = true;
    public Boolean memoryAlertEnabled = true;
    public Boolean diskAlertEnabled = true;
    public Boolean resourceAlertDurationEnabled = true;
    public Boolean resourceRecoverDurationEnabled = true;
    public Boolean resourceAlertCooldownEnabled = true;
    @Min(1)
    @Max(31536000)
    public Integer resourceAlertDurationSeconds = 180;
    @Min(1)
    @Max(31536000)
    public Integer resourceRecoverDurationSeconds = 180;
    @Min(0)
    @Max(31536000)
    public Integer resourceAlertCooldownSeconds = 600;
    @Min(1)
    @Max(31536000)
    public Integer checkInterval = 60;
    @Min(1)
    public Integer checkIntervalValue;
    public String checkIntervalUnit = "seconds";
    public Long alertGroupId;
    public Boolean enabled = true;
}
