package com.live.monitor.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class HostMapperQueryTest {
    @Test
    void hostSelectReturnsOneRowWhenMonitorServiceHasMultipleAlertGroups() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:host-multi-alert-groups;MODE=LEGACY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE host_config (id BIGINT PRIMARY KEY, monitor_service_id BIGINT, enabled INT, host_name VARCHAR(255), ip VARCHAR(255))");
        jdbc.execute("CREATE TABLE alert_group (id BIGINT PRIMARY KEY, enabled INT)");
        jdbc.execute("CREATE TABLE service_alert_group (service_id BIGINT, group_id BIGINT, PRIMARY KEY(service_id, group_id))");
        jdbc.execute("CREATE TABLE monitor_service (id BIGINT PRIMARY KEY, enabled INT)");
        jdbc.execute("CREATE TABLE service_latest_status (service_id BIGINT PRIMARY KEY, status VARCHAR(32), message VARCHAR(255), checked_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE host_latest_metric (" +
            "host_id BIGINT PRIMARY KEY, cpu_usage_percent DOUBLE, load_average DOUBLE, memory_used_percent DOUBLE, " +
            "disk_used_percent DOUBLE, cpu_core_count INT, memory_total_mb DOUBLE, disk_mount_count INT, " +
            "disk_metrics_json VARCHAR(1000), physical_disk_metrics_json VARCHAR(1000), checked_at TIMESTAMP)");
        jdbc.update("INSERT INTO host_config (id, monitor_service_id, enabled, host_name, ip) VALUES (1, 99, 1, 'host-a', '10.0.0.1')");
        jdbc.update("INSERT INTO alert_group (id, enabled) VALUES (10, 1), (11, 1)");
        jdbc.update("INSERT INTO service_alert_group (service_id, group_id) VALUES (99, 10), (99, 11)");
        jdbc.update("INSERT INTO monitor_service(id,enabled) VALUES(99,1)");
        jdbc.update("INSERT INTO service_latest_status(service_id,status,message,checked_at) VALUES(99,'UP','collected successfully',CURRENT_TIMESTAMP)");

        List<Map<String, Object>> rows = jdbc.queryForList(HostMapper.HOST_SELECT + "WHERE h.id = 1");

        assertEquals(1, rows.size());
        assertEquals(10L, ((Number) rows.get(0).get("alert_group_id")).longValue());
        assertEquals("UP", rows.get(0).get("last_status"));
        assertEquals("collected successfully", rows.get(0).get("last_message"));
        org.junit.jupiter.api.Assertions.assertNotNull(rows.get(0).get("last_checked_at"));
        jdbc.update("UPDATE service_latest_status SET status='UNKNOWN',message='CPU collection failed' WHERE service_id=99");
        rows = jdbc.queryForList(HostMapper.HOST_SELECT + "WHERE h.id = 1");
        assertEquals("UNKNOWN", rows.get(0).get("last_status"));
        assertEquals("CPU collection failed", rows.get(0).get("last_message"));
    }
}
