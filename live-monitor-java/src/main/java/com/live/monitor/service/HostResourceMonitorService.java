package com.live.monitor.service;

import com.live.monitor.dto.CheckResult;
import com.live.monitor.entity.HostConfig;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.HostMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class HostResourceMonitorService {
    private final HostMapper hostMapper;
    private final SshService sshService;

    public HostResourceMonitorService(HostMapper hostMapper, SshService sshService) {
        this.hostMapper = hostMapper;
        this.sshService = sshService;
    }

    public CheckResult check(MonitorService service, double timeoutSeconds) {
        HostConfig host = service.hostId == null ? null : hostMapper.findHost(service.hostId);
        if (host == null || !Boolean.TRUE.equals(host.enabled)) {
            return new CheckResult("UNKNOWN", null, "Host is not found or disabled");
        }

        long start = System.nanoTime();
        int timeoutMillis = (int) Math.max(1000, Math.round(timeoutSeconds * 1000));
        Map<String, Object> metrics = collectAndStore(host, timeoutMillis);
        Double cpu = metricValue(metrics, "cpu_usage_percent");
        Double memory = metricValue(metrics, "memory_used_percent");
        Double disk = metricValue(metrics, "disk_used_percent");
        if (cpu == null || disk == null) {
            return new CheckResult("UNKNOWN", elapsedMillis(start), "Unable to parse host CPU or disk usage");
        }

        double cpuThreshold = service.cpuThresholdPercent == null ? 85D : service.cpuThresholdPercent;
        double diskThreshold = service.diskThresholdPercent == null ? 85D : service.diskThresholdPercent;
        String message = String.format(
            "CPU %.1f%% / %.1f%%, Memory %s, Disk %.1f%% / %.1f%%",
            cpu,
            cpuThreshold,
            memory == null ? "-" : String.format("%.1f%%", memory),
            disk,
            diskThreshold
        );
        if (cpu >= cpuThreshold || disk >= diskThreshold) {
            return new CheckResult("DOWN", elapsedMillis(start), message);
        }
        return new CheckResult("UP", elapsedMillis(start), message);
    }

    public Map<String, Object> collectAndStore(HostConfig host, int timeoutMillis) {
        Double cpu = parseNumber(sshService.exec(host, cpuCommand(), timeoutMillis));
        Double load = parseNumber(sshService.exec(host, "uptime | awk -F'load average:' '{gsub(/^[ \\t]+/,\"\",$2); split($2,a,\",\"); print a[1]}'", timeoutMillis));
        Double memory = parseNumber(sshService.exec(host, "free -m | awk '/Mem:/ {printf \"%.1f\", ($3/$2)*100}'", timeoutMillis));
        Double disk = parseNumber(sshService.exec(host, "df -P / | awk 'NR==2 {gsub(\"%\",\"\",$5); print $5}'", timeoutMillis));
        hostMapper.insertMetric(host.id, cpu, load, memory, disk);

        Map<String, Object> metrics = new LinkedHashMap<String, Object>();
        metrics.put("cpu_usage_percent", cpu);
        metrics.put("load_average", load);
        metrics.put("memory_used_percent", memory);
        metrics.put("disk_used_percent", disk);
        return metrics;
    }

    private Double metricValue(Map<String, Object> metrics, String key) {
        Object value = metrics.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    private String cpuCommand() {
        return "LANG=C top -bn1 | awk '/Cpu\\(s\\)|%Cpu/ {for(i=1;i<=NF;i++){if($i ~ /id/){v=$(i-1); gsub(/[^0-9.]/,\"\",v); if(v!=\"\"){printf \"%.1f\", 100-v; exit}}}}'";
    }

    private Double parseNumber(String output) {
        if (!StringUtils.hasText(output) || output.contains("Exception:")) {
            return null;
        }
        String first = output.trim().split("\\s+")[0].replace("%", "");
        try {
            return Double.valueOf(first);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer elapsedMillis(long start) {
        return (int) Math.max(0, (System.nanoTime() - start) / 1_000_000L);
    }
}
