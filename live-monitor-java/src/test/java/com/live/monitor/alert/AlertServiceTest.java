package com.live.monitor.alert;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.live.monitor.entity.AlertChannel;
import com.live.monitor.entity.AlertPolicy;
import com.live.monitor.entity.AlertRecord;
import com.live.monitor.entity.AlertState;
import com.live.monitor.entity.CheckEvent;
import com.live.monitor.entity.EventType;
import com.live.monitor.entity.MonitorResult;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.AlertMapper;
import com.live.monitor.store.RocksDbHistoryRepository;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class AlertServiceTest {
    @Test
    void latencyPolicyDoesNotBypassConsecutiveDownPolicyForDownResult() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        MonitorResult result = monitorResult("DOWN", 5000);

        when(alertMapper.listPoliciesByGroup(2L)).thenReturn(Collections.singletonList(latencyPolicy()));

        service.evaluate(monitorService, result, "UP");

        verify(historyRepository, never()).saveAlertRecord(any(AlertRecord.class));
    }

    @Test
    void latencyPolicyTriggersForSlowUpResult() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        MonitorResult result = monitorResult("UP", 5000);

        when(alertMapper.listPoliciesByGroup(2L)).thenReturn(Collections.singletonList(latencyPolicy()));
        when(alertMapper.listChannelsByGroup(2L)).thenReturn(Collections.emptyList());
        when(historyRepository.saveAlertRecord(any(AlertRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.evaluate(monitorService, result, "UP");

        verify(historyRepository).saveAlertRecord(any(AlertRecord.class));
    }

    @Test
    void consecutiveDownPolicyDoesNotTriggerForCurrentUpResult() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        MonitorResult result = monitorResult("UP", 100);

        when(alertMapper.listPoliciesByGroup(2L)).thenReturn(Collections.singletonList(consecutiveDownPolicy()));
        when(historyRepository.listMonitorResults(1L, 4)).thenReturn(Arrays.asList(
            monitorResult("DOWN", 100),
            monitorResult("DOWN", 100),
            monitorResult("DOWN", 100)
        ));

        service.evaluate(monitorService, result, "DOWN");

        verify(historyRepository, never()).saveAlertRecord(any(AlertRecord.class));
    }

    @Test
    void hostResourceThresholdAlertDispatchesWithoutMarkingServiceDown() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        monitorService.serviceType = "host";
        MonitorResult result = monitorResult("UP", 120);
        markHostResourceAlert(result);
        result.message = "CPU 45.0% / 85.0%, Memory 90.0% / 80.0%, Disk 20.0% / 85.0%";

        when(alertMapper.listChannelsByGroup(2L)).thenReturn(Collections.emptyList());
        when(historyRepository.saveAlertRecord(any(AlertRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.evaluate(monitorService, result, "UP");

        verify(historyRepository).saveAlertRecord(any(AlertRecord.class));
        verify(alertMapper, never()).listPoliciesByGroup(2L);
    }

    @Test
    void hostResourceThresholdAlertTypeIsIgnoredWhenNoMetricExceedsThreshold() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        monitorService.serviceType = "host";
        MonitorResult result = monitorResult("UP", 120);
        markHostResourceAlert(result);
        result.message = "CPU 0.7% / 85.0%, Memory 79.5% / 85.0%, Disk 60.0% / 85.0%";

        service.evaluate(monitorService, result, "UP");

        verify(historyRepository, never()).saveAlertRecord(any(AlertRecord.class));
        verify(alertMapper, never()).listChannelsByGroup(anyLong());
    }

    @Test
    void serviceDownEventIsNormalizedBeforeQueueConsumption() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        monitorService.serviceType = "redis";
        monitorService.alertGroupId = null;
        MonitorResult result = monitorResult("DOWN", 120);
        result.message = "ConnectException: refused";

        service.evaluate(monitorService, result, "UP");

        ArgumentCaptor<CheckEvent> eventCaptor = ArgumentCaptor.forClass(CheckEvent.class);
        verify(alertMapper).insertCheckEvent(eventCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(EventType.SERVICE_DOWN.name(), eventCaptor.getValue().eventType);
    }

    @Test
    void apiTimeoutEventIsNormalizedBeforeQueueConsumption() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        monitorService.serviceType = "api";
        monitorService.alertGroupId = null;
        MonitorResult result = monitorResult("DOWN", 5000);
        result.message = "SocketTimeoutException: timeout";

        service.evaluate(monitorService, result, "UP");

        ArgumentCaptor<CheckEvent> eventCaptor = ArgumentCaptor.forClass(CheckEvent.class);
        verify(alertMapper).insertCheckEvent(eventCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(EventType.API_TIMEOUT.name(), eventCaptor.getValue().eventType);
    }

    @Test
    void databaseAssertionEventIsNormalizedBeforeQueueConsumption() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        monitorService.serviceType = "mysql";
        monitorService.alertGroupId = null;
        MonitorResult result = monitorResult("DOWN", 120);
        result.message = "MySQL 8.0, result: 0, rule: = 1";

        service.evaluate(monitorService, result, "UP");

        ArgumentCaptor<CheckEvent> eventCaptor = ArgumentCaptor.forClass(CheckEvent.class);
        verify(alertMapper).insertCheckEvent(eventCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(EventType.DB_ASSERT_FAIL.name(), eventCaptor.getValue().eventType);
    }

    @Test
    void databaseAssertionAlertUsesBusinessLanguageTemplate() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertDeliveryService deliveryService = mock(AlertDeliveryService.class);
        AlertService service = new AlertService(alertMapper, historyRepository, deliveryService);
        MonitorService monitorService = monitorService();
        monitorService.serviceType = "mysql";
        monitorService.serviceConsecutiveFailures = 1;
        monitorService.host = "10.0.0.9";
        monitorService.port = 3306;

        MonitorResult result = monitorResult("DOWN", 68);
        result.checkedAt = "2026-06-05 10:11:12";
        result.message = "MySQL 8.0.36, result: order_count=0, rule: > 0, "
            + "api assertion failed: field(\"order_count\") > 0, reason: order_count = [0] > 0 not matched";

        AlertChannel channel = new AlertChannel();
        channel.channelType = "sms";
        channel.enabled = true;
        when(alertMapper.listPoliciesByGroup(2L)).thenReturn(Collections.emptyList());
        when(alertMapper.listChannelsByGroup(2L)).thenReturn(Collections.singletonList(channel));
        when(deliveryService.renderTemplate(eq("sms_database_assertion_alert.j2"), any(Map.class), anyString())).thenReturn("rendered");
        when(deliveryService.send(eq(channel), eq("rendered"))).thenReturn(AlertDeliveryService.DeliveryResult.success());
        when(historyRepository.saveAlertRecord(any(AlertRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.evaluate(monitorService, result, "UP");

        ArgumentCaptor<Map<String, Object>> variablesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(deliveryService).renderTemplate(eq("sms_database_assertion_alert.j2"), variablesCaptor.capture(), anyString());
        Map<String, Object> variables = variablesCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("MySQL 8.0.36", variables.get("databaseProduct"));
        org.junit.jupiter.api.Assertions.assertEquals("order_count=0", variables.get("databaseResult"));
        org.junit.jupiter.api.Assertions.assertEquals("> 0", variables.get("databaseRule"));
        org.junit.jupiter.api.Assertions.assertEquals(
            "数据库查询结果不符合业务规则：> 0",
            variables.get("databaseSummary")
        );
        org.junit.jupiter.api.Assertions.assertEquals("order_count = [0] > 0 not matched", variables.get("databaseReason"));
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(variables.get("businessImpact")).contains("业务判断"));
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(variables.get("actionSuggestion")).contains("监控 SQL"));
    }

    @Test
    void hostResourceTemplateVariablesIdentifyTriggeredMetrics() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertDeliveryService deliveryService = mock(AlertDeliveryService.class);
        AlertService service = new AlertService(alertMapper, historyRepository, deliveryService);
        MonitorService monitorService = monitorService();
        monitorService.serviceType = "host";
        monitorService.host = "10.0.0.8";
        MonitorResult result = monitorResult("UP", 120);
        markHostResourceAlert(result);
        result.checkedAt = "2026-06-03 09:06:44";
        result.message = "CPU 45.0% / 85.0%, Memory 90.0% / 80.0%, Disk 20.0% / disabled";

        AlertChannel channel = new AlertChannel();
        channel.channelType = "sms";
        channel.enabled = true;
        when(alertMapper.listChannelsByGroup(2L)).thenReturn(Collections.singletonList(channel));
        when(deliveryService.renderTemplate(eq("alert_host_resource.j2"), any(Map.class), anyString())).thenReturn("rendered");
        when(deliveryService.send(eq(channel), eq("rendered"))).thenReturn(AlertDeliveryService.DeliveryResult.success());
        when(historyRepository.saveAlertRecord(any(AlertRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.evaluate(monitorService, result, "UP");

        ArgumentCaptor<Map<String, Object>> variablesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(deliveryService).renderTemplate(eq("alert_host_resource.j2"), variablesCaptor.capture(), anyString());
        Map<String, Object> variables = variablesCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("内存使用率超过告警阈值（90.0% > 80.0%）", variables.get("alertReason"));
        org.junit.jupiter.api.Assertions.assertEquals("1项指标超过阈值", variables.get("alertSummary"));
        org.junit.jupiter.api.Assertions.assertEquals("10.0.0.8", variables.get("host"));
        org.junit.jupiter.api.Assertions.assertEquals("45.0%", variables.get("cpuText"));
        org.junit.jupiter.api.Assertions.assertEquals("90.0%", variables.get("memoryText"));
        org.junit.jupiter.api.Assertions.assertEquals("20.0%", variables.get("diskText"));
        org.junit.jupiter.api.Assertions.assertEquals("HOST-20260603090644", variables.get("alertId"));
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(variables.get("alertItems")).contains("内存使用率"));
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(variables.get("alertItems")).contains("超限幅度：+10.0%"));
    }

    @Test
    void hostResourceRecoveryTemplateKeepsPreviousAlertSnapshot() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertDeliveryService deliveryService = mock(AlertDeliveryService.class);
        AlertService service = new AlertService(alertMapper, historyRepository, deliveryService);
        MonitorService monitorService = monitorService();
        monitorService.serviceType = "host";
        monitorService.host = "192.168.195.232";
        monitorService.configJson = "{\"resource_recover_duration_seconds\":120}";
        Map<String, AlertState> states = new HashMap<String, AlertState>();

        AlertChannel channel = new AlertChannel();
        channel.channelType = "sms";
        channel.enabled = true;
        when(alertMapper.listPoliciesByGroup(2L)).thenReturn(Collections.singletonList(recoveredPolicy()));
        when(alertMapper.listChannelsByGroup(2L)).thenReturn(Collections.singletonList(channel));
        when(alertMapper.findAlertState(anyLong(), anyString())).thenAnswer(invocation ->
            states.get(invocation.getArgument(0) + ":" + invocation.getArgument(1))
        );
        when(alertMapper.upsertAlertState(any(AlertState.class))).thenAnswer(invocation -> {
            AlertState state = invocation.getArgument(0);
            states.put(state.serviceId + ":" + state.alertKey, state);
            return 1;
        });
        when(deliveryService.renderTemplate(anyString(), any(Map.class), anyString())).thenReturn("rendered");
        when(deliveryService.send(eq(channel), eq("rendered"))).thenReturn(AlertDeliveryService.DeliveryResult.success());
        when(historyRepository.saveAlertRecord(any(AlertRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MonitorResult alert = monitorResult("UP", 120);
        markHostResourceAlert(alert);
        alert.checkedAt = "2026-06-03 09:06:44";
        alert.message = "CPU 92.4% / 85.0%, Memory 54.9% / 85.0%, Disk 44.0% / 85.0%";
        service.evaluate(monitorService, alert, "UP");

        MonitorResult firstNormal = monitorResult("UP", 110);
        markHostResourceEvent(firstNormal);
        firstNormal.checkedAt = "2026-06-03 09:10:00";
        firstNormal.message = "CPU 78.2% / 85.0%, Memory 52.1% / 85.0%, Disk 44.0% / 85.0%";
        service.evaluate(monitorService, firstNormal, "UP");

        MonitorResult recovered = monitorResult("UP", 100);
        markHostResourceEvent(recovered);
        recovered.checkedAt = "2026-06-03 09:16:22";
        recovered.message = "CPU 78.2% / 85.0%, Memory 52.1% / 85.0%, Disk 44.0% / 85.0%";
        service.evaluate(monitorService, recovered, "UP");

        ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> variablesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(deliveryService, times(2)).renderTemplate(templateCaptor.capture(), variablesCaptor.capture(), anyString());
        org.junit.jupiter.api.Assertions.assertEquals("alert_host_resource.j2", templateCaptor.getAllValues().get(0));
        org.junit.jupiter.api.Assertions.assertEquals("alert_host_resource_recover.j2", templateCaptor.getAllValues().get(1));

        Map<String, Object> variables = variablesCaptor.getAllValues().get(1);
        org.junit.jupiter.api.Assertions.assertEquals("CPU使用率已恢复正常（78.2% < 85.0%）", variables.get("recoverReason"));
        org.junit.jupiter.api.Assertions.assertEquals("9分钟38秒", variables.get("duration"));
        org.junit.jupiter.api.Assertions.assertEquals("HOST-20260603090644", variables.get("alertId"));
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(variables.get("recoverItems")).contains("CPU使用率"));
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(variables.get("historyAlert")).contains("CPU使用率：92.4%"));
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(variables.get("historyAlert")).contains("告警时间：2026-06-03 09:06:44"));
    }

    @Test
    void hostResourceCooldownSuppressesRepeatedNotificationAfterRecovery() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        monitorService.serviceType = "host";
        monitorService.checkInterval = 60;
        monitorService.configJson = "{\"resource_recover_duration_seconds\":60,\"resource_alert_cooldown_seconds\":600}";
        Map<String, AlertState> states = new HashMap<String, AlertState>();

        when(alertMapper.listPoliciesByGroup(2L)).thenReturn(Collections.emptyList());
        when(alertMapper.listChannelsByGroup(2L)).thenReturn(Collections.emptyList());
        when(alertMapper.findAlertState(anyLong(), anyString())).thenAnswer(invocation ->
            states.get(invocation.getArgument(0) + ":" + invocation.getArgument(1))
        );
        when(alertMapper.upsertAlertState(any(AlertState.class))).thenAnswer(invocation -> {
            AlertState state = invocation.getArgument(0);
            states.put(state.serviceId + ":" + state.alertKey, state);
            return 1;
        });
        when(historyRepository.saveAlertRecord(any(AlertRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MonitorResult firstAlert = monitorResult("UP", 120);
        markHostResourceAlert(firstAlert);
        firstAlert.checkedAt = "2026-06-03 09:00:00";
        firstAlert.message = "CPU 90.0% / 85.0%, Memory 40.0% / 85.0%, Disk 20.0% / 85.0%";
        service.evaluate(monitorService, firstAlert, "UP");

        MonitorResult recovered = monitorResult("UP", 100);
        markHostResourceEvent(recovered);
        recovered.checkedAt = "2026-06-03 09:01:00";
        recovered.message = "CPU 70.0% / 85.0%, Memory 40.0% / 85.0%, Disk 20.0% / 85.0%";
        service.evaluate(monitorService, recovered, "UP");

        MonitorResult secondAlert = monitorResult("UP", 120);
        markHostResourceAlert(secondAlert);
        secondAlert.checkedAt = "2026-06-03 09:05:00";
        secondAlert.message = "CPU 91.0% / 85.0%, Memory 40.0% / 85.0%, Disk 20.0% / 85.0%";
        service.evaluate(monitorService, secondAlert, "UP");

        verify(historyRepository, times(1)).saveAlertRecord(any(AlertRecord.class));
        AlertState state = states.get("1:host_resource_threshold");
        org.junit.jupiter.api.Assertions.assertEquals("ALERTING", state.state);
        org.junit.jupiter.api.Assertions.assertEquals("2026-06-03 09:00:00", state.lastAlertAt);
    }

    @Test
    void hostResourceRecoveryConfirmationCanBeDisabled() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        monitorService.serviceType = "host";
        monitorService.checkInterval = 60;
        monitorService.configJson = "{\"resource_recover_duration_enabled\":false,\"resource_recover_duration_seconds\":600}";
        Map<String, AlertState> states = new HashMap<String, AlertState>();

        when(alertMapper.listPoliciesByGroup(2L)).thenReturn(Collections.singletonList(recoveredPolicy()));
        when(alertMapper.listChannelsByGroup(2L)).thenReturn(Collections.emptyList());
        when(alertMapper.findAlertState(anyLong(), anyString())).thenAnswer(invocation ->
            states.get(invocation.getArgument(0) + ":" + invocation.getArgument(1))
        );
        when(alertMapper.upsertAlertState(any(AlertState.class))).thenAnswer(invocation -> {
            AlertState state = invocation.getArgument(0);
            states.put(state.serviceId + ":" + state.alertKey, state);
            return 1;
        });
        when(historyRepository.saveAlertRecord(any(AlertRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MonitorResult alert = monitorResult("UP", 120);
        markHostResourceAlert(alert);
        alert.checkedAt = "2026-06-03 09:00:00";
        alert.message = "CPU 90.0% / 85.0%, Memory 40.0% / 85.0%, Disk 20.0% / 85.0%";
        service.evaluate(monitorService, alert, "UP");

        MonitorResult recovered = monitorResult("UP", 100);
        markHostResourceEvent(recovered);
        recovered.checkedAt = "2026-06-03 09:01:00";
        recovered.message = "CPU 70.0% / 85.0%, Memory 40.0% / 85.0%, Disk 20.0% / 85.0%";
        service.evaluate(monitorService, recovered, "UP");

        verify(historyRepository, times(2)).saveAlertRecord(any(AlertRecord.class));
        AlertState state = states.get("1:host_resource_threshold");
        org.junit.jupiter.api.Assertions.assertEquals("RECOVERED", state.state);
    }

    @Test
    void hostUpEventIsNormalizedBeforeQueueConsumption() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        monitorService.serviceType = "host";
        monitorService.alertGroupId = null;
        monitorService.serviceAlertEnabled = false;

        MonitorResult result = monitorResult("UP", 100);
        result.checkedAt = "2026-06-04 14:02:11";
        result.message = "CPU 0.4% / 85.0%, Memory 51.0% / 85.0%, Disk 45.0% / 85.0%";

        service.evaluate(monitorService, result, "UP");

        ArgumentCaptor<CheckEvent> eventCaptor = ArgumentCaptor.forClass(CheckEvent.class);
        verify(alertMapper).insertCheckEvent(eventCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(EventType.SERVICE_RECOVERED.name(), eventCaptor.getValue().eventType);
        verify(alertMapper, never()).upsertAlertState(any(AlertState.class));
        verify(historyRepository, never()).saveAlertRecord(any(AlertRecord.class));
    }

    @Test
    void hostResourceCooldownCanBeDisabled() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        monitorService.serviceType = "host";
        monitorService.checkInterval = 60;
        monitorService.configJson = "{\"resource_recover_duration_seconds\":60,\"resource_alert_cooldown_enabled\":false,\"resource_alert_cooldown_seconds\":600}";
        Map<String, AlertState> states = new HashMap<String, AlertState>();

        when(alertMapper.listPoliciesByGroup(2L)).thenReturn(Collections.emptyList());
        when(alertMapper.listChannelsByGroup(2L)).thenReturn(Collections.emptyList());
        when(alertMapper.findAlertState(anyLong(), anyString())).thenAnswer(invocation ->
            states.get(invocation.getArgument(0) + ":" + invocation.getArgument(1))
        );
        when(alertMapper.upsertAlertState(any(AlertState.class))).thenAnswer(invocation -> {
            AlertState state = invocation.getArgument(0);
            states.put(state.serviceId + ":" + state.alertKey, state);
            return 1;
        });
        when(historyRepository.saveAlertRecord(any(AlertRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MonitorResult firstAlert = monitorResult("UP", 120);
        markHostResourceAlert(firstAlert);
        firstAlert.checkedAt = "2026-06-03 09:00:00";
        firstAlert.message = "CPU 90.0% / 85.0%, Memory 40.0% / 85.0%, Disk 20.0% / 85.0%";
        service.evaluate(monitorService, firstAlert, "UP");

        MonitorResult recovered = monitorResult("UP", 100);
        markHostResourceEvent(recovered);
        recovered.checkedAt = "2026-06-03 09:01:00";
        recovered.message = "CPU 70.0% / 85.0%, Memory 40.0% / 85.0%, Disk 20.0% / 85.0%";
        service.evaluate(monitorService, recovered, "UP");

        MonitorResult secondAlert = monitorResult("UP", 120);
        markHostResourceAlert(secondAlert);
        secondAlert.checkedAt = "2026-06-03 09:05:00";
        secondAlert.message = "CPU 91.0% / 85.0%, Memory 40.0% / 85.0%, Disk 20.0% / 85.0%";
        service.evaluate(monitorService, secondAlert, "UP");

        verify(historyRepository, times(2)).saveAlertRecord(any(AlertRecord.class));
        AlertState state = states.get("1:host_resource_threshold");
        org.junit.jupiter.api.Assertions.assertEquals("2026-06-03 09:05:00", state.lastAlertAt);
    }

    @Test
    void availabilityStateRequiresConfirmationAndSuppressesDuplicatesUntilRecovery() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        Map<String, AlertState> states = new HashMap<String, AlertState>();

        when(alertMapper.listPoliciesByGroup(2L)).thenReturn(Arrays.asList(consecutiveDownPolicy(), recoveredPolicy()));
        when(alertMapper.listChannelsByGroup(2L)).thenReturn(Collections.emptyList());
        when(alertMapper.findAlertState(anyLong(), anyString())).thenAnswer(invocation ->
            states.get(invocation.getArgument(0) + ":" + invocation.getArgument(1))
        );
        when(alertMapper.upsertAlertState(any(AlertState.class))).thenAnswer(invocation -> {
            AlertState state = invocation.getArgument(0);
            states.put(state.serviceId + ":" + state.alertKey, state);
            return 1;
        });
        when(historyRepository.saveAlertRecord(any(AlertRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.evaluate(monitorService, monitorResult("DOWN", 100), "UP");
        service.evaluate(monitorService, monitorResult("DOWN", 100), "DOWN");

        verify(historyRepository, never()).saveAlertRecord(any(AlertRecord.class));

        service.evaluate(monitorService, monitorResult("DOWN", 100), "DOWN");
        service.evaluate(monitorService, monitorResult("DOWN", 100), "DOWN");

        verify(historyRepository, times(1)).saveAlertRecord(any(AlertRecord.class));

        service.evaluate(monitorService, monitorResult("UP", 100), "DOWN");
        verify(historyRepository, times(1)).saveAlertRecord(any(AlertRecord.class));

        service.evaluate(monitorService, monitorResult("UP", 100), "UP");

        verify(historyRepository, times(2)).saveAlertRecord(any(AlertRecord.class));
    }

    @Test
    void availabilityUsesServiceConfiguredFailureAndRecoveryCounts() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        monitorService.serviceConsecutiveFailures = 4;
        monitorService.serviceRecoverSuccesses = 3;
        Map<String, AlertState> states = new HashMap<String, AlertState>();

        when(alertMapper.listPoliciesByGroup(2L)).thenReturn(Collections.emptyList());
        when(alertMapper.listChannelsByGroup(2L)).thenReturn(Collections.emptyList());
        when(alertMapper.findAlertState(anyLong(), anyString())).thenAnswer(invocation ->
            states.get(invocation.getArgument(0) + ":" + invocation.getArgument(1))
        );
        when(alertMapper.upsertAlertState(any(AlertState.class))).thenAnswer(invocation -> {
            AlertState state = invocation.getArgument(0);
            states.put(state.serviceId + ":" + state.alertKey, state);
            return 1;
        });
        when(historyRepository.saveAlertRecord(any(AlertRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.evaluate(monitorService, monitorResult("DOWN", 100), "UP");
        service.evaluate(monitorService, monitorResult("DOWN", 100), "DOWN");
        service.evaluate(monitorService, monitorResult("DOWN", 100), "DOWN");
        verify(historyRepository, never()).saveAlertRecord(any(AlertRecord.class));

        service.evaluate(monitorService, monitorResult("DOWN", 100), "DOWN");
        verify(historyRepository, times(1)).saveAlertRecord(any(AlertRecord.class));

        service.evaluate(monitorService, monitorResult("UP", 100), "DOWN");
        service.evaluate(monitorService, monitorResult("UP", 100), "UP");
        verify(historyRepository, times(1)).saveAlertRecord(any(AlertRecord.class));

        service.evaluate(monitorService, monitorResult("UP", 100), "UP");
        verify(historyRepository, times(2)).saveAlertRecord(any(AlertRecord.class));
    }

    @Test
    void availabilityCooldownSuppressesRepeatedNotificationAfterRecovery() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        monitorService.serviceAlertCooldownSeconds = 600;
        Map<String, AlertState> states = new HashMap<String, AlertState>();

        when(alertMapper.listPoliciesByGroup(2L)).thenReturn(Collections.emptyList());
        when(alertMapper.listChannelsByGroup(2L)).thenReturn(Collections.emptyList());
        when(alertMapper.findAlertState(anyLong(), anyString())).thenAnswer(invocation ->
            states.get(invocation.getArgument(0) + ":" + invocation.getArgument(1))
        );
        when(alertMapper.upsertAlertState(any(AlertState.class))).thenAnswer(invocation -> {
            AlertState state = invocation.getArgument(0);
            states.put(state.serviceId + ":" + state.alertKey, state);
            return 1;
        });
        when(historyRepository.saveAlertRecord(any(AlertRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.evaluate(monitorService, monitorResultAt("DOWN", "2026-06-03 09:00:00"), "UP");
        service.evaluate(monitorService, monitorResultAt("DOWN", "2026-06-03 09:01:00"), "DOWN");
        service.evaluate(monitorService, monitorResultAt("DOWN", "2026-06-03 09:02:00"), "DOWN");
        verify(historyRepository, times(1)).saveAlertRecord(any(AlertRecord.class));

        service.evaluate(monitorService, monitorResultAt("UP", "2026-06-03 09:03:00"), "DOWN");
        service.evaluate(monitorService, monitorResultAt("UP", "2026-06-03 09:04:00"), "UP");
        verify(historyRepository, times(2)).saveAlertRecord(any(AlertRecord.class));

        service.evaluate(monitorService, monitorResultAt("DOWN", "2026-06-03 09:05:00"), "UP");
        service.evaluate(monitorService, monitorResultAt("DOWN", "2026-06-03 09:06:00"), "DOWN");
        service.evaluate(monitorService, monitorResultAt("DOWN", "2026-06-03 09:07:00"), "DOWN");

        verify(historyRepository, times(2)).saveAlertRecord(any(AlertRecord.class));
        AlertState state = states.get("1:availability");
        org.junit.jupiter.api.Assertions.assertEquals("ALERTING", state.state);
        org.junit.jupiter.api.Assertions.assertEquals("2026-06-03 09:02:00", state.lastAlertAt);
    }

    @Test
    void availabilityAlertCanBeDisabledPerService() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertService service = new AlertService(alertMapper, historyRepository, mock(AlertDeliveryService.class));
        MonitorService monitorService = monitorService();
        monitorService.serviceAlertEnabled = false;

        service.evaluate(monitorService, monitorResult("DOWN", 100), "UP");
        service.evaluate(monitorService, monitorResult("DOWN", 100), "DOWN");
        service.evaluate(monitorService, monitorResult("DOWN", 100), "DOWN");

        verify(historyRepository, never()).saveAlertRecord(any(AlertRecord.class));
        verify(alertMapper, never()).upsertAlertState(any(AlertState.class));
    }

    private MonitorService monitorService() {
        MonitorService service = new MonitorService();
        service.id = 1L;
        service.serviceName = "demo";
        service.serviceType = "web";
        service.alertGroupId = 2L;
        service.alertGroupEnabled = true;
        return service;
    }

    private MonitorResult monitorResult(String status, Integer responseTimeMs) {
        MonitorResult result = new MonitorResult();
        result.serviceId = 1L;
        result.status = status;
        result.responseTimeMs = responseTimeMs;
        result.message = "HTTP timeout";
        return result;
    }

    private MonitorResult monitorResultAt(String status, String checkedAt) {
        MonitorResult result = monitorResult(status, 100);
        result.checkedAt = checkedAt;
        return result;
    }

    private void markHostResourceAlert(MonitorResult result) {
        result.eventType = EventType.HOST_CPU_HIGH.name();
        result.alertType = "host_resource_threshold";
    }

    private void markHostResourceEvent(MonitorResult result) {
        result.eventType = EventType.SERVICE_RECOVERED.name();
    }

    private AlertPolicy latencyPolicy() {
        AlertPolicy policy = new AlertPolicy();
        policy.id = 2L;
        policy.policyName = "Latency > 3 seconds";
        policy.triggerType = "latency_gt_ms";
        policy.triggerValue = "3000";
        policy.enabled = true;
        return policy;
    }

    private AlertPolicy consecutiveDownPolicy() {
        AlertPolicy policy = new AlertPolicy();
        policy.id = 1L;
        policy.policyName = "DOWN consecutive 3 times";
        policy.triggerType = "consecutive_down";
        policy.triggerValue = "3";
        policy.enabled = true;
        return policy;
    }

    private AlertPolicy recoveredPolicy() {
        AlertPolicy policy = new AlertPolicy();
        policy.id = 3L;
        policy.policyName = "Service recovered";
        policy.triggerType = "recovered";
        policy.triggerValue = "UP";
        policy.enabled = true;
        return policy;
    }
}
