package com.live.monitor.service;

import com.live.monitor.entity.TUser;
import com.live.monitor.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AuthService {
    private final UserMapper userMapper;

    public AuthService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public TUser authenticate(String username, String password) {
        if (!StringUtils.hasText(username) || password == null) {
            return null;
        }
        TUser user = userMapper.findByUserId(username.trim());
        if (user == null || !enabled(user) || user.password == null || !user.password.equals(password)) {
            return null;
        }
        userMapper.markLogin(user.id);
        user.password = null;
        return user;
    }

    private boolean enabled(TUser user) {
        return user.status == null || user.status.intValue() == 1;
    }
}
