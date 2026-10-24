package com.live.monitor.config;

import com.live.monitor.controller.AuthController;
import com.live.monitor.entity.TUser;
import com.live.monitor.ops.OpsAuthorization;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.live.monitor.service.EmbedTokenService;
import com.live.monitor.service.EmbedTokenService.EmbedToken;
import com.live.monitor.service.SystemMetricsService;
import java.time.Instant;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String SESSION_EMBED_EXPIRES_AT = "EMBED_EXPIRES_AT";
    private final SystemMetricsService systemMetricsService;
    private final EmbedTokenService embedTokenService;
    @Autowired private ObjectProvider<OpsAuthorization> operations;

    public AuthInterceptor(SystemMetricsService systemMetricsService, EmbedTokenService embedTokenService) {
        this.systemMetricsService = systemMetricsService;
        this.embedTokenService = embedTokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        systemMetricsService.recordRequest(request.getMethod(), request.getRequestURI(), request.getQueryString());
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublic(request.getRequestURI())) {
            return true;
        }
        HttpSession session = request.getSession(false);
        if (isAuthenticatedSession(session)) {
            String uri = request.getRequestURI();
            Object principal = session.getAttribute(AuthController.SESSION_USER);
            if (operations != null && principal instanceof TUser && isApi(uri)
                && !uri.startsWith("/api/ops/") && !uri.startsWith("/api/auth/")
                && !operations.getObject().legacyInterfaceAllowed(((TUser) principal).userId, uri)) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"code\":\"LEGACY_INTERFACE_DENIED\",\"message\":\"Operations-only account: use authorized operations APIs\"}");
                return false;
            }
            return true;
        }
        EmbedToken token = embedTokenService.verify(embedTokenValue(request));
        if (token != null) {
            bindEmbedSession(request, token);
            return true;
        }
        if (isApi(request.getRequestURI())) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"detail\":\"authentication required\"}");
            return false;
        }
        return true;
    }

    private boolean isPublic(String uri) {
        return "/login.html".equals(uri)
            || "/login".equals(uri)
            || "/favicon.svg".equals(uri)
            || (uri != null && uri.startsWith("/static/"))
            || "/api/auth/login".equals(uri)
            || "/api/auth/me".equals(uri)
            || "/api/auth/current-user".equals(uri)
            || "/api/external/messages".equals(uri)
            || "/api/health".equals(uri);
    }

    private String embedTokenValue(HttpServletRequest request) {
        String token = request.getParameter("token");
        if (token == null || token.trim().isEmpty()) {
            token = request.getHeader("X-Embed-Token");
        }
        return token;
    }

    private boolean isAuthenticatedSession(HttpSession session) {
        if (session == null || session.getAttribute(AuthController.SESSION_USER) == null) {
            return false;
        }
        Object expiresAt = session.getAttribute(SESSION_EMBED_EXPIRES_AT);
        if (expiresAt == null) {
            return true;
        }
        long now = Instant.now().getEpochSecond();
        if (expiresAt instanceof Number && ((Number) expiresAt).longValue() >= now) {
            return true;
        }
        session.invalidate();
        return false;
    }

    private void bindEmbedSession(HttpServletRequest request, EmbedToken token) {
        TUser user = new TUser();
        user.userId = "admin";
        user.name = "admin";
        user.status = 1;
        HttpSession session = request.getSession(true);
        session.setAttribute(AuthController.SESSION_USER, user);
        session.setAttribute(SESSION_EMBED_EXPIRES_AT, token.expiresAt);
    }

    private boolean isApi(String uri) {
        return uri != null && uri.startsWith("/api/");
    }
}
