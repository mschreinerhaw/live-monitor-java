package com.live.monitor.service;

import com.live.monitor.entity.TUser;
import com.live.monitor.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AuthService {
    private final UserMapper userMapper;
    private final CryptoService cryptoService;

    public AuthService(UserMapper userMapper, CryptoService cryptoService) {
        this.userMapper = userMapper;
        this.cryptoService = cryptoService;
    }

    public TUser authenticate(String username, String password) {
        if (!StringUtils.hasText(username) || password == null) {
            return null;
        }
        TUser user = userMapper.findByUserId(username.trim());
        if (user == null || !enabled(user) || !passwordMatches(user, password)) {
            return null;
        }
        encryptLegacyPasswordIfNeeded(user);
        userMapper.markLogin(user.id);
        user.password = null;
        return user;
    }

    public boolean passwordMatches(TUser user, String password) {
        return user != null
            && user.password != null
            && password != null
            && storedPasswordPlainText(user.password).equals(password);
    }

    public String encryptPassword(String password) {
        return cryptoService.encrypt(password);
    }

    private String storedPasswordPlainText(String storedPassword) {
        return cryptoService.decryptIfEncrypted(storedPassword);
    }

    private void encryptLegacyPasswordIfNeeded(TUser user) {
        if (user != null && user.id != null && user.password != null && !cryptoService.isEncrypted(user.password)) {
            userMapper.updatePassword(user.id, encryptPassword(user.password));
        }
    }

    private boolean enabled(TUser user) {
        return user.status == null || user.status.intValue() == 1;
    }
}
