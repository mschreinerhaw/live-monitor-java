package com.live.monitor.config;

import javax.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchemaMigrationService {
    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        if (!tableExists("monitor_service")) {
            return;
        }
        addColumnIfMissing("monitor_service", "service_category", "VARCHAR(64) NOT NULL DEFAULT 'middleware'");
        addColumnIfMissing("monitor_service", "endpoint", "VARCHAR(1024)");
        addColumnIfMissing("monitor_service", "check_mode", "VARCHAR(64) NOT NULL DEFAULT 'ping'");
        addColumnIfMissing("monitor_service", "check_command", "VARCHAR(100000)");
        addColumnIfMissing("monitor_service", "expected_result", "VARCHAR(100000)");
        addColumnIfMissing("monitor_service", "config_json", "VARCHAR(100000) NOT NULL DEFAULT '{}'");
        addColumnIfMissing("monitor_service", "secret_config_json", "VARCHAR(100000) NOT NULL DEFAULT '{}'");
        addColumnIfMissing("host_process_config", "check_command", "VARCHAR(100000)");
        addColumnIfMissing("host_config", "monitor_service_id", "BIGINT");
        addColumnIfMissing("host_config", "cluster_name", "VARCHAR(255) DEFAULT 'Server Host'");
        addColumnIfMissing("host_config", "cpu_threshold_percent", "DOUBLE DEFAULT 85");
        addColumnIfMissing("host_config", "disk_threshold_percent", "DOUBLE DEFAULT 85");
        addColumnIfMissing("host_config", "check_interval", "INT DEFAULT 60");
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

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        if (tableExists(tableName) && !columnExists(tableName, columnName)) {
            jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }
}
