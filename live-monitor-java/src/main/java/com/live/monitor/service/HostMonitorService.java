package com.live.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.dto.HostPayload;
import com.live.monitor.dto.HostProcessPayload;
import com.live.monitor.entity.HostConfig;
import com.live.monitor.entity.HostProcessConfig;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.HostMapper;
import com.live.monitor.mapper.MonitorServiceMapper;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HostMonitorService {
    private final HostMapper hostMapper;
    private final MonitorServiceMapper serviceMapper;
    private final CryptoService cryptoService;
    private final SshService sshService;
    private final ObjectMapper objectMapper;

    public HostMonitorService(
        HostMapper hostMapper,
        MonitorServiceMapper serviceMapper,
        CryptoService cryptoService,
        SshService sshService,
        ObjectMapper objectMapper
    ) {
        this.hostMapper = hostMapper;
        this.serviceMapper = serviceMapper;
        this.cryptoService = cryptoService;
        this.sshService = sshService;
        this.objectMapper = objectMapper;
    }

    public List<HostConfig> listHosts(boolean includeDisabled) {
        List<HostConfig> hosts = hostMapper.listHosts(includeDisabled ? 1 : 0);
        for (HostConfig host : hosts) {
            if (host.monitorServiceId == null) {
                syncMonitorService(host, host.alertGroupId);
            }
            mask(host);
        }
        return hosts;
    }

    public HostConfig getHost(Long id) {
        HostConfig host = requireHost(id);
        if (host.monitorServiceId == null) {
            syncMonitorService(host, host.alertGroupId);
        }
        mask(host);
        return host;
    }

    @Transactional
    public HostConfig createHost(HostPayload payload) {
        HostConfig host = fromPayload(payload);
        hostMapper.insertHost(host);
        syncMonitorService(host, payload.alertGroupId);
        return getHost(host.id);
    }

    @Transactional
    public HostConfig updateHost(Long id, HostPayload payload) {
        HostConfig existing = requireHost(id);
        HostConfig host = fromPayload(payload);
        host.id = id;
        host.monitorServiceId = existing.monitorServiceId;
        if (hostMapper.updateHost(host) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "host not found");
        }
        syncMonitorService(host, payload.alertGroupId);
        return getHost(id);
    }

    @Transactional
    public boolean deleteHost(Long id) {
        HostConfig host = hostMapper.findHost(id);
        if (host == null) {
            return false;
        }
        int deleted = hostMapper.deleteHost(id);
        if (deleted > 0 && host.monitorServiceId != null) {
            serviceMapper.delete(host.monitorServiceId);
        }
        return deleted > 0;
    }

    public List<HostProcessConfig> listProcesses(Long hostId) {
        requireHost(hostId);
        return hostMapper.listProcesses(hostId);
    }

    public HostProcessConfig addProcess(Long hostId, HostProcessPayload payload) {
        requireHost(hostId);
        HostProcessConfig process = new HostProcessConfig();
        process.hostId = hostId;
        process.processName = payload.processName;
        process.matchKeyword = StringUtils.hasText(payload.matchKeyword) ? payload.matchKeyword : payload.processName;
        process.checkCommand = payload.checkCommand;
        process.enabled = payload.enabled == null || payload.enabled;
        hostMapper.insertProcess(process);
        return process;
    }

    public boolean deleteProcess(Long id) {
        return hostMapper.deleteProcess(id) > 0;
    }

    public HostProcessConfig updateProcess(Long id, HostProcessPayload payload) {
        HostProcessConfig existing = hostMapper.findProcess(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "process config not found");
        }
        existing.processName = payload.processName;
        existing.matchKeyword = StringUtils.hasText(payload.matchKeyword) ? payload.matchKeyword : payload.processName;
        existing.checkCommand = payload.checkCommand;
        existing.enabled = payload.enabled == null || payload.enabled;
        hostMapper.updateProcess(existing);
        return existing;
    }

    public Map<String, Object> metrics(Long hostId) {
        HostConfig host = requireHost(hostId);
        Map<String, Object> metrics = new LinkedHashMap<String, Object>();
        metrics.put("cpu_usage_percent", host.cpuUsagePercent);
        metrics.put("load_average", host.loadAverage);
        metrics.put("memory_used_percent", host.memoryUsedPercent);
        metrics.put("disk_used_percent", host.diskUsedPercent);
        metrics.put("checked_at", host.metricCheckedAt);
        return metrics;
    }

    public Map<String, Object> processStatus(Long hostId) {
        HostConfig host = requireHost(hostId);
        Map<String, Object> result = new HashMap<String, Object>();
        for (HostProcessConfig process : hostMapper.listProcesses(hostId)) {
            if (!Boolean.TRUE.equals(process.enabled)) {
                continue;
            }
            SshService.ExecResult execResult = sshService.execResult(host, process.checkCommand, 10000);
            String output = execResult.combinedOutput();
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("process_name", process.processName);
            item.put("match_keyword", process.matchKeyword);
            item.put("check_command", process.checkCommand);
            item.put("exit_status", execResult.exitStatus);
            item.put("running", !execResult.error && execResult.exitStatus != null && execResult.exitStatus == 0);
            item.put("output", output);
            result.put(String.valueOf(process.id), item);
        }
        return result;
    }

    private HostConfig requireHost(Long id) {
        HostConfig host = hostMapper.findHost(id);
        if (host == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "host not found");
        }
        return host;
    }

    private HostConfig fromPayload(HostPayload payload) {
        HostConfig host = new HostConfig();
        host.hostName = payload.hostName;
        host.ip = payload.ip;
        host.sshPort = payload.sshPort == null ? 22 : payload.sshPort;
        host.sshUser = payload.sshUser;
        host.sshPasswordCipher = cryptoService.encrypt(payload.sshPassword);
        host.privateKeyCipher = cryptoService.encrypt(payload.privateKey);
        host.clusterName = StringUtils.hasText(payload.clusterName) ? payload.clusterName.trim() : "服务器主机";
        host.cpuThresholdPercent = payload.cpuThresholdPercent == null ? 85D : payload.cpuThresholdPercent;
        host.diskThresholdPercent = payload.diskThresholdPercent == null ? 85D : payload.diskThresholdPercent;
        host.checkInterval = payload.checkInterval == null ? 60 : payload.checkInterval;
        host.alertGroupId = payload.alertGroupId;
        host.enabled = payload.enabled == null || payload.enabled;
        return host;
    }

    private void syncMonitorService(HostConfig host, Long alertGroupId) {
        MonitorService service = new MonitorService();
        service.id = host.monitorServiceId;
        service.serviceName = host.hostName;
        service.serviceCategory = "host";
        service.serviceType = "host";
        service.clusterName = StringUtils.hasText(host.clusterName) ? host.clusterName : "服务器主机";
        service.endpoint = host.ip;
        service.host = host.ip;
        service.port = host.sshPort;
        service.hostId = host.id;
        service.cpuThresholdPercent = host.cpuThresholdPercent;
        service.diskThresholdPercent = host.diskThresholdPercent;
        service.checkMode = "host_resource";
        service.expectedResult = "CPU<" + host.cpuThresholdPercent + ",DISK<" + host.diskThresholdPercent;
        service.checkTimeoutSeconds = 10D;
        service.checkInterval = host.checkInterval == null ? 60 : host.checkInterval;
        service.enabled = host.enabled;
        service.configJson = hostMonitorConfigJson(host);
        service.secretConfigJson = "{}";

        if (service.id == null || serviceMapper.findById(service.id) == null) {
            serviceMapper.insert(service);
            host.monitorServiceId = service.id;
            hostMapper.updateMonitorServiceId(host.id, service.id);
        } else {
            serviceMapper.update(service);
        }

        if (alertGroupId == null) {
            serviceMapper.unbindAlertGroup(service.id);
        } else {
            serviceMapper.bindAlertGroup(service.id, alertGroupId);
        }
    }

    private String hostMonitorConfigJson(HostConfig host) {
        try {
            Map<String, Object> config = new LinkedHashMap<String, Object>();
            config.put("host_id", host.id);
            config.put("host", host.ip);
            config.put("port", host.sshPort);
            config.put("cpu_threshold_percent", host.cpuThresholdPercent);
            config.put("disk_threshold_percent", host.diskThresholdPercent);
            return objectMapper.writeValueAsString(config);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid host monitor config");
        }
    }

    private void mask(HostConfig host) {
        host.sshPasswordCipher = null;
        host.privateKeyCipher = null;
    }

}
