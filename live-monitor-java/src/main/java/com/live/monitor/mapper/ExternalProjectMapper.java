package com.live.monitor.mapper;

import com.live.monitor.entity.ExternalProject;
import com.live.monitor.entity.ExternalProjectAlertGroup;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ExternalProjectMapper {
    @Select("SELECT * FROM external_project ORDER BY enabled DESC, project_name, id")
    List<ExternalProject> list();

    @Select("SELECT * FROM external_project WHERE id = #{id}")
    ExternalProject findById(@Param("id") Long id);

    @Select("SELECT * FROM external_project WHERE project_name = #{projectName} AND id != #{excludeId} LIMIT 1")
    ExternalProject findDuplicateName(@Param("projectName") String projectName, @Param("excludeId") Long excludeId);

    @Select("SELECT * FROM external_project WHERE enabled = 1 " +
        "AND #{message} LIKE CONCAT('%', match_keyword, '%') " +
        "ORDER BY CHAR_LENGTH(match_keyword) DESC, id LIMIT 1")
    ExternalProject findFirstMatching(@Param("message") String message);

    @Insert("INSERT INTO external_project (project_name, match_keyword, description, enabled) " +
        "VALUES (#{projectName}, #{matchKeyword}, #{description}, #{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ExternalProject project);

    @Update("UPDATE external_project SET project_name = #{projectName}, match_keyword = #{matchKeyword}, " +
        "description = #{description}, enabled = #{enabled}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int update(ExternalProject project);

    @Delete("DELETE FROM external_project WHERE id = #{id}")
    int delete(@Param("id") Long id);

    @Select("SELECT rel.project_id, rel.group_id, ag.group_name, ag.enabled AS group_enabled " +
        "FROM external_project_alert_group rel JOIN alert_group ag ON ag.id = rel.group_id " +
        "WHERE rel.project_id = #{projectId} ORDER BY rel.group_id")
    List<ExternalProjectAlertGroup> listAlertGroups(@Param("projectId") Long projectId);

    @Delete("DELETE FROM external_project_alert_group WHERE project_id = #{projectId}")
    int deleteAlertGroups(@Param("projectId") Long projectId);

    @Insert("<script><choose>" +
        "<when test=\"_databaseId == 'mysql'\">" +
        "INSERT INTO external_project_alert_group (project_id, group_id) VALUES (#{projectId}, #{groupId}) " +
        "ON DUPLICATE KEY UPDATE group_id = VALUES(group_id)" +
        "</when>" +
        "<otherwise>MERGE INTO external_project_alert_group KEY(project_id, group_id) " +
        "VALUES (#{projectId}, #{groupId})</otherwise>" +
        "</choose></script>")
    int insertAlertGroup(@Param("projectId") Long projectId, @Param("groupId") Long groupId);
}
