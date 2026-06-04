package com.live.monitor.rule;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ApiRuleEvaluatorTest {
    private final ApiRuleEvaluator evaluator = new ApiRuleEvaluator(new ObjectMapper());

    @Test
    void supportsJsonKeywordAndNumericLogic() {
        ApiRuleEvaluator.Evaluation result = evaluator.evaluate(
            "json(\"$.data.cpu\") < 80",
            new ApiRuleEvaluator.ResponseContext(200, 35, "{\"msg\":\"success\",\"data\":{\"cpu\":73}}")
        );

        assertTrue(result.matched);
        assertEquals("$.data.cpu = 73 < 80.0", result.hitContent);
    }

    @Test
    void supportsRegexExtractionAgainstTextResponses() {
        ApiRuleEvaluator.Evaluation result = evaluator.evaluate(
            "regex(\"CPU:\\\\s*([0-9.]+)\") < 80",
            new ApiRuleEvaluator.ResponseContext(200, 42, "status=OK\nCPU: 91.5")
        );

        assertFalse(result.matched);
        assertNotNull(result.failureReason);
    }

    @Test
    void supportsContainsAgainstJsonFieldValues() {
        ApiRuleEvaluator.Evaluation result = evaluator.evaluate(
            "contains(json(\"$.FUND_CODE\"), \"011\") && icontains(json(\"$.status\"), \"ok\")",
            new ApiRuleEvaluator.ResponseContext(200, 68, "{\"FUND_CODE\":\"011389\",\"status\":\"OK\"}")
        );

        assertTrue(result.matched);
        assertEquals("$.status = OK icontains(\"ok\")", result.hitContent);
    }

    @Test
    void comparesQuotedNumericStringsExactly() {
        ApiRuleEvaluator.Evaluation result = evaluator.evaluate(
            "json(\"$.FUND_CODE\") == \"011389\"",
            new ApiRuleEvaluator.ResponseContext(200, 68, "{\"FUND_CODE\":\"011389\"}")
        );

        assertTrue(result.matched);

        ApiRuleEvaluator.Evaluation failed = evaluator.evaluate(
            "json(\"$.FUND_CODE\") == \"11389\"",
            new ApiRuleEvaluator.ResponseContext(200, 68, "{\"FUND_CODE\":\"011389\"}")
        );

        assertFalse(failed.matched);
        assertEquals("$.FUND_CODE = 011389 == 11389 not matched", failed.failureReason);
    }

    @Test
    void supportsFieldNameLookupAcrossTableRows() {
        String body = "{\"rows\":["
            + "{\"FUND_CODE\":\"011389\",\"FUND_NAME\":\"国都聚成\"},"
            + "{\"FUND_CODE\":\"002020\",\"FUND_NAME\":\"国都创新驱动\"}"
            + "]}";

        ApiRuleEvaluator.Evaluation result = evaluator.evaluate(
            "contains(json(\"$.FUND_NAME\"), \"创新\") && field(\"FUND_CODE\") == \"011389\"",
            new ApiRuleEvaluator.ResponseContext(200, 68, body)
        );

        assertTrue(result.matched);
    }

    @Test
    void supportsSameRowFieldComparisonAcrossTableRows() {
        String body = "{\"rows\":["
            + "{\"FUND_CODE\":\"011389\",\"fund_code\":\"011389\"},"
            + "{\"FUND_CODE\":\"002020\",\"fund_code\":\"002020\"}"
            + "]}";

        ApiRuleEvaluator.Evaluation result = evaluator.evaluate(
            "allRowsCompare(\"FUND_CODE\", \"==\", \"fund_code\")",
            new ApiRuleEvaluator.ResponseContext(200, 68, body)
        );

        assertTrue(result.matched);

        ApiRuleEvaluator.Evaluation failed = evaluator.evaluate(
            "allRowsCompare(\"FUND_CODE\", \"==\", \"missing_code\")",
            new ApiRuleEvaluator.ResponseContext(200, 68, body)
        );

        assertFalse(failed.matched);
    }

    @Test
    void supportsSourceQualifiedFieldLookupForCrossDatabaseRules() {
        String body = "{\"sources\":{"
            + "\"A\":{\"rows\":[{\"FUND_CODE\":\"011389\"},{\"FUND_CODE\":\"002020\"}]},"
            + "\"B\":{\"rows\":[{\"FUND_CODE\":\"002020\"},{\"FUND_CODE\":\"011389\"}]}"
            + "}}";

        ApiRuleEvaluator.Evaluation result = evaluator.evaluate(
            "sameValues(\"A.FUND_CODE\", \"B.FUND_CODE\") && field(\"A.FUND_CODE\") == \"011389\"",
            new ApiRuleEvaluator.ResponseContext(200, 68, body)
        );

        assertTrue(result.matched);
    }

    @Test
    void reportsInvalidExpressionsAsFailedRules() {
        ApiRuleEvaluator.Evaluation result = evaluator.evaluate(
            "json(\"$.code\") ==",
            new ApiRuleEvaluator.ResponseContext(200, 10, "{\"code\":0}")
        );

        assertFalse(result.matched);
    }

    @Test
    void rejectsRulesThatExceedLengthLimit() {
        StringBuilder rule = new StringBuilder("contains(\"");
        for (int i = 0; i < 1001; i++) {
            rule.append('a');
        }
        rule.append("\")");

        ApiRuleEvaluator.Evaluation result = evaluator.evaluate(
            rule.toString(),
            new ApiRuleEvaluator.ResponseContext(200, 10, "ok")
        );

        assertFalse(result.matched);
        assertTrue(result.failureReason.contains("rule length exceeds"));
    }

    @Test
    void rejectsOversizedResponseBodiesBeforeParsing() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 262145; i++) {
            body.append('x');
        }

        ApiRuleEvaluator.Evaluation result = evaluator.evaluate(
            "contains(\"x\")",
            new ApiRuleEvaluator.ResponseContext(200, 10, body.toString())
        );

        assertFalse(result.matched);
        assertTrue(result.failureReason.contains("response body exceeds"));
    }

    @Test
    void rejectsJsonPathsThatExceedDepthLimit() {
        ApiRuleEvaluator.Evaluation result = evaluator.evaluate(
            "json(\"$.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a.a\") == 1",
            new ApiRuleEvaluator.ResponseContext(200, 10, "{}")
        );

        assertFalse(result.matched);
        assertTrue(result.failureReason.contains("json path depth exceeds"));
    }
}
