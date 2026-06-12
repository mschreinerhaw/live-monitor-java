package com.live.monitor.service;

import com.live.monitor.dto.AuditLogPageResponse;
import com.live.monitor.entity.LoginAuditLog;
import com.live.monitor.entity.TUser;
import com.live.monitor.mapper.LoginAuditLogMapper;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LoginAuditLogService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

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

    public AuditLogPageResponse listPage(TUser currentUser, Integer page, Integer pageSize, String query) {
        requireAdmin(currentUser);
        int safePageSize = normalizePageSize(pageSize);
        int safePage = Math.max(page == null ? 1 : page, 1);
        String keyword = null;
        String actionKeyword = null;
        if (StringUtils.hasText(query)) {
            String normalized = query.trim().toLowerCase(Locale.ROOT);
            keyword = "%" + normalized + "%";
            actionKeyword = actionKeyword(normalized);
        }

        long total = loginAuditLogMapper.count(keyword, actionKeyword);
        int totalPages = total <= 0 ? 1 : (int) Math.ceil(total / (double) safePageSize);
        safePage = Math.min(safePage, totalPages);
        int offset = (safePage - 1) * safePageSize;
        List<LoginAuditLog> rows = total <= 0
            ? Collections.emptyList()
            : loginAuditLogMapper.listPage(keyword, actionKeyword, safePageSize, offset);
        return new AuditLogPageResponse(rows, safePage, safePageSize, total, totalPages);
    }

    private int normalizePageSize(Integer pageSize) {
        int value = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        return Math.max(1, Math.min(value, MAX_PAGE_SIZE));
    }

    private String actionKeyword(String normalizedQuery) {
        if (normalizedQuery.contains("登出") || normalizedQuery.contains("退出") || normalizedQuery.contains("logout")) {
            return "LOGOUT";
        }
        if (normalizedQuery.contains("登录") || normalizedQuery.contains("登陆") || normalizedQuery.contains("login")) {
            return "LOGIN";
        }
        return null;
    }

    private void requireAdmin(TUser currentUser) {
        if (currentUser == null || !"admin".equalsIgnoreCase(currentUser.userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin permission required");
        }
    }
}
