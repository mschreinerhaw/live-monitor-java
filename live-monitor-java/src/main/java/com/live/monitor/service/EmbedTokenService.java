package com.live.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.config.LiveMonitorProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EmbedTokenService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int DEFAULT_TTL_SECONDS = 86400;
    private static final long LONG_TERM_EXPIRES_AT = 253402300799L;

    private final ObjectMapper objectMapper;
    private final byte[] signingKey;
    private final boolean longTerm;

    public EmbedTokenService(LiveMonitorProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.signingKey = ("live-monitor-embed:" + properties.getSecretKey()).getBytes(StandardCharsets.UTF_8);
        this.longTerm = properties.isEmbedTokenLongTerm();
    }

    public EmbedToken createAdminToken(Long serviceId, String viewId) {
        long now = Instant.now().getEpochSecond();
        EmbedToken payload = new EmbedToken();
        payload.user = "admin";
        payload.issuedAt = now;
        payload.longTerm = longTerm;
        payload.expiresAt = longTerm ? LONG_TERM_EXPIRES_AT : now + DEFAULT_TTL_SECONDS;
        payload.serviceId = serviceId;
        payload.viewId = StringUtils.hasText(viewId) ? viewId.trim() : null;
        payload.token = sign(payload);
        return payload;
    }

    public boolean isLongTerm() {
        return longTerm;
    }

    public EmbedToken verify(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || !StringUtils.hasText(parts[0]) || !StringUtils.hasText(parts[1])) {
                return null;
            }
            byte[] expected = hmac(parts[0].getBytes(StandardCharsets.UTF_8));
            byte[] actual = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(expected, actual)) {
                return null;
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
            EmbedToken payload = objectMapper.readValue(payloadBytes, EmbedToken.class);
            if (!"admin".equalsIgnoreCase(payload.user) || payload.expiresAt < Instant.now().getEpochSecond()) {
                return null;
            }
            payload.token = token;
            return payload;
        } catch (Exception ex) {
            return null;
        }
    }

    private String sign(EmbedToken payload) {
        try {
            byte[] payloadBytes = objectMapper.writeValueAsBytes(payload);
            String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes);
            String encodedSignature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(encodedPayload.getBytes(StandardCharsets.UTF_8)));
            return encodedPayload + "." + encodedSignature;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create embed token", ex);
        }
    }

    private byte[] hmac(byte[] value) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
        return mac.doFinal(value);
    }

    public static class EmbedToken {
        public String user;
        public long issuedAt;
        public long expiresAt;
        public boolean longTerm;
        public Long serviceId;
        public String viewId;
        public String token;
    }
}
