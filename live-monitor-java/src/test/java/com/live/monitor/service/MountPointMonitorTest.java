package com.live.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.entity.HostConfig;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MountPointMonitorTest {
    private final String root = "24 1 8:1 / / rw,relatime - xfs /dev/sda1 rw\n";
    private HostConfig host(String... paths) throws Exception {
        HostConfig host = new HostConfig(); host.mountMonitorEnabled = true; host.diskThresholdPercent = 85D;
        host.mountPointsJson = new ObjectMapper().writeValueAsString(paths); return host;
    }
    private Map<String, Object> disk(String path, int percent) {
        Map<String, Object> row = new LinkedHashMap<>(); row.put("mount", path); row.put("used_percent", percent);
        row.put("total_bytes", 1000L); row.put("available_bytes", 100L); return row;
    }
    @Test void directoryFallbackToParentIsMissingAndNeverReportsParentCapacity() throws Exception {
        Map<String, Object> row = MountPointMonitor.collect(host("/data"), Arrays.asList(disk("/", 20)), root).get(0);
        assertEquals("MISSING", row.get("mount_state")); assertEquals(false, row.get("mounted")); assertNull(row.get("used_percent"));
    }
    @Test void readOnlyAndHighUsageAreDetectedWithExplicitReasons() throws Exception {
        String mounts = root + "25 24 8:2 / /data ro,relatime - ext4 /dev/sdb1 rw\n26 24 8:3 / /backup rw - xfs /dev/sdc1 rw\n";
        List<Map<String, Object>> rows = MountPointMonitor.collect(host("/data", "/backup"), Arrays.asList(disk("/data", 10), disk("/backup", 90)), mounts);
        assertEquals("READ_ONLY", rows.get(0).get("mount_state")); assertEquals(true, rows.get(0).get("read_only"));
        assertEquals("HIGH_USAGE", rows.get(1).get("mount_state")); assertTrue(MountPointMonitor.summary(rows).contains("90.0%"));
    }
    @Test void failedOrIncompleteMountTableIsUnknownRatherThanUnmounted() throws Exception {
        for (String output : Arrays.asList("", "Exception: SSH timed out", "25 24 8:2 / /data rw - xfs /dev/sdb1 rw")) {
            Map<String, Object> row = MountPointMonitor.collect(host("/data"), Arrays.asList(disk("/data", 70)), output).get(0);
            assertEquals("UNKNOWN", row.get("mount_state")); assertNull(row.get("mounted")); assertNull(row.get("used_percent"));
        }
    }
    @Test void missingCapacityIsUnknownButReadOnlyFaultIsStillKnown() throws Exception {
        List<Map<String, Object>> rows = MountPointMonitor.collect(host("/", "/data"), Collections.emptyList(), root + "25 24 8:2 / /data ro - xfs /dev/sdb1 rw");
        assertEquals("UNKNOWN", rows.get(0).get("mount_state")); assertEquals("READ_ONLY", rows.get(1).get("mount_state"));
    }
    @Test void escapedPathsAndNetworkMountsUseExactMountTablePaths() throws Exception {
        String mounts = root + "25 24 0:42 / /business\\040data rw - nfs4 server:/export rw\n";
        Map<String, Object> row = MountPointMonitor.collect(host("/business data"), Arrays.asList(disk("/business data", 85)), mounts).get(0);
        assertEquals("OK", row.get("mount_state")); assertEquals("nfs4", row.get("fs_type"));
        assertEquals("/data", MountPointMonitor.normalize(" /data//./ "));
    }
    @Test void confirmationRequiresTheSameMountAndFaultAcrossSamples() throws Exception {
        List<Map<String, Object>> missing = MountPointMonitor.collect(host("/data"), Collections.emptyList(), root);
        ObjectMapper json = new ObjectMapper();
        Map<String, Object> sample = Collections.singletonMap("disk_metrics_json", json.writeValueAsString(missing));
        assertTrue(MountPointMonitor.confirmed(missing, Arrays.asList(sample, sample, sample), 3));
        assertFalse(MountPointMonitor.confirmed(missing, Arrays.asList(sample, sample), 3));
        Map<String, Object> unknown = Collections.singletonMap("disk_metrics_json", json.writeValueAsString(MountPointMonitor.collect(host("/data"), Collections.emptyList(), "")));
        assertFalse(MountPointMonitor.confirmed(missing, Arrays.asList(sample, unknown, sample), 3));
        Map<String, Object> other = Collections.singletonMap("disk_metrics_json", json.writeValueAsString(MountPointMonitor.collect(host("/backup"), Collections.emptyList(), root)));
        assertFalse(MountPointMonitor.confirmed(missing, Arrays.asList(sample, other, sample), 3));
    }
}
