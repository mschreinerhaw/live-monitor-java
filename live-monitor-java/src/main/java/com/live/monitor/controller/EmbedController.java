package com.live.monitor.controller;

import com.live.monitor.entity.TUser;
import com.live.monitor.service.EmbedTokenService;
import com.live.monitor.service.EmbedTokenService.EmbedToken;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class EmbedController {
    private final EmbedTokenService embedTokenService;

    public EmbedController(EmbedTokenService embedTokenService) {
        this.embedTokenService = embedTokenService;
    }

    @PostMapping("/api/embed-token")
    public Map<String, Object> createEmbedToken(@RequestBody(required = false) Map<String, Object> payload, HttpSession session) {
        Object sessionUser = session.getAttribute(AuthController.SESSION_USER);
        if (!(sessionUser instanceof TUser) || !isAdmin((TUser) sessionUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin user required");
        }

        String targetPath = normalizeTargetPath(stringPayload(payload, "target_path", "targetPath", "path"));
        EmbedToken token = embedTokenService.createAdminToken(
            longPayload(payload, "service_id", "serviceId"),
            stringPayload(payload, "view_id", "viewId")
        );
        String url = buildAuthUrl(targetPath, token.token);

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("url", url);
        result.put("token", token.token);
        result.put("expires_at", Instant.ofEpochSecond(token.expiresAt).toString());
        result.put("expires_at_epoch", token.expiresAt);
        result.put("long_term", token.longTerm);
        if (!token.longTerm) {
            result.put("ttl_seconds", token.expiresAt - token.issuedAt);
        }
        return result;
    }

    private String normalizeTargetPath(String targetPath) {
        String value = StringUtils.hasText(targetPath) ? targetPath.trim() : "/dashboard";
        if (!value.startsWith("/") || value.startsWith("//") || value.startsWith("/api/") || value.contains("\r") || value.contains("\n")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid target path");
        }
        return value;
    }

    private String buildAuthUrl(String targetPath, String token) {
        String hash = "";
        String beforeHash = targetPath;
        int hashIndex = targetPath.indexOf('#');
        if (hashIndex >= 0) {
            beforeHash = targetPath.substring(0, hashIndex);
            hash = targetPath.substring(hashIndex);
        }
        String path = beforeHash;
        String query = "";
        int queryIndex = beforeHash.indexOf('?');
        if (queryIndex >= 0) {
            path = beforeHash.substring(0, queryIndex);
            query = beforeHash.substring(queryIndex + 1);
        }
        UriComponentsBuilder builder = ServletUriComponentsBuilder.fromCurrentContextPath().path(path);
        if (StringUtils.hasText(query)) {
            builder.query(query);
            builder.replaceQueryParam("token");
        }
        return builder.queryParam("token", token).build().toUriString() + hash;
    }

    private boolean isAdmin(TUser user) {
        return user != null && "admin".equalsIgnoreCase(user.userId);
    }

    private Long longPayload(Map<String, Object> payload, String... keys) {
        Object value = payloadValue(payload, keys);
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid service id");
        }
    }

    private String stringPayload(Map<String, Object> payload, String... keys) {
        Object value = payloadValue(payload, keys);
        return value == null ? null : String.valueOf(value);
    }

    private Object payloadValue(Map<String, Object> payload, String... keys) {
        if (payload == null) {
            return null;
        }
        for (String key : keys) {
            if (payload.containsKey(key)) {
                return payload.get(key);
            }
        }
        return null;
    }
}
