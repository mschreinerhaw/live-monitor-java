package com.live.monitor.config;

import com.live.monitor.controller.AuthController;
import com.live.monitor.service.SystemMetricsService;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final SystemMetricsService systemMetricsService;

    public AuthInterceptor(SystemMetricsService systemMetricsService) {
        this.systemMetricsService = systemMetricsService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        systemMetricsService.recordRequest(request.getMethod(), request.getRequestURI(), request.getQueryString());
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublic(request.getRequestURI())) {
            return true;
        }
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(AuthController.SESSION_USER) != null) {
            return true;
        }
        if (isApi(request.getRequestURI())) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"detail\":\"authentication required\"}");
        } else {
            response.sendRedirect("/login.html");
        }
        return false;
    }

    private boolean isPublic(String uri) {
        return "/login.html".equals(uri)
            || "/api/auth/login".equals(uri)
            || "/api/auth/me".equals(uri)
            || "/api/health".equals(uri);
    }

    private boolean isApi(String uri) {
        return uri != null && uri.startsWith("/api/");
    }
}
