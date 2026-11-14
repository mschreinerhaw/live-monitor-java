package com.live.monitor.dolphin;

import com.fasterxml.jackson.databind.JsonNode;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.entity.TUser;
import com.live.monitor.mapper.AlertMapper;
import com.live.monitor.service.DolphinSchedulerService;
import com.live.monitor.service.MonitorCheckExecution;
import com.live.monitor.util.MonitorTime;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import javax.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/dolphinscheduler/{id}/enterprise")
public class DolphinEnterpriseController {
    final DolphinStore store;
    final DolphinSchedulerService api;
    final DolphinCollector collector;
    final AlertMapper groups;

    public DolphinEnterpriseController(
            DolphinStore store,
            DolphinSchedulerService api,
            DolphinCollector collector,
            AlertMapper groups) {
        this.store = store;
        this.api = api;
        this.collector = collector;
        this.groups = groups;
    }

    void admin(HttpSession session) {
        Object user = session.getAttribute(com.live.monitor.controller.AuthController.SESSION_USER);
        if (!(user instanceof TUser) || !"admin".equalsIgnoreCase(((TUser) user).userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator required");
    }

    void project(long cluster, String code) {
        api.require(cluster);
        if (code == null || code.isEmpty()) return;
        if (!code.matches("[0-9]{1,18}"))
            throw new IllegalArgumentException("Invalid project code");
        Set<String> scope = new HashSet<>();
        for (String p :
                Objects.toString(api.config(api.require(cluster)).get("project_codes"), "")
                        .split(",")) if (!p.trim().isEmpty()) scope.add(p.trim());
        if (!scope.isEmpty() && !scope.contains(code))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Project outside configured scope");
    }

    List<Map<String, Object>> scoped(long id, String kind, String project) {
        project(id, project);
        List<Map<String, Object>> rows = store.entities(id, kind, project);
        Set<String> scope = new HashSet<>();
        for (String p :
                Objects.toString(api.config(api.require(id)).get("project_codes"), "").split(","))
            if (!p.trim().isEmpty()) scope.add(p.trim());
        return scope.isEmpty()
                ? rows
                : rows.stream()
                        .filter(r -> scope.contains(r.get("project_code")))
                        .collect(Collectors.toList());
    }

    @PostMapping("/sync")
    public Object sync(@PathVariable long id, HttpSession session) {
        admin(session);
        collector.enqueue(api.require(id), true);
        return Collections.singletonMap("status", "queued");
    }

    @PostMapping("/test")
    public Object test(@PathVariable long id, HttpSession session) {
        admin(session);
        MonitorService s = api.require(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project_count", collector.projects(s).size());
        result.put("status", "connected");
        result.put("read_only", true);
        return result;
    }

    @GetMapping("/dashboard")
    public Object dashboard(@PathVariable long id) {
        MonitorService s = api.require(id);
        List<Map<String, Object>> collections = collectionStates(id);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> projects = projectRows(id);
        result.put("projects", projects);
        result.put("collection", collections);
        result.put("nodes", store.entities(id, "node", null));
        result.put("databases", store.entities(id, "database", null));
        result.put("project_count", hasSuccess(id, "inventory") ? projects.size() : null);
        result.put(
                "workflow_definitions",
                hasSuccess(id, "inventory")
                        ? scoped(id, "workflow_definition", null).size()
                        : null);
        result.put(
                "online_workflows",
                hasSuccess(id, "inventory")
                        ? scoped(id, "workflow_definition", null).stream()
                                .filter(r -> "ONLINE".equals(r.get("releaseState")))
                                .count()
                        : null);
        result.put(
                "task_definitions",
                hasSuccess(id, "inventory") ? scoped(id, "task_definition", null).size() : null);
        result.put(
                "workflow_statistics",
                hasSuccess(id, "runtime")
                        ? statistics(scoped(id, "workflow_instance", null))
                        : null);
        result.put(
                "task_statistics",
                hasSuccess(id, "runtime") ? statistics(scoped(id, "task_instance", null)) : null);
        result.put(
                "active_incidents",
                store.incidents(id).stream()
                        .filter(r -> DolphinAlerts.integer(r, "active") == 1)
                        .count());
        result.put(
                "pending_deliveries",
                store.jdbc()
                        .queryForObject(
                                "SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=? AND"
                                        + " delivery_status IN ('PENDING','FAILED','SENDING')",
                                Long.class,
                                id));
        result.put(
                "api_profile",
                api.config(s)
                        .getOrDefault(
                                "api_profile", "ld-2.0.8-compatible (verify per deployment)"));
        result.put(
                "enterprise_enabled", Boolean.TRUE.equals(api.config(s).get("enterprise_enabled")));
        result.put("server_time", MonitorTime.nowText());
        return result;
    }

    boolean hasSuccess(long id, String lane) {
        return store.collection(id, lane).get("last_success") != null;
    }

    List<Map<String, Object>> collectionStates(long id) {
        Map<String, Object> config = api.config(api.require(id));
        List<Map<String, Object>> states = store.collections(id);
        for (Map<String, Object> state : states) {
            String lane = state.get("lane").toString().split(":")[0];
            int interval =
                    DolphinCollector.setting(
                            config,
                            lane + "_interval_seconds",
                            lane.equals("resources") ? 15 : lane.equals("runtime") ? 30 : 300,
                            1,
                            86400);
            String status = "INITIALIZING";
            if (DolphinAlerts.integer(state, "failures") > 0) status = "FAILED";
            else if (state.get("last_success") != null) {
                long age =
                        Duration.between(
                                        DolphinCollector.parse(
                                                state.get("last_success").toString()),
                                        MonitorTime.now())
                                .getSeconds();
                state.put("age_seconds", age);
                status = age > interval * 3L ? "STALE" : "FRESH";
                if (lane.equals("runtime") && state.get("watermark") != null) {
                    long lag =
                            Duration.between(
                                            DolphinCollector.parse(
                                                    state.get("watermark").toString()),
                                            api.sourceNow(api.require(id)))
                                    .getSeconds();
                    state.put("cursor_lag_seconds", lag);
                    if (lag > interval * 3L) status = "CATCHING_UP";
                }
            }
            state.put("collection_status", status);
            state.put("fresh", "FRESH".equals(status));
        }
        return states;
    }

    List<Map<String, Object>> projectRows(long id) {
        List<Map<String, Object>> rows = scoped(id, "project", null);
        for (Map<String, Object> row : rows) {
            String code = row.get("id").toString();
            List<Map<String, Object>> defs = store.entities(id, "workflow_definition", code),
                    tasks = store.entities(id, "task_definition", code),
                    runs = store.entities(id, "workflow_instance", code),
                    taskRuns = store.entities(id, "task_instance", code);
            Map<String, Object> checkpoint = store.collection(id, "inventory:" + code);
            boolean known = checkpoint.get("last_success") != null;
            row.put("workflow_count", known ? defs.size() : null);
            row.put(
                    "online_count",
                    known
                            ? defs.stream()
                                    .filter(r -> "ONLINE".equals(r.get("releaseState")))
                                    .count()
                            : null);
            row.put(
                    "offline_count",
                    known
                            ? defs.stream()
                                    .filter(r -> "OFFLINE".equals(r.get("releaseState")))
                                    .count()
                            : null);
            row.put("task_definition_count", known ? tasks.size() : null);
            row.put(
                    "schedule_count",
                    known
                            ? defs.stream()
                                    .filter(r -> "ONLINE".equals(r.get("scheduleReleaseState")))
                                    .count()
                            : null);
            row.put(
                    "unknown_schedule_count",
                    known
                            ? defs.stream()
                                    .filter(r -> r.get("scheduleReleaseState") == null)
                                    .count()
                            : null);
            boolean runtimeKnown =
                    store.collection(id, "runtime:" + code).get("last_success") != null;
            row.put("workflow_statistics", runtimeKnown ? statistics(runs) : null);
            row.put("task_statistics", runtimeKnown ? statistics(taskRuns) : null);
            row.put(
                    "etl_dates",
                    taskRuns.stream()
                            .map(r -> Objects.toString(r.get("etl_date"), ""))
                            .filter(v -> !v.isEmpty())
                            .distinct()
                            .sorted()
                            .collect(Collectors.toList()));
            row.put(
                    "last_run",
                    runs.stream()
                            .map(r -> Objects.toString(r.get("start_time"), ""))
                            .max(String::compareTo)
                            .orElse(""));
            row.put("config", store.config(id, code));
            row.put("inventory_status", checkpoint);
            row.put("runtime_status", store.collection(id, "runtime:" + code));
        }
        return rows;
    }

    @GetMapping("/projects")
    public Object projects(@PathVariable long id) {
        api.require(id);
        return projectRows(id);
    }

    @GetMapping("/users")
    public Object users(@PathVariable long id) {
        api.require(id);
        return store.entities(id, "user", null);
    }

    @GetMapping("/projects/{project}/authorizations")
    public Object authorizations(@PathVariable long id, @PathVariable String project) {
        return scoped(id, "authorization", project);
    }

    @GetMapping("/definitions")
    public Object definitions(
            @PathVariable long id,
            @RequestParam(required = false) String project,
            @RequestParam(defaultValue = "workflow_definition") String kind) {
        if (!Arrays.asList("workflow_definition", "task_definition").contains(kind))
            throw new IllegalArgumentException("Invalid definition kind");
        List<Map<String, Object>> definitions = scoped(id, kind, project);
        Map<String, String> latest = new HashMap<>();
        for (Map<String, Object> instance : scoped(id, "workflow_instance", project)) {
            String code = Objects.toString(instance.get("definition_code"), ""),
                    at = Objects.toString(instance.get("start_time"), "");
            if (at.compareTo(latest.getOrDefault(code, "")) > 0) latest.put(code, at);
        }
        for (Map<String, Object> row : definitions)
            row.put(
                    "last_collected_run",
                    latest.get(
                            Objects.toString(
                                    row.get("definition_code"),
                                    Objects.toString(row.get("id"), ""))));
        return definitions;
    }

    @GetMapping("/instances")
    public Object instances(
            @PathVariable long id,
            @RequestParam(defaultValue = "workflow_instance") String kind,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String workflow,
            @RequestParam(required = false) String task,
            @RequestParam(required = false) String state,
            @RequestParam(name = "etl_date", required = false) String date,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        if (!Arrays.asList("workflow_instance", "task_instance").contains(kind))
            throw new IllegalArgumentException("Invalid instance kind");
        if (date != null && !date.isEmpty()) LocalDate.parse(date);
        if (start != null && !start.isEmpty()) DolphinCollector.parse(start);
        if (end != null && !end.isEmpty()) DolphinCollector.parse(end);
        if (!empty(start)
                && !empty(end)
                && DolphinCollector.parse(start).isAfter(DolphinCollector.parse(end)))
            throw new IllegalArgumentException("Start time must not follow end time");
        List<Map<String, Object>> rows =
                scoped(id, kind, project).stream()
                        .filter(r -> empty(workflow) || workflow.equals(r.get("definition_code")))
                        .filter(r -> empty(task) || task.equals(r.get("task_code")))
                        .filter(r -> empty(state) || state.equals(r.get("state")))
                        .filter(r -> empty(date) || date.equals(r.get("etl_date")))
                        .filter(
                                r ->
                                        empty(start)
                                                || Objects.toString(r.get("start_time"), "")
                                                                .compareTo(start.replace('T', ' '))
                                                        >= 0)
                        .filter(
                                r ->
                                        empty(end)
                                                || Objects.toString(r.get("start_time"), "")
                                                                .compareTo(end.replace('T', ' '))
                                                        <= 0)
                        .sorted(
                                Comparator.comparing(
                                                (Map<String, Object> r) ->
                                                        Objects.toString(r.get("start_time"), ""))
                                        .reversed())
                        .collect(Collectors.toList());
        int size = Math.max(1, Math.min(200, pageSize)),
                p = Math.max(1, Math.min(page, Math.max(1, (rows.size() + size - 1) / size))),
                from = (p - 1) * size;
        List<Map<String, Object>> items =
                rows.subList(Math.min(from, rows.size()), Math.min(from + size, rows.size()));
        Map<String, List<Map<String, Object>>> children = new HashMap<>();
        if ("workflow_instance".equals(kind))
            for (Map<String, Object> child : scoped(id, "task_instance", project))
                children.computeIfAbsent(
                                Objects.toString(child.get("workflow_instance_id"), ""),
                                k -> new ArrayList<>())
                        .add(child);
        for (Map<String, Object> row : items) {
            if (!Objects.toString(row.get("start_time"), "").isEmpty()) {
                try {
                    java.time.LocalDateTime until =
                            empty(Objects.toString(row.get("end_time"), ""))
                                    ? DolphinCollector.ACTIVE.contains(row.get("state"))
                                            ? api.sourceNow(api.require(id))
                                            : null
                                    : DolphinCollector.parse(row.get("end_time").toString());
                    if (until != null) {
                        long seconds =
                                Math.max(
                                        0,
                                        Duration.between(
                                                        DolphinCollector.parse(
                                                                row.get("start_time").toString()),
                                                        until)
                                                .getSeconds());
                        row.put("duration_seconds", seconds);
                        row.put("duration", seconds + " s");
                    }
                } catch (java.time.DateTimeException ignored) {
                    /* unavailable source time stays unknown */
                }
            }
            if ("workflow_instance".equals(kind)) {
                List<Map<String, Object>> nodes = new ArrayList<>();
                for (Map<String, Object> child :
                        children.getOrDefault(row.get("id").toString(), Collections.emptyList())) {
                    if (DolphinCollector.ACTIVE.contains(child.get("state"))) {
                        Map<String, Object> node = new LinkedHashMap<>();
                        for (String field : Arrays.asList("id", "name", "host", "state"))
                            node.put(field, child.get(field));
                        nodes.add(node);
                    }
                    if (DolphinAlerts.failed(child)
                            && empty(Objects.toString(row.get("failure_reason"), "")))
                        row.put("failure_reason", child.get("failure_reason"));
                }
                row.put("running_nodes", nodes);
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items);
        response.put(
                "total",
                hasSuccess(
                                        id,
                                        project == null || project.isEmpty()
                                                ? "runtime"
                                                : "runtime:" + project)
                                || !rows.isEmpty()
                        ? rows.size()
                        : null);
        response.put("page", p);
        response.put("page_size", size);
        response.put("statistics", statistics(rows));
        response.put("collection", collectionStates(id));
        return response;
    }

    @GetMapping("/business-dates")
    public Object dates(@PathVariable long id, @RequestParam(required = false) String project) {
        Map<String, List<Map<String, Object>>> groups = new TreeMap<>();
        for (Map<String, Object> row : scoped(id, "task_instance", project))
            groups.computeIfAbsent(
                            Objects.toString(row.get("etl_date"), "").isEmpty()
                                    ? "UNKNOWN"
                                    : row.get("etl_date").toString(),
                            k -> new ArrayList<>())
                    .add(row);
        List<Map<String, Object>> result = new ArrayList<>();
        groups.forEach(
                (date, rows) -> {
                    Map<String, Object> r = new LinkedHashMap<>(statistics(rows));
                    r.put("etl_date", date);
                    Map<String, Map<String, Object>> latest = new HashMap<>();
                    for (Map<String, Object> row : rows) {
                        String key =
                                row.get("project_code")
                                        + ":"
                                        + row.get("definition_code")
                                        + ":"
                                        + row.get("task_code");
                        Map<String, Object> old = latest.get(key);
                        if (old == null
                                || Objects.toString(row.get("start_time"), "")
                                                .compareTo(
                                                        Objects.toString(old.get("start_time"), ""))
                                        >= 0) latest.put(key, row);
                    }
                    Map<String, Object> logical = statistics(new ArrayList<>(latest.values()));
                    r.put("latest_attempt_statistics", logical);
                    r.put("retry_attempts", rows.size() - latest.size());
                    r.put(
                            "completion_rate",
                            "UNKNOWN".equals(date) || latest.isEmpty()
                                    ? null
                                    : 100.0
                                            * ((Number) logical.get("success")).doubleValue()
                                            / latest.size());
                    result.add(r);
                });
        return result;
    }

    @GetMapping("/nodes/{node}/history")
    public Object nodeHistory(
            @PathVariable long id,
            @PathVariable String node,
            @RequestParam(defaultValue = "24") int hours) {
        api.require(id);
        if (hours < 1 || hours > 168)
            throw new IllegalArgumentException("History range must be 1–168 hours");
        return store.samples(id, node, hours);
    }

    @GetMapping("/logs/{task}")
    public Object log(
            @PathVariable long id,
            @PathVariable String task,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> row = store.entity(id, "task_instance", task);
        if (row == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task has not been collected");
        project(id, row.get("project_code").toString());
        if (offset < 0 || offset > 1000000 || limit < 1 || limit > 500)
            throw new IllegalArgumentException("Invalid log window");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("taskInstanceId", task);
        params.put("skipLineNum", Integer.toString(offset));
        params.put("limit", Integer.toString(limit));
        JsonNode data = api.get(api.require(id), "/log/detail", params);
        String content =
                data.isTextual()
                        ? data.asText()
                        : data.path("message").asText(data.path("log").asText(""));
        return Collections.singletonMap("content", redactLog(content));
    }

    static String redactLog(String value) {
        String safe =
                value.replaceAll(
                        "(?i)(password|passwd|secret|token|api_key)([\\s=:]+)[^\\s,;&]+",
                        "$1$2[redacted]");
        return safe.substring(0, Math.min(200000, safe.length()));
    }

    @GetMapping("/rules")
    public Object rules(@PathVariable long id) {
        api.require(id);
        return store.rules(id);
    }

    @GetMapping("/incidents")
    public Object incidents(@PathVariable long id) {
        api.require(id);
        return store.incidents(id);
    }

    @PutMapping("/projects/{project}/config")
    public Object config(
            @PathVariable long id,
            @PathVariable String project,
            @RequestBody Map<String, Object> body,
            HttpSession session) {
        admin(session);
        project(id, project);
        String primary = Objects.toString(body.get("primary_owner"), ""),
                backup = Objects.toString(body.get("backup_owner"), "");
        if (primary.length() > 255 || backup.length() > 255)
            throw new IllegalArgumentException("Owner name too long");
        List<Long> ids =
                store.groups(store.encode(body.getOrDefault("group_ids", Collections.emptyList())));
        for (Long group : ids)
            if (groups.findGroup(group) == null)
                throw new IllegalArgumentException("Alert group not found");
        store.saveConfig(
                id,
                project,
                primary,
                backup,
                ids,
                !Boolean.FALSE.equals(body.get("alert_enabled")));
        return store.config(id, project);
    }

    @PutMapping("/rules")
    public Object rule(
            @PathVariable long id, @RequestBody Map<String, Object> rule, HttpSession session) {
        admin(session);
        api.require(id);
        if (!Arrays.asList("platform", "project", "workflow", "task")
                        .contains(rule.get("scope_type"))
                || !Objects.toString(rule.get("scope_id"), "").matches("[0-9]{1,18}|\\*"))
            throw new IllegalArgumentException("Invalid rule scope");
        if (!Arrays.asList(
                        "WORKFLOW_FAILURE",
                        "TASK_FAILURE",
                        "TIMEOUT",
                        "ETL_DEADLINE",
                        "BACKLOG",
                        "CPU_HIGH",
                        "MEMORY_HIGH",
                        "JVM_HIGH",
                        "DISK_LOW")
                .contains(rule.get("rule_type")))
            throw new IllegalArgumentException("Invalid rule type");
        if (rule.get("threshold_value") != null) {
            double value = Double.parseDouble(rule.get("threshold_value").toString());
            if (!Double.isFinite(value) || value < 0 || value > 31536000)
                throw new IllegalArgumentException("Invalid rule threshold");
        }
        if ("ETL_DEADLINE".equals(rule.get("rule_type"))) {
            LocalDate.parse(Objects.toString(rule.get("business_date"), ""));
            DolphinCollector.parse(Objects.toString(rule.get("deadline"), ""));
        }
        store.saveRule(id, rule);
        return store.rules(id);
    }

    static boolean empty(String value) {
        return value == null || value.isEmpty();
    }

    public static Map<String, Object> statistics(List<Map<String, Object>> rows) {
        Map<String, Object> r = new LinkedHashMap<>();
        int success = 0, failed = 0, running = 0, waiting = 0, other = 0, unknown = 0;
        for (Map<String, Object> row : rows) {
            String state = Objects.toString(row.get("state"), "");
            if (DolphinAlerts.success(row)) success++;
            else if ("FAILURE".equals(state)) failed++;
            else if ("RUNNING_EXECUTION".equals(state)) running++;
            else if (DolphinCollector.ACTIVE.contains(state)) waiting++;
            else other++;
            if (Objects.toString(row.get("etl_date"), "").isEmpty()) unknown++;
        }
        r.put("total", rows.size());
        r.put("success", success);
        r.put("failed", failed);
        r.put("running", running);
        r.put("waiting", waiting);
        r.put("other", other);
        r.put("active", running + waiting);
        r.put("unfinished", rows.size() - success);
        r.put("unknown_business_date", unknown);
        return r;
    }

    @ExceptionHandler({IllegalArgumentException.class, java.time.DateTimeException.class})
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Object invalid(Exception ex) {
        return Collections.singletonMap("detail", MonitorCheckExecution.safe(ex.getMessage()));
    }
}
