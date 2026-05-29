package com.live.monitor.service;

import com.live.monitor.dto.HostPayload;
import com.live.monitor.dto.HostProcessPayload;
import com.live.monitor.entity.HostConfig;
import com.live.monitor.entity.HostProcessConfig;
import com.live.monitor.mapper.HostMapper;
import java.util.HashMap;
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
    private final CryptoService cryptoService;
    private final SshService sshService;

    public HostMonitorService(HostMapper hostMapper, CryptoService cryptoService, SshService sshService) {
        this.hostMapper = hostMapper;
        this.cryptoService = cryptoService;
        this.sshService = sshService;
    }

    public List<HostConfig> listHosts(boolean includeDisabled) {
        List<HostConfig> hosts = hostMapper.listHosts(includeDisabled ? 1 : 0);
        for (HostConfig host : hosts) {
            mask(host);
        }
        return hosts;
    }

    public HostConfig getHost(Long id) {
        HostConfig host = requireHost(id);
        mask(host);
        return host;
    }

    @Transactional
    public HostConfig createHost(HostPayload payload) {
        HostConfig host = fromPayload(payload);
        hostMapper.insertHost(host);
        return getHost(host.id);
    }

    @Transactional
    public HostConfig updateHost(Long id, HostPayload payload) {
        HostConfig host = fromPayload(payload);
        host.id = id;
        if (hostMapper.updateHost(host) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "host not found");
        }
        return getHost(id);
    }

    public boolean deleteHost(Long id) {
        return hostMapper.deleteHost(id) > 0;
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
        process.matchKeyword = payload.matchKeyword;
        process.enabled = payload.enabled == null || payload.enabled;
        hostMapper.insertProcess(process);
        return process;
    }

    public boolean deleteProcess(Long id) {
        return hostMapper.deleteProcess(id) > 0;
    }

    public Map<String, Object> metrics(Long hostId) {
        HostConfig host = requireHost(hostId);
        Map<String, Object> metrics = new HashMap<String, Object>();
        metrics.put("cpu", sshService.exec(host, "top -bn1 | head -5 || uptime", 10000));
        metrics.put("load", sshService.exec(host, "uptime", 10000));
        metrics.put("memory", sshService.exec(host, "free -m", 10000));
        metrics.put("disk", sshService.exec(host, "df -h", 10000));
        return metrics;
    }

    public Map<String, Object> processStatus(Long hostId) {
        HostConfig host = requireHost(hostId);
        Map<String, Object> result = new HashMap<String, Object>();
        for (HostProcessConfig process : hostMapper.listProcesses(hostId)) {
            if (!Boolean.TRUE.equals(process.enabled)) {
                continue;
            }
            String keyword = shellSingleQuote(process.matchKeyword);
            String output = sshService.exec(host, "ps -ef | grep " + keyword + " | grep -v grep", 10000);
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("process_name", process.processName);
            item.put("match_keyword", process.matchKeyword);
            item.put("running", StringUtils.hasText(output) && !output.contains("Exception:"));
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
        host.enabled = payload.enabled == null || payload.enabled;
        return host;
    }

    private void mask(HostConfig host) {
        host.sshPasswordCipher = null;
        host.privateKeyCipher = null;
    }

    private String shellSingleQuote(String value) {
        return "'" + String.valueOf(value).replace("'", "'\"'\"'") + "'";
    }
}
