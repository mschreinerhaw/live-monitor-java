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
        result.alertType = "host_resource_threshold";
        result.message = "CPU 45.0% / 85.0%, Memory 90.0% / 80.0%, Disk 20.0% / 85.0%";

        when(alertMapper.listChannelsByGroup(2L)).thenReturn(Collections.emptyList());
        when(historyRepository.saveAlertRecord(any(AlertRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.evaluate(monitorService, result, "UP");

        verify(historyRepository).saveAlertRecord(any(AlertRecord.class));
        verify(alertMapper, never()).listPoliciesByGroup(2L);
    }

    @Test
    void hostResourceTemplateVariablesIncludeAlertAndRecoverReasons() {
        AlertMapper alertMapper = mock(AlertMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        AlertDeliveryService deliveryService = mock(AlertDeliveryService.class);
        AlertService service = new AlertService(alertMapper, historyRepository, deliveryService);
        MonitorService monitorService = monitorService();
        monitorService.serviceType = "host";
        monitorService.host = "10.0.0.8";
        MonitorResult result = monitorResult("UP", 120);
        result.alertType = "host_resource_threshold";
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
        org.junit.jupiter.api.Assertions.assertEquals("内存使用率 90.0% >= 告警阈值 80.0%", variables.get("alertReason"));
        org.junit.jupiter.api.Assertions.assertEquals("所有已启用指标低于告警阈值（CPU使用率 < 85.0%；内存使用率 < 80.0%）", variables.get("recoverReason"));
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
