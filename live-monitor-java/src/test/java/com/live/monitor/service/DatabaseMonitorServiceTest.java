package com.live.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.live.monitor.dto.CheckResult;
import org.junit.jupiter.api.Test;

class DatabaseMonitorServiceTest {
    private final DatabaseMonitorService service = new DatabaseMonitorService();

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

    private CheckResult check(String sql, String expected, String operator) {
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
            "org.h2.Driver",
            "jdbc:h2:mem:db-result-rules;DB_CLOSE_DELAY=-1",
            3D
        );
    }
}
