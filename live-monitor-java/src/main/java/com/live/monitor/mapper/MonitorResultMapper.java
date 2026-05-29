package com.live.monitor.mapper;

import com.live.monitor.entity.MonitorResult;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MonitorResultMapper {
    @Insert("INSERT INTO monitor_result (service_id, status, response_time_ms, message) " +
        "VALUES (#{serviceId}, #{status}, #{responseTimeMs}, #{message})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MonitorResult result);

    @Select("SELECT * FROM monitor_result WHERE id = #{id}")
    MonitorResult findById(@Param("id") Long id);

    @Select("SELECT * FROM monitor_result WHERE service_id = #{serviceId} " +
        "ORDER BY checked_at DESC, id DESC LIMIT #{limit}")
    List<MonitorResult> listByService(@Param("serviceId") Long serviceId, @Param("limit") int limit);

    @Select("SELECT r.*, s.service_name, s.service_type, s.cluster_name FROM monitor_result r " +
        "JOIN monitor_service s ON s.id = r.service_id WHERE r.id = (" +
        "SELECT id FROM monitor_result WHERE service_id = r.service_id ORDER BY checked_at DESC, id DESC LIMIT 1" +
        ") ORDER BY r.checked_at DESC, r.id DESC LIMIT #{limit}")
    List<MonitorResult> listRecent(@Param("limit") int limit);
}
