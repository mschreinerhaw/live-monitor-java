package com.live.monitor.service;

import com.live.monitor.entity.LoginAuditLog;
import com.live.monitor.entity.TUser;
import com.live.monitor.mapper.LoginAuditLogMapper;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LoginAuditLogService {
    private static final int DEFAULT_LIMIT = 200;

    private final LoginAuditLogMapper loginAuditLogMapper;

    public LoginAuditLogService(LoginAuditLogMapper loginAuditLogMapper) {
        this.loginAuditLogMapper = loginAuditLogMapper;
    }

    public void record(TUser user, String action, String ipAddress) {
        if (user == null || !StringUtils.hasText(user.userId) || !StringUtils.hasText(action)) {
            return;
        }
        LoginAuditLog log = new LoginAuditLog();
        log.userId = user.userId;
        log.userName = StringUtils.hasText(user.name) ? user.name : user.userId;
        log.action = action;
        log.ipAddress = StringUtils.hasText(ipAddress) ? ipAddress : "-";
        loginAuditLogMapper.insert(log);
    }

    public List<LoginAuditLog> listRecent(TUser currentUser) {
        requireAdmin(currentUser);
        return loginAuditLogMapper.listRecent(DEFAULT_LIMIT);
    }

    private void requireAdmin(TUser currentUser) {
        if (currentUser == null || !"admin".equalsIgnoreCase(currentUser.userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin permission required");
        }
    }
}
