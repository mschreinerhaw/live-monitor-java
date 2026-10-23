package com.live.monitor.dto;

import java.util.List;
import java.util.Map;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

public class DataSendPreviewPayload {
    @NotNull(message = "请选择已维护的数据库资产")
    public Long databaseConnectionServiceId;

    @NotBlank(message = "查询语句不能为空")
    public String databaseQuery;

    @Min(value = 1, message = "发送条数至少为 1 条")
    @Max(value = 10, message = "发送数据最多限制 10 条记录")
    public Integer maxRows = 10;

    public List<String> selectedColumns;

    public Map<String, String> columnAliases;

    public String title;

    public String layoutMode; // single_line, multi_line, compact

    @Min(0)
    public Double checkTimeoutSeconds = 3.0;
}
