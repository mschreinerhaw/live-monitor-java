package com.live.monitor.mapper;

import com.live.monitor.entity.MonitorService;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface MonitorServiceMapper {
    String SERVICE_SELECT =
        "SELECT s.*, sag.group_id AS alert_group_id, ag.group_name AS alert_group_name, " +
        "ag.enabled AS alert_group_enabled, COALESCE(ls.status, 'UNKNOWN') AS last_status, " +
        "ls.response_time_ms AS last_response_time_ms, ls.message AS last_message, ls.checked_at AS last_checked_at " +
        "FROM monitor_service s " +
        "LEFT JOIN service_alert_group sag ON sag.service_id = s.id " +
        "LEFT JOIN alert_group ag ON ag.id = sag.group_id " +
        "LEFT JOIN service_latest_status ls ON ls.service_id = s.id ";

    @Select(SERVICE_SELECT + "WHERE (#{includeDisabled} = 1 OR s.enabled = 1) " +
        "ORDER BY s.cluster_name IS NULL, s.cluster_name, s.service_name")
    List<MonitorService> list(@Param("includeDisabled") int includeDisabled);

    @Select(SERVICE_SELECT + "WHERE s.id = #{id}")
    MonitorService findById(@Param("id") Long id);

    @Insert("INSERT INTO monitor_service (" +
        "service_name, service_category, service_type, cluster_name, monitor_reason, endpoint, host, port, check_mode, " +
        "check_command, expected_result, check_timeout_seconds, config_json, secret_config_json, " +
        "check_interval, alert_config_id, enabled" +
        ") VALUES (" +
        "#{serviceName}, #{serviceCategory}, #{serviceType}, #{clusterName}, #{monitorReason}, #{endpoint}, #{host}, #{port}, #{checkMode}, " +
        "#{checkCommand}, #{expectedResult}, #{checkTimeoutSeconds}, #{configJson}, #{secretConfigJson}, " +
        "#{checkInterval}, #{alertConfigId}, #{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MonitorService service);

    @Update("UPDATE monitor_service SET " +
        "service_name = #{serviceName}, service_category = #{serviceCategory}, service_type = #{serviceType}, " +
        "cluster_name = #{clusterName}, monitor_reason = #{monitorReason}, endpoint = #{endpoint}, host = #{host}, port = #{port}, " +
        "check_mode = #{checkMode}, check_command = #{checkCommand}, expected_result = #{expectedResult}, " +
        "check_timeout_seconds = #{checkTimeoutSeconds}, config_json = #{configJson}, " +
        "secret_config_json = #{secretConfigJson}, check_interval = #{checkInterval}, " +
        "alert_config_id = #{alertConfigId}, enabled = #{enabled} WHERE id = #{id}")
    int update(MonitorService service);

    @Update("UPDATE monitor_service SET check_interval = #{checkInterval} WHERE id = #{serviceId}")
    int updateCheckInterval(@Param("serviceId") Long serviceId, @Param("checkInterval") int checkInterval);

    @Delete("DELETE FROM monitor_service WHERE id = #{id}")
    int delete(@Param("id") Long id);

    @Select("SELECT status FROM service_latest_status WHERE service_id = #{serviceId}")
    String latestStatus(@Param("serviceId") Long serviceId);

    @Insert("MERGE INTO service_alert_group KEY(service_id) VALUES (#{serviceId}, #{groupId})")
    int bindAlertGroup(@Param("serviceId") Long serviceId, @Param("groupId") Long groupId);

    @Delete("DELETE FROM service_alert_group WHERE service_id = #{serviceId}")
    int unbindAlertGroup(@Param("serviceId") Long serviceId);

    @Insert("MERGE INTO service_latest_status KEY(service_id) " +
        "VALUES (#{serviceId}, #{status}, #{responseTimeMs}, #{message}, #{checkedAt})")
    int upsertLatestStatus(
        @Param("serviceId") Long serviceId,
        @Param("status") String status,
        @Param("responseTimeMs") Integer responseTimeMs,
        @Param("message") String message,
        @Param("checkedAt") String checkedAt
    );
}
