package com.live.monitor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
            .addPathPatterns(
                "/",
                "/dashboard",
                "/services/**",
                "/alerts/**",
                "/api/**",
                "/index.html",
                "/service.html",
                "/add_service.html",
                "/alert_settings.html"
            )
            .excludePathPatterns("/api/auth/login", "/api/auth/me", "/api/health", "/login.html");
    }
}
