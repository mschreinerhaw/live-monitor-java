package com.live.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.alert.AlertDeliveryService;
import com.live.monitor.dto.DataSendExecutePayload;
import com.live.monitor.dto.DataSendPreviewPayload;
import com.live.monitor.dto.DataSendPreviewResponse;
import com.live.monitor.dto.DataSendResultResponse;
import com.live.monitor.entity.AlertChannel;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.AlertMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DataSendServiceTest {
    private LiveMonitorService liveMonitorService;
    private DatabaseMonitorService databaseMonitorService;
    private AlertDeliveryService alertDeliveryService;
    private AlertMapper alertMapper;
    private DataSendService dataSendService;

    @BeforeEach
    void setUp() {
        liveMonitorService = mock(LiveMonitorService.class);
        databaseMonitorService = mock(DatabaseMonitorService.class);
        alertDeliveryService = mock(AlertDeliveryService.class);
        alertMapper = mock(AlertMapper.class);
        dataSendService = new DataSendService(
            liveMonitorService,
            databaseMonitorService,
            alertDeliveryService,
            alertMapper,
            new ObjectMapper()
        );
    }

    @Test
    void rejectsWhenSelectedColumnsExceedMobileSmsLimit() {
        DataSendPreviewPayload payload = new DataSendPreviewPayload();
        payload.databaseConnectionServiceId = 10L;
        payload.databaseQuery = "SELECT col1, col2, col3, col4, col5 FROM t_test";
        payload.selectedColumns = Arrays.asList("col1", "col2", "col3", "col4", "col5"); // 5 columns > 4

        ResponseStatusException ex = assertThrows(
            ResponseStatusException.class,
            () -> dataSendService.preview(payload)
        );
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertTrue(ex.getReason().contains("列数超过手机短信显示限制"));
    }

    @Test
    void limitsMaxRowsToTenRecordsAndFormatsSmsContentCorrectly() {
        MonitorService dbSource = new MonitorService();
        dbSource.id = 10L;
        dbSource.serviceName = "资金交易库";
        dbSource.serviceType = "mysql";
        when(liveMonitorService.databaseConnectionForUse(null, 10L)).thenReturn(dbSource);

        DatabaseMonitorService.PreviewResult mockResult = new DatabaseMonitorService.PreviewResult();
        mockResult.columns = Arrays.asList("FUND_CODE", "FUND_NAME", "AMOUNT");
        mockResult.rows = new ArrayList<>();
        for (int i = 1; i <= 15; i++) { // 15 rows returned from DB
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("FUND_CODE", "00000" + i);
            row.put("FUND_NAME", "基金" + i);
            row.put("AMOUNT", 1000 + i);
            mockResult.rows.add(row);
        }

        when(databaseMonitorService.preview(
            eq("mysql"), any(), any(), any(), any(), any(), any(), any(), any(), eq(10), anyDouble()
        )).thenReturn(mockResult);

        DataSendPreviewPayload payload = new DataSendPreviewPayload();
        payload.databaseConnectionServiceId = 10L;
        payload.databaseQuery = "SELECT FUND_CODE, FUND_NAME, AMOUNT FROM t_fund";
        payload.maxRows = 10;
        payload.selectedColumns = Arrays.asList("FUND_CODE", "FUND_NAME", "AMOUNT");
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("FUND_CODE", "代码");
        aliases.put("FUND_NAME", "名称");
        aliases.put("AMOUNT", "金额");
        payload.columnAliases = aliases;
        payload.title = "大额申赎数据";

        DataSendPreviewResponse response = dataSendService.preview(payload);

        assertEquals(10, response.rowCount, "最多发送 10 条记录");
        assertEquals(3, response.columnCount, "选中的列数为 3 列");
        assertNotNull(response.smsContent);
        assertTrue(response.smsContent.contains("【大额申赎数据】共10条"));
        assertTrue(response.smsContent.contains("[1] 代码:000001 | 名称:基金1 | 金额:1001"));
        assertTrue(response.smsContent.contains("时间:"));
    }

    @Test
    void executeSendDeliversViaChannel() {
        MonitorService dbSource = new MonitorService();
        dbSource.id = 10L;
        dbSource.serviceName = "核心账户库";
        dbSource.serviceType = "mysql";
        when(liveMonitorService.databaseConnectionForUse(null, 10L)).thenReturn(dbSource);

        DatabaseMonitorService.PreviewResult mockResult = new DatabaseMonitorService.PreviewResult();
        mockResult.columns = Arrays.asList("ID", "NAME");
        mockResult.rows = Collections.singletonList(Collections.singletonMap("NAME", "张三"));
        when(databaseMonitorService.preview(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyDouble()))
            .thenReturn(mockResult);

        AlertChannel channel = new AlertChannel();
        channel.id = 5L;
        channel.channelName = "短信网关";
        channel.channelType = "sms";
        channel.enabled = true;
        when(alertMapper.findChannel(5L)).thenReturn(channel);
        when(alertDeliveryService.send(any(), any())).thenReturn(AlertDeliveryService.DeliveryResult.success());

        DataSendExecutePayload payload = new DataSendExecutePayload();
        payload.databaseConnectionServiceId = 10L;
        payload.databaseQuery = "SELECT ID, NAME FROM t_user";
        payload.channelId = 5L;
        payload.mobiles = "18211092191";

        DataSendResultResponse result = dataSendService.executeSend(payload);
        assertTrue(result.success);
        assertEquals(1, result.rowCount);
        assertEquals(1, dataSendService.listHistory().size());
    }
}
