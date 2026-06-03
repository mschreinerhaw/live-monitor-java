package com.live.monitor.mapper;

import com.live.monitor.entity.LoginAuditLog;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface LoginAuditLogMapper {
    @Insert("INSERT INTO login_audit_log (user_id, user_name, action, ip_address, event_time) " +
        "VALUES (#{userId}, #{userName}, #{action}, #{ipAddress}, CURRENT_TIMESTAMP)")
    int insert(LoginAuditLog log);

    @Select("SELECT id, user_id AS user_id, user_name AS user_name, action, " +
        "ip_address AS ip_address, event_time AS event_time " +
        "FROM login_audit_log ORDER BY event_time DESC, id DESC LIMIT #{limit}")
    List<LoginAuditLog> listRecent(@Param("limit") int limit);
}
