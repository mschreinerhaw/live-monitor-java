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
    @Test void mountPreviewUsesStoredCredentialsWithoutSavingOrPublishingAlerts() {
        HostMapper mapper=mock(HostMapper.class); HostConfig existing=new HostConfig();existing.id=1L;existing.sshPasswordCipher="stored";
        when(mapper.findHost(1L)).thenReturn(existing);
        HostResourceMonitorService resources=mock(HostResourceMonitorService.class);
        when(resources.previewMounts(any(),org.mockito.ArgumentMatchers.eq(10000))).thenAnswer(call->{
            HostConfig candidate=call.getArgument(0);assertEquals("stored",candidate.sshPasswordCipher);assertEquals("[\"/data\"]",candidate.mountPointsJson);
            return java.util.Collections.emptyList();});
        HostMonitorService service=new HostMonitorService(mapper,mock(MonitorServiceMapper.class),mock(CryptoService.class),mock(SshService.class),resources,mock(RocksDbHistoryRepository.class),new ObjectMapper());
        HostPayload payload=new HostPayload();payload.hostName="preview";payload.ip="127.0.0.1";payload.mountMonitorEnabled=true;payload.mountPoints=Arrays.asList("/data");
        assertEquals(0,service.previewMounts(payload,1L).size());
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.never()).insertHost(any());
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.never()).updateHost(any());
    }
    @Test
    void hostApiKeepsActualCheckStatusAndExposesAnUnambiguousTimestamp() {
        HostMapper hosts = mock(HostMapper.class);
        HostConfig host = new HostConfig(); host.id=1L; host.monitorServiceId=99L; host.enabled=true;
        host.lastStatus="UNKNOWN"; host.lastMessage="CPU sample unavailable"; host.lastCheckedAt="2026-11-13 16:00:00.0";
        host.cpuUsagePercent=20D;
        when(hosts.findHost(1L)).thenReturn(host);
        HostMonitorService service = new HostMonitorService(hosts, mock(MonitorServiceMapper.class), mock(CryptoService.class), mock(SshService.class), mock(HostResourceMonitorService.class), mock(RocksDbHistoryRepository.class), new ObjectMapper());
        HostConfig result=service.getHost(1L);
        assertEquals("UNKNOWN",result.lastStatus);
        assertEquals("CPU sample unavailable",result.lastMessage);
        assertEquals(java.time.Instant.parse("2026-11-13T08:00:00Z").toEpochMilli(),result.lastCheckedAtEpochMs);
    }
    @Test
    void sshTestUsesEditedConnectionAndRetainsStoredCredentialsWithoutSaving() {
        HostMapper hosts = mock(HostMapper.class);
        HostConfig existing = new HostConfig(); existing.id = 7L;
        existing.sshPasswordCipher = "stored-password"; existing.privateKeyCipher = "stored-key";
        when(hosts.findHost(7L)).thenReturn(existing);
        SshService ssh = mock(SshService.class);
        when(ssh.execResult(any(HostConfig.class), org.mockito.ArgumentMatchers.eq("printf live-monitor-ssh-ok"), org.mockito.ArgumentMatchers.eq(10000)))
            .thenAnswer(call -> {
                HostConfig candidate = call.getArgument(0);
                assertEquals(2222, candidate.sshPort);
                assertEquals("deploy", candidate.sshUser);
                assertEquals("stored-password", candidate.sshPasswordCipher);
                assertEquals("stored-key", candidate.privateKeyCipher);
                return new SshService.ExecResult("live-monitor-ssh-ok", "", 0, false);
            });
        HostMonitorService service = new HostMonitorService(hosts, mock(MonitorServiceMapper.class), mock(CryptoService.class), ssh,
            mock(HostResourceMonitorService.class), mock(RocksDbHistoryRepository.class), new ObjectMapper());
        HostPayload payload = new HostPayload(); payload.hostName = "edited"; payload.ip = "127.0.0.1"; payload.sshPort = 2222; payload.sshUser = "deploy";
        assertEquals(true, service.testSsh(payload, 7L).get("success"));
        org.mockito.Mockito.verify(hosts, org.mockito.Mockito.never()).insertHost(any());
        org.mockito.Mockito.verify(hosts, org.mockito.Mockito.never()).updateHost(any());
    }

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
