package com.live.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.dto.CheckResult;
import com.live.monitor.entity.HostConfig;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.HostMapper;
import com.live.monitor.store.RocksDbHistoryRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HostResourceMonitorServiceTest {
    private final HostResourceMonitorService service = new HostResourceMonitorService(null, null, null, null);

    @Test
    void parseDiskMetricsUsesDfTypedByteOutputAndKeepsRealLocalMounts() {
        String output = "Filesystem              Type 1B-blocks    Used Available Capacity Mounted on\n" +
            "/dev/mapper/ao-root     xfs  53660876800 12878610432 40782266368 24% /\n" +
            "/dev/sda1               xfs  1023303680  163728588  859575092 16% /boot\n" +
            "tmpfs                   tmpfs 104857600 0 104857600 0% /run/user/0\n" +
            "overlay                 overlay 53660876800 12878610432 40782266368 24% /var/lib/docker/overlay2/abc\n";

        List<Map<String, Object>> disks = service.parseDiskMetrics(output);

        assertEquals(2, disks.size());
        assertEquals("/", disks.get(0).get("mount_point"));
        assertEquals("/", disks.get(0).get("mount"));
        assertEquals("/dev/mapper/ao-root", disks.get(0).get("filesystem"));
        assertEquals("xfs", disks.get(0).get("fs_type"));
        assertEquals(53660876800L, disks.get(0).get("total_bytes"));
        assertEquals(12878610432L, disks.get(0).get("used_bytes"));
        assertEquals(40782266368L, disks.get(0).get("available_bytes"));
        assertEquals(24, disks.get(0).get("used_percent"));
        assertEquals("/boot", disks.get(1).get("mount_point"));
    }

    @Test
    void parseDiskMetricsFiltersUnsupportedTypesAndIgnoredMounts() {
        String output = "Filesystem Type 1B-blocks Used Available Capacity Mounted on\n" +
            "/dev/sda1 ext4 1000 200 800 20% /\n" +
            "/dev/sdb1 nfs4 1000 200 800 20% /data-nfs\n" +
            "/dev/sdc1 ext4 1000 200 800 20% /run/media/disk\n" +
            "/dev/sdd1 btrfs 1000 300 700 30% /var/lib/docker\n" +
            "/dev/sde1 xfs 1000 400 600 40% /snap/core\n" +
            "/dev/loop0 iso9660 1000 1000 0 100% /media/CentOS7\n" +
            "/dev/sr0 udf 1000 1000 0 100% /media/cdrom\n" +
            "/dev/loop1 ext4 1000 1000 0 100% /data/loop-ext4\n" +
            "/dev/sdf1 ext4 1000 1000 0 100% /media/usb\n" +
            "/dev/sdg1 ext4 1000 1000 0 100% /mnt/data\n" +
            "/dev/sdh1 ext4 1000 1000 0 100% /var/lib/docker/overlay2\n" +
            "/dev/sdi1 ext4 1000 1000 0 100% /var/lib/kubelet/pods/abc";

        List<Map<String, Object>> disks = service.parseDiskMetrics(output);

        assertEquals(3, disks.size());
        assertEquals("/", disks.get(0).get("mount_point"));
        assertEquals("/var/lib/docker", disks.get(1).get("mount_point"));
        assertEquals("/mnt/data", disks.get(2).get("mount_point"));
    }

    @Test
    void parseLsblkDiskCountCountsOnlyDiskType() {
        String output = "sda disk\n" +
            "sda1 part\n" +
            "dm-0 lvm\n" +
            "loop0 loop\n" +
            "sr0 rom\n" +
            "nvme0n1 disk\n";

        assertEquals(2, service.parseLsblkDiskCount(output));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsePhysicalDiskMetricsReadsCapacityAndMountPoints() {
        String output = "NAME=\"sda\" TYPE=\"disk\" SIZE=\"107374182400\" PKNAME=\"\" MOUNTPOINT=\"\"\n" +
            "NAME=\"sda1\" TYPE=\"part\" SIZE=\"536870912\" PKNAME=\"sda\" MOUNTPOINT=\"/boot\"\n" +
            "NAME=\"sda2\" TYPE=\"part\" SIZE=\"106837311488\" PKNAME=\"sda\" MOUNTPOINT=\"/\"\n" +
            "NAME=\"nvme0n1\" TYPE=\"disk\" SIZE=\"214748364800\" PKNAME=\"\" MOUNTPOINT=\"\"\n" +
            "NAME=\"nvme0n1p1\" TYPE=\"part\" SIZE=\"214748364800\" PKNAME=\"nvme0n1\" MOUNTPOINT=\"/data\"\n" +
            "NAME=\"loop0\" TYPE=\"loop\" SIZE=\"67108864\" PKNAME=\"\" MOUNTPOINT=\"/snap/core\"\n";

        List<Map<String, Object>> disks = service.parsePhysicalDiskMetrics(output);

        assertEquals(2, disks.size());
        assertEquals("sda", disks.get(0).get("name"));
        assertEquals("/dev/sda", disks.get(0).get("device"));
        assertEquals(107374182400L, disks.get(0).get("total_bytes"));
        assertEquals(true, disks.get(0).get("mounted"));
        assertEquals(Arrays.asList("/boot", "/"), (List<String>) disks.get(0).get("mount_points"));
        assertEquals("nvme0n1", disks.get(1).get("name"));
        assertEquals(214748364800L, disks.get(1).get("total_bytes"));
        assertEquals(Arrays.asList("/data"), (List<String>) disks.get(1).get("mount_points"));
    }

    @Test
    void parseLsblkDiskCountReturnsZeroWhenLsblkHasNoDisks() {
        String output = "loop0 loop\n" +
            "sr0 rom\n";

        assertEquals(0, service.parseLsblkDiskCount(output));
    }

    @Test
    void parseProcPartitionsDiskCountIgnoresPartitionsAndVirtualDevices() {
        String output = "major minor  #blocks  name\n\n" +
            "   8        0  104857600 sda\n" +
            "   8        1     524288 sda1\n" +
            " 259        0  209715200 nvme0n1\n" +
            " 259        1     524288 nvme0n1p1\n" +
            "   7        0      65536 loop0\n" +
            "  11        0    1048575 sr0\n";

        assertEquals(2, service.parseProcPartitionsDiskCount(output));
    }

    @Test
    void parseFdiskDiskCountIgnoresLoopMapperAndPartitions() {
        String output = "Disk /dev/sda: 100 GiB, 107374182400 bytes, 209715200 sectors\n" +
            "Disk /dev/sda1: 512 MiB, 536870912 bytes, 1048576 sectors\n" +
            "Disk /dev/nvme0n1: 200 GiB, 214748364800 bytes, 419430400 sectors\n" +
            "Disk /dev/loop0: 64 MiB, 67108864 bytes, 131072 sectors\n" +
            "Disk /dev/mapper/vg-root: 50 GiB, 53687091200 bytes, 104857600 sectors\n";

        assertEquals(2, service.parseFdiskDiskCount(output));
    }

    @Test
    void diskCountParsersReturnNullForMissingOrErrorOutput() {
        assertNull(service.parseLsblkDiskCount(""));
        assertNull(service.parseProcPartitionsDiskCount("Exception: command failed"));
        assertNull(service.parseFdiskDiskCount("Disklabel type: gpt"));
    }

    @Test
    void checkUsesConfiguredMemoryThreshold() {
        HostConfig host = hostWithThresholds();
        host.memoryThresholdPercent = 80D;
        HostResourceMonitorService monitor = monitorWithMetrics(host, "45.0", "90.0", "20%");
        MonitorService monitorService = hostMonitorService();

        CheckResult result = monitor.check(monitorService, 10D);

        assertEquals("UP", result.status);
        assertEquals(HostResourceMonitorService.HOST_RESOURCE_THRESHOLD_ALERT, result.alertType);
        assertEquals("CPU 45.0% / 85.0%, Memory 90.0% / 80.0%, Disk 20.0% / 85.0%", result.message);
    }

    @Test
    void checkIgnoresDisabledDiskThreshold() {
        HostConfig host = hostWithThresholds();
        host.diskThresholdPercent = 80D;
        host.diskAlertEnabled = false;
        HostResourceMonitorService monitor = monitorWithMetrics(host, "45.0", "40.0", "95%");
        MonitorService monitorService = hostMonitorService();

        CheckResult result = monitor.check(monitorService, 10D);

        assertEquals("UP", result.status);
        assertNull(result.alertType);
        assertEquals("CPU 45.0% / 85.0%, Memory 40.0% / 85.0%, Disk 95.0% / disabled", result.message);
    }

    @Test
    void checkRequiresConfiguredConsecutiveCpuSamplesBeforeAlerting() {
        HostConfig host = hostWithThresholds();
        host.checkInterval = 30;
        host.resourceAlertDurationSeconds = 180;
        HostResourceMonitorService monitor = monitorWithMetrics(host, "90.0", "40.0", "20%", metricRows(5, 90D, 40D, 20D));
        MonitorService monitorService = hostMonitorService();

        CheckResult result = monitor.check(monitorService, 10D);

        assertEquals("UP", result.status);
        assertNull(result.alertType);
    }

    @Test
    void checkAlertsAfterConfiguredConsecutiveCpuSamples() {
        HostConfig host = hostWithThresholds();
        host.checkInterval = 30;
        host.resourceAlertDurationSeconds = 180;
        HostResourceMonitorService monitor = monitorWithMetrics(host, "90.0", "40.0", "20%", metricRows(6, 90D, 40D, 20D));
        MonitorService monitorService = hostMonitorService();

        CheckResult result = monitor.check(monitorService, 10D);

        assertEquals("UP", result.status);
        assertEquals(HostResourceMonitorService.HOST_RESOURCE_THRESHOLD_ALERT, result.alertType);
    }

    @Test
    void checkAlertsImmediatelyWhenAlertDurationConfirmationDisabled() {
        HostConfig host = hostWithThresholds();
        host.checkInterval = 30;
        host.resourceAlertDurationEnabled = false;
        host.resourceAlertDurationSeconds = 180;
        HostResourceMonitorService monitor = monitorWithMetrics(host, "90.0", "40.0", "20%");
        MonitorService monitorService = hostMonitorService();

        CheckResult result = monitor.check(monitorService, 10D);

        assertEquals("UP", result.status);
        assertEquals(HostResourceMonitorService.HOST_RESOURCE_THRESHOLD_ALERT, result.alertType);
    }

    @Test
    void checkDoesNotAlertWhenCpuEqualsThreshold() {
        HostConfig host = hostWithThresholds();
        host.cpuThresholdPercent = 85D;
        HostResourceMonitorService monitor = monitorWithMetrics(host, "85.0", "40.0", "20%");
        MonitorService monitorService = hostMonitorService();

        CheckResult result = monitor.check(monitorService, 10D);

        assertEquals("UP", result.status);
        assertNull(result.alertType);
    }

    @Test
    void checkDoesNotAlertWhenAllMetricsAreBelowThresholds() {
        HostConfig host = hostWithThresholds();
        HostResourceMonitorService monitor = monitorWithMetrics(host, "0.7", "79.5", "60%");
        MonitorService monitorService = hostMonitorService();

        CheckResult result = monitor.check(monitorService, 10D);

        assertEquals("UP", result.status);
        assertNull(result.alertType);
        assertEquals("CPU 0.7% / 85.0%, Memory 79.5% / 85.0%, Disk 60.0% / 85.0%", result.message);
    }

    private HostConfig hostWithThresholds() {
        HostConfig host = new HostConfig();
        host.id = 1L;
        host.enabled = true;
        host.cpuThresholdPercent = 85D;
        host.memoryThresholdPercent = 85D;
        host.diskThresholdPercent = 85D;
        host.cpuAlertEnabled = true;
        host.memoryAlertEnabled = true;
        host.diskAlertEnabled = true;
        host.checkInterval = 30;
        host.resourceAlertDurationSeconds = 1;
        host.resourceRecoverDurationSeconds = 180;
        host.resourceAlertCooldownSeconds = 600;
        return host;
    }

    private MonitorService hostMonitorService() {
        MonitorService monitorService = new MonitorService();
        monitorService.id = 10L;
        monitorService.hostId = 1L;
        monitorService.serviceType = "host";
        return monitorService;
    }

    private HostResourceMonitorService monitorWithMetrics(
        HostConfig host,
        String cpuPercent,
        String memoryPercent,
        String diskPercent
    ) {
        return monitorWithMetrics(host, cpuPercent, memoryPercent, diskPercent, null);
    }

    private HostResourceMonitorService monitorWithMetrics(
        HostConfig host,
        String cpuPercent,
        String memoryPercent,
        String diskPercent,
        List<Map<String, Object>> historyRows
    ) {
        HostMapper hostMapper = mock(HostMapper.class);
        RocksDbHistoryRepository historyRepository = mock(RocksDbHistoryRepository.class);
        SshService sshService = mock(SshService.class);
        when(hostMapper.findHost(1L)).thenReturn(host);
        when(historyRepository.listHostMetrics(any(), anyInt(), anyInt())).thenReturn(historyRows);
        when(sshService.exec(any(HostConfig.class), anyString(), anyInt())).thenAnswer(invocation -> {
            String command = invocation.getArgument(1);
            if (command.contains("/proc/stat")) {
                double usage = Double.parseDouble(cpuPercent);
                double totalDelta = 100D;
                double idleDelta = totalDelta * (1D - usage / 100D);
                return "100 200\n" + (100D + idleDelta) + " " + (200D + totalDelta);
            }
            if (command.contains("/proc/loadavg")) {
                return "0.10";
            }
            if (command.contains("MemAvailable")) {
                return memoryPercent;
            }
            if (command.contains("MemTotal") && command.contains("$2/1024")) {
                return "1024";
            }
            if (command.startsWith("df ")) {
                return "Filesystem Type 1B-blocks Used Available Capacity Mounted on\n" +
                    "/dev/sda1 xfs 1000 200 800 " + diskPercent + " /";
            }
            if (command.startsWith("lsblk ")) {
                return "sda disk";
            }
            return "";
        });
        return new HostResourceMonitorService(hostMapper, historyRepository, sshService, new ObjectMapper());
    }

    private List<Map<String, Object>> metricRows(int count, Double cpu, Double memory, Double disk) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("cpu_usage_percent", cpu);
            row.put("memory_used_percent", memory);
            row.put("disk_used_percent", disk);
            rows.add(row);
        }
        return rows;
    }
}
