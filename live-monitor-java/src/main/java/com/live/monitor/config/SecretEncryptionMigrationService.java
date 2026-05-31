package com.live.monitor.config;

import com.live.monitor.service.CryptoService;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@DependsOn("schemaMigrationService")
public class SecretEncryptionMigrationService {
    private final JdbcTemplate jdbcTemplate;
    private final CryptoService cryptoService;

    public SecretEncryptionMigrationService(JdbcTemplate jdbcTemplate, CryptoService cryptoService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cryptoService = cryptoService;
    }

    @PostConstruct
    public void migrate() {
        encryptUserPasswords();
        encryptMonitorServiceSecrets();
        encryptAlertChannelConfigs();
    }

    private void encryptUserPasswords() {
        if (!tableExists("tuser")) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, password FROM tuser");
        for (Map<String, Object> row : rows) {
            String password = stringValue(rowValue(row, "password"));
            if (shouldEncrypt(password)) {
                jdbcTemplate.update("UPDATE tuser SET password = ? WHERE id = ?", cryptoService.encrypt(password), rowValue(row, "id"));
            }
        }
    }

    private void encryptMonitorServiceSecrets() {
        if (!tableExists("monitor_service") || !columnExists("monitor_service", "secret_config_json")) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, secret_config_json FROM monitor_service");
        for (Map<String, Object> row : rows) {
            String secretConfig = stringValue(rowValue(row, "secret_config_json"));
            if (shouldEncryptJson(secretConfig)) {
                jdbcTemplate.update(
                    "UPDATE monitor_service SET secret_config_json = ? WHERE id = ?",
                    cryptoService.encrypt(secretConfig),
                    rowValue(row, "id")
                );
            }
        }
    }

    private void encryptAlertChannelConfigs() {
        if (!tableExists("alert_channel") || !columnExists("alert_channel", "config_json")) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, config_json FROM alert_channel");
        for (Map<String, Object> row : rows) {
            String config = stringValue(rowValue(row, "config_json"));
            if (shouldEncryptJson(config)) {
                jdbcTemplate.update(
                    "UPDATE alert_channel SET config_json = ? WHERE id = ?",
                    cryptoService.encrypt(config),
                    rowValue(row, "id")
                );
            }
        }
    }

    private boolean shouldEncrypt(String value) {
        return StringUtils.hasText(value) && !cryptoService.isEncrypted(value);
    }

    private boolean shouldEncryptJson(String value) {
        if (!shouldEncrypt(value)) {
            return false;
        }
        String trimmed = value.trim();
        return !trimmed.equals("{}") && !trimmed.equals("[]");
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Object rowValue(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        if (row.containsKey(key.toUpperCase())) {
            return row.get(key.toUpperCase());
        }
        return row.get(key.toLowerCase());
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = SCHEMA() AND table_name = ?",
            Integer.class,
            tableName.toLowerCase()
        );
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema = SCHEMA() AND table_name = ? AND column_name = ?",
            Integer.class,
            tableName.toLowerCase(),
            columnName.toLowerCase()
        );
        return count != null && count > 0;
    }
}
