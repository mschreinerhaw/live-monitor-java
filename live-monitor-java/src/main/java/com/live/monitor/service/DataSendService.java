package com.live.monitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.alert.AlertDeliveryService;
import com.live.monitor.dto.DataSendExecutePayload;
import com.live.monitor.dto.DataSendPreviewPayload;
import com.live.monitor.dto.DataSendPreviewResponse;
import com.live.monitor.dto.DataSendResultResponse;
import com.live.monitor.entity.AlertChannel;
import com.live.monitor.entity.AlertGroup;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.AlertMapper;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DataSendService {
    public static final int MAX_SMS_ROWS = 10;
    public static final int MAX_SMS_COLUMNS = 4;
    private static final int MAX_HISTORY_SIZE = 50;
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
        new TypeReference<Map<String, Object>>() {};

    private final LiveMonitorService liveMonitorService;
    private final DatabaseMonitorService databaseMonitorService;
    private final AlertDeliveryService alertDeliveryService;
    private final AlertMapper alertMapper;
    private final ObjectMapper objectMapper;
    private final ConcurrentLinkedDeque<DataSendResultResponse> sendHistory = new ConcurrentLinkedDeque<DataSendResultResponse>();

    public DataSendService(
        @org.springframework.context.annotation.Lazy LiveMonitorService liveMonitorService,
        DatabaseMonitorService databaseMonitorService,
        AlertDeliveryService alertDeliveryService,
        AlertMapper alertMapper,
        ObjectMapper objectMapper
    ) {
        this.liveMonitorService = liveMonitorService;
        this.databaseMonitorService = databaseMonitorService;
        this.alertDeliveryService = alertDeliveryService;
        this.alertMapper = alertMapper;
        this.objectMapper = objectMapper;
    }

    public com.live.monitor.dto.CheckResult runServiceCheck(MonitorService service, double timeout) {
        long started = System.nanoTime();
        try {
            DataSendExecutePayload payload = new DataSendExecutePayload();
            payload.databaseConnectionServiceId = service.databaseConnectionServiceId;
            payload.databaseQuery = service.databaseQuery;
            payload.maxRows = service.dataSendMaxRows != null ? service.dataSendMaxRows : 10;
            payload.selectedColumns = service.dataSendSelectedColumns;
            payload.columnAliases = service.dataSendColumnAliases;
            payload.title = StringUtils.hasText(service.dataSendTitle) ? service.dataSendTitle : service.serviceName;
            payload.checkTimeoutSeconds = timeout;
            if (service.alertGroupId != null) {
                payload.alertGroupId = service.alertGroupId;
            }

            DataSendResultResponse sendResult = executeSend(payload);
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            return new com.live.monitor.dto.CheckResult("UP", (int) elapsed, "数据短信发送成功：" + sendResult.rowCount + " 条记录，接收方: " + sendResult.recipientSummary);
        } catch (Exception ex) {
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            return new com.live.monitor.dto.CheckResult("DOWN", (int) elapsed, "数据发送失败: " + ex.getMessage());
        }
    }

    public DataSendPreviewResponse preview(DataSendPreviewPayload payload) {
        if (payload.databaseConnectionServiceId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "请选择已维护的数据库资产");
        }
        if (!StringUtils.hasText(payload.databaseQuery)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "查询语句不能为空");
        }

        // 验证列数限制：不可超过 4 列
        if (payload.selectedColumns != null && payload.selectedColumns.size() > MAX_SMS_COLUMNS) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "列数超过手机短信显示限制（最多允许选择 " + MAX_SMS_COLUMNS + " 列，以保证手机短信排版清晰不乱行）"
            );
        }

        int requestedRows = payload.maxRows == null ? MAX_SMS_ROWS : payload.maxRows;
        int limitRows = Math.min(MAX_SMS_ROWS, Math.max(1, requestedRows));

        MonitorService source = liveMonitorService.databaseConnectionForUse(null, payload.databaseConnectionServiceId);
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到指定的数据库资产或该资产不是数据库类型");
        }

        double timeout = payload.checkTimeoutSeconds == null ? 3.0 : payload.checkTimeoutSeconds;
        DatabaseMonitorService.PreviewResult queryResult;
        try {
            queryResult = databaseMonitorService.preview(
                source.serviceType,
                source.host,
                source.port,
                source.databaseName,
                source.databaseUsername,
                source.databasePassword,
                payload.databaseQuery,
                source.jdbcDriverClass,
                source.jdbcUrl,
                limitRows,
                timeout
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "执行数据库查询失败：" + ex.getMessage());
        }

        List<String> allColumns = queryResult.columns != null ? queryResult.columns : new ArrayList<String>();
        List<String> effectiveSelectedColumns = resolveSelectedColumns(payload.selectedColumns, allColumns);

        if (effectiveSelectedColumns.size() > MAX_SMS_COLUMNS) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "列数超过手机短信显示限制（最多允许选择 " + MAX_SMS_COLUMNS + " 列）"
            );
        }

        List<Map<String, Object>> rows = queryResult.rows != null ? queryResult.rows : new ArrayList<Map<String, Object>>();
        if (rows.size() > MAX_SMS_ROWS) {
            rows = rows.subList(0, MAX_SMS_ROWS);
        }

        String title = StringUtils.hasText(payload.title)
            ? payload.title.trim()
            : (StringUtils.hasText(source.serviceName) ? source.serviceName : "数据推送");

        String smsText = formatSmsContent(
            title,
            effectiveSelectedColumns,
            payload.columnAliases,
            rows,
            payload.layoutMode
        );

        DataSendPreviewResponse response = new DataSendPreviewResponse();
        response.success = true;
        response.message = "数据查询与短信预览生成成功";
        response.databaseConnectionServiceId = source.id;
        response.databaseServiceName = source.serviceName;
        response.allColumns = allColumns;
        response.selectedColumns = effectiveSelectedColumns;
        response.columnAliases = payload.columnAliases != null ? payload.columnAliases : new LinkedHashMap<String, String>();
        response.rows = rows;
        response.smsContent = smsText;
        response.rowCount = rows.size();
        response.columnCount = effectiveSelectedColumns.size();
        response.charCount = smsText.length();
        response.smsCount = calculateSmsSplitCount(smsText.length());
        return response;
    }

    public DataSendResultResponse executeSend(DataSendExecutePayload payload) {
        DataSendPreviewResponse previewResult = preview(payload);

        if (previewResult.rowCount == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "查询结果为空，无有效数据可供发送");
        }

        String content = previewResult.smsContent;
        String sentAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        List<String> recipientList = new ArrayList<String>();
        boolean sendSuccess = false;
        String failureMessage = null;

        try {
            if (payload.channelId != null) {
                // 指定了特定渠道
                AlertChannel channel = alertMapper.findChannel(payload.channelId);
                if (channel == null) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到指定的告警/短信渠道");
                }
                AlertChannel sendChannel = cloneChannelWithMobiles(channel, payload.mobiles);
                AlertDeliveryService.DeliveryResult result = alertDeliveryService.send(sendChannel, content);
                if (!result.success) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "渠道发送失败: " + result.message);
                }
                recipientList.add(channel.channelName + " (" + channel.channelType + ")");
                sendSuccess = true;
            } else if (payload.alertGroupId != null) {
                // 指定了告警组
                AlertGroup group = alertMapper.findGroup(payload.alertGroupId);
                if (group == null) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到指定的告警组");
                }
                List<AlertChannel> channels = alertMapper.listChannelsByGroup(payload.alertGroupId);
                if (channels.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "所选告警组未绑定任何发送渠道");
                }
                int successCount = 0;
                List<String> errors = new ArrayList<String>();
                for (AlertChannel channel : channels) {
                    if (channel.enabled == null || channel.enabled) {
                        AlertChannel sendChannel = cloneChannelWithMobiles(channel, payload.mobiles);
                        AlertDeliveryService.DeliveryResult res = alertDeliveryService.send(sendChannel, content);
                        if (res.success) {
                            successCount++;
                            recipientList.add(channel.channelName);
                        } else {
                            errors.add(channel.channelName + ": " + res.message);
                        }
                    }
                }
                if (successCount == 0 && !errors.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "告警组渠道发送全部失败: " + String.join("; ", errors));
                }
                sendSuccess = true;
            } else if (StringUtils.hasText(payload.mobiles)) {
                // 仅指定了手机号，查找启用的 SMS 渠道发送
                List<AlertChannel> smsChannels = findEnabledSmsChannels();
                if (smsChannels.isEmpty()) {
                    throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "系统尚未配置或启用 SMS 短信渠道，请先在【告警设置】中配置短信网关渠道"
                    );
                }
                AlertChannel target = cloneChannelWithMobiles(smsChannels.get(0), payload.mobiles);
                AlertDeliveryService.DeliveryResult res = alertDeliveryService.send(target, content);
                if (!res.success) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "短信发送失败: " + res.message);
                }
                recipientList.add("手机号: " + payload.mobiles);
                sendSuccess = true;
            } else {
                // 没有指定渠道、告警组或手机号，尝试使用默认短信渠道或第一个可用渠道
                List<AlertChannel> smsChannels = findEnabledSmsChannels();
                if (!smsChannels.isEmpty()) {
                    AlertChannel target = smsChannels.get(0);
                    AlertDeliveryService.DeliveryResult res = alertDeliveryService.send(target, content);
                    if (!res.success) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "默认短信渠道发送失败: " + res.message);
                    }
                    recipientList.add(target.channelName);
                    sendSuccess = true;
                } else {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "请选择接收短信渠道、告警组或填写接收手机号");
                }
            }
        } catch (ResponseStatusException ex) {
            failureMessage = ex.getReason();
            throw ex;
        } catch (Exception ex) {
            failureMessage = ex.getMessage();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "短信发送异常: " + ex.getMessage(), ex);
        } finally {
            DataSendResultResponse record = new DataSendResultResponse();
            record.success = sendSuccess;
            record.message = sendSuccess ? "数据短信发送成功" : ("发送失败: " + failureMessage);
            record.databaseConnectionServiceId = previewResult.databaseConnectionServiceId;
            record.databaseServiceName = previewResult.databaseServiceName;
            record.rowCount = previewResult.rowCount;
            record.columnCount = previewResult.columnCount;
            record.selectedColumns = previewResult.selectedColumns;
            record.smsContent = previewResult.smsContent;
            record.recipientSummary = recipientList.isEmpty() ? (payload.mobiles != null ? payload.mobiles : "-") : String.join(", ", recipientList);
            record.sentAt = sentAt;
            addHistory(record);
        }

        DataSendResultResponse response = new DataSendResultResponse();
        response.success = true;
        response.message = "数据短信发送成功（共 " + previewResult.rowCount + " 条记录，" + previewResult.columnCount + " 列）";
        response.databaseConnectionServiceId = previewResult.databaseConnectionServiceId;
        response.databaseServiceName = previewResult.databaseServiceName;
        response.rowCount = previewResult.rowCount;
        response.columnCount = previewResult.columnCount;
        response.selectedColumns = previewResult.selectedColumns;
        response.smsContent = previewResult.smsContent;
        response.recipientSummary = String.join(", ", recipientList);
        response.sentAt = sentAt;
        return response;
    }

    public List<DataSendResultResponse> listHistory() {
        return new ArrayList<DataSendResultResponse>(sendHistory);
    }

    private void addHistory(DataSendResultResponse record) {
        sendHistory.addFirst(record);
        while (sendHistory.size() > MAX_HISTORY_SIZE) {
            sendHistory.removeLast();
        }
    }

    /**
     * 针对手机短信屏幕优化的内容排版
     * 1. 顶部标头：【标题】共 N 条
     * 2. 每行单条记录，列数严格限制 <= 4 列，字段名短别名 + 管道符分隔
     * 3. 字段值适当截断防撑破屏幕
     * 4. 底部附加时间
     */
    public String formatSmsContent(
        String title,
        List<String> columns,
        Map<String, String> columnAliases,
        List<Map<String, Object>> rows,
        String layoutMode
    ) {
        StringBuilder sb = new StringBuilder();
        String safeTitle = StringUtils.hasText(title) ? title.trim() : "数据提醒";
        sb.append("【").append(safeTitle).append("】");
        sb.append("共").append(rows.size()).append("条\n");

        if (rows.isEmpty()) {
            sb.append("(无记录)");
        } else {
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                sb.append("[").append(i + 1).append("] ");

                List<String> colPairs = new ArrayList<String>();
                for (String col : columns) {
                    String alias = columnAliases != null && StringUtils.hasText(columnAliases.get(col))
                        ? columnAliases.get(col).trim()
                        : col;
                    Object val = row.get(col);
                    String valStr = formatCellValueForSms(val);
                    colPairs.add(alias + ":" + valStr);
                }

                sb.append(String.join(" | ", colPairs));
                if (i < rows.size() - 1) {
                    sb.append("\n");
                }
            }
        }

        String now = new SimpleDateFormat("MM-dd HH:mm").format(new Date());
        sb.append("\n时间:").append(now);
        return sb.toString();
    }

    private String formatCellValueForSms(Object val) {
        if (val == null) {
            return "-";
        }
        String str = String.valueOf(val).trim();
        if (str.isEmpty()) {
            return "-";
        }
        // 去除换行符以保护短信排版
        str = str.replace("\r", "").replace("\n", " ");
        // 手机屏幕一行限制，单个字段值超过 20 字符截断
        if (str.length() > 20) {
            return str.substring(0, 18) + "..";
        }
        return str;
    }

    private List<String> resolveSelectedColumns(List<String> userSelected, List<String> allColumns) {
        if (userSelected != null && !userSelected.isEmpty()) {
            List<String> result = new ArrayList<String>();
            for (String col : userSelected) {
                if (StringUtils.hasText(col) && allColumns.contains(col.trim()) && !result.contains(col.trim())) {
                    result.add(col.trim());
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        // 如果未指定或指定的列不在结果集中，默认取前 3 列（最多 4 列）
        if (allColumns.size() <= MAX_SMS_COLUMNS) {
            return new ArrayList<String>(allColumns);
        }
        return new ArrayList<String>(allColumns.subList(0, Math.min(3, allColumns.size())));
    }

    private int calculateSmsSplitCount(int charCount) {
        if (charCount <= 70) {
            return 1;
        }
        return (int) Math.ceil((double) charCount / 67.0);
    }

    private List<AlertChannel> findEnabledSmsChannels() {
        List<AlertChannel> result = new ArrayList<AlertChannel>();
        for (AlertChannel channel : alertMapper.listChannels(0)) {
            if ("sms".equalsIgnoreCase(channel.channelType) && (channel.enabled == null || channel.enabled)) {
                result.add(channel);
            }
        }
        return result;
    }

    private AlertChannel cloneChannelWithMobiles(AlertChannel source, String mobiles) {
        if (!StringUtils.hasText(mobiles)) {
            return source;
        }
        try {
            AlertChannel cloned = new AlertChannel();
            cloned.id = source.id;
            cloned.channelName = source.channelName;
            cloned.channelType = source.channelType;
            cloned.enabled = source.enabled;
            cloned.createdAt = source.createdAt;

            Map<String, Object> config = source.configJson != null
                ? objectMapper.readValue(source.configJson, MAP_TYPE)
                : new LinkedHashMap<String, Object>();
            config.put("alert_mobile", mobiles.trim());
            cloned.configJson = objectMapper.writeValueAsString(config);
            return cloned;
        } catch (Exception ex) {
            return source;
        }
    }
}
