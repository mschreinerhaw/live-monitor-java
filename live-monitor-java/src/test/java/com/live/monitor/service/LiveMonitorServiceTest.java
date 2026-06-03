package com.live.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.live.monitor.alert.AlertService;
import com.live.monitor.dto.ServicePayload;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.MonitorServiceMapper;
import com.live.monitor.store.RocksDbHistoryRepository;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class LiveMonitorServiceTest {
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
        new TypeReference<Map<String, Object>>() {};

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsServiceWithSnakeCaseAvailabilityAlertConfigPersistedAndHydrated() throws Exception {
        ObjectMapper requestMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        ServicePayload payload = requestMapper.readValue(
            "{"
                + "\"service_name\":\"Checkout Health\","
                + "\"service_type\":\"web\","
                + "\"url\":\"https://example.com/health\","
                + "\"check_interval\":60,"
                + "\"check_timeout_seconds\":3,"
                + "\"service_alert_enabled\":false,"
                + "\"service_consecutive_failures\":5,"
                + "\"service_recover_successes\":4,"
                + "\"service_alert_cooldown_seconds\":120,"
                + "\"enabled\":true"
                + "}",
            ServicePayload.class
        );

        MonitorServiceMapper serviceMapper = mock(MonitorServiceMapper.class);
        AtomicReference<MonitorService> storedRow = new AtomicReference<MonitorService>();
        when(serviceMapper.insert(any(MonitorService.class))).thenAnswer(invocation -> {
            MonitorService inserted = invocation.getArgument(0);
            inserted.id = 42L;
            storedRow.set(databaseRow(inserted));
            return 1;
        });
        when(serviceMapper.findById(42L)).thenAnswer(invocation -> storedRow.get());

        LiveMonitorService service = new LiveMonitorService(
            serviceMapper,
            mock(RocksDbHistoryRepository.class),
            mock(MonitorRunnerService.class),
            mock(AlertService.class),
            objectMapper,
            mock(CryptoService.class),
            mock(TransactionTemplate.class)
        );

        MonitorService created = service.create(payload);
        Map<String, Object> config = objectMapper.readValue(storedRow.get().configJson, MAP_TYPE);

        assertFalse((Boolean) config.get("service_alert_enabled"));
        assertEquals(5, ((Number) config.get("service_consecutive_failures")).intValue());
        assertEquals(4, ((Number) config.get("service_recover_successes")).intValue());
        assertEquals(120, ((Number) config.get("service_alert_cooldown_seconds")).intValue());
        assertFalse(created.serviceAlertEnabled);
        assertEquals(5, created.serviceConsecutiveFailures.intValue());
        assertEquals(4, created.serviceRecoverSuccesses.intValue());
        assertEquals(120, created.serviceAlertCooldownSeconds.intValue());
    }

    private MonitorService databaseRow(MonitorService source) {
        MonitorService row = new MonitorService();
        row.id = source.id;
        row.serviceName = source.serviceName;
        row.serviceCategory = source.serviceCategory;
        row.serviceType = source.serviceType;
        row.endpoint = source.endpoint;
        row.host = source.host;
        row.port = source.port;
        row.checkMode = source.checkMode;
        row.checkTimeoutSeconds = source.checkTimeoutSeconds;
        row.checkInterval = source.checkInterval;
        row.configJson = source.configJson;
        row.secretConfigJson = source.secretConfigJson;
        row.enabled = source.enabled;
        return row;
    }
}
