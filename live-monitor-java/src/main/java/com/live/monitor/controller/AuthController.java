package com.live.monitor.controller;

import com.live.monitor.config.AuthInterceptor;
import com.live.monitor.dto.LoginPayload;
import com.live.monitor.entity.TUser;
import com.live.monitor.service.AuthService;
import com.live.monitor.service.EmbedTokenService;
import com.live.monitor.service.LoginAuditLogService;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.StringUtils;

@RestController
public class AuthController {
    public static final String SESSION_USER = "LOGIN_USER";

    private final AuthService authService;
    private final EmbedTokenService embedTokenService;
    private final LoginAuditLogService loginAuditLogService;

    public AuthController(AuthService authService,
                          EmbedTokenService embedTokenService,
                          LoginAuditLogService loginAuditLogService) {
        this.authService = authService;
        this.embedTokenService = embedTokenService;
        this.loginAuditLogService = loginAuditLogService;
    }

    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@Valid @RequestBody LoginPayload payload,
                                     HttpServletRequest request,
                                     HttpSession session) {
        TUser user = authService.authenticate(payload.username, payload.password);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid username or password");
        }
        session.setAttribute(SESSION_USER, user);
        session.removeAttribute(AuthInterceptor.SESSION_EMBED_EXPIRES_AT);
        loginAuditLogService.record(user, "LOGIN", clientIp(request));
        return currentUser(user, true);
    }

    @PostMapping("/api/auth/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object user = session == null ? null : session.getAttribute(SESSION_USER);
        if (user instanceof TUser) {
            loginAuditLogService.record((TUser) user, "LOGOUT", clientIp(request));
        }
        if (session != null) {
            session.invalidate();
        }
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("authenticated", false);
        return result;
    }

    @GetMapping("/api/auth/me")
    public Map<String, Object> me(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object user = session == null ? null : session.getAttribute(SESSION_USER);
        if (!(user instanceof TUser) && embedTokenService.verify(embedTokenValue(request)) != null) {
            TUser embedUser = new TUser();
            embedUser.userId = "admin";
            embedUser.name = "admin";
            embedUser.status = 1;
            return currentUser(embedUser, true, true);
        }
        boolean embedSession = session != null && session.getAttribute(AuthInterceptor.SESSION_EMBED_EXPIRES_AT) != null;
        return currentUser(user instanceof TUser ? (TUser) user : null, user instanceof TUser, embedSession);
    }

    private String embedTokenValue(HttpServletRequest request) {
        String token = request.getParameter("token");
        if (token == null || token.trim().isEmpty()) {
            token = request.getHeader("X-Embed-Token");
        }
        return token;
    }

    private String clientIp(HttpServletRequest request) {
        String[] headerNames = new String[] {
            "X-Forwarded-For",
            "X-Real-IP",
            "CF-Connecting-IP"
        };
        for (String headerName : headerNames) {
            String value = request.getHeader(headerName);
            if (StringUtils.hasText(value)) {
                return value.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private Map<String, Object> currentUser(TUser user, boolean authenticated) {
        return currentUser(user, authenticated, false);
    }

    private Map<String, Object> currentUser(TUser user, boolean authenticated, boolean embed) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("authenticated", authenticated);
        result.put("embed", embed);
        if (user != null) {
            result.put("user_id", user.userId);
            result.put("display_name", user.userId);
            result.put("name", user.name == null ? user.userId : user.name);
            result.put("admin", "admin".equalsIgnoreCase(user.userId));
        }
        return result;
    }
}
