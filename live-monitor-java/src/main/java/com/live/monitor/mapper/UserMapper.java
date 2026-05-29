package com.live.monitor.mapper;

import com.live.monitor.entity.TUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface UserMapper {
    @Select("SELECT ID AS id, UserID AS user_id, Password AS password, Name AS name, " +
        "Status AS status, Logins AS logins FROM tuser WHERE UserID = #{userId} LIMIT 1")
    TUser findByUserId(@Param("userId") String userId);

    @Update("UPDATE tuser SET LastLogin = CURRENT_TIMESTAMP, Logins = COALESCE(Logins, 0) + 1 WHERE ID = #{id}")
    int markLogin(@Param("id") Long id);
}
