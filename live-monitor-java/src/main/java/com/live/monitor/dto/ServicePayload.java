package com.live.monitor.dto;

import java.util.Map;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class ServicePayload {
    @NotBlank
    public String serviceName;
    public String serviceCategory;
    @NotBlank
    public String serviceType;
    public String clusterName;
    @Size(max = 1000)
    public String monitorReason;
    public String endpoint;
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
    public Boolean ignoreSslVerification = false;
    public String checkMode;
    public String checkCommand;
    public String expectedResult;
    public Long hostId;
    public String processName;
    public String processMatchKeyword;
    public String processMatchMode = "fuzzy";
    public String processCheckCommand;
    @Min(1)
    public Integer processMinInstances = 1;
    public Map<String, Object> config;
    public Map<String, Object> secretConfig;
    @Min(0)
    public Double checkTimeoutSeconds;
    public String redisUsername;
    public String redisPassword;
    public Boolean redisClusterMode = false;
    public String zookeeperCheckMode = "ruok";
    public String zookeeperCheckCommand = "ruok";
    @Min(1)
    public Integer zookeeperExpectedNodes;
    public String databaseName;
    public String databaseUsername;
    public String databasePassword;
    public String databaseQuery;
    public String jdbcDriverClass;
    public String jdbcUrl;
    @Min(1)
    @Max(31536000)
    public Integer checkInterval = 60;
    @Min(1)
    public Integer checkIntervalValue;
    public String checkIntervalUnit = "seconds";
    public Long alertConfigId;
    public Long alertGroupId;
    public Boolean enabled = true;
}
