package com.live.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.service.EmbedTokenService.EmbedToken;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class EmbedTokenServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsAndVerifiesAdminEmbedToken() {
        EmbedTokenService service = new EmbedTokenService(properties("secret-one"), objectMapper);

        EmbedToken created = service.createAdminToken(42L, "dashboard");
        EmbedToken verified = service.verify(created.token);

        assertNotNull(verified);
        assertEquals("admin", verified.user);
        assertEquals(42L, verified.serviceId.longValue());
        assertEquals("dashboard", verified.viewId);
    }

    @Test
    void rejectsTamperedToken() {
        EmbedTokenService service = new EmbedTokenService(properties("secret-one"), objectMapper);
        EmbedToken created = service.createAdminToken(null, null);

        assertNull(service.verify(created.token + "x"));
    }

    @Test
    void createsTokenWithDefaultTwentyFourHourTtl() {
        EmbedTokenService service = new EmbedTokenService(properties("secret-one"), objectMapper);

        EmbedToken created = service.createAdminToken(null, "dashboard");

        assertEquals(86400, created.expiresAt - created.issuedAt);
    }

    @Test
    void createsLongTermTokenWhenConfigured() {
        LiveMonitorProperties properties = properties("secret-one");
        properties.setEmbedTokenLongTerm(true);
        EmbedTokenService service = new EmbedTokenService(properties, objectMapper);

        EmbedToken created = service.createAdminToken(null, "dashboard");

        assertEquals(true, created.longTerm);
        assertEquals(253402300799L, created.expiresAt);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        EmbedTokenService service = new EmbedTokenService(properties("secret-one"), objectMapper);
        EmbedToken payload = new EmbedToken();
        payload.user = "admin";
        payload.issuedAt = Instant.now().minusSeconds(120).getEpochSecond();
        payload.expiresAt = Instant.now().minusSeconds(60).getEpochSecond();

        assertNull(service.verify(sign(payload, "secret-one")));
    }

    private LiveMonitorProperties properties(String secretKey) {
        LiveMonitorProperties properties = new LiveMonitorProperties();
        properties.setSecretKey(secretKey);
        return properties;
    }

    private String sign(EmbedToken payload, String secretKey) throws Exception {
        byte[] payloadBytes = objectMapper.writeValueAsBytes(payload);
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(("live-monitor-embed:" + secretKey).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String encodedSignature = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        return encodedPayload + "." + encodedSignature;
    }
}
