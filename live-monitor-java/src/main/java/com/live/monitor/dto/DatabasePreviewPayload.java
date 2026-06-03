package com.live.monitor.dto;

import java.util.List;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

public class DatabasePreviewPayload {
    @NotBlank
    public String serviceType;
    public String host;
    @Min(1)
    @Max(65535)
    public Integer port;
    public String databaseName;
    public String databaseUsername;
    public String databasePassword;
    public String databaseQuery;
    public String jdbcDriverClass;
    public String jdbcUrl;
    @Min(0)
    public Double checkTimeoutSeconds;
    public List<String> databaseAssertionFields;
}
