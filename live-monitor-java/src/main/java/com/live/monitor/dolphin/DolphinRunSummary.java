package com.live.monitor.dolphin;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Read-only accounting: definitions without observed runs are never instance counts. */
public final class DolphinRunSummary {
    private DolphinRunSummary() {}

    public static List<Map<String, Object>> filter(
            List<Map<String, Object>> rows, String date, String start, String end) {
        return rows.stream()
                .filter(r -> inTime(r, start, end))
                .filter(r -> empty(date) || date.equals(text(r, "etl_date")))
                .collect(Collectors.toList());
    }

    public static Map<String, Object> missing(
            List<Map<String, Object>> definitions,
            List<Map<String, Object>> runs,
            String date,
            String start,
            String end,
            boolean reliable) {
        Set<String> online = new HashSet<>(), executed = new HashSet<>(), unknown = new HashSet<>();
        for (Map<String, Object> d : definitions)
            if ("ONLINE".equals(d.get("releaseState"))) online.add(text(d, "id"));
        for (Map<String, Object> run : runs) {
            String code = text(run, "definition_code");
            if (!online.contains(code)) continue;
            boolean timeUnknown = (!empty(start) || !empty(end)) && empty(text(run, "start_time"));
            if (timeUnknown) {
                unknown.add(code);
                continue;
            }
            if (!inTime(run, start, end)) continue;
            if (!empty(date) && empty(text(run, "etl_date"))) unknown.add(code);
            else if (empty(date) || date.equals(text(run, "etl_date"))) executed.add(code);
        }
        unknown.removeAll(executed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executed_workflows", executed.size());
        result.put(
                "unclassified_workflows",
                reliable ? unknown.size() : online.size() - executed.size());
        result.put(
                "not_executed_workflows",
                reliable ? online.size() - executed.size() - unknown.size() : null);
        result.put("execution_coverage_reliable", reliable);
        return result;
    }

    public static void validate(String date, String start, String end) {
        if (!empty(date)) LocalDate.parse(date);
        if (!empty(start)) DolphinCollector.parse(start);
        if (!empty(end)) DolphinCollector.parse(end);
        if (!empty(start)
                && !empty(end)
                && DolphinCollector.parse(start).isAfter(DolphinCollector.parse(end)))
            throw new IllegalArgumentException("Start time must not follow end time");
    }

    static boolean inTime(Map<String, Object> row, String start, String end) {
        String at = text(row, "start_time");
        if (empty(start) && empty(end)) return true;
        if (empty(at)) return false;
        try {
            java.time.LocalDateTime time = DolphinCollector.parse(at);
            return (empty(start) || !time.isBefore(DolphinCollector.parse(start)))
                    && (empty(end) || !time.isAfter(DolphinCollector.parse(end)));
        } catch (java.time.DateTimeException invalid) {
            return false;
        }
    }

    static String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }

    static boolean empty(String value) {
        return value == null || value.isEmpty();
    }
}
