package com.live.monitor.service;

import com.live.monitor.dto.CheckResult;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DatabaseMonitorService {
    public CheckResult check(
        String type,
        String host,
        Integer port,
        String databaseName,
        String username,
        String password,
        String query,
        String expectedResult,
        double timeoutSeconds
    ) {
        if (!StringUtils.hasText(host) || port == null) {
            return new CheckResult("UNKNOWN", null, "Host and port are required for database checks");
        }
        String normalizedType = type == null ? "" : type.toLowerCase();
        if ("oracle".equals(normalizedType) && !StringUtils.hasText(databaseName)) {
            return new CheckResult("UNKNOWN", null, "Oracle service name or SID is required");
        }

        long started = System.nanoTime();
        int timeoutSecondsInt = Math.max(1, (int) Math.ceil(timeoutSeconds));
        String jdbcUrl = jdbcUrl(normalizedType, host, port, databaseName, timeoutSecondsInt);
        String sql = StringUtils.hasText(query) ? query.trim() : defaultQuery(normalizedType);
        try {
            DriverManager.setLoginTimeout(timeoutSecondsInt);
            Properties properties = new Properties();
            if (StringUtils.hasText(username)) {
                properties.put("user", username.trim());
            }
            if (StringUtils.hasText(password)) {
                properties.put("password", password);
            }
            try (Connection connection = DriverManager.getConnection(jdbcUrl, properties);
                 Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSecondsInt);
                boolean hasResultSet = statement.execute(sql);
                String value = resultValue(statement, hasResultSet);
                boolean ok = !StringUtils.hasText(expectedResult) || value.contains(expectedResult.trim());
                String product = connection.getMetaData().getDatabaseProductName();
                String version = connection.getMetaData().getDatabaseProductVersion();
                String message = product + " " + shortText(version, 32) + ", result: " + shortText(value, 120);
                return new CheckResult(ok ? "UP" : "DOWN", elapsedMs(started), message);
            }
        } catch (Exception ex) {
            return new CheckResult("DOWN", elapsedMs(started), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private String jdbcUrl(String type, String host, Integer port, String databaseName, int timeoutSeconds) {
        String db = StringUtils.hasText(databaseName) ? databaseName.trim() : "";
        if ("mysql".equals(type)) {
            return "jdbc:mysql://" + host.trim() + ":" + port + "/" + db
                + "?connectTimeout=" + timeoutSeconds * 1000
                + "&socketTimeout=" + timeoutSeconds * 1000
                + "&useUnicode=true&characterEncoding=utf8&serverTimezone=UTC";
        }
        if ("postgresql".equals(type) || "postgres".equals(type)) {
            String databasePath = StringUtils.hasText(db) ? db : "postgres";
            return "jdbc:postgresql://" + host.trim() + ":" + port + "/" + databasePath
                + "?connectTimeout=" + timeoutSeconds
                + "&socketTimeout=" + timeoutSeconds;
        }
        if ("oracle".equals(type)) {
            if (db.toLowerCase().startsWith("sid:")) {
                return "jdbc:oracle:thin:@" + host.trim() + ":" + port + ":" + db.substring(4);
            }
            return "jdbc:oracle:thin:@//" + host.trim() + ":" + port + "/" + db;
        }
        throw new IllegalArgumentException("Unsupported database type: " + type);
    }

    private String defaultQuery(String type) {
        return "oracle".equals(type) ? "SELECT 1 FROM dual" : "SELECT 1";
    }

    private String resultValue(Statement statement, boolean hasResultSet) throws Exception {
        if (!hasResultSet) {
            return "update count " + statement.getUpdateCount();
        }
        try (ResultSet resultSet = statement.getResultSet()) {
            if (!resultSet.next()) {
                return "empty result";
            }
            Object value = resultSet.getObject(1);
            return value == null ? "null" : String.valueOf(value);
        }
    }

    private String shortText(String value, int limit) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit - 3) + "...";
    }

    private int elapsedMs(long started) {
        return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
