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
        "ag.enabled AS alert_group_enabled, COALESCE(r.status, 'UNKNOWN') AS last_status, " +
        "r.response_time_ms AS last_response_time_ms, r.message AS last_message, r.checked_at AS last_checked_at " +
        "FROM monitor_service s " +
        "LEFT JOIN service_alert_group sag ON sag.service_id = s.id " +
        "LEFT JOIN alert_group ag ON ag.id = sag.group_id " +
        "LEFT JOIN monitor_result r ON r.id = (" +
        "  SELECT id FROM monitor_result WHERE service_id = s.id ORDER BY checked_at DESC, id DESC LIMIT 1" +
        ") ";

    @Select(SERVICE_SELECT + "WHERE (#{includeDisabled} = 1 OR s.enabled = 1) " +
        "ORDER BY s.cluster_name IS NULL, s.cluster_name, s.service_name")
    List<MonitorService> list(@Param("includeDisabled") int includeDisabled);

    @Select(SERVICE_SELECT + "WHERE s.id = #{id}")
    MonitorService findById(@Param("id") Long id);

    @Insert("INSERT INTO monitor_service (" +
        "service_name, service_type, cluster_name, host, port, url, http_method, expected_status_code, " +
        "response_keyword, check_timeout_seconds, redis_username, redis_password, redis_cluster_mode, " +
        "zookeeper_check_mode, zookeeper_check_command, zookeeper_expected_nodes, check_interval, " +
        "alert_config_id, enabled" +
        ") VALUES (" +
        "#{serviceName}, #{serviceType}, #{clusterName}, #{host}, #{port}, #{url}, #{httpMethod}, #{expectedStatusCode}, " +
        "#{responseKeyword}, #{checkTimeoutSeconds}, #{redisUsername}, #{redisPassword}, #{redisClusterMode}, " +
        "#{zookeeperCheckMode}, #{zookeeperCheckCommand}, #{zookeeperExpectedNodes}, #{checkInterval}, " +
        "#{alertConfigId}, #{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MonitorService service);

    @Update("UPDATE monitor_service SET " +
        "service_name = #{serviceName}, service_type = #{serviceType}, cluster_name = #{clusterName}, " +
        "host = #{host}, port = #{port}, url = #{url}, http_method = #{httpMethod}, " +
        "expected_status_code = #{expectedStatusCode}, response_keyword = #{responseKeyword}, " +
        "check_timeout_seconds = #{checkTimeoutSeconds}, redis_username = #{redisUsername}, " +
        "redis_password = COALESCE(#{redisPassword}, redis_password), redis_cluster_mode = #{redisClusterMode}, " +
        "zookeeper_check_mode = #{zookeeperCheckMode}, zookeeper_check_command = #{zookeeperCheckCommand}, " +
        "zookeeper_expected_nodes = #{zookeeperExpectedNodes}, check_interval = #{checkInterval}, " +
        "alert_config_id = #{alertConfigId}, enabled = #{enabled} WHERE id = #{id}")
    int update(MonitorService service);

    @Delete("DELETE FROM monitor_service WHERE id = #{id}")
    int delete(@Param("id") Long id);

    @Select("SELECT status FROM monitor_result WHERE service_id = #{serviceId} ORDER BY checked_at DESC, id DESC LIMIT 1")
    String latestStatus(@Param("serviceId") Long serviceId);

    @Insert("INSERT OR REPLACE INTO service_alert_group (service_id, group_id) VALUES (#{serviceId}, #{groupId})")
    int bindAlertGroup(@Param("serviceId") Long serviceId, @Param("groupId") Long groupId);

    @Delete("DELETE FROM service_alert_group WHERE service_id = #{serviceId}")
    int unbindAlertGroup(@Param("serviceId") Long serviceId);
}
