package com.live.monitor.mapper;

import com.live.monitor.entity.HostConfig;
import com.live.monitor.entity.HostProcessConfig;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface HostMapper {
    @Select("SELECT * FROM host_config WHERE (#{includeDisabled} = 1 OR enabled = 1) ORDER BY enabled DESC, host_name")
    List<HostConfig> listHosts(@Param("includeDisabled") int includeDisabled);

    @Select("SELECT * FROM host_config WHERE id = #{id}")
    HostConfig findHost(@Param("id") Long id);

    @Insert("INSERT INTO host_config (host_name, ip, ssh_port, ssh_user, ssh_password_cipher, private_key_cipher, enabled) " +
        "VALUES (#{hostName}, #{ip}, #{sshPort}, #{sshUser}, #{sshPasswordCipher}, #{privateKeyCipher}, #{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertHost(HostConfig host);

    @Update("UPDATE host_config SET host_name = #{hostName}, ip = #{ip}, ssh_port = #{sshPort}, ssh_user = #{sshUser}, " +
        "ssh_password_cipher = COALESCE(#{sshPasswordCipher}, ssh_password_cipher), " +
        "private_key_cipher = COALESCE(#{privateKeyCipher}, private_key_cipher), enabled = #{enabled} WHERE id = #{id}")
    int updateHost(HostConfig host);

    @Delete("DELETE FROM host_config WHERE id = #{id}")
    int deleteHost(@Param("id") Long id);

    @Select("SELECT * FROM host_process_config WHERE host_id = #{hostId} ORDER BY enabled DESC, process_name")
    List<HostProcessConfig> listProcesses(@Param("hostId") Long hostId);

    @Insert("INSERT INTO host_process_config (host_id, process_name, match_keyword, enabled) " +
        "VALUES (#{hostId}, #{processName}, #{matchKeyword}, #{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertProcess(HostProcessConfig process);

    @Delete("DELETE FROM host_process_config WHERE id = #{id}")
    int deleteProcess(@Param("id") Long id);
}
