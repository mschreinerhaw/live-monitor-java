package com.live.monitor.dto;

import java.util.List;

public class DataSendResultResponse {
    public boolean success;
    public String message;
    public Long databaseConnectionServiceId;
    public String databaseServiceName;
    public int rowCount;
    public int columnCount;
    public List<String> selectedColumns;
    public String smsContent;
    public String recipientSummary;
    public String sentAt;
}
