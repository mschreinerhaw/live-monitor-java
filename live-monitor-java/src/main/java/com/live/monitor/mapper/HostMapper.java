package com.live.monitor.mapper;

import com.live.monitor.entity.HostConfig;
import com.live.monitor.entity.HostProcessConfig;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface HostMapper {
    String HOST_SELECT = "SELECT h.*, sag.group_id AS alert_group_id, " +
        "hm.cpu_usage_percent, hm.load_average, hm.memory_used_percent, hm.disk_used_percent, " +
        "hm.cpu_core_count, hm.memory_total_mb, hm.disk_mount_count, hm.disk_metrics_json, " +
        "hm.checked_at AS metric_checked_at FROM host_config h " +
        "LEFT JOIN service_alert_group sag ON sag.service_id = h.monitor_service_id " +
        "LEFT JOIN host_latest_metric hm ON hm.host_id = h.id ";

    @Select(HOST_SELECT + "WHERE (#{includeDisabled} = 1 OR h.enabled = 1) ORDER BY h.enabled DESC, h.cluster_name, h.host_name")
    List<HostConfig> listHosts(@Param("includeDisabled") int includeDisabled);

    @Select(HOST_SELECT + "WHERE h.id = #{id}")
    HostConfig findHost(@Param("id") Long id);

    @Select("SELECT * FROM host_config WHERE enabled = 1 AND (ip = #{address} OR host_name = #{address}) ORDER BY id LIMIT 1")
    HostConfig findEnabledByAddress(@Param("address") String address);

    @Insert("INSERT INTO host_config (host_name, ip, ssh_port, ssh_user, ssh_password_cipher, private_key_cipher, " +
        "monitor_service_id, cluster_name, cpu_threshold_percent, disk_threshold_percent, check_interval, enabled) " +
        "VALUES (#{hostName}, #{ip}, #{sshPort}, #{sshUser}, #{sshPasswordCipher}, #{privateKeyCipher}, " +
        "#{monitorServiceId}, #{clusterName}, #{cpuThresholdPercent}, #{diskThresholdPercent}, #{checkInterval}, #{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertHost(HostConfig host);

    @Update("UPDATE host_config SET host_name = #{hostName}, ip = #{ip}, ssh_port = #{sshPort}, ssh_user = #{sshUser}, " +
        "ssh_password_cipher = COALESCE(#{sshPasswordCipher}, ssh_password_cipher), " +
        "private_key_cipher = COALESCE(#{privateKeyCipher}, private_key_cipher), monitor_service_id = #{monitorServiceId}, " +
        "cluster_name = #{clusterName}, cpu_threshold_percent = #{cpuThresholdPercent}, " +
        "disk_threshold_percent = #{diskThresholdPercent}, check_interval = #{checkInterval}, enabled = #{enabled} WHERE id = #{id}")
    int updateHost(HostConfig host);

    @Update("UPDATE host_config SET monitor_service_id = #{monitorServiceId} WHERE id = #{id}")
    int updateMonitorServiceId(@Param("id") Long id, @Param("monitorServiceId") Long monitorServiceId);

    @Delete("DELETE FROM host_config WHERE id = #{id}")
    int deleteHost(@Param("id") Long id);

    @Select("SELECT * FROM host_process_config WHERE host_id = #{hostId} ORDER BY enabled DESC, process_name")
    List<HostProcessConfig> listProcesses(@Param("hostId") Long hostId);

    @Insert("INSERT INTO host_process_config (host_id, process_name, match_keyword, check_command, enabled) " +
        "VALUES (#{hostId}, #{processName}, #{matchKeyword}, #{checkCommand}, #{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertProcess(HostProcessConfig process);

    @Delete("DELETE FROM host_process_config WHERE id = #{id}")
    int deleteProcess(@Param("id") Long id);

    @Select("SELECT * FROM host_process_config WHERE id = #{id}")
    HostProcessConfig findProcess(@Param("id") Long id);

    @Update("UPDATE host_process_config SET process_name = #{processName}, match_keyword = #{matchKeyword}, " +
        "check_command = #{checkCommand}, enabled = #{enabled} WHERE id = #{id}")
    int updateProcess(HostProcessConfig process);

    @Insert("MERGE INTO host_latest_metric " +
        "(host_id, cpu_usage_percent, load_average, memory_used_percent, disk_used_percent, " +
        "cpu_core_count, memory_total_mb, disk_mount_count, disk_metrics_json, checked_at) " +
        "KEY(host_id) VALUES (#{hostId}, #{cpuUsagePercent}, #{loadAverage}, #{memoryUsedPercent}, #{diskUsedPercent}, " +
        "#{cpuCoreCount}, #{memoryTotalMb}, #{diskMountCount}, #{diskMetricsJson}, CURRENT_TIMESTAMP)")
    int upsertLatestMetric(
        @Param("hostId") Long hostId,
        @Param("cpuUsagePercent") Double cpuUsagePercent,
        @Param("loadAverage") Double loadAverage,
        @Param("memoryUsedPercent") Double memoryUsedPercent,
        @Param("diskUsedPercent") Double diskUsedPercent,
        @Param("cpuCoreCount") Integer cpuCoreCount,
        @Param("memoryTotalMb") Double memoryTotalMb,
        @Param("diskMountCount") Integer diskMountCount,
        @Param("diskMetricsJson") String diskMetricsJson
    );
}
