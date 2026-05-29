package com.live.monitor.entity;

public class MonitorService {
    public Long id;
    public String serviceName;
    public String serviceType;
    public String clusterName;
    public String host;
    public Integer port;
    public String url;
    public String httpMethod;
    public Integer expectedStatusCode;
    public String responseKeyword;
    public Double checkTimeoutSeconds;
    public String redisUsername;
    public String redisPassword;
    public Boolean redisClusterMode;
    public String zookeeperCheckMode;
    public String zookeeperCheckCommand;
    public Integer zookeeperExpectedNodes;
    public Integer checkInterval;
    public Long alertConfigId;
    public Long alertGroupId;
    public String alertGroupName;
    public Boolean alertGroupEnabled;
    public Boolean enabled;
    public String createdAt;
    public String lastStatus;
    public Integer lastResponseTimeMs;
    public String lastMessage;
    public String lastCheckedAt;
}
