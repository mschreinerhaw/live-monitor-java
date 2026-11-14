package com.live.monitor.dolphin;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class DolphinRunSummaryTest {
    Map<String, Object> definition(String id, String state) {
        Map<String, Object> r = new HashMap<>();
        r.put("id", id);
        r.put("releaseState", state);
        return r;
    }

    Map<String, Object> run(String code, String date, String state, String time) {
        Map<String, Object> r = new HashMap<>();
        r.put("definition_code", code);
        r.put("etl_date", date);
        r.put("state", state);
        r.put("start_time", time);
        return r;
    }

    @Test
    void retriesAreInstancesAndMissingIsDistinctOnlineDefinitionCount() {
        List<Map<String, Object>> defs =
                Arrays.asList(
                        definition("1", "ONLINE"),
                        definition("2", "ONLINE"),
                        definition("3", "ONLINE"),
                        definition("4", "OFFLINE"));
        List<Map<String, Object>> runs =
                Arrays.asList(
                        run("1", "2026-11-14", "FAILURE", "2026-11-14 01:00:00"),
                        run("1", "2026-11-14", "SUCCESS", "2026-11-14 02:00:00"),
                        run("2", "2026-11-14", "WAITING_DEPEND", "2026-11-14 03:00:00"),
                        run("4", "2026-11-14", "RUNNING_EXECUTION", "2026-11-14 04:00:00"));
        Map<String, Object> absent =
                DolphinRunSummary.missing(defs, runs, "2026-11-14", null, null, true);
        assertEquals(1, absent.get("not_executed_workflows"));
        assertEquals(2, absent.get("executed_workflows"));
        Map<String, Object> statistics =
                DolphinEnterpriseController.statistics(
                        DolphinRunSummary.filter(runs, "2026-11-14", null, null));
        assertEquals(4, statistics.get("total"));
        assertEquals(1, statistics.get("running"));
        assertEquals(1, statistics.get("success"));
        assertEquals(1, statistics.get("failed"));
        assertEquals(1, statistics.get("waiting"));
    }

    @Test
    void unknownBusinessDateDoesNotBecomeMissingAndFailuresMakeAbsenceUnknown() {
        List<Map<String, Object>> defs =
                Arrays.asList(definition("1", "ONLINE"), definition("2", "ONLINE"));
        List<Map<String, Object>> runs =
                Collections.singletonList(run("1", "", "SUCCESS", "2026-11-14 01:00:00"));
        Map<String, Object> counts =
                DolphinRunSummary.missing(defs, runs, "2026-11-14", null, null, true);
        assertEquals(1, counts.get("unclassified_workflows"));
        assertEquals(1, counts.get("not_executed_workflows"));
        assertNull(
                DolphinRunSummary.missing(defs, runs, "2026-11-14", null, null, false)
                        .get("not_executed_workflows"));
        assertTrue(DolphinRunSummary.filter(runs, "2026-11-14", null, null).isEmpty());
    }

    @Test
    void sameWorkflowAcrossDatesAndTimeFiltersRemainSeparate() {
        List<Map<String, Object>> runs =
                Arrays.asList(
                        run("1", "2026-11-13", "SUCCESS", "2026-11-14 01:00:00"),
                        run("1", "2026-11-14", "RUNNING_EXECUTION", "2026-11-14 02:00:00"));
        assertEquals(1, DolphinRunSummary.filter(runs, "2026-11-14", null, null).size());
        assertEquals(
                "SUCCESS",
                DolphinRunSummary.filter(runs, null, "2026-11-14T00:00:00", "2026-11-14T01:30:00")
                        .get(0)
                        .get("state"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DolphinRunSummary.validate(
                                null, "2026-11-15 00:00:00", "2026-11-14 00:00:00"));
    }
}
