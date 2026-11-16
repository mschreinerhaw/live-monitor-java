package com.live.monitor.controller;

import com.live.monitor.service.LiveMonitorService;
import com.live.monitor.service.HostMonitorService;
import com.live.monitor.entity.MonitorService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OverviewControllerTest {
    @Test void activeAlertsUseStoredStateAndFailureDoesNotBecomeZero() {
        LiveMonitorService services = mock(LiveMonitorService.class);
        HostMonitorService hosts = mock(HostMonitorService.class);
        com.live.monitor.mapper.AlertMapper alerts = mock(com.live.monitor.mapper.AlertMapper.class);
        when(services.dashboard()).thenReturn(Collections.emptyMap());
        when(services.listServices(true)).thenReturn(Collections.emptyList());
        when(hosts.listHosts(true)).thenReturn(Collections.emptyList());
        when(alerts.countActiveServiceAlerts()).thenReturn(7).thenThrow(new IllegalStateException("unavailable"));
        OverviewController controller = new OverviewController(services, hosts, alerts);
        assertEquals(7, controller.summary().get("active_service_alerts"));
        Map<String,Object> failed = controller.summary();
        assertFalse(failed.containsKey("active_service_alerts"));
        assertTrue(failed.containsKey("hosts"));
        assertTrue(((Map<?,?>)failed.get("errors")).containsKey("alerts"));
    }
    @Test void snapshotDoesNotExposeConnectionSecrets() {
        LiveMonitorService services = mock(LiveMonitorService.class);
        HostMonitorService hosts = mock(HostMonitorService.class);
        MonitorService s = new MonitorService();
        s.id = 7L; s.hostId = 3L; s.databasePassword = "private";
        when(services.dashboard()).thenReturn(Collections.emptyMap());
        when(services.listServices(true)).thenReturn(Collections.singletonList(s));
        when(hosts.listHosts(true)).thenReturn(Collections.emptyList());
        Map<String,Object> result = new OverviewController(services, hosts, mock(com.live.monitor.mapper.AlertMapper.class)).summary();
        Map<?,?> row = (Map<?,?>)((List<?>)result.get("services")).get(0);
        assertEquals(3L, row.get("host_id"));
        assertFalse(result.toString().contains("private"));
        verifyNoMoreInteractionsAfterReads(services, hosts);
    }
    private void verifyNoMoreInteractionsAfterReads(LiveMonitorService services, HostMonitorService hosts) {
        verify(services).dashboard(); verify(services).listServices(true); verify(hosts).listHosts(true);
        verifyNoMoreInteractions(services, hosts);
    }
    @Test void failedServiceSnapshotDoesNotHideHosts() {
        LiveMonitorService services = mock(LiveMonitorService.class);
        HostMonitorService hosts = mock(HostMonitorService.class);
        when(services.dashboard()).thenThrow(new IllegalStateException("offline"));
        when(hosts.listHosts(true)).thenReturn(Collections.emptyList());
        Map<String,Object> result = new OverviewController(services, hosts, mock(com.live.monitor.mapper.AlertMapper.class)).summary();
        assertTrue(result.containsKey("hosts"));
        assertTrue(((Map<?,?>)result.get("errors")).containsKey("services"));
        assertFalse(result.containsKey("services"));
    }
}
