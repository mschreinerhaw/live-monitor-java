package com.live.monitor.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaMigrationServiceTest {
    @Test
    void createsExternalProjectTablesAndMatchesMostSpecificKeyword() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:external-project-routing;MODE=LEGACY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE alert_group (id BIGINT PRIMARY KEY)");
        jdbcTemplate.update("INSERT INTO alert_group (id) VALUES (10)");
        SchemaMigrationService migrationService = new SchemaMigrationService(
            jdbcTemplate,
            new DatabaseDialect(jdbcTemplate)
        );
        Method createTables = SchemaMigrationService.class.getDeclaredMethod("createExternalProjectTables");
        createTables.setAccessible(true);
        createTables.invoke(migrationService);
        jdbcTemplate.update(
            "INSERT INTO external_project (project_name, match_keyword, enabled) VALUES (?, ?, 1)",
            "IDS", "IDS"
        );
        jdbcTemplate.update(
            "INSERT INTO external_project (project_name, match_keyword, enabled) VALUES (?, ?, 1)",
            "IDS3.0-ZYZQ", "IDS3.0-ZYZQ"
        );

        String matched = jdbcTemplate.queryForObject(
            "SELECT project_name FROM external_project WHERE enabled = 1 " +
                "AND ? LIKE CONCAT('%', match_keyword, '%') " +
                "ORDER BY CHAR_LENGTH(match_keyword) DESC, id LIMIT 1",
            String.class,
            "项目名称:IDS3.0-ZYZQ,流程名称:告警测试"
        );

        assertEquals("IDS3.0-ZYZQ", matched);
    }

    @Test
    void enablesExternalMessageServiceOnlyOnce() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:external-message-default;MODE=LEGACY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE monitor_service (id BIGINT PRIMARY KEY, service_type VARCHAR(64), enabled INT)");
        jdbcTemplate.execute("CREATE TABLE migration_marker (id VARCHAR(128) PRIMARY KEY, completed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.update("INSERT INTO monitor_service (id, service_type, enabled) VALUES (1, 'external_message', 0)");
        SchemaMigrationService migrationService = new SchemaMigrationService(
            jdbcTemplate,
            new DatabaseDialect(jdbcTemplate)
        );
        Method ensure = SchemaMigrationService.class.getDeclaredMethod("ensureExternalMessageService");
        ensure.setAccessible(true);

        ensure.invoke(migrationService);
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT enabled FROM monitor_service WHERE id = 1", Integer.class
        ));

        jdbcTemplate.update("UPDATE monitor_service SET enabled = 0 WHERE id = 1");
        ensure.invoke(migrationService);
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT enabled FROM monitor_service WHERE id = 1", Integer.class
        ));
    }

    @Test
    void migratesLegacyServiceAlertGroupPrimaryKeyToComposite() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:schema-migration;MODE=LEGACY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE monitor_service (id BIGINT PRIMARY KEY)");
        jdbcTemplate.execute("CREATE TABLE alert_group (id BIGINT PRIMARY KEY)");
        jdbcTemplate.execute("CREATE TABLE service_alert_group (" +
            "service_id BIGINT PRIMARY KEY, group_id BIGINT NOT NULL, " +
            "FOREIGN KEY(service_id) REFERENCES monitor_service(id), " +
            "FOREIGN KEY(group_id) REFERENCES alert_group(id))");
        jdbcTemplate.update("INSERT INTO monitor_service (id) VALUES (1)");
        jdbcTemplate.update("INSERT INTO alert_group (id) VALUES (10), (11)");
        jdbcTemplate.update("INSERT INTO service_alert_group (service_id, group_id) VALUES (1, 10)");

        SchemaMigrationService migrationService = new SchemaMigrationService(
            jdbcTemplate,
            new DatabaseDialect(jdbcTemplate)
        );
        Method migration = SchemaMigrationService.class.getDeclaredMethod(
            "migrateServiceAlertGroupCompositePrimaryKey"
        );
        migration.setAccessible(true);
        migration.invoke(migrationService);

        jdbcTemplate.update("INSERT INTO service_alert_group (service_id, group_id) VALUES (1, 11)");
        Integer bindingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM service_alert_group WHERE service_id = 1",
            Integer.class
        );
        Integer primaryKeyColumnCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.key_column_usage k " +
                "JOIN information_schema.table_constraints c " +
                "ON c.constraint_catalog = k.constraint_catalog " +
                "AND c.constraint_schema = k.constraint_schema " +
                "AND c.constraint_name = k.constraint_name " +
                "WHERE LOWER(k.table_name) = 'service_alert_group' AND c.constraint_type = 'PRIMARY KEY'",
            Integer.class
        );
        List<String> primaryKeyColumns = jdbcTemplate.queryForList(
            "SELECT LOWER(k.column_name) FROM information_schema.key_column_usage k " +
                "JOIN information_schema.table_constraints c " +
                "ON c.constraint_catalog = k.constraint_catalog " +
                "AND c.constraint_schema = k.constraint_schema " +
                "AND c.constraint_name = k.constraint_name " +
                "WHERE LOWER(k.table_name) = 'service_alert_group' AND c.constraint_type = 'PRIMARY KEY' " +
                "ORDER BY k.ordinal_position",
            String.class
        );

        assertEquals(2, bindingCount);
        assertEquals(2, primaryKeyColumnCount);
        assertEquals(Arrays.asList("service_id", "group_id"), primaryKeyColumns);
    }
}
