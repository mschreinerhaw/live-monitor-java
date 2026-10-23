package com.live.monitor.mapper;

import com.live.monitor.entity.ExternalMessageAuditRecord;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ExternalMessageAuditMapper {
    @Insert("INSERT INTO external_message_audit (" +
        "service_id, client_ip, content_type, project_name, message_summary, message_length, request_status, " +
        "delivery_count, success_count, failed_count, detail" +
        ") VALUES (" +
        "#{serviceId}, #{clientIp}, #{contentType}, #{projectName}, #{messageSummary}, #{messageLength}, #{requestStatus}, " +
        "#{deliveryCount}, #{successCount}, #{failedCount}, #{detail})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ExternalMessageAuditRecord record);

    @Select("<script>" +
        "SELECT * FROM external_message_audit " +
        "<where>" +
        "<if test='status != null and status != \"\"'> request_status = #{status} </if>" +
        "<if test='query != null and query != \"\"'>" +
        " AND (LOWER(COALESCE(client_ip, '')) LIKE CONCAT('%', LOWER(#{query}), '%')" +
        " OR LOWER(COALESCE(project_name, '')) LIKE CONCAT('%', LOWER(#{query}), '%')" +
        " OR LOWER(COALESCE(message_summary, '')) LIKE CONCAT('%', LOWER(#{query}), '%')" +
        " OR LOWER(COALESCE(detail, '')) LIKE CONCAT('%', LOWER(#{query}), '%'))" +
        "</if>" +
        "</where> ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}" +
        "</script>")
    List<ExternalMessageAuditRecord> listPage(
        @Param("status") String status,
        @Param("query") String query,
        @Param("limit") int limit,
        @Param("offset") int offset
    );

    @Select("<script>SELECT COUNT(*) FROM external_message_audit " +
        "<where>" +
        "<if test='status != null and status != \"\"'> request_status = #{status} </if>" +
        "<if test='query != null and query != \"\"'>" +
        " AND (LOWER(COALESCE(client_ip, '')) LIKE CONCAT('%', LOWER(#{query}), '%')" +
        " OR LOWER(COALESCE(project_name, '')) LIKE CONCAT('%', LOWER(#{query}), '%')" +
        " OR LOWER(COALESCE(message_summary, '')) LIKE CONCAT('%', LOWER(#{query}), '%')" +
        " OR LOWER(COALESCE(detail, '')) LIKE CONCAT('%', LOWER(#{query}), '%'))" +
        "</if>" +
        "</where></script>")
    long count(@Param("status") String status, @Param("query") String query);

    @Delete("DELETE FROM external_message_audit WHERE created_at < #{cutoff}")
    int deleteBefore(@Param("cutoff") String cutoff);
}
