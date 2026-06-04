package com.live.monitor.config;

import java.sql.Connection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseDialect {
    private final JdbcTemplate jdbcTemplate;
    private volatile String productName;

    public DatabaseDialect(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isH2() {
        return productName().toLowerCase().contains("h2");
    }

    public boolean isMysql() {
        String name = productName().toLowerCase();
        return name.contains("mysql") || name.contains("mariadb");
    }

    public String productName() {
        String current = productName;
        if (current != null) {
            return current;
        }
        String resolved = jdbcTemplate.execute((ConnectionCallback<String>) this::databaseProductName);
        productName = resolved == null ? "" : resolved;
        return productName;
    }

    private String databaseProductName(Connection connection) {
        try {
            return connection.getMetaData().getDatabaseProductName();
        } catch (Exception ex) {
            return "";
        }
    }
}
