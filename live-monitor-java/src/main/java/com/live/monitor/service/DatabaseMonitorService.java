package com.live.monitor.service;

import com.live.monitor.dto.CheckResult;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSetMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DatabaseMonitorService {
    private volatile ClassLoader externalDriverClassLoader;

    public CheckResult check(
        String type,
        String host,
        Integer port,
        String databaseName,
        String username,
        String password,
        String query,
        String expectedResult,
        String resultOperator,
        String jdbcDriverClass,
        String jdbcUrl,
        double timeoutSeconds
    ) {
        String normalizedType = type == null ? "" : type.toLowerCase();
        boolean genericJdbc = "jdbc".equals(normalizedType);
        if (genericJdbc && (!StringUtils.hasText(jdbcDriverClass) || !StringUtils.hasText(jdbcUrl))) {
            return new CheckResult("UNKNOWN", null, "JDBC driver class and URL are required");
        }
        if (!genericJdbc && (!StringUtils.hasText(host) || port == null)) {
            return new CheckResult("UNKNOWN", null, "Host and port are required for database checks");
        }
        if ("oracle".equals(normalizedType) && !StringUtils.hasText(databaseName)) {
            return new CheckResult("UNKNOWN", null, "Oracle service name or SID is required");
        }

        long started = System.nanoTime();
        int timeoutSecondsInt = Math.max(1, (int) Math.ceil(timeoutSeconds));
        String url = genericJdbc
            ? jdbcUrl.trim()
            : jdbcUrl(normalizedType, host, port, databaseName, timeoutSecondsInt, jdbcDriverClass);
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
            try (Connection connection = connect(url, properties, jdbcDriverClass);
                 Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSecondsInt);
                boolean hasResultSet = statement.execute(sql);
                QueryResult queryResult = queryResult(statement, hasResultSet);
                boolean ok = matchesExpectedResult(queryResult, expectedResult, resultOperator);
                String product = connection.getMetaData().getDatabaseProductName();
                String version = connection.getMetaData().getDatabaseProductVersion();
                String message = product + " " + shortText(version, 32) + ", result: " + shortText(queryResult.displayValue, 120);
                if (StringUtils.hasText(expectedResult)) {
                    message += ", rule: " + operatorLabel(resultOperator) + " " + expectedResult.trim();
                }
                return new CheckResult(ok ? "UP" : "DOWN", elapsedMs(started), message);
            }
        } catch (Exception ex) {
            return new CheckResult("DOWN", elapsedMs(started), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private Connection connect(String jdbcUrl, Properties properties, String driverClassName) throws Exception {
        if (!StringUtils.hasText(driverClassName)) {
            return DriverManager.getConnection(jdbcUrl, properties);
        }
        ClassLoader classLoader = externalDriverClassLoader();
        Class<?> driverClass = Class.forName(driverClassName.trim(), true, classLoader);
        Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
        Connection connection = driver.connect(jdbcUrl, properties);
        if (connection == null) {
            throw new SQLException("Driver " + driverClassName + " does not accept URL: " + jdbcUrl);
        }
        return connection;
    }

    private ClassLoader externalDriverClassLoader() throws Exception {
        ClassLoader current = externalDriverClassLoader;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (externalDriverClassLoader != null) {
                return externalDriverClassLoader;
            }
            List<URL> urls = new ArrayList<URL>();
            collectJarUrls(new File("lib"), urls);
            collectJarUrls(new File("libs"), urls);
            URL[] jarUrls = urls.toArray(new URL[0]);
            externalDriverClassLoader = new ExternalDriverClassLoader(jarUrls, Thread.currentThread().getContextClassLoader());
            return externalDriverClassLoader;
        }
    }

    private void collectJarUrls(File directory, List<URL> urls) throws Exception {
        if (!directory.isDirectory()) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase().endsWith(".jar")) {
                urls.add(file.toURI().toURL());
            }
        }
    }

    private String jdbcUrl(
        String type,
        String host,
        Integer port,
        String databaseName,
        int timeoutSeconds,
        String jdbcDriverClass
    ) {
        String db = StringUtils.hasText(databaseName) ? databaseName.trim() : "";
        if ("mysql".equals(type)) {
            return mysqlJdbcUrl(host, port, db, timeoutSeconds, jdbcDriverClass);
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

    private String mysqlJdbcUrl(
        String host,
        Integer port,
        String databaseName,
        int timeoutSeconds,
        String jdbcDriverClass
    ) {
        String base = "jdbc:mysql://" + host.trim() + ":" + port + "/" + databaseName
            + "?connectTimeout=" + timeoutSeconds * 1000
            + "&socketTimeout=" + timeoutSeconds * 1000
            + "&useUnicode=true"
            + "&characterEncoding=UTF-8"
            + "&useSSL=false"
            + "&zeroDateTimeBehavior=convertToNull";
        if (isLegacyMysqlDriver(jdbcDriverClass)) {
            return base;
        }
        return base
            + "&serverTimezone=UTC"
            + "&allowPublicKeyRetrieval=true";
    }

    private boolean isLegacyMysqlDriver(String jdbcDriverClass) {
        return "com.mysql.jdbc.Driver".equals(StringUtils.hasText(jdbcDriverClass) ? jdbcDriverClass.trim() : "");
    }

    private String defaultQuery(String type) {
        return "oracle".equals(type) ? "SELECT 1 FROM dual" : "SELECT 1";
    }

    private QueryResult queryResult(Statement statement, boolean hasResultSet) throws Exception {
        if (!hasResultSet) {
            int count = statement.getUpdateCount();
            return new QueryResult("update count " + count, String.valueOf(count));
        }
        try (ResultSet resultSet = statement.getResultSet()) {
            if (!resultSet.next()) {
                return new QueryResult("empty result", "");
            }
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            StringBuilder builder = new StringBuilder();
            int row = 0;
            String firstValue = "";
            do {
                if (row > 0) {
                    builder.append(" | ");
                }
                for (int column = 1; column <= columnCount; column++) {
                    if (column > 1) {
                        builder.append(", ");
                    }
                    String label = metaData.getColumnLabel(column);
                    if (StringUtils.hasText(label)) {
                        builder.append(label).append("=");
                    }
                    Object value = resultSet.getObject(column);
                    String text = value == null ? "null" : String.valueOf(value);
                    if (row == 0 && column == 1) {
                        firstValue = text;
                    }
                    builder.append(text);
                }
                row++;
            } while (row < 20 && builder.length() < 4000 && resultSet.next());
            return new QueryResult(builder.toString(), firstValue);
        }
    }

    private boolean matchesExpectedResult(QueryResult result, String expectedResult, String operator) {
        if (!StringUtils.hasText(expectedResult)) {
            return true;
        }
        String expected = expectedResult.trim();
        String actual = result.firstValue == null ? "" : result.firstValue.trim();
        String display = result.displayValue == null ? "" : result.displayValue;
        String normalizedOperator = normalizeOperator(operator);
        if ("fuzzy".equals(normalizedOperator)) {
            String expectedLower = expected.toLowerCase(Locale.ROOT);
            return display.toLowerCase(Locale.ROOT).contains(expectedLower)
                || actual.toLowerCase(Locale.ROOT).contains(expectedLower);
        }
        if ("exact".equals(normalizedOperator)) {
            return actual.equals(expected) || display.trim().equals(expected);
        }
        if ("eq".equals(normalizedOperator)) {
            BigDecimal actualNumber = decimalValue(actual);
            BigDecimal expectedNumber = decimalValue(expected);
            if (actualNumber != null && expectedNumber != null) {
                return actualNumber.compareTo(expectedNumber) == 0;
            }
            return actual.equals(expected);
        }
        BigDecimal actualNumber = decimalValue(actual);
        BigDecimal expectedNumber = decimalValue(expected);
        if (actualNumber == null || expectedNumber == null) {
            return false;
        }
        int compared = actualNumber.compareTo(expectedNumber);
        if ("gt".equals(normalizedOperator)) {
            return compared > 0;
        }
        if ("gte".equals(normalizedOperator)) {
            return compared >= 0;
        }
        if ("lt".equals(normalizedOperator)) {
            return compared < 0;
        }
        if ("lte".equals(normalizedOperator)) {
            return compared <= 0;
        }
        return display.contains(expected) || actual.contains(expected);
    }

    private String normalizeOperator(String value) {
        String operator = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("gt".equals(operator) || "gte".equals(operator) || "lt".equals(operator)
            || "lte".equals(operator) || "eq".equals(operator) || "exact".equals(operator)) {
            return operator;
        }
        return "fuzzy";
    }

    private String operatorLabel(String value) {
        String operator = normalizeOperator(value);
        if ("gt".equals(operator)) {
            return ">";
        }
        if ("gte".equals(operator)) {
            return ">=";
        }
        if ("lt".equals(operator)) {
            return "<";
        }
        if ("lte".equals(operator)) {
            return "<=";
        }
        if ("eq".equals(operator)) {
            return "=";
        }
        if ("exact".equals(operator)) {
            return "exact";
        }
        return "contains";
    }

    private BigDecimal decimalValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim().replace(",", ""));
        } catch (NumberFormatException ex) {
            return null;
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

    private static final class QueryResult {
        private final String displayValue;
        private final String firstValue;

        private QueryResult(String displayValue, String firstValue) {
            this.displayValue = displayValue;
            this.firstValue = firstValue;
        }
    }

    private static final class ExternalDriverClassLoader extends URLClassLoader {
        private ExternalDriverClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && !name.startsWith("java.") && !name.startsWith("javax.")) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                        // Fall back to the application class loader for framework and shared classes.
                    }
                }
                if (loaded == null) {
                    loaded = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
