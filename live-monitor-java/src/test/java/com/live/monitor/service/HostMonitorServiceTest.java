package com.live.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.dto.HostPayload;
import com.live.monitor.entity.HostConfig;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.entity.ServiceAlertGroupBinding;
import com.live.monitor.mapper.HostMapper;
import com.live.monitor.mapper.MonitorServiceMapper;
import com.live.monitor.store.RocksDbHistoryRepository;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class HostMonitorServiceTest {
    @Test
    void updateHostReplacesAndReturnsAllSelectedAlertGroups() {
        HostMapper hostMapper = mock(HostMapper.class);
        MonitorServiceMapper serviceMapper = mock(MonitorServiceMapper.class);
        HostConfig storedHost = new HostConfig();
        storedHost.id = 7L;
        storedHost.monitorServiceId = 99L;
        storedHost.hostName = "host-a";
        storedHost.ip = "10.0.0.1";
        storedHost.enabled = true;
        when(hostMapper.findHost(7L)).thenReturn(storedHost);
        when(hostMapper.updateHost(any(HostConfig.class))).thenReturn(1);

        MonitorService storedService = new MonitorService();
        storedService.id = 99L;
        when(serviceMapper.findById(99L)).thenReturn(storedService);
        ServiceAlertGroupBinding email = binding(99L, 10L, "Email");
        ServiceAlertGroupBinding sms = binding(99L, 11L, "SMS");
        when(serviceMapper.listServiceAlertGroups(any())).thenReturn(Arrays.asList(email, sms));

        HostPayload payload = new HostPayload();
        payload.hostName = "host-a";
        payload.ip = "10.0.0.1";
        payload.alertGroupIds = Arrays.asList(10L, 11L, 10L);

        HostMonitorService service = new HostMonitorService(
            hostMapper,
            serviceMapper,
            mock(CryptoService.class),
            mock(SshService.class),
            mock(HostResourceMonitorService.class),
            mock(RocksDbHistoryRepository.class),
            new ObjectMapper()
        );

        HostConfig result = service.updateHost(7L, payload);

        InOrder writes = inOrder(serviceMapper);
        writes.verify(serviceMapper).unbindAlertGroup(99L);
        writes.verify(serviceMapper).bindAlertGroup(99L, 10L);
        writes.verify(serviceMapper).bindAlertGroup(99L, 11L);
        assertEquals(Arrays.asList(10L, 11L), result.alertGroupIds);
        assertEquals(Arrays.asList("Email", "SMS"), result.alertGroupNames);
    }

    private ServiceAlertGroupBinding binding(Long serviceId, Long groupId, String groupName) {
        ServiceAlertGroupBinding binding = new ServiceAlertGroupBinding();
        binding.serviceId = serviceId;
        binding.groupId = groupId;
        binding.groupName = groupName;
        binding.groupEnabled = true;
        return binding;
    }
}
