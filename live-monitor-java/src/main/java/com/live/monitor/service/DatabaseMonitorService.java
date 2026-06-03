package com.live.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.dto.CheckResult;
import com.live.monitor.rule.ApiRuleEvaluator;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DatabaseMonitorService {
    private static final int ABSOLUTE_MAX_RESULT_ROWS = 10;
    private static final String[] FORBIDDEN_SQL_KEYWORDS = {
        "insert", "update", "delete", "drop", "alter", "truncate", "call",
        "merge", "create", "replace", "grant", "revoke", "execute", "exec"
    };

    private final ApiRuleEvaluator apiRuleEvaluator;
    private final ObjectMapper objectMapper;
    private final LiveMonitorProperties properties;
    private volatile ClassLoader externalDriverClassLoader;

    public DatabaseMonitorService(
        ApiRuleEvaluator apiRuleEvaluator,
        ObjectMapper objectMapper,
        LiveMonitorProperties properties
    ) {
        this.apiRuleEvaluator = apiRuleEvaluator;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

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
        return check(
            type,
            host,
            port,
            databaseName,
            username,
            password,
            query,
            expectedResult,
            resultOperator,
            null,
            null,
            jdbcDriverClass,
            jdbcUrl,
            timeoutSeconds
        );
    }

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
        String apiAssertionExpression,
        List<String> assertionFields,
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
        int timeoutSecondsInt = queryTimeoutSeconds(timeoutSeconds);
        String url = genericJdbc
            ? jdbcUrl.trim()
            : jdbcUrl(normalizedType, host, port, databaseName, timeoutSecondsInt, jdbcDriverClass);
        String sql = StringUtils.hasText(query) ? query.trim() : defaultQuery(normalizedType);
        try {
            validateReadOnlySql(sql);
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
                statement.setMaxRows(databaseResultMaxRows());
                boolean hasResultSet = statement.execute(sql);
                QueryResult queryResult = queryResult(statement, hasResultSet, assertionFields);
                boolean ok = matchesExpectedResult(queryResult, expectedResult, resultOperator);
                ApiRuleEvaluator.Evaluation apiRule = null;
                if (StringUtils.hasText(apiAssertionExpression)) {
                    apiRule = apiRuleEvaluator.evaluate(
                        apiAssertionExpression,
                        new ApiRuleEvaluator.ResponseContext(0, elapsedMs(started), queryResult.ruleValue())
                    );
                    ok = ok && apiRule.matched;
                }
                String product = connection.getMetaData().getDatabaseProductName();
                String version = connection.getMetaData().getDatabaseProductVersion();
                String message = product + " " + shortText(version, 32) + ", result: " + shortText(queryResult.displayValue, 120);
                if (StringUtils.hasText(expectedResult)) {
                    message += ", rule: " + operatorLabel(resultOperator) + " " + expectedResult.trim();
                }
                if (apiRule != null) {
                    message += ", " + apiRule.message + ruleDetail(apiRule);
                }
                return new CheckResult(ok ? "UP" : "DOWN", elapsedMs(started), message);
            }
        } catch (Exception ex) {
            return new CheckResult("DOWN", elapsedMs(started), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    public PreviewResult preview(
        String type,
        String host,
        Integer port,
        String databaseName,
        String username,
        String password,
        String query,
        String jdbcDriverClass,
        String jdbcUrl,
        double timeoutSeconds
    ) {
        String normalizedType = type == null ? "" : type.toLowerCase(Locale.ROOT);
        boolean genericJdbc = "jdbc".equals(normalizedType);
        if (genericJdbc && (!StringUtils.hasText(jdbcDriverClass) || !StringUtils.hasText(jdbcUrl))) {
            throw new IllegalArgumentException("JDBC driver class and URL are required");
        }
        if (!genericJdbc && (!StringUtils.hasText(host) || port == null)) {
            throw new IllegalArgumentException("Host and port are required for database preview");
        }
        int timeoutSecondsInt = queryTimeoutSeconds(timeoutSeconds);
        String url = genericJdbc
            ? jdbcUrl.trim()
            : jdbcUrl(normalizedType, host, port, databaseName, timeoutSecondsInt, jdbcDriverClass);
        String sql = StringUtils.hasText(query) ? query.trim() : defaultQuery(normalizedType);
        validateReadOnlySql(sql);
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
                int maxRows = databaseResultMaxRows();
                statement.setQueryTimeout(timeoutSecondsInt);
                statement.setMaxRows(maxRows);
                boolean hasResultSet = statement.execute(sql);
                if (!hasResultSet) {
                    PreviewResult result = new PreviewResult();
                    result.columns = new ArrayList<String>();
                    result.rows = new ArrayList<Map<String, Object>>();
                    result.message = "update count " + statement.getUpdateCount();
                    result.maxRows = maxRows;
                    return result;
                }
                return previewResult(statement.getResultSet(), maxRows);
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex.getClass().getSimpleName() + ": " + ex.getMessage());
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

    private QueryResult queryResult(Statement statement, boolean hasResultSet, List<String> assertionFields) throws Exception {
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
            List<String> columns = columnLabels(metaData);
            Map<String, String> selectedFields = selectedFieldMap(assertionFields);
            List<Map<String, Object>> selectedRows = new ArrayList<Map<String, Object>>();
            int maxRows = databaseResultMaxRows();
            StringBuilder builder = new StringBuilder();
            int row = 0;
            String firstValue = "";
            do {
                Map<String, Object> selectedRow = new LinkedHashMap<String, Object>();
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
                    String selectedName = selectedFields.get(normalizeFieldName(label));
                    if (row < maxRows && selectedName != null) {
                        selectedRow.put(label, value == null ? null : String.valueOf(value));
                        selectedRow.put(selectedName, value == null ? null : String.valueOf(value));
                        selectedRow.put(normalizeFieldName(label), value == null ? null : String.valueOf(value));
                    }
                }
                if (!selectedRow.isEmpty()) {
                    selectedRows.add(selectedRow);
                }
                row++;
            } while (row < maxRows && builder.length() < 4000 && resultSet.next());
            String ruleValue = selectedRows.isEmpty() ? firstValue : selectedRowsJson(selectedRows, columns, assertionFields);
            return new QueryResult(builder.toString(), firstValue, ruleValue);
        }
    }

    private PreviewResult previewResult(ResultSet resultSet, int maxRows) throws Exception {
        try (ResultSet closeable = resultSet) {
            ResultSetMetaData metaData = closeable.getMetaData();
            List<String> columns = columnLabels(metaData);
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            int row = 0;
            while (row < maxRows && closeable.next()) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                for (int column = 1; column <= columns.size(); column++) {
                    Object value = closeable.getObject(column);
                    item.put(columns.get(column - 1), value == null ? null : String.valueOf(value));
                }
                rows.add(item);
                row++;
            }
            PreviewResult result = new PreviewResult();
            result.columns = columns;
            result.rows = rows;
            result.maxRows = maxRows;
            result.message = "preview limited to " + maxRows + " rows";
            return result;
        }
    }

    private int databaseResultMaxRows() {
        if (properties == null) {
            return 5;
        }
        return Math.max(1, Math.min(ABSOLUTE_MAX_RESULT_ROWS, properties.getDatabaseResultMaxRows()));
    }

    private int queryTimeoutSeconds(double timeoutSeconds) {
        if (properties != null) {
            return properties.getDatabaseQueryTimeoutSeconds();
        }
        if (Double.isNaN(timeoutSeconds) || Double.isInfinite(timeoutSeconds)) {
            return 3;
        }
        return Math.max(1, Math.min(60, (int) Math.ceil(timeoutSeconds)));
    }

    private void validateReadOnlySql(String sql) {
        String normalized = normalizeSqlForValidation(sql);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("SQL is required");
        }
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.indexOf(';') >= 0) {
            throw new IllegalArgumentException("Only a single read-only SELECT SQL statement is allowed");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.matches("(?s)^select\\b.*")) {
            throw new IllegalArgumentException("Only read-only SELECT SQL is allowed");
        }
        lower = maskSqlStringLiterals(normalized).toLowerCase(Locale.ROOT);
        if (lower.matches("(?s).*\\bfor\\s+update\\b.*")) {
            throw new IllegalArgumentException("SELECT FOR UPDATE is not allowed");
        }
        for (String keyword : FORBIDDEN_SQL_KEYWORDS) {
            if (lower.matches("(?s).*\\b" + keyword + "\\b.*")) {
                throw new IllegalArgumentException("SQL keyword is not allowed: " + keyword);
            }
        }
    }

    private String normalizeSqlForValidation(String sql) {
        if (sql == null) {
            return "";
        }
        String value = sql.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            if (value.startsWith("--")) {
                int end = value.indexOf('\n');
                value = end >= 0 ? value.substring(end + 1).trim() : "";
                changed = true;
            } else if (value.startsWith("/*")) {
                int end = value.indexOf("*/");
                value = end >= 0 ? value.substring(end + 2).trim() : "";
                changed = true;
            }
        }
        return value;
    }

    private String maskSqlStringLiterals(String sql) {
        StringBuilder builder = new StringBuilder(sql.length());
        boolean inSingleQuote = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (current == '\'') {
                builder.append(' ');
                if (inSingleQuote && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    builder.append(' ');
                    index++;
                    continue;
                }
                inSingleQuote = !inSingleQuote;
            } else {
                builder.append(inSingleQuote ? ' ' : current);
            }
        }
        return builder.toString();
    }

    private String ruleDetail(ApiRuleEvaluator.Evaluation apiRule) {
        String detail = "";
        if (StringUtils.hasText(apiRule.hitContent)) {
            detail += ", hit: " + shortText(apiRule.hitContent, 120);
        }
        if (StringUtils.hasText(apiRule.failureReason)) {
            detail += ", reason: " + shortText(apiRule.failureReason, 120);
        }
        return detail;
    }

    private List<String> columnLabels(ResultSetMetaData metaData) throws Exception {
        List<String> columns = new ArrayList<String>();
        for (int column = 1; column <= metaData.getColumnCount(); column++) {
            String label = metaData.getColumnLabel(column);
            columns.add(StringUtils.hasText(label) ? label : "column_" + column);
        }
        return columns;
    }

    private Map<String, String> selectedFieldMap(List<String> assertionFields) {
        Map<String, String> selected = new LinkedHashMap<String, String>();
        if (assertionFields == null) {
            return selected;
        }
        for (String field : assertionFields) {
            if (StringUtils.hasText(field)) {
                selected.put(normalizeFieldName(field), field.trim());
            }
        }
        return selected;
    }

    private String selectedRowsJson(List<Map<String, Object>> rows, List<String> columns, List<String> fields) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("rows", rows);
        payload.put("columns", columns);
        payload.put("fields", fields);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return rows.toString();
        }
    }

    private String normalizeFieldName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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
        private final String ruleValue;

        private QueryResult(String displayValue, String firstValue) {
            this(displayValue, firstValue, firstValue);
        }

        private QueryResult(String displayValue, String firstValue, String ruleValue) {
            this.displayValue = displayValue;
            this.firstValue = firstValue;
            this.ruleValue = ruleValue;
        }

        private String ruleValue() {
            return StringUtils.hasText(ruleValue) ? ruleValue : displayValue;
        }
    }

    public static final class PreviewResult {
        public List<String> columns;
        public List<Map<String, Object>> rows;
        public Integer maxRows;
        public String message;
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
