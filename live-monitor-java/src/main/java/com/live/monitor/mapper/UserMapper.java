package com.live.monitor.mapper;

import com.live.monitor.entity.TUser;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface UserMapper {
    @Select("SELECT ID AS id, UserID AS user_id, Password AS password, Name AS name, " +
        "Grade AS grade, Status AS status, Logins AS logins, LastLogin AS last_login, " +
        "UserAttribute AS user_attribute FROM tuser WHERE UserID = #{userId} LIMIT 1")
    TUser findByUserId(@Param("userId") String userId);

    @Select("SELECT ID AS id, UserID AS user_id, Name AS name, Grade AS grade, Status AS status, " +
        "Logins AS logins, LastLogin AS last_login, UserAttribute AS user_attribute " +
        "FROM tuser ORDER BY ID")
    List<TUser> findAll();

    @Insert("INSERT INTO tuser (ID, UserID, Password, Name, Grade, Logins, Status, UserAttribute) " +
        "SELECT COALESCE(MAX(ID), 0) + 1, #{userId}, #{password}, #{name}, 1, 0, #{status}, 0 FROM tuser")
    int insertUser(@Param("userId") String userId,
                   @Param("password") String password,
                   @Param("name") String name,
                   @Param("status") Integer status);

    @Update("UPDATE tuser SET Password = #{password}, ChgPwdTime = CURRENT_TIMESTAMP WHERE ID = #{id}")
    int updatePassword(@Param("id") Long id, @Param("password") String password);

    @Update("UPDATE tuser SET Status = #{status} WHERE ID = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    @Delete("DELETE FROM tuser WHERE ID = #{id}")
    int deleteById(@Param("id") Long id);

    @Update("UPDATE tuser SET LastLogin = CURRENT_TIMESTAMP, Logins = COALESCE(Logins, 0) + 1 WHERE ID = #{id}")
    int markLogin(@Param("id") Long id);
}
