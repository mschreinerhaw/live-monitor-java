package com.live.monitor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SchemaMigrationService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SchemaMigrationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void migrate() {
        configureSqlite();

        if (!tableExists("monitor_service")) {
            return;
        }

        boolean legacySchema = hasColumn("monitor_service", "url")
            || hasColumn("monitor_service", "redis_password")
            || hasColumn("monitor_service", "zookeeper_check_mode");

        addColumnIfMissing("monitor_service", "service_category", "TEXT NOT NULL DEFAULT 'middleware'");
        addColumnIfMissing("monitor_service", "endpoint", "TEXT");
        addColumnIfMissing("monitor_service", "check_mode", "TEXT NOT NULL DEFAULT 'ping'");
        addColumnIfMissing("monitor_service", "check_command", "TEXT");
        addColumnIfMissing("monitor_service", "expected_result", "TEXT");
        addColumnIfMissing("monitor_service", "config_json", "TEXT NOT NULL DEFAULT '{}'");
        addColumnIfMissing("monitor_service", "secret_config_json", "TEXT NOT NULL DEFAULT '{}'");

        if (legacySchema) {
            migrateLegacyMonitorServiceRows();
        }

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_monitor_service_category_enabled ON monitor_service(service_category, enabled)");
        if (tableExists("host_process_config")) {
            addColumnIfMissing("host_process_config", "check_command", "TEXT");
            jdbcTemplate.update(
                "UPDATE host_process_config SET check_command = 'ps -ef | grep ' || quote(match_keyword) || ' | grep -v grep' " +
                    "WHERE (check_command IS NULL OR TRIM(check_command) = '') AND match_keyword IS NOT NULL AND TRIM(match_keyword) <> ''"
            );
        }
        if (tableExists("host_config")) {
            addColumnIfMissing("host_config", "monitor_service_id", "INTEGER");
            addColumnIfMissing("host_config", "cluster_name", "TEXT DEFAULT '服务器主机'");
            addColumnIfMissing("host_config", "cpu_threshold_percent", "REAL DEFAULT 85");
            addColumnIfMissing("host_config", "disk_threshold_percent", "REAL DEFAULT 85");
            addColumnIfMissing("host_config", "check_interval", "INTEGER DEFAULT 60");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS host_metric (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "host_id INTEGER NOT NULL, " +
                "cpu_usage_percent REAL, " +
                "load_average REAL, " +
                "memory_used_percent REAL, " +
                "disk_used_percent REAL, " +
                "checked_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                "FOREIGN KEY(host_id) REFERENCES host_config(id) ON DELETE CASCADE" +
                ")");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_host_metric_host_time ON host_metric(host_id, checked_at DESC)");
        }
    }

    private void configureSqlite() {
        jdbcTemplate.execute("PRAGMA busy_timeout = 5000");
        jdbcTemplate.execute("PRAGMA journal_mode = WAL");
        jdbcTemplate.execute("PRAGMA synchronous = NORMAL");
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            Integer.class,
            tableName
        );
        return count != null && count > 0;
    }

    private boolean hasColumn(String tableName, String columnName) {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")");
        for (Map<String, Object> column : columns) {
            if (columnName.equalsIgnoreCase(String.valueOf(column.get("name")))) {
                return true;
            }
        }
        return false;
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        if (!hasColumn(tableName, columnName)) {
            jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }

    private void migrateLegacyMonitorServiceRows() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT id, service_type, host, port, url, http_method, expected_status_code, response_keyword, " +
                "redis_username, redis_password, redis_cluster_mode, zookeeper_check_mode, " +
                "zookeeper_check_command, zookeeper_expected_nodes, config_json FROM monitor_service"
        );
        for (Map<String, Object> row : rows) {
            String currentConfig = stringValue(row.get("config_json"));
            if (StringUtils.hasText(currentConfig) && !"{}".equals(currentConfig.trim())) {
                continue;
            }
            updateLegacyMonitorServiceRow(row);
        }
    }

    private void updateLegacyMonitorServiceRow(Map<String, Object> row) {
        String type = normalizeType(stringValue(row.get("service_type")));
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        Map<String, Object> secretConfig = new LinkedHashMap<String, Object>();

        putIfNotNull(config, "host", stringValue(row.get("host")));
        putIfNotNull(config, "port", row.get("port"));

        String endpoint = endpointFromHostPort(stringValue(row.get("host")), intValue(row.get("port")));
        String checkMode = "ping";
        String checkCommand = null;
        String expectedResult = null;

        if ("web".equals(type) || "nginx".equals(type)) {
            endpoint = stringValue(row.get("url"));
            checkMode = "http";
            putIfNotNull(config, "url", endpoint);
            putIfNotNull(config, "http_method", stringValue(row.get("http_method")));
            putIfNotNull(config, "expected_status_code", row.get("expected_status_code"));
            putIfNotNull(config, "response_keyword", stringValue(row.get("response_keyword")));
            expectedResult = row.get("expected_status_code") == null ? null : String.valueOf(row.get("expected_status_code"));
        } else if ("redis".equals(type)) {
            checkMode = "redis_ping";
            putIfNotNull(config, "redis_username", stringValue(row.get("redis_username")));
            putIfNotNull(config, "redis_cluster_mode", row.get("redis_cluster_mode"));
            putIfNotNull(secretConfig, "redis_password", stringValue(row.get("redis_password")));
        } else if ("zookeeper".equals(type)) {
            checkMode = StringUtils.hasText(stringValue(row.get("zookeeper_check_mode")))
                ? stringValue(row.get("zookeeper_check_mode"))
                : "ruok";
            checkCommand = StringUtils.hasText(stringValue(row.get("zookeeper_check_command")))
                ? stringValue(row.get("zookeeper_check_command"))
                : "ruok";
            putIfNotNull(config, "zookeeper_check_mode", checkMode);
            putIfNotNull(config, "zookeeper_check_command", checkCommand);
            putIfNotNull(config, "zookeeper_expected_nodes", row.get("zookeeper_expected_nodes"));
        }

        jdbcTemplate.update(
            "UPDATE monitor_service SET service_category = ?, endpoint = ?, check_mode = ?, check_command = ?, " +
                "expected_result = ?, config_json = ?, secret_config_json = ? WHERE id = ?",
            inferCategory(type),
            endpoint,
            checkMode,
            checkCommand,
            expectedResult,
            toJson(config),
            toJson(secretConfig),
            row.get("id")
        );
    }

    private String inferCategory(String type) {
        if ("web".equals(type) || "http".equals(type) || "https".equals(type) || "nginx".equals(type)) {
            return "web";
        }
        if ("mysql".equals(type) || "postgresql".equals(type) || "postgres".equals(type)
            || "oracle".equals(type) || "sqlserver".equals(type) || "mongodb".equals(type)) {
            return "database";
        }
        if ("java".equals(type) || "jvm".equals(type) || "java_process".equals(type) || "process".equals(type)) {
            return "process";
        }
        if ("redis".equals(type) || "zookeeper".equals(type) || "kafka".equals(type) || "rabbitmq".equals(type)) {
            return "middleware";
        }
        return "custom";
    }

    private String endpointFromHostPort(String host, Integer port) {
        if (!StringUtils.hasText(host)) {
            return null;
        }
        return port == null ? host.trim() : host.trim() + ":" + port;
    }

    private String normalizeType(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null && (!(value instanceof String) || StringUtils.hasText((String) value))) {
            map.put(key, value);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            try {
                return Integer.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize monitor service config", ex);
        }
    }
}
