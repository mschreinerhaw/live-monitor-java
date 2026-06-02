package com.live.monitor.mapper;

import com.live.monitor.entity.AlertChannel;
import com.live.monitor.entity.AlertGroup;
import com.live.monitor.entity.AlertPolicy;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AlertMapper {
    @Select("SELECT * FROM alert_policy WHERE (#{includeDisabled} = 1 OR enabled = 1) ORDER BY enabled DESC, id")
    List<AlertPolicy> listPolicies(@Param("includeDisabled") int includeDisabled);

    @Select("SELECT p.* FROM alert_policy p JOIN group_policy_rel rel ON rel.policy_id = p.id " +
        "WHERE rel.group_id = #{groupId} ORDER BY p.id")
    List<AlertPolicy> listPoliciesByGroup(@Param("groupId") Long groupId);

    @Select("SELECT * FROM alert_channel WHERE (#{includeDisabled} = 1 OR enabled = 1) ORDER BY enabled DESC, channel_name")
    List<AlertChannel> listChannels(@Param("includeDisabled") int includeDisabled);

    @Select("SELECT * FROM alert_channel WHERE id = #{id}")
    AlertChannel findChannel(@Param("id") Long id);

    @Select("SELECT c.* FROM alert_channel c JOIN group_channel_rel rel ON rel.channel_id = c.id " +
        "WHERE rel.group_id = #{groupId} ORDER BY c.channel_name")
    List<AlertChannel> listChannelsByGroup(@Param("groupId") Long groupId);

    @Select("SELECT COUNT(*) FROM group_channel_rel WHERE channel_id = #{channelId}")
    int countGroupsByChannel(@Param("channelId") Long channelId);

    @Select("SELECT COUNT(*) FROM service_alert_group sag " +
        "JOIN group_channel_rel rel ON rel.group_id = sag.group_id " +
        "WHERE rel.channel_id = #{channelId}")
    int countServicesByChannel(@Param("channelId") Long channelId);

    @Insert("INSERT INTO alert_channel (channel_name, channel_type, config_json, enabled) " +
        "VALUES (#{channelName}, #{channelType}, #{configJson}, #{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertChannel(AlertChannel channel);

    @Update("UPDATE alert_channel SET channel_name = #{channelName}, channel_type = #{channelType}, " +
        "config_json = #{configJson}, enabled = #{enabled} WHERE id = #{id}")
    int updateChannel(AlertChannel channel);

    @Delete("DELETE FROM alert_channel WHERE id = #{id}")
    int deleteChannel(@Param("id") Long id);

    @Select("SELECT * FROM alert_group WHERE (#{includeDisabled} = 1 OR enabled = 1) ORDER BY enabled DESC, group_name")
    List<AlertGroup> listGroups(@Param("includeDisabled") int includeDisabled);

    @Select("SELECT * FROM alert_group WHERE id = #{id}")
    AlertGroup findGroup(@Param("id") Long id);

    @Insert("INSERT INTO alert_group (group_name, description, enabled) VALUES (#{groupName}, #{description}, #{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertGroup(AlertGroup group);

    @Update("UPDATE alert_group SET group_name = #{groupName}, description = #{description}, enabled = #{enabled} WHERE id = #{id}")
    int updateGroup(AlertGroup group);

    @Delete("DELETE FROM alert_group WHERE id = #{id}")
    int deleteGroup(@Param("id") Long id);

    @Delete("DELETE FROM group_policy_rel WHERE group_id = #{groupId}")
    int deleteGroupPolicies(@Param("groupId") Long groupId);

    @Delete("DELETE FROM group_channel_rel WHERE group_id = #{groupId}")
    int deleteGroupChannels(@Param("groupId") Long groupId);

    @Insert("MERGE INTO group_policy_rel KEY(group_id, policy_id) VALUES (#{groupId}, #{policyId})")
    int insertGroupPolicy(@Param("groupId") Long groupId, @Param("policyId") Long policyId);

    @Insert("MERGE INTO group_channel_rel KEY(group_id, channel_id) VALUES (#{groupId}, #{channelId})")
    int insertGroupChannel(@Param("groupId") Long groupId, @Param("channelId") Long channelId);

    @Select("SELECT COUNT(*) FROM service_alert_group WHERE group_id = #{groupId}")
    int countServicesByGroup(@Param("groupId") Long groupId);

}
