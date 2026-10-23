package com.live.monitor.dto;

import java.util.List;
import java.util.Map;

public class DataSendPreviewResponse {
    public boolean success;
    public String message;
    public Long databaseConnectionServiceId;
    public String databaseServiceName;
    public List<String> allColumns;
    public List<String> selectedColumns;
    public Map<String, String> columnAliases;
    public List<Map<String, Object>> rows;
    public String smsContent;
    public int rowCount;
    public int columnCount;
    public int charCount;
    public int smsCount;
}
