package com.live.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.dto.CheckResult;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.rule.ApiRuleEvaluator;
import org.junit.jupiter.api.Test;

class DatabaseMonitorServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DatabaseMonitorService service = new DatabaseMonitorService(
        new ApiRuleEvaluator(new ObjectMapper()),
        objectMapper,
        new LiveMonitorProperties()
    );

    @Test
    void supportsGreaterThanResultRule() {
        CheckResult result = check("SELECT 8 AS cnt", "5", "gt");

        assertEquals("UP", result.status);
    }

    @Test
    void supportsLessThanResultRule() {
        CheckResult result = check("SELECT 8 AS cnt", "5", "lt");

        assertEquals("DOWN", result.status);
    }

    @Test
    void supportsNumericEqualsResultRule() {
        CheckResult result = check("SELECT 5.0 AS cnt", "5", "eq");

        assertEquals("UP", result.status);
    }

    @Test
    void supportsExactMatchAgainstFirstColumnValue() {
        CheckResult result = check("SELECT 'READY' AS status", "READY", "exact");

        assertEquals("UP", result.status);
    }

    @Test
    void keepsFuzzyMatchCompatibleWithRenderedResultText() {
        CheckResult result = check("SELECT 5 AS cnt", "cnt=5", "fuzzy");

        assertEquals("UP", result.status);
    }

    @Test
    void supportsJsonAssertionAgainstFirstColumnValue() {
        CheckResult result = checkWithRule(
            "SELECT '{\"code\":0,\"data\":{\"count\":8}}' AS payload",
            null,
            "fuzzy",
            "json(\"$.code\") == 0 && json(\"$.data.count\") > 5"
        );

        assertEquals("UP", result.status);
    }

    @Test
    void supportsRegexAssertionAgainstTextColumnValue() {
        CheckResult result = checkWithRule(
            "SELECT 'status=OK CPU: 91.5' AS payload",
            null,
            "fuzzy",
            "contains(\"status=OK\") && regex(\"CPU:\\\\s*([0-9.]+)\") < 80"
        );

        assertEquals("DOWN", result.status);
        assertTrue(result.message.contains("reason:"));
    }

    @Test
    void supportsAssertionAgainstSelectedFieldsAcrossPreviewRows() {
        java.util.List<String> fields = new java.util.ArrayList<String>();
        fields.add("code");
        fields.add("status");

        CheckResult result = service.check(
            "jdbc",
            null,
            null,
            null,
            null,
            null,
            "SELECT 0 AS code, 'OK' AS status UNION ALL SELECT 1 AS code, 'WARN' AS status",
            null,
            "fuzzy",
            "json(\"$.rows[0].code\") == 0 && json(\"$.rows[1].status\") == \"WARN\"",
            fields,
            "org.h2.Driver",
            "jdbc:h2:mem:db-selected-fields;DB_CLOSE_DELAY=-1",
            3D
        );

        assertEquals("UP", result.status);
    }

    @Test
    void exposesFirstSelectedRowFieldsAtTopLevelForVisualAssertions() {
        java.util.List<String> fields = new java.util.ArrayList<String>();
        fields.add("FUND_CODE");
        fields.add("FUND_NAME");

        CheckResult result = service.check(
            "jdbc",
            null,
            null,
            null,
            null,
            null,
            "SELECT '011389' AS FUND_CODE, '国都聚成' AS FUND_NAME UNION ALL SELECT '002020' AS FUND_CODE, '国都创新驱动' AS FUND_NAME",
            null,
            "fuzzy",
            "contains(field(\"FUND_NAME\"), \"国都聚成\") && field(\"FUND_CODE\") == \"011389\"",
            fields,
            "org.h2.Driver",
            "jdbc:h2:mem:db-selected-fields-top-level;DB_CLOSE_DELAY=-1",
            3D
        );

        assertEquals("UP", result.status);
    }

    @Test
    void supportsSameRowFieldComparisonRules() {
        java.util.List<String> fields = new java.util.ArrayList<String>();
        fields.add("FUND_CODE");
        fields.add("fund_code");

        CheckResult result = service.check(
            "jdbc",
            null,
            null,
            null,
            null,
            null,
            "SELECT '011389' AS FUND_CODE, '011389' AS fund_code UNION ALL SELECT '002020' AS FUND_CODE, '002020' AS fund_code",
            null,
            "fuzzy",
            "allRowsCompare(\"FUND_CODE\", \"==\", \"fund_code\")",
            fields,
            "org.h2.Driver",
            "jdbc:h2:mem:db-selected-fields-compare;DB_CLOSE_DELAY=-1",
            3D
        );

        assertEquals("UP", result.status);
    }

    @Test
    void supportsCrossDatabaseResultComparison() {
        MonitorService.CrossDatabaseQuery left = new MonitorService.CrossDatabaseQuery();
        left.alias = "A";
        left.serviceType = "jdbc";
        left.jdbcDriverClass = "org.h2.Driver";
        left.jdbcUrl = "jdbc:h2:mem:db-cross-left;DB_CLOSE_DELAY=-1";
        left.databaseQuery = "SELECT '011389' AS FUND_CODE UNION ALL SELECT '002020' AS FUND_CODE";
        left.assertionFields = java.util.Arrays.asList("FUND_CODE");

        MonitorService.CrossDatabaseQuery right = new MonitorService.CrossDatabaseQuery();
        right.alias = "B";
        right.serviceType = "jdbc";
        right.jdbcDriverClass = "org.h2.Driver";
        right.jdbcUrl = "jdbc:h2:mem:db-cross-right;DB_CLOSE_DELAY=-1";
        right.databaseQuery = "SELECT '002020' AS FUND_CODE UNION ALL SELECT '011389' AS FUND_CODE";
        right.assertionFields = java.util.Arrays.asList("FUND_CODE");

        CheckResult result = service.checkCrossDatabase(
            java.util.Arrays.asList(left, right),
            "sameValues(\"A.FUND_CODE\", \"B.FUND_CODE\")",
            3D
        );

        assertEquals("UP", result.status);
    }

    @Test
    void previewsAtMostFiveRows() {
        DatabaseMonitorService.PreviewResult preview = service.preview(
            "jdbc",
            null,
            null,
            null,
            null,
            null,
            "SELECT X AS val FROM SYSTEM_RANGE(1, 10)",
            "org.h2.Driver",
            "jdbc:h2:mem:db-preview;DB_CLOSE_DELAY=-1",
            3D
        );

        assertEquals(1, preview.columns.size());
        assertEquals(5, preview.rows.size());
        assertEquals(5, preview.maxRows.intValue());
    }

    @Test
    void usesConfiguredDatabaseResultMaxRows() {
        DatabaseMonitorService configured = serviceWithMaxRows(7);
        DatabaseMonitorService.PreviewResult preview = configured.preview(
            "jdbc",
            null,
            null,
            null,
            null,
            null,
            "SELECT X AS val FROM SYSTEM_RANGE(1, 10)",
            "org.h2.Driver",
            "jdbc:h2:mem:db-preview-seven;DB_CLOSE_DELAY=-1",
            3D
        );

        assertEquals(7, preview.rows.size());
        assertEquals(7, preview.maxRows.intValue());
    }

    @Test
    void capsConfiguredDatabaseResultMaxRowsAtTen() {
        DatabaseMonitorService configured = serviceWithMaxRows(15);
        DatabaseMonitorService.PreviewResult preview = configured.preview(
            "jdbc",
            null,
            null,
            null,
            null,
            null,
            "SELECT X AS val FROM SYSTEM_RANGE(1, 20)",
            "org.h2.Driver",
            "jdbc:h2:mem:db-preview-ten;DB_CLOSE_DELAY=-1",
            3D
        );

        assertEquals(10, preview.rows.size());
        assertEquals(10, preview.maxRows.intValue());
    }

    @Test
    void clampsConfiguredDatabaseQueryTimeoutSeconds() {
        LiveMonitorProperties properties = new LiveMonitorProperties();

        properties.setDatabaseQueryTimeoutSeconds(0);
        assertEquals(1, properties.getDatabaseQueryTimeoutSeconds());

        properties.setDatabaseQueryTimeoutSeconds(120);
        assertEquals(60, properties.getDatabaseQueryTimeoutSeconds());

        properties.setDatabaseQueryTimeoutSeconds(8);
        assertEquals(8, properties.getDatabaseQueryTimeoutSeconds());
    }

    @Test
    void rejectsNonSelectSqlForChecks() {
        CheckResult result = check("DELETE FROM health_check", null, "fuzzy");

        assertEquals("DOWN", result.status);
        assertTrue(result.message.contains("Only read-only SELECT SQL is allowed"));
    }

    @Test
    void rejectsNonSelectSqlForPreview() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> service.preview(
                "jdbc",
                null,
                null,
                null,
                null,
                null,
                "UPDATE health_check SET code = 1",
                "org.h2.Driver",
                "jdbc:h2:mem:db-preview-reject-update;DB_CLOSE_DELAY=-1",
                3D
            )
        );

        assertTrue(exception.getMessage().contains("Only read-only SELECT SQL is allowed"));
    }

    @Test
    void rejectsMultipleStatements() {
        CheckResult result = check("SELECT 1; DROP TABLE health_check", null, "fuzzy");

        assertEquals("DOWN", result.status);
        assertTrue(result.message.contains("Only a single read-only SELECT SQL statement is allowed"));
    }

    private CheckResult check(String sql, String expected, String operator) {
        return checkWithRule(sql, expected, operator, null);
    }

    private CheckResult checkWithRule(String sql, String expected, String operator, String apiRule) {
        return service.check(
            "jdbc",
            null,
            null,
            null,
            null,
            null,
            sql,
            expected,
            operator,
            apiRule,
            null,
            "org.h2.Driver",
            "jdbc:h2:mem:db-result-rules;DB_CLOSE_DELAY=-1",
            3D
        );
    }

    private DatabaseMonitorService serviceWithMaxRows(int rows) {
        LiveMonitorProperties properties = new LiveMonitorProperties();
        properties.setDatabaseResultMaxRows(rows);
        return new DatabaseMonitorService(
            new ApiRuleEvaluator(new ObjectMapper()),
            objectMapper,
            properties
        );
    }
}
