package com.live.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.alert.AlertService;
import com.live.monitor.entity.AlertRecord;
import com.live.monitor.entity.ExternalProject;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.MonitorServiceMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ExternalMessageRelayServiceTest {
    @Test
    void acceptsJsonMessageAndReturnsAggregateDeliveryResult() {
        MonitorServiceMapper mapper = mock(MonitorServiceMapper.class);
        LiveMonitorService liveMonitorService = mock(LiveMonitorService.class);
        AlertService alertService = mock(AlertService.class);
        ExternalProjectService projectService = mock(ExternalProjectService.class);
        ExternalMessageRelayService relay = new ExternalMessageRelayService(
            mapper, liveMonitorService, alertService, new ObjectMapper(),
            mock(ExternalMessageAuditService.class), projectService
        );
        MonitorService service = new MonitorService();
        service.id = 7L;
        service.serviceName = "外部 REST 消息转发";
        service.alertGroupIds = Arrays.asList(2L, 3L);
        service.enabled = true;
        ExternalProject project = new ExternalProject();
        project.id = 9L;
        project.projectName = "IDS3.0-ZYZQ";
        AlertRecord succeeded = record("success");
        AlertRecord failed = record("failed");

        when(mapper.findFirstIdByServiceType("external_message")).thenReturn(7L);
        when(liveMonitorService.getService(7L)).thenReturn(service);
        when(projectService.route("{\"projectName\":\"IDS3.0-ZYZQ\",\"message\":\"磁盘空间不足\"}", service))
            .thenReturn(new ExternalProjectService.RoutedProject(project, service));
        when(alertService.forwardText(service, "磁盘空间不足")).thenReturn(Arrays.asList(succeeded, failed));

        Map<String, Object> result = relay.forward(
            "{\"projectName\":\"IDS3.0-ZYZQ\",\"message\":\"磁盘空间不足\"}",
            "application/json;charset=UTF-8"
        );

        verify(alertService).forwardText(service, "磁盘空间不足");
        assertEquals(false, result.get("success"));
        assertEquals(2, result.get("delivery_count"));
        assertEquals(1, result.get("success_count"));
        assertEquals(1, result.get("failed_count"));
        assertEquals("IDS3.0-ZYZQ", result.get("project_name"));
    }

    @Test
    void keepsPlainTextUnchanged() {
        ExternalMessageRelayService relay = new ExternalMessageRelayService(
            mock(MonitorServiceMapper.class),
            mock(LiveMonitorService.class),
            mock(AlertService.class),
            new ObjectMapper(),
            mock(ExternalMessageAuditService.class),
            mock(ExternalProjectService.class)
        );

        assertEquals("  first line\nsecond line  ", relay.extractMessage("  first line\nsecond line  ", "text/plain"));
    }

    @Test
    void serializesArbitraryJsonWithoutTextField() {
        ExternalMessageRelayService relay = new ExternalMessageRelayService(
            mock(MonitorServiceMapper.class),
            mock(LiveMonitorService.class),
            mock(AlertService.class),
            new ObjectMapper(),
            mock(ExternalMessageAuditService.class),
            mock(ExternalProjectService.class)
        );

        assertEquals(
            "{\"severity\":\"warn\",\"data\":{\"diskUsed\":95},\"tags\":[\"prod\",\"db\"]}",
            relay.extractMessage(
                "{\"severity\":\"warn\",\"data\":{\"diskUsed\":95},\"tags\":[\"prod\",\"db\"]}",
                "application/json"
            )
        );
        assertEquals("[1,true,{\"state\":\"down\"}]", relay.extractMessage(
            "[1,true,{\"state\":\"down\"}]", "application/json"
        ));
    }

    @Test
    void rejectsInvalidJson() {
        ExternalMessageRelayService relay = new ExternalMessageRelayService(
            mock(MonitorServiceMapper.class),
            mock(LiveMonitorService.class),
            mock(AlertService.class),
            new ObjectMapper(),
            mock(ExternalMessageAuditService.class),
            mock(ExternalProjectService.class)
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> relay.extractMessage("{\"severity\":", "application/json")
        );
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    @Test
    void rejectsAndAuditsRequestWhenExternalChannelIsDisabled() {
        MonitorServiceMapper mapper = mock(MonitorServiceMapper.class);
        LiveMonitorService liveMonitorService = mock(LiveMonitorService.class);
        AlertService alertService = mock(AlertService.class);
        ExternalMessageAuditService auditService = mock(ExternalMessageAuditService.class);
        ExternalMessageRelayService relay = new ExternalMessageRelayService(
            mapper, liveMonitorService, alertService, new ObjectMapper(), auditService,
            mock(ExternalProjectService.class)
        );
        MonitorService service = new MonitorService();
        service.id = 7L;
        service.enabled = false;
        when(mapper.findFirstIdByServiceType("external_message")).thenReturn(7L);
        when(liveMonitorService.getService(7L)).thenReturn(service);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> relay.forward("blocked", "text/plain", "10.0.0.8")
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(auditService).record(7L, "10.0.0.8", "text/plain", "blocked", "REJECTED", 0, 0, 0, "channel disabled");
    }

    private AlertRecord record(String status) {
        AlertRecord record = new AlertRecord();
        record.alertStatus = status;
        return record;
    }
}
