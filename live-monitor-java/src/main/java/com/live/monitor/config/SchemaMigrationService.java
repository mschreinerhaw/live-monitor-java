package com.live.monitor.config;

import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchemaMigrationService {
    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationService.class);

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseDialect databaseDialect;

    public SchemaMigrationService(JdbcTemplate jdbcTemplate, DatabaseDialect databaseDialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.databaseDialect = databaseDialect;
    }

    @PostConstruct
    public void migrate() {
        if (!tableExists("monitor_service")) {
            return;
        }
        createLoginAuditLogTable();
        createExternalMessageAuditTable();
        createEventDrivenAlertTables();
        widenColumnIfPresent("tuser", "password", "VARCHAR(512)");
        addColumnIfMissing("monitor_service", "service_category", "VARCHAR(64) NOT NULL DEFAULT 'middleware'");
        addColumnIfMissing("monitor_service", "monitor_reason", "VARCHAR(1000)");
        addColumnIfMissing("monitor_service", "endpoint", "VARCHAR(1024)");
        addColumnIfMissing("monitor_service", "check_mode", "VARCHAR(64) NOT NULL DEFAULT 'ping'");
        addColumnIfMissing("monitor_service", "check_command", "VARCHAR(100000)", "LONGTEXT");
        addColumnIfMissing("monitor_service", "expected_result", "VARCHAR(100000)", "LONGTEXT");
        addColumnIfMissing("monitor_service", "config_json", "VARCHAR(100000) NOT NULL DEFAULT '{}'", "LONGTEXT");
        addColumnIfMissing("monitor_service", "secret_config_json", "VARCHAR(100000) NOT NULL DEFAULT '{}'", "LONGTEXT");
        addColumnIfMissing("host_process_config", "check_command", "VARCHAR(100000)", "LONGTEXT");
        addColumnIfMissing("host_config", "monitor_service_id", "BIGINT");
        addColumnIfMissing("host_config", "cluster_name", "VARCHAR(255) DEFAULT 'Server Host'");
        addColumnIfMissing("host_config", "remark", "VARCHAR(1000)");
        addColumnIfMissing("host_config", "cpu_threshold_percent", "DOUBLE DEFAULT 85");
        addColumnIfMissing("host_config", "memory_threshold_percent", "DOUBLE DEFAULT 85");
        addColumnIfMissing("host_config", "disk_threshold_percent", "DOUBLE DEFAULT 85");
        addColumnIfMissing("host_config", "cpu_alert_enabled", "INT DEFAULT 1");
        addColumnIfMissing("host_config", "memory_alert_enabled", "INT DEFAULT 1");
        addColumnIfMissing("host_config", "disk_alert_enabled", "INT DEFAULT 1");
        addColumnIfMissing("host_config", "resource_alert_duration_enabled", "INT DEFAULT 1");
        addColumnIfMissing("host_config", "resource_recover_duration_enabled", "INT DEFAULT 1");
        addColumnIfMissing("host_config", "resource_alert_cooldown_enabled", "INT DEFAULT 1");
        addColumnIfMissing("host_config", "resource_alert_duration_seconds", "INT DEFAULT 180");
        addColumnIfMissing("host_config", "resource_recover_duration_seconds", "INT DEFAULT 180");
        addColumnIfMissing("host_config", "resource_alert_cooldown_seconds", "INT DEFAULT 600");
        addColumnIfMissing("host_config", "check_interval", "INT DEFAULT 60");
        addColumnIfMissing("host_latest_metric", "cpu_core_count", "INT");
        addColumnIfMissing("host_latest_metric", "memory_total_mb", "DOUBLE");
        addColumnIfMissing("host_latest_metric", "disk_mount_count", "INT");
        addColumnIfMissing("host_latest_metric", "disk_metrics_json", "VARCHAR(100000)", "LONGTEXT");
        addColumnIfMissing("host_latest_metric", "physical_disk_metrics_json", "VARCHAR(100000)", "LONGTEXT");
        addColumnIfMissing("monitor_check_event", "event_type", "VARCHAR(64)");
        migrateServiceAlertGroupCompositePrimaryKey();
        createCommonIndexes();
        ensureExternalMessageService();
    }

    private void ensureExternalMessageService() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM monitor_service WHERE service_type = 'external_message'",
            Integer.class
        );
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update(
            "INSERT INTO monitor_service (" +
                "service_name, service_category, service_type, cluster_name, monitor_reason, " +
                "check_mode, config_json, secret_config_json, check_interval, enabled" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "外部 REST 消息转发",
            "alert",
            "external_message",
            "告警中心",
            "接收外部 REST API 文本，并转发到本服务绑定的告警组",
            "manual",
            "{}",
            "{}",
            31536000,
            0
        );
        log.info("Created built-in external REST message forwarding service");
    }

    /**
     * The initial version of {@code service_alert_group} enforced a one-to-one binding by
     * declaring {@code PRIMARY KEY (service_id)}. We now support one service being bound to
     * multiple alert groups, so this migration relaxes the PK to the composite
     * {@code (service_id, group_id)} idempotently on both MySQL and H2.
     */
    private void migrateServiceAlertGroupCompositePrimaryKey() {
        if (!tableExists("service_alert_group")) {
            return;
        }
        try {
            if (hasServiceAlertGroupCompositePrimaryKey()) {
                return; // Already composite.
            }
        } catch (Exception ignored) {
            // Fall through and attempt the migration when metadata inspection is unavailable.
        }
        try {
            if (databaseDialect.isMysql()) {
                // Keep a usable service_id index throughout the ALTER. MySQL can reject two
                // separate statements because the first DROP temporarily breaks the FK index.
                jdbcTemplate.execute(
                    "ALTER TABLE service_alert_group " +
                        "DROP PRIMARY KEY, ADD PRIMARY KEY (service_id, group_id)"
                );
            } else {
                rebuildH2ServiceAlertGroupTable();
            }
            if (!hasServiceAlertGroupCompositePrimaryKey()) {
                throw new IllegalStateException("service_alert_group primary key is not (service_id, group_id)");
            }
            log.info("Migrated service_alert_group PRIMARY KEY to (service_id, group_id)");
        } catch (Exception ex) {
            throw new IllegalStateException(
                "Failed to enable multiple alert groups: service_alert_group requires composite primary key",
                ex
            );
        }
    }

    private boolean hasServiceAlertGroupCompositePrimaryKey() {
        java.util.List<String> columns = databaseDialect.isMysql()
            ? jdbcTemplate.queryForList(
                "SELECT LOWER(column_name) FROM information_schema.key_column_usage " +
                    "WHERE table_schema = SCHEMA() AND LOWER(table_name) = 'service_alert_group' " +
                    "AND LOWER(constraint_name) = 'primary' ORDER BY ordinal_position",
                String.class
            )
            : jdbcTemplate.queryForList(
                "SELECT LOWER(k.column_name) FROM information_schema.key_column_usage k " +
                    "JOIN information_schema.table_constraints c " +
                    "ON c.constraint_catalog = k.constraint_catalog " +
                    "AND c.constraint_schema = k.constraint_schema " +
                    "AND c.constraint_name = k.constraint_name " +
                    "WHERE k.table_schema = SCHEMA() AND LOWER(k.table_name) = 'service_alert_group' " +
                    "AND c.constraint_type = 'PRIMARY KEY' ORDER BY k.ordinal_position",
                String.class
            );
        return columns.equals(java.util.Arrays.asList("service_id", "group_id"));
    }

    private void rebuildH2ServiceAlertGroupTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS service_alert_group_migration");
        jdbcTemplate.execute("CREATE TABLE service_alert_group_migration (" +
            "service_id BIGINT NOT NULL, " +
            "group_id BIGINT NOT NULL, " +
            "PRIMARY KEY (service_id, group_id), " +
            "FOREIGN KEY(service_id) REFERENCES monitor_service(id) ON DELETE CASCADE, " +
            "FOREIGN KEY(group_id) REFERENCES alert_group(id) ON DELETE CASCADE)");
        jdbcTemplate.execute("INSERT INTO service_alert_group_migration (service_id, group_id) " +
            "SELECT DISTINCT service_id, group_id FROM service_alert_group");
        jdbcTemplate.execute("DROP TABLE service_alert_group");
        jdbcTemplate.execute("ALTER TABLE service_alert_group_migration RENAME TO service_alert_group");
    }

    private void createLoginAuditLogTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS login_audit_log (" +
            identityColumn("id") + ", " +
            "user_id VARCHAR(50) NOT NULL, " +
            "user_name VARCHAR(30), " +
            "action VARCHAR(20) NOT NULL, " +
            "ip_address VARCHAR(64), " +
            "event_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
            primaryKeySuffix() + ")");
        createIndexIfMissing("login_audit_log", "idx_login_audit_log_time", "event_time DESC");
    }

    private void createExternalMessageAuditTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS external_message_audit (" +
            identityColumn("id") + ", " +
            "service_id BIGINT, " +
            "client_ip VARCHAR(64), " +
            "content_type VARCHAR(255), " +
            "message_summary VARCHAR(1000), " +
            "message_length INT DEFAULT 0, " +
            "request_status VARCHAR(32) NOT NULL, " +
            "delivery_count INT DEFAULT 0, " +
            "success_count INT DEFAULT 0, " +
            "failed_count INT DEFAULT 0, " +
            "detail VARCHAR(2000), " +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
            primaryKeySuffix() + ")");
        createIndexIfMissing("external_message_audit", "idx_external_message_audit_time", "created_at DESC");
        createIndexIfMissing("external_message_audit", "idx_external_message_audit_status", "request_status, created_at DESC");
    }

    private void createEventDrivenAlertTables() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS monitor_check_event (" +
            identityColumn("id") + ", " +
            "service_id BIGINT NOT NULL, " +
            "status VARCHAR(32) NOT NULL, " +
            "response_time_ms INT, " +
            "message " + largeTextType() + ", " +
            "event_type VARCHAR(64), " +
            "alert_type VARCHAR(64), " +
            "checked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
            "consumed INT DEFAULT 0, " +
            "consumed_at TIMESTAMP, " +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
            primaryKeySuffix() + ")");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS alert_state (" +
            "service_id BIGINT NOT NULL, " +
            "alert_key VARCHAR(128) NOT NULL, " +
            "state VARCHAR(32) NOT NULL, " +
            "fail_count INT DEFAULT 0, " +
            "recover_count INT DEFAULT 0, " +
            "active_policy_id BIGINT, " +
            "active_trigger_type VARCHAR(64), " +
            "last_status VARCHAR(32), " +
            "last_message " + largeTextType() + ", " +
            "last_event_at TIMESTAMP, " +
            "last_alert_at TIMESTAMP, " +
            "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
            "PRIMARY KEY (service_id, alert_key))");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS alert_notify_record (" +
            identityColumn("id") + ", " +
            "service_id BIGINT NOT NULL, " +
            "alert_key VARCHAR(128) NOT NULL, " +
            "alert_record_id BIGINT, " +
            "alert_type VARCHAR(64), " +
            "notify_status VARCHAR(32), " +
            "notify_message " + largeTextType() + ", " +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
            primaryKeySuffix() + ")");
        createIndexIfMissing("monitor_check_event", "idx_monitor_check_event_service_time", "service_id, checked_at DESC");
        createIndexIfMissing("monitor_check_event", "idx_monitor_check_event_consumed", "consumed, id");
        createIndexIfMissing("alert_notify_record", "idx_alert_notify_record_service_time", "service_id, created_at DESC");
    }

    private void createCommonIndexes() {
        createIndexIfMissing("monitor_service", "idx_monitor_service_type_enabled", "service_type, enabled");
        createIndexIfMissing("monitor_service", "idx_monitor_service_category_enabled", "service_category, enabled");
        createIndexIfMissing("service_latest_status", "idx_service_latest_status_time", "checked_at DESC");
        createIndexIfMissing("service_alert_group", "idx_service_alert_group_group", "group_id");
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = SCHEMA() AND LOWER(table_name) = ?",
            Integer.class,
            tableName.toLowerCase()
        );
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema = SCHEMA() AND LOWER(table_name) = ? AND LOWER(column_name) = ?",
            Integer.class,
            tableName.toLowerCase(),
            columnName.toLowerCase()
        );
        return count != null && count > 0;
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        addColumnIfMissing(tableName, columnName, definition, definition);
    }

    private void addColumnIfMissing(String tableName, String columnName, String h2Definition, String mysqlDefinition) {
        if (tableExists(tableName) && !columnExists(tableName, columnName)) {
            String definition = databaseDialect.isMysql() ? mysqlDefinition : h2Definition;
            jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }

    private void widenColumnIfPresent(String tableName, String columnName, String definition) {
        if (tableExists(tableName) && columnExists(tableName, columnName)) {
            String action = databaseDialect.isMysql() ? " MODIFY COLUMN " : " ALTER COLUMN ";
            jdbcTemplate.execute("ALTER TABLE " + tableName + action + columnName + " " + definition);
        }
    }

    private String identityColumn(String columnName) {
        if (databaseDialect.isMysql()) {
            return columnName + " BIGINT NOT NULL AUTO_INCREMENT";
        }
        return columnName + " BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY";
    }

    private String primaryKeySuffix() {
        return databaseDialect.isMysql() ? ", PRIMARY KEY (id)" : "";
    }

    private String largeTextType() {
        return databaseDialect.isMysql() ? "LONGTEXT" : "VARCHAR(100000)";
    }

    private void createIndexIfMissing(String tableName, String indexName, String columns) {
        if (!tableExists(tableName) || indexExists(tableName, indexName)) {
            return;
        }
        try {
            jdbcTemplate.execute("CREATE INDEX " + indexName + " ON " + tableName + "(" + columns + ")");
        } catch (Exception ex) {
            log.warn("Failed to create index {} on {}: {}", indexName, tableName, ex.getMessage());
        }
    }

    private boolean indexExists(String tableName, String indexName) {
        String sql = databaseDialect.isMysql()
            ? "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = SCHEMA() AND LOWER(table_name) = ? AND LOWER(index_name) = ?"
            : "SELECT COUNT(*) FROM information_schema.indexes WHERE table_schema = SCHEMA() AND LOWER(table_name) = ? AND LOWER(index_name) = ?";
        Integer count = jdbcTemplate.queryForObject(
            sql,
            Integer.class,
            tableName.toLowerCase(),
            indexName.toLowerCase()
        );
        return count != null && count > 0;
    }
}
