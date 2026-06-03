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
    static final String HOST_RESOURCE_THRESHOLD_ALERT = "host_resource_threshold";
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

        boolean cpuAlertEnabled = alertEnabled(host.cpuAlertEnabled);
        boolean memoryAlertEnabled = alertEnabled(host.memoryAlertEnabled);
        boolean diskAlertEnabled = alertEnabled(host.diskAlertEnabled);
        double cpuThreshold = host.cpuThresholdPercent == null ? 85D : host.cpuThresholdPercent;
        double memoryThreshold = host.memoryThresholdPercent == null ? 85D : host.memoryThresholdPercent;
        double diskThreshold = host.diskThresholdPercent == null ? 85D : host.diskThresholdPercent;
        String message = String.format(
            "CPU %s / %s, Memory %s / %s, Disk %s / %s",
            percentText(cpu),
            thresholdText(cpuAlertEnabled, cpuThreshold),
            percentText(memory),
            thresholdText(memoryAlertEnabled, memoryThreshold),
            percentText(disk),
            thresholdText(diskAlertEnabled, diskThreshold)
        );
        List<String> missingMetrics = new ArrayList<String>();
        if (cpuAlertEnabled && cpu == null) {
            missingMetrics.add("CPU");
        }
        if (memoryAlertEnabled && memory == null) {
            missingMetrics.add("memory");
        }
        if (diskAlertEnabled && disk == null) {
            missingMetrics.add("disk");
        }
        if (!missingMetrics.isEmpty()) {
            return new CheckResult("UNKNOWN", elapsedMillis(start), "Unable to parse host " + String.join("/", missingMetrics) + " usage. " + message);
        }
        if (sustainedThresholdExceeded(host, metrics, new ResourceThreshold("cpu_usage_percent", cpuAlertEnabled, cpuThreshold),
            new ResourceThreshold("memory_used_percent", memoryAlertEnabled, memoryThreshold),
            new ResourceThreshold("disk_used_percent", diskAlertEnabled, diskThreshold))) {
            return new CheckResult("UP", elapsedMillis(start), message, HOST_RESOURCE_THRESHOLD_ALERT);
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
        String physicalDiskOutput = sshService.exec(host, physicalDiskCommand(), timeoutMillis);
        List<Map<String, Object>> physicalDisks = parsePhysicalDiskMetrics(physicalDiskOutput);
        attachPhysicalDiskDetails(mountDisks, physicalDisks);
        Integer diskDeviceCount = physicalDisks.isEmpty() ? parseLsblkDiskCount(sshService.exec(host, diskDeviceCommand(), timeoutMillis)) : physicalDisks.size();
        if (diskDeviceCount == null) {
            diskDeviceCount = parseProcPartitionsDiskCount(sshService.exec(host, procPartitionsDiskCommand(), timeoutMillis));
        }
        if (diskDeviceCount == null) {
            diskDeviceCount = parseFdiskDiskCount(sshService.exec(host, fdiskDiskCommand(), timeoutMillis));
        }
        Double disk = maxDiskUsage(mountDisks);
        Integer mountPointCount = mountDisks.size();
        String diskMetricsJson = toJson(mountDisks);
        String physicalDiskMetricsJson = toJson(physicalDisks);
        historyRepository.saveHostMetric(host.id, cpu, load, memory, disk, diskMetricsJson);
        hostMapper.upsertLatestMetric(host.id, cpu, load, memory, disk, cpuCoreCount, memoryTotalMb, diskDeviceCount, diskMetricsJson, physicalDiskMetricsJson);

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
        metrics.put("physical_disk_metrics", physicalDisks.isEmpty() ? null : physicalDisks);
        metrics.put("mounts", mountDisks.isEmpty() ? null : mountDisks);
        return metrics;
    }

    private Double metricValue(Map<String, Object> metrics, String key) {
        Object value = metrics.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    private boolean alertEnabled(Boolean value) {
        return value == null || value;
    }

    private String percentText(Double value) {
        return value == null ? "-" : String.format("%.1f%%", value);
    }

    private String thresholdText(boolean enabled, double threshold) {
        return enabled ? String.format("%.1f%%", threshold) : "disabled";
    }

    private boolean sustainedThresholdExceeded(HostConfig host, Map<String, Object> currentMetrics, ResourceThreshold... thresholds) {
        int requiredSamples = requiredSamples(host.resourceAlertDurationSeconds, host.checkInterval);
        List<Map<String, Object>> rows = recentHostMetrics(host.id, requiredSamples);
        if (rows.isEmpty()) {
            rows.add(currentMetrics);
        }
        if (rows.size() < requiredSamples) {
            return false;
        }
        for (ResourceThreshold threshold : thresholds) {
            if (!threshold.enabled) {
                continue;
            }
            boolean exceeded = true;
            for (Map<String, Object> row : rows) {
                Double value = metricValue(row, threshold.metricKey);
                if (value == null || value <= threshold.thresholdPercent) {
                    exceeded = false;
                    break;
                }
            }
            if (exceeded) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> recentHostMetrics(Long hostId, int requiredSamples) {
        if (historyRepository == null || hostId == null) {
            return new ArrayList<Map<String, Object>>();
        }
        List<Map<String, Object>> rows = historyRepository.listHostMetrics(hostId, 1, requiredSamples);
        if (rows == null) {
            return new ArrayList<Map<String, Object>>();
        }
        return new ArrayList<Map<String, Object>>(rows);
    }

    private int requiredSamples(Integer durationSeconds, Integer checkIntervalSeconds) {
        int duration = durationSeconds == null || durationSeconds < 1 ? 180 : durationSeconds;
        int interval = checkIntervalSeconds == null || checkIntervalSeconds < 1 ? 60 : checkIntervalSeconds;
        return Math.max(1, (int) Math.ceil((double) duration / interval));
    }

    private static class ResourceThreshold {
        private final String metricKey;
        private final boolean enabled;
        private final double thresholdPercent;

        private ResourceThreshold(String metricKey, boolean enabled, double thresholdPercent) {
            this.metricKey = metricKey;
            this.enabled = enabled;
            this.thresholdPercent = thresholdPercent;
        }
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

    private String physicalDiskCommand() {
        return "lsblk -b -P -o NAME,TYPE,SIZE,PKNAME,MOUNTPOINT 2>/dev/null";
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

    List<Map<String, Object>> parsePhysicalDiskMetrics(String output) {
        if (!StringUtils.hasText(output) || output.contains("Exception:")) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> disks = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> disksByName = new LinkedHashMap<String, Map<String, Object>>();
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        String[] lines = output.trim().split("\\r?\\n");
        for (String line : lines) {
            Map<String, String> row = parseLsblkPairs(line);
            if (!row.isEmpty()) {
                rows.add(row);
            }
            String name = cleanDiskName(row.get("NAME"));
            String type = row.get("TYPE");
            if (!StringUtils.hasText(name) || !"disk".equals(type) || !isWholeDiskName(name)) {
                continue;
            }
            Long totalBytes = parseLong(row.get("SIZE"));
            Map<String, Object> disk = new LinkedHashMap<String, Object>();
            disk.put("name", name);
            disk.put("device", "/dev/" + name);
            disk.put("total_bytes", totalBytes);
            disk.put("mount_points", new ArrayList<String>());
            disk.put("mounted", false);
            disks.add(disk);
            disksByName.put(name, disk);
        }
        for (Map<String, String> row : rows) {
            String mountPoint = row.get("MOUNTPOINT");
            if (!StringUtils.hasText(mountPoint) || isIgnoredMountPoint(mountPoint)) {
                continue;
            }
            String parentName = cleanDiskName(row.get("PKNAME"));
            if (!StringUtils.hasText(parentName) && "disk".equals(row.get("TYPE"))) {
                parentName = cleanDiskName(row.get("NAME"));
            }
            Map<String, Object> disk = disksByName.get(parentName);
            if (disk == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<String> mountPoints = (List<String>) disk.get("mount_points");
            if (!mountPoints.contains(mountPoint)) {
                mountPoints.add(mountPoint);
            }
            disk.put("mounted", true);
        }
        return disks;
    }

    private void attachPhysicalDiskDetails(List<Map<String, Object>> mountDisks, List<Map<String, Object>> physicalDisks) {
        if (mountDisks == null || mountDisks.isEmpty() || physicalDisks == null || physicalDisks.isEmpty()) {
            return;
        }
        for (Map<String, Object> mountDisk : mountDisks) {
            String mount = String.valueOf(mountDisk.get("mount"));
            for (Map<String, Object> physicalDisk : physicalDisks) {
                @SuppressWarnings("unchecked")
                List<String> mountPoints = (List<String>) physicalDisk.get("mount_points");
                if (mountPoints == null || !mountPoints.contains(mount)) {
                    continue;
                }
                mountDisk.put("physical_disk_name", physicalDisk.get("name"));
                mountDisk.put("physical_disk_device", physicalDisk.get("device"));
                mountDisk.put("physical_disk_total_bytes", physicalDisk.get("total_bytes"));
                break;
            }
        }
    }

    private Map<String, String> parseLsblkPairs(String line) {
        Map<String, String> pairs = new LinkedHashMap<String, String>();
        if (!StringUtils.hasText(line)) {
            return pairs;
        }
        int index = 0;
        while (index < line.length()) {
            while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
                index++;
            }
            int equals = line.indexOf('=', index);
            if (equals <= index) {
                break;
            }
            String key = line.substring(index, equals);
            index = equals + 1;
            String value = "";
            if (index < line.length() && line.charAt(index) == '"') {
                int end = line.indexOf('"', index + 1);
                if (end < 0) {
                    end = line.length();
                }
                value = line.substring(index + 1, end);
                index = end + 1;
            } else {
                int end = index;
                while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
                    end++;
                }
                value = line.substring(index, end);
                index = end;
            }
            pairs.put(key, value);
        }
        return pairs;
    }

    private String cleanDiskName(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String cleaned = name.trim();
        if (cleaned.startsWith("/dev/")) {
            cleaned = cleaned.substring("/dev/".length());
        }
        return cleaned;
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
