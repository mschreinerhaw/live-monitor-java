package com.live.monitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.entity.HostConfig;
import java.util.*;

/** Read-only mount-table checks. A directory on a parent filesystem is not a mount. */
public final class MountPointMonitor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private MountPointMonitor() { }

    public static List<String> paths(String value) {
        try { List<String> paths = value == null ? null : JSON.readValue(value, new TypeReference<List<String>>() { }); return paths == null ? Collections.emptyList() : paths; }
        catch (Exception e) { return Collections.emptyList(); }
    }

    public static String normalize(String path) {
        String value = path == null ? "" : path.trim().replaceAll("/{2,}", "/").replaceAll("/\\.(?=/|$)", "");
        if (value.isEmpty() && path != null && path.trim().startsWith("/")) value = "/";
        while (value.length() > 1 && value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    static String decode(String value) {
        return value.replace("\\040", " ").replace("\\011", "\t").replace("\\012", "\n").replace("\\134", "\\");
    }

    public static List<Map<String, Object>> collect(HostConfig host, List<Map<String, Object>> disks, String output) {
        Map<String, String[]> mounts = new LinkedHashMap<>();
        if (output != null && !output.contains("Exception:")) for (String line : output.split("\\r?\\n")) {
            String[] parts = line.trim().split("\\s+");
            int separator = Arrays.asList(parts).indexOf("-");
            if (separator >= 6 && parts.length > separator + 3 && parts[0].matches("\\d+")) mounts.put(decode(parts[4]), new String[] { decode(parts[separator + 2]), parts[separator + 1], parts[5] + "," + parts[separator + 3] });
        }
        boolean valid = mounts.containsKey("/");
        List<Map<String, Object>> result = new ArrayList<>();
        for (String path : paths(host.mountPointsJson)) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (Map<String, Object> disk : disks) if (path.equals(disk.get("mount"))) row.putAll(disk);
            row.put("mount", path); row.put("mount_point", path); row.put("monitored_mount", true);
            String[] entry = mounts.get(path);
            String state, reason;
            if (!valid) { state = "UNKNOWN"; reason = "挂载表采集失败，无法判断挂载状态"; }
            else if (entry == null) { state = "MISSING"; reason = "挂载丢失：路径不是独立挂载点"; }
            else {
                row.put("filesystem", entry[0]); row.put("fs_type", entry[1]);
                boolean readOnly = Arrays.asList(entry[2].split(",")).contains("ro");
                row.put("read_only", readOnly);
                Double used = row.get("used_percent") instanceof Number ? ((Number) row.get("used_percent")).doubleValue() : null;
                double threshold = host.diskThresholdPercent == null ? 85D : host.diskThresholdPercent;
                if (readOnly) { state = "READ_ONLY"; reason = "挂载点处于只读状态"; }
                else if (used == null) { state = "UNKNOWN"; reason = "已挂载，但容量采集失败"; }
                else if (used > threshold) { state = "HIGH_USAGE"; reason = String.format(java.util.Locale.ROOT, "空间使用率 %.1f%% 超过阈值 %.1f%%", used, threshold); }
                else { state = "OK"; reason = "挂载正常，可读写，空间使用率正常"; }
            }
            row.put("mounted", valid ? entry != null : null);
            if (entry == null || !valid) { row.remove("used_percent"); row.remove("total_bytes"); row.remove("used_bytes"); row.remove("available_bytes"); }
            row.put("mount_state", state); row.put("mount_reason", reason); result.add(row);
        }
        return result;
    }

    public static List<Map<String, Object>> monitored(List<Map<String, Object>> disks) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (disks != null) for (Map<String, Object> row : disks) if (Boolean.TRUE.equals(row.get("monitored_mount"))) result.add(row);
        return result;
    }

    public static boolean abnormal(Map<String, Object> row) {
        return Arrays.asList("MISSING", "READ_ONLY", "HIGH_USAGE").contains(row.get("mount_state"));
    }

    public static String summary(List<Map<String, Object>> rows) {
        boolean unknown = rows.isEmpty(), abnormal = false;
        List<String> reasons = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            unknown |= "UNKNOWN".equals(row.get("mount_state")); abnormal |= abnormal(row);
            reasons.add(row.get("mount") + "：" + row.get("mount_reason"));
        }
        return "MountStatus=" + (abnormal ? "ABNORMAL" : unknown ? "UNKNOWN" : "OK") + "; 挂载点 " + String.join("；", reasons);
    }

    public static boolean confirmed(List<Map<String, Object>> current, List<Map<String, Object>> history, int required) {
        if (required <= 1) return current.stream().anyMatch(MountPointMonitor::abnormal);
        if (history == null || history.size() < required) return false;
        for (Map<String, Object> problem : current) {
            if (!abnormal(problem)) continue;
            boolean sustained = true;
            for (int i = 0; i < required; i++) {
                Object raw = history.get(i).get("disk_metrics_json");
                List<Map<String, Object>> rows;
                try { rows = JSON.readValue(String.valueOf(raw), new TypeReference<List<Map<String, Object>>>() { }); }
                catch (Exception e) { sustained = false; break; }
                boolean same = rows != null && rows.stream().anyMatch(row -> Objects.equals(problem.get("mount"), row.get("mount")) && Objects.equals(problem.get("mount_state"), row.get("mount_state")));
                if (!same) { sustained = false; break; }
            }
            if (sustained) return true;
        }
        return false;
    }
}
