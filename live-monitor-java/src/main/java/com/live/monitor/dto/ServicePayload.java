package com.live.monitor.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

public class ServicePayload {
    @NotBlank
    public String serviceName;
    @NotBlank
    public String serviceType;
    public String clusterName;
    public String host;
    @Min(1)
    @Max(65535)
    public Integer port;
    public String url;
    public String httpMethod = "GET";
    @Min(100)
    @Max(599)
    public Integer expectedStatusCode;
    public String responseKeyword;
    @Min(0)
    public Double checkTimeoutSeconds;
    public String redisUsername;
    public String redisPassword;
    public Boolean redisClusterMode = false;
    public String zookeeperCheckMode = "ruok";
    public String zookeeperCheckCommand = "ruok";
    @Min(1)
    public Integer zookeeperExpectedNodes;
    @Min(5)
    public Integer checkInterval = 60;
    public Long alertConfigId;
    public Long alertGroupId;
    public Boolean enabled = true;
}
