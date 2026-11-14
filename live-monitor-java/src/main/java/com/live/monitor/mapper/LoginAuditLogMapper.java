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

    @Select("<script>" +
        "SELECT id, user_id, user_name, action, ip_address, event_time " +
        "FROM login_audit_log " +
        "<where>" +
        "  <if test='keyword != null and keyword != \"\"'>" +
        "    (LOWER(user_id) LIKE #{keyword} " +
        "    OR LOWER(COALESCE(user_name, '')) LIKE #{keyword} " +
        "    OR LOWER(COALESCE(ip_address, '')) LIKE #{keyword} " +
        "    OR LOWER(COALESCE(action, '')) LIKE #{keyword} " +
        "    <if test='actionKeyword != null and actionKeyword != \"\"'>" +
        "      OR action = #{actionKeyword} " +
        "    </if>" +
        "    )" +
        "  </if>" +
        "</where>" +
        " ORDER BY event_time DESC, id DESC LIMIT #{limit} OFFSET #{offset}" +
        "</script>")
    List<LoginAuditLog> listPage(@Param("keyword") String keyword,
                                 @Param("actionKeyword") String actionKeyword,
                                 @Param("limit") int limit,
                                 @Param("offset") int offset);

    @Select("<script>" +
        "SELECT COUNT(*) FROM login_audit_log " +
        "<where>" +
        "  <if test='keyword != null and keyword != \"\"'>" +
        "    (LOWER(user_id) LIKE #{keyword} " +
        "    OR LOWER(COALESCE(user_name, '')) LIKE #{keyword} " +
        "    OR LOWER(COALESCE(ip_address, '')) LIKE #{keyword} " +
        "    OR LOWER(COALESCE(action, '')) LIKE #{keyword} " +
        "    <if test='actionKeyword != null and actionKeyword != \"\"'>" +
        "      OR action = #{actionKeyword} " +
        "    </if>" +
        "    )" +
        "  </if>" +
        "</where>" +
        "</script>")
    long count(@Param("keyword") String keyword, @Param("actionKeyword") String actionKeyword);
}
