package com.live.monitor.controller;

import com.live.monitor.dto.HostPayload;
import com.live.monitor.dto.HostProcessPayload;
import com.live.monitor.entity.HostConfig;
import com.live.monitor.entity.HostProcessConfig;
import com.live.monitor.service.HostMonitorService;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class HostController {
    private final HostMonitorService hostMonitorService;

    public HostController(HostMonitorService hostMonitorService) {
        this.hostMonitorService = hostMonitorService;
    }

    @GetMapping("/api/hosts")
    public List<HostConfig> hosts(@RequestParam(name = "include_disabled", defaultValue = "false") boolean includeDisabled) {
        return hostMonitorService.listHosts(includeDisabled);
    }

    @GetMapping("/api/hosts/summary")
    public Map<String, Object> summary() {
        return hostMonitorService.summary();
    }

    @PostMapping("/api/hosts")
    @ResponseStatus(HttpStatus.CREATED)
    public HostConfig createHost(@Valid @RequestBody HostPayload payload) {
        return hostMonitorService.createHost(payload);
    }

    @GetMapping("/api/hosts/{hostId}")
    public HostConfig host(@PathVariable Long hostId) {
        return hostMonitorService.getHost(hostId);
    }

    @PutMapping("/api/hosts/{hostId}")
    public HostConfig updateHost(@PathVariable Long hostId, @Valid @RequestBody HostPayload payload) {
        return hostMonitorService.updateHost(hostId, payload);
    }

    @DeleteMapping("/api/hosts/{hostId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteHost(@PathVariable Long hostId) {
        if (!hostMonitorService.deleteHost(hostId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "host not found");
        }
    }

    @GetMapping("/api/hosts/{hostId}/metrics")
    public Map<String, Object> metrics(@PathVariable Long hostId) {
        return hostMonitorService.metrics(hostId);
    }

    @GetMapping("/api/hosts/{hostId}/metrics/history")
    public List<Map<String, Object>> metricHistory(
        @PathVariable Long hostId,
        @RequestParam(name = "days", defaultValue = "7") Integer days,
        @RequestParam(name = "limit", defaultValue = "10000") Integer limit
    ) {
        return hostMonitorService.metricHistory(hostId, days, limit);
    }

    @PostMapping("/api/hosts/{hostId}/metrics/refresh")
    public Map<String, Object> refreshMetrics(@PathVariable Long hostId) {
        return hostMonitorService.refreshMetrics(hostId);
    }

    @PostMapping("/api/hosts/metrics/refresh")
    public Map<String, Object> refreshAllMetrics() {
        return hostMonitorService.refreshAllMetrics();
    }

    @GetMapping("/api/hosts/{hostId}/processes")
    public List<HostProcessConfig> processes(@PathVariable Long hostId) {
        return hostMonitorService.listProcesses(hostId);
    }

    @PostMapping("/api/hosts/{hostId}/processes")
    @ResponseStatus(HttpStatus.CREATED)
    public HostProcessConfig addProcess(@PathVariable Long hostId, @Valid @RequestBody HostProcessPayload payload) {
        return hostMonitorService.addProcess(hostId, payload);
    }

    @PutMapping("/api/host-processes/{processId}")
    public HostProcessConfig updateProcess(@PathVariable Long processId, @Valid @RequestBody HostProcessPayload payload) {
        return hostMonitorService.updateProcess(processId, payload);
    }

    @DeleteMapping("/api/host-processes/{processId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProcess(@PathVariable Long processId) {
        if (!hostMonitorService.deleteProcess(processId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "process config not found");
        }
    }

    @GetMapping("/api/hosts/{hostId}/process-status")
    public Map<String, Object> processStatus(@PathVariable Long hostId) {
        return hostMonitorService.processStatus(hostId);
    }
}
