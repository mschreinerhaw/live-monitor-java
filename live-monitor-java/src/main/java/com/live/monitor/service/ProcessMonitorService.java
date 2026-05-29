package com.live.monitor.service;

import com.live.monitor.dto.CheckResult;
import com.live.monitor.entity.HostConfig;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.HostMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ProcessMonitorService {
    private final HostMapper hostMapper;
    private final SshService sshService;

    public ProcessMonitorService(HostMapper hostMapper, SshService sshService) {
        this.hostMapper = hostMapper;
        this.sshService = sshService;
    }

    public CheckResult check(MonitorService service, double timeoutSeconds) {
        if (!StringUtils.hasText(service.checkCommand) && !StringUtils.hasText(service.processMatchKeyword)) {
            return new CheckResult("UNKNOWN", null, "Process check command is required");
        }
        HostConfig host = resolveHost(service);
        if (host == null) {
            return new CheckResult("UNKNOWN", null, "No enabled SSH host found for process check");
        }

        long start = System.nanoTime();
        if (StringUtils.hasText(service.checkCommand)) {
            return checkByCommand(service, host, start, timeoutSeconds);
        }

        String command = countCommand(service.processMatchKeyword, service.processMatchMode);
        String output = sshService.exec(host, command, timeoutMillis(timeoutSeconds));
        Integer count = parseCount(output);
        if (count == null) {
            return new CheckResult("UNKNOWN", elapsedMillis(start), output);
        }

        int minimum = service.processMinInstances == null ? 1 : Math.max(1, service.processMinInstances);
        String processName = StringUtils.hasText(service.processName) ? service.processName : service.processMatchKeyword;
        if (count >= minimum) {
            return new CheckResult("UP", elapsedMillis(start), processName + " running instances: " + count);
        }
        return new CheckResult("DOWN", elapsedMillis(start), processName + " running instances: " + count + ", expected at least " + minimum);
    }

    private CheckResult checkByCommand(MonitorService service, HostConfig host, long start, double timeoutSeconds) {
        SshService.ExecResult result = sshService.execResult(host, service.checkCommand, timeoutMillis(timeoutSeconds));
        String output = result.combinedOutput();
        String processName = StringUtils.hasText(service.processName) ? service.processName : "process";
        if (result.error || result.exitStatus == null || result.exitStatus < 0) {
            return new CheckResult("UNKNOWN", elapsedMillis(start), output);
        }
        if (result.exitStatus != 0) {
            return new CheckResult("DOWN", elapsedMillis(start), processName + " command exit " + result.exitStatus + ": " + output);
        }
        if (StringUtils.hasText(service.expectedResult) && !String.valueOf(output).contains(service.expectedResult)) {
            return new CheckResult("DOWN", elapsedMillis(start), processName + " output did not contain expected result: " + service.expectedResult);
        }
        return new CheckResult("UP", elapsedMillis(start), StringUtils.hasText(output) ? output : processName + " command succeeded");
    }

    private HostConfig resolveHost(MonitorService service) {
        if (service.hostId != null) {
            HostConfig host = hostMapper.findHost(service.hostId);
            if (host != null && Boolean.TRUE.equals(host.enabled)) {
                return host;
            }
        }
        if (StringUtils.hasText(service.host)) {
            return hostMapper.findEnabledByAddress(service.host.trim());
        }
        return null;
    }

    private String countCommand(String keyword, String matchMode) {
        String pgrepMode = "exact".equals(matchMode) ? "-x" : "-f";
        return "(pgrep " + pgrepMode + " " + shellSingleQuote(keyword) + " 2>/dev/null || true) | wc -l";
    }

    private Integer parseCount(String output) {
        if (!StringUtils.hasText(output) || output.contains("Exception:")) {
            return null;
        }
        String trimmed = output.trim();
        try {
            return Integer.valueOf(trimmed.split("\\s+")[0]);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int timeoutMillis(double timeoutSeconds) {
        return (int) Math.max(1000, Math.round(timeoutSeconds * 1000));
    }

    private Integer elapsedMillis(long start) {
        return (int) Math.max(0, (System.nanoTime() - start) / 1_000_000L);
    }

    private String shellSingleQuote(String value) {
        return "'" + String.valueOf(value).replace("'", "'\"'\"'") + "'";
    }
}
