package com.live.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.dto.CheckResult;
import com.live.monitor.entity.HostConfig;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.HostMapper;
import com.live.monitor.store.RocksDbHistoryRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class HostResourceMonitorService {
    private static final List<String> VALID_DISK_FS_TYPES = Arrays.asList("xfs", "ext4", "ext3", "ext2", "btrfs");
    private static final List<String> IGNORED_FILESYSTEM_PREFIXES = Arrays.asList("/dev/loop", "/dev/sr");
    private static final List<String> IGNORED_MOUNT_PREFIXES = Arrays.asList(
        "/run",
        "/var/lib/docker/overlay2",
        "/var/lib/docker/containers",
        "/var/lib/kubelet/pods",
        "/snap",
        "/media"
    );

    private final HostMapper hostMapper;
    private final RocksDbHistoryRepository historyRepository;
    private final SshService sshService;
    private final ObjectMapper objectMapper;

    public HostResourceMonitorService(
        HostMapper hostMapper,
        RocksDbHistoryRepository historyRepository,
        SshService sshService,
        ObjectMapper objectMapper
    ) {
        this.hostMapper = hostMapper;
        this.historyRepository = historyRepository;
        this.sshService = sshService;
        this.objectMapper = objectMapper;
    }

    public CheckResult check(MonitorService service, double timeoutSeconds) {
        HostConfig host = service.hostId == null ? null : hostMapper.findHost(service.hostId);
        if (host == null || !Boolean.TRUE.equals(host.enabled)) {
            return new CheckResult("UNKNOWN", null, "Host is not found or disabled");
        }

        long start = System.nanoTime();
        int timeoutMillis = (int) Math.max(3000, Math.round(timeoutSeconds * 1000));
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
        Double cpu = parseCpuUsage(sshService.exec(host, cpuCommand(), timeoutMillis));
        Double load = parseNumber(sshService.exec(host, "awk '{print $1}' /proc/loadavg", timeoutMillis));
        Double memory = parseNumber(sshService.exec(host, memoryCommand(), timeoutMillis));
        Integer cpuCoreCount = parseInteger(sshService.exec(host, cpuCoreCommand(), timeoutMillis));
        Double memoryTotalMb = parseNumber(sshService.exec(host, memoryTotalCommand(), timeoutMillis));
        List<Map<String, Object>> mountDisks = parseDiskMetrics(sshService.exec(host, diskCommand(), timeoutMillis));
        Integer diskDeviceCount = parseLsblkDiskCount(sshService.exec(host, diskDeviceCommand(), timeoutMillis));
        if (diskDeviceCount == null) {
            diskDeviceCount = parseProcPartitionsDiskCount(sshService.exec(host, procPartitionsDiskCommand(), timeoutMillis));
        }
        if (diskDeviceCount == null) {
            diskDeviceCount = parseFdiskDiskCount(sshService.exec(host, fdiskDiskCommand(), timeoutMillis));
        }
        Double disk = maxDiskUsage(mountDisks);
        Integer mountPointCount = mountDisks.size();
        String diskMetricsJson = toJson(mountDisks);
        historyRepository.saveHostMetric(host.id, cpu, load, memory, disk, diskMetricsJson);
        hostMapper.upsertLatestMetric(host.id, cpu, load, memory, disk, cpuCoreCount, memoryTotalMb, diskDeviceCount, diskMetricsJson);

        Map<String, Object> metrics = new LinkedHashMap<String, Object>();
        metrics.put("cpu_usage_percent", cpu);
        metrics.put("load_average", load);
        metrics.put("memory_used_percent", memory);
        metrics.put("disk_used_percent", disk);
        metrics.put("cpu_core_count", cpuCoreCount);
        metrics.put("memory_total_mb", memoryTotalMb);
        metrics.put("disk_device_count", diskDeviceCount);
        metrics.put("disk_mount_count", diskDeviceCount);
        metrics.put("mount_point_count", mountPointCount);
        metrics.put("max_disk_usage_percent", disk);
        metrics.put("disk_metrics", mountDisks.isEmpty() ? null : mountDisks);
        metrics.put("mounts", mountDisks.isEmpty() ? null : mountDisks);
        return metrics;
    }

    private Double metricValue(Map<String, Object> metrics, String key) {
        Object value = metrics.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    private String cpuCommand() {
        return "awk 'NR==1 {idle=$5+$6; total=0; for(i=2;i<=NF;i++) total+=$i; print idle,total}' /proc/stat; " +
            "sleep 1; awk 'NR==1 {idle=$5+$6; total=0; for(i=2;i<=NF;i++) total+=$i; print idle,total}' /proc/stat";
    }

    private String memoryCommand() {
        return "awk '/^MemTotal:/ {total=$2} /^MemAvailable:/ {avail=$2} /^MemFree:/ {free=$2} " +
            "/^Buffers:/ {buffers=$2} /^Cached:/ {cached=$2} " +
            "END {if (!avail) avail=free+buffers+cached; if (total>0) printf \"%.1f\", (total-avail)*100/total}' /proc/meminfo";
    }

    private String cpuCoreCommand() {
        return "(command -v nproc >/dev/null 2>&1 && nproc) || grep -c '^processor' /proc/cpuinfo";
    }

    private String memoryTotalCommand() {
        return "awk '/^MemTotal:/ {printf \"%.0f\", $2/1024}' /proc/meminfo";
    }

    private String diskCommand() {
        return "df -P -T -B1 2>/dev/null";
    }

    private String diskDeviceCommand() {
        return "lsblk -d -n -o NAME,TYPE 2>/dev/null";
    }

    private String procPartitionsDiskCommand() {
        return "cat /proc/partitions 2>/dev/null";
    }

    private String fdiskDiskCommand() {
        return "fdisk -l 2>/dev/null";
    }

    Double parseCpuUsage(String output) {
        if (!StringUtils.hasText(output) || output.contains("Exception:")) {
            return null;
        }
        String[] lines = output.trim().split("\\r?\\n");
        if (lines.length < 2) {
            return parseNumber(output);
        }
        double[] first = parseCpuSnapshot(lines[0]);
        double[] second = parseCpuSnapshot(lines[lines.length - 1]);
        if (first == null || second == null) {
            return null;
        }
        double idle = second[0] - first[0];
        double total = second[1] - first[1];
        if (total <= 0D) {
            return null;
        }
        return round1(Math.max(0D, Math.min(100D, (1D - idle / total) * 100D)));
    }

    private double[] parseCpuSnapshot(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new double[] {Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    List<Map<String, Object>> parseDiskMetrics(String output) {
        if (!StringUtils.hasText(output) || output.contains("Exception:")) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> disks = new ArrayList<Map<String, Object>>();
        String[] lines = output.trim().split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!StringUtils.hasText(trimmed) || trimmed.startsWith("Filesystem ")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 7) {
                continue;
            }
            String filesystem = parts[0];
            String fsType = parts[1];
            Long totalBytes = parseLong(parts[2]);
            Long usedBytes = parseLong(parts[3]);
            Long availableBytes = parseLong(parts[4]);
            Double usedPercent = parseNumber(parts[5]);
            String mountPoint = parts[6];
            if (totalBytes == null || usedBytes == null || availableBytes == null || usedPercent == null) {
                continue;
            }
            if (!isValidDiskFs(fsType) || isIgnoredFilesystem(filesystem) || isIgnoredMountPoint(mountPoint)) {
                continue;
            }
            Map<String, Object> disk = new LinkedHashMap<String, Object>();
            disk.put("mount_point", mountPoint);
            disk.put("mount", mountPoint);
            disk.put("filesystem", filesystem);
            disk.put("fs_type", fsType);
            disk.put("total_bytes", totalBytes);
            disk.put("used_bytes", usedBytes);
            disk.put("available_bytes", availableBytes);
            disk.put("used_percent", usedPercent.intValue());
            disks.add(disk);
        }
        return disks;
    }

    private boolean isValidDiskFs(String type) {
        return type != null && VALID_DISK_FS_TYPES.contains(type.toLowerCase());
    }

    private boolean isIgnoredFilesystem(String filesystem) {
        if (filesystem == null) {
            return false;
        }
        for (String prefix : IGNORED_FILESYSTEM_PREFIXES) {
            if (filesystem.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isIgnoredMountPoint(String mountPoint) {
        if (mountPoint == null) {
            return false;
        }
        for (String prefix : IGNORED_MOUNT_PREFIXES) {
            if (mountPoint.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    Integer parseLsblkDiskCount(String output) {
        if (!StringUtils.hasText(output) || output.contains("Exception:")) {
            return null;
        }
        int count = 0;
        boolean hasRows = false;
        String[] lines = output.trim().split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            hasRows = true;
            if ("disk".equals(parts[parts.length - 1])) {
                count++;
            }
        }
        return hasRows ? count : null;
    }

    Integer parseProcPartitionsDiskCount(String output) {
        if (!StringUtils.hasText(output) || output.contains("Exception:")) {
            return null;
        }
        int count = 0;
        boolean hasRows = false;
        String[] lines = output.trim().split("\\r?\\n");
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 4 || !isWholeDiskName(parts[3])) {
                continue;
            }
            hasRows = true;
            count++;
        }
        return hasRows ? count : null;
    }

    Integer parseFdiskDiskCount(String output) {
        if (!StringUtils.hasText(output) || output.contains("Exception:")) {
            return null;
        }
        int count = 0;
        String[] lines = output.trim().split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("Disk /dev/")) {
                continue;
            }
            int nameStart = "Disk /dev/".length();
            int nameEnd = trimmed.indexOf(':', nameStart);
            if (nameEnd <= nameStart) {
                continue;
            }
            String name = trimmed.substring(nameStart, nameEnd);
            if (isWholeDiskName(name)) {
                count++;
            }
        }
        return count > 0 ? count : null;
    }

    private boolean isWholeDiskName(String name) {
        return name.matches("^(sd[a-z]+|hd[a-z]+|vd[a-z]+|xvd[a-z]+|nvme\\d+n\\d+|mmcblk\\d+|pmem\\d+|dasd[a-z]+)$");
    }

    private Double maxDiskUsage(List<Map<String, Object>> diskMetrics) {
        Double max = null;
        for (Map<String, Object> disk : diskMetrics) {
            Object value = disk.get("used_percent");
            if (value instanceof Number && (max == null || ((Number) value).doubleValue() > max)) {
                max = ((Number) value).doubleValue();
            }
        }
        return max;
    }

    private String toJson(List<Map<String, Object>> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private Double parseNumber(String output) {
        if (!StringUtils.hasText(output) || output.contains("Exception:")) {
            return null;
        }
        String first = output.trim().split("\\s+")[0].replace("%", "").replace(',', '.');
        try {
            return Double.valueOf(first);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer parseInteger(String output) {
        Double value = parseNumber(output);
        return value == null ? null : value.intValue();
    }

    private Double round1(double value) {
        return Math.round(value * 10D) / 10D;
    }

    private Integer elapsedMillis(long start) {
        return (int) Math.max(0, (System.nanoTime() - start) / 1_000_000L);
    }
}
