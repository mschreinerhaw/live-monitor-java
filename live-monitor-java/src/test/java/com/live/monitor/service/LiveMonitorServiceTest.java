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
import com.live.monitor.dto.CheckResult;
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
                + "\"api_assertion_expression\":\"contains(\\\"success\\\") && json(\\\"$.code\\\") == 0\","
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
        assertEquals("contains(\"success\") && json(\"$.code\") == 0", config.get("api_assertion_expression"));
        assertFalse(created.serviceAlertEnabled);
        assertEquals(5, created.serviceConsecutiveFailures.intValue());
        assertEquals(4, created.serviceRecoverSuccesses.intValue());
        assertEquals(120, created.serviceAlertCooldownSeconds.intValue());
        assertEquals("contains(\"success\") && json(\"$.code\") == 0", created.apiAssertionExpression);
    }

    @Test
    void createsDatabaseServiceWithSelectedAssertionFieldsPersistedAndHydrated() throws Exception {
        ObjectMapper requestMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        ServicePayload payload = requestMapper.readValue(
            "{"
                + "\"service_name\":\"Trade SQL\","
                + "\"service_type\":\"jdbc\","
                + "\"jdbc_driver_class\":\"org.h2.Driver\","
                + "\"jdbc_url\":\"jdbc:h2:mem:db-fields\","
                + "\"database_query\":\"SELECT 0 AS code, 'OK' AS status\","
                + "\"database_assertion_fields\":[\"code\",\"status\"],"
                + "\"api_assertion_expression\":\"json(\\\"$.rows[0].code\\\") == 0\","
                + "\"check_interval\":60,"
                + "\"check_timeout_seconds\":3,"
                + "\"enabled\":true"
                + "}",
            ServicePayload.class
        );

        MonitorServiceMapper serviceMapper = mock(MonitorServiceMapper.class);
        AtomicReference<MonitorService> storedRow = new AtomicReference<MonitorService>();
        when(serviceMapper.insert(any(MonitorService.class))).thenAnswer(invocation -> {
            MonitorService inserted = invocation.getArgument(0);
            inserted.id = 43L;
            storedRow.set(databaseRow(inserted));
            return 1;
        });
        when(serviceMapper.findById(43L)).thenAnswer(invocation -> storedRow.get());

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

        assertEquals("json(\"$.rows[0].code\") == 0", config.get("api_assertion_expression"));
        assertEquals(java.util.Arrays.asList("code", "status"), config.get("database_assertion_fields"));
        assertEquals(java.util.Arrays.asList("code", "status"), created.databaseAssertionFields);
    }

    @Test
    void testingExistingDatabaseServiceReusesSavedPasswordWhenPasswordOmitted() throws Exception {
        ObjectMapper requestMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        ServicePayload payload = requestMapper.readValue(
            "{"
                + "\"service_name\":\"Trade SQL\","
                + "\"service_type\":\"jdbc\","
                + "\"jdbc_driver_class\":\"org.h2.Driver\","
                + "\"jdbc_url\":\"jdbc:h2:mem:db-fields\","
                + "\"database_username\":\"monitor\","
                + "\"database_query\":\"SELECT 1\","
                + "\"check_interval\":60,"
                + "\"check_timeout_seconds\":3,"
                + "\"enabled\":true"
                + "}",
            ServicePayload.class
        );

        MonitorService existing = new MonitorService();
        existing.id = 45L;
        existing.serviceName = "Trade SQL";
        existing.serviceType = "jdbc";
        existing.checkInterval = 60;
        existing.checkTimeoutSeconds = 3D;
        existing.configJson = "{"
            + "\"database_username\":\"monitor\","
            + "\"jdbc_driver_class\":\"org.h2.Driver\","
            + "\"jdbc_url\":\"jdbc:h2:mem:db-fields\""
            + "}";
        existing.secretConfigJson = "{\"database_password\":\"saved-secret\"}";
        existing.enabled = true;

        MonitorServiceMapper serviceMapper = mock(MonitorServiceMapper.class);
        when(serviceMapper.findById(45L)).thenReturn(existing);
        MonitorRunnerService runnerService = mock(MonitorRunnerService.class);
        AtomicReference<MonitorService> checkedService = new AtomicReference<MonitorService>();
        when(runnerService.run(any(MonitorService.class))).thenAnswer(invocation -> {
            checkedService.set(invocation.getArgument(0));
            return new CheckResult("UP", 1, "ok");
        });

        LiveMonitorService service = new LiveMonitorService(
            serviceMapper,
            mock(RocksDbHistoryRepository.class),
            runnerService,
            mock(AlertService.class),
            objectMapper,
            mock(CryptoService.class),
            mock(TransactionTemplate.class)
        );

        service.test(45L, payload);

        assertEquals("saved-secret", checkedService.get().databasePassword);
    }

    @Test
    void testingDatabaseServiceCanReuseReferencedConnectionInfo() throws Exception {
        ObjectMapper requestMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        ServicePayload payload = requestMapper.readValue(
            "{"
                + "\"service_name\":\"Trade Orders SQL\","
                + "\"service_type\":\"jdbc\","
                + "\"database_connection_service_id\":46,"
                + "\"database_query\":\"SELECT count(*) FROM orders\","
                + "\"check_interval\":60,"
                + "\"check_timeout_seconds\":3,"
                + "\"enabled\":true"
                + "}",
            ServicePayload.class
        );

        MonitorService source = new MonitorService();
        source.id = 46L;
        source.serviceName = "Trade DB Connection";
        source.serviceType = "jdbc";
        source.checkInterval = 60;
        source.checkTimeoutSeconds = 3D;
        source.configJson = "{"
            + "\"database_username\":\"monitor\","
            + "\"jdbc_driver_class\":\"org.h2.Driver\","
            + "\"jdbc_url\":\"jdbc:h2:mem:trade\""
            + "}";
        source.secretConfigJson = "{\"database_password\":\"source-secret\"}";
        source.enabled = true;

        MonitorServiceMapper serviceMapper = mock(MonitorServiceMapper.class);
        when(serviceMapper.findById(46L)).thenReturn(source);
        MonitorRunnerService runnerService = mock(MonitorRunnerService.class);
        AtomicReference<MonitorService> checkedService = new AtomicReference<MonitorService>();
        when(runnerService.run(any(MonitorService.class))).thenAnswer(invocation -> {
            checkedService.set(invocation.getArgument(0));
            return new CheckResult("UP", 1, "ok");
        });

        LiveMonitorService service = new LiveMonitorService(
            serviceMapper,
            mock(RocksDbHistoryRepository.class),
            runnerService,
            mock(AlertService.class),
            objectMapper,
            mock(CryptoService.class),
            mock(TransactionTemplate.class)
        );

        service.test(payload);

        assertEquals(Long.valueOf(46L), checkedService.get().databaseConnectionServiceId);
        assertEquals("org.h2.Driver", checkedService.get().jdbcDriverClass);
        assertEquals("jdbc:h2:mem:trade", checkedService.get().jdbcUrl);
        assertEquals("monitor", checkedService.get().databaseUsername);
        assertEquals("source-secret", checkedService.get().databasePassword);
        assertEquals("SELECT count(*) FROM orders", checkedService.get().databaseQuery);
    }

    @Test
    void createsApiServiceAsSeparateHttpRequestType() throws Exception {
        ObjectMapper requestMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        ServicePayload payload = requestMapper.readValue(
            "{"
                + "\"service_name\":\"Order API Health\","
                + "\"service_type\":\"api\","
                + "\"url\":\"https://api.example.com/v1/health\","
                + "\"http_method\":\"POST\","
                + "\"expected_status_code\":200,"
                + "\"api_headers\":[{\"name\":\"X-App-Code\",\"value\":\"trade\"}],"
                + "\"api_content_type\":\"application/json\","
                + "\"api_request_body\":\"{\\\"custNo\\\":\\\"10001\\\"}\","
                + "\"api_auth_type\":\"custom_header\","
                + "\"api_auth_app_id\":\"order-monitor\","
                + "\"api_response_time_ms\":3000,"
                + "\"api_json_assertions\":\"$.code == 0\","
                + "\"api_text_assertion_mode\":\"contains\","
                + "\"api_text_assertion_value\":\"success\","
                + "\"api_assertion_expression\":\"json(\\\"$.code\\\") == 0 && responseMs() < 3000\","
                + "\"check_interval\":60,"
                + "\"check_timeout_seconds\":3,"
                + "\"enabled\":true"
                + "}",
            ServicePayload.class
        );

        MonitorServiceMapper serviceMapper = mock(MonitorServiceMapper.class);
        AtomicReference<MonitorService> storedRow = new AtomicReference<MonitorService>();
        when(serviceMapper.insert(any(MonitorService.class))).thenAnswer(invocation -> {
            MonitorService inserted = invocation.getArgument(0);
            inserted.id = 44L;
            storedRow.set(databaseRow(inserted));
            return 1;
        });
        when(serviceMapper.findById(44L)).thenAnswer(invocation -> storedRow.get());

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

        assertEquals("api", storedRow.get().serviceType);
        assertEquals("api", storedRow.get().serviceCategory);
        assertEquals("api", created.serviceType);
        assertEquals("api", created.serviceCategory);
        assertEquals("https://api.example.com/v1/health", config.get("url"));
        assertEquals("POST", config.get("http_method"));
        assertEquals(200, ((Number) config.get("expected_status_code")).intValue());
        assertEquals("application/json", config.get("api_content_type"));
        assertEquals("{\"custNo\":\"10001\"}", config.get("api_request_body"));
        assertEquals("custom_header", config.get("api_auth_type"));
        assertEquals("order-monitor", config.get("api_auth_app_id"));
        assertEquals(3000, ((Number) config.get("api_response_time_ms")).intValue());
        assertEquals("$.code == 0", config.get("api_json_assertions"));
        assertEquals("contains", config.get("api_text_assertion_mode"));
        assertEquals("success", config.get("api_text_assertion_value"));
        assertEquals("json(\"$.code\") == 0 && responseMs() < 3000", config.get("api_assertion_expression"));
        assertEquals("application/json", created.apiContentType);
        assertEquals(1, created.apiHeaders.size());
        assertEquals("X-App-Code", created.apiHeaders.get(0).get("name"));
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
