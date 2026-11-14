package com.live.monitor.dolphin;

import com.fasterxml.jackson.databind.JsonNode;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.MonitorServiceMapper;
import com.live.monitor.service.DolphinSchedulerService;
import com.live.monitor.service.MonitorCheckExecution;
import com.live.monitor.util.MonitorTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

import javax.annotation.PreDestroy;

@Service
public class DolphinCollector {
    private com.live.monitor.alert.AlertOutbox auditOutbox;

    @org.springframework.beans.factory.annotation.Autowired
    public void setAuditOutbox(com.live.monitor.alert.AlertOutbox outbox) {
        this.auditOutbox = outbox;
    }

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final Set<String> ACTIVE =
            new HashSet<>(
                    Arrays.asList(
                            "RUNNING_EXECUTION",
                            "SUBMITTED_SUCCESS",
                            "DELAY_EXECUTION",
                            "SERIAL_WAIT",
                            "READY_PAUSE",
                            "READY_STOP",
                            "WAITING_THREAD",
                            "WAITING_DEPEND",
                            "NEED_FAULT_TOLERANCE"));
    final DolphinSchedulerService api;
    final DolphinStore store;
    final MonitorServiceMapper services;
    final DolphinResources resources;
    final DolphinAlerts alerts;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(
                    4,
                    4,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(32),
                    r -> {
                        Thread t = new Thread(r, "dolphin-collector");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    public DolphinCollector(
            DolphinSchedulerService api,
            DolphinStore store,
            MonitorServiceMapper services,
            DolphinResources resources,
            DolphinAlerts alerts) {
        this.api = api;
        this.store = store;
        this.services = services;
        this.resources = resources;
        this.alerts = alerts;
    }

    public static int setting(
            Map<String, Object> config, String name, int fallback, int min, int max) {
        Object v = config.get(name);
        int n = v == null ? fallback : Integer.parseInt(v.toString());
        if (n < min || n > max)
            throw new IllegalArgumentException(name + " must be " + min + "–" + max);
        return n;
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        for (MonitorService s : services.list(0))
            if ("dolphinscheduler".equals(s.serviceType)
                    && Boolean.TRUE.equals(api.config(s).get("enterprise_enabled")))
                enqueue(s, false);
    }

    public void enqueue(MonitorService s, boolean force) {
        Map<String, Object> c = api.config(s);
        for (String lane : Arrays.asList("inventory", "users", "runtime", "resources")) {
            int seconds =
                    setting(
                            c,
                            lane + "_interval_seconds",
                            lane.equals("resources") ? 15 : lane.equals("runtime") ? 30 : 300,
                            lane.equals("resources") ? 10 : 15,
                            86400);
            Map<String, Object> previous = store.collection(s.id, lane);
            String last = Objects.toString(previous.get("last_attempt"), "");
            if (!force
                    && !last.isEmpty()
                    && parse(last).plusSeconds(seconds).isAfter(MonitorTime.now())) continue;
            String key = s.id + ":" + lane;
            if (!running.add(key)) continue;
            try {
                workers.execute(
                        () -> {
                            try {
                                collect(s, lane);
                            } finally {
                                running.remove(key);
                            }
                        });
            } catch (RejectedExecutionException ex) {
                running.remove(key);
            }
        }
    }

    public void collect(MonitorService service, String lane) {
        store.attempt(service.id, lane);
        try {
            if ("inventory".equals(lane)) inventory(service);
            else if ("users".equals(lane)) users(service);
            else if ("runtime".equals(lane)) runtime(service);
            else if ("resources".equals(lane)) resources.collect(service);
            else throw new IllegalArgumentException("Unknown collection lane");
            String watermark = MonitorTime.nowText();
            if ("runtime".equals(lane))
                for (Map<String, Object> checkpoint : store.collections(service.id))
                    if (Objects.toString(checkpoint.get("lane"), "").startsWith("runtime:")
                            && checkpoint.get("watermark") != null
                            && checkpoint.get("watermark").toString().compareTo(watermark) < 0)
                        watermark = checkpoint.get("watermark").toString();
            store.success(service.id, lane, watermark);
            if ("runtime".equals(lane))
                store.retain(
                        service.id, setting(api.config(service), "retention_days", 90, 1, 3650));
        } catch (Exception ex) {
            store.failure(service.id, lane, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            org.slf4j.LoggerFactory.getLogger(DolphinCollector.class)
                    .warn(
                            "DolphinScheduler collection failed for cluster {} lane {}",
                            service.id,
                            lane,
                            ex);
        }
        try {
            alerts.evaluate(service, lane);
            if (!store.collection(service.id, "alerts:" + lane).isEmpty())
                store.success(service.id, "alerts:" + lane, MonitorTime.nowText());
        } catch (Exception ex) {
            store.failure(
                    service.id,
                    "alerts:" + lane,
                    MonitorCheckExecution.safe(
                            ex.getClass().getSimpleName() + ": " + ex.getMessage()));
            org.slf4j.LoggerFactory.getLogger(DolphinCollector.class)
                    .warn(
                            "DolphinScheduler incident evaluation failed for cluster {} lane {}",
                            service.id,
                            lane,
                            ex);
            if (auditOutbox != null)
                auditOutbox.recordBlocked(
                        service.id,
                        "ds_engine:" + lane,
                        "enterprise_engine",
                        "Delivery skipped: enterprise incident evaluation failed before task"
                                + " creation: "
                                + MonitorCheckExecution.safe(
                                        ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
    }

    List<JsonNode> pages(MonitorService service, String path, Map<String, String> params) {
        return api.all(service, path, params, System.nanoTime() + TimeUnit.MINUTES.toNanos(5));
    }

    List<JsonNode> projects(MonitorService service) {
        Set<String> scope = new HashSet<>();
        for (String v : Objects.toString(api.config(service).get("project_codes"), "").split(","))
            if (!v.trim().isEmpty()) scope.add(v.trim());
        List<JsonNode> rows = pages(service, "/projects", Collections.emptyMap());
        List<JsonNode> selected = new ArrayList<>();
        Set<String> found = new HashSet<>();
        for (JsonNode row : rows) {
            String id = row.path("code").asText();
            if (scope.isEmpty() || scope.contains(id)) {
                selected.add(row);
                found.add(id);
            }
        }
        if (!found.containsAll(scope))
            throw new IllegalStateException("Configured projects are unavailable or unauthorized");
        return selected;
    }

    void inventory(MonitorService s) {
        List<JsonNode> projects = projects(s);
        List<Map<String, Object>> projectRows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> found = new HashSet<>();
        for (JsonNode p : projects) {
            String code = p.path("code").asText();
            found.add(code);
            Map<String, Object> project =
                    fields(p, "code", "name", "description", "userId", "userName");
            project.put("id", code);
            project.put("project_code", code);
            project.put("owner", p.path("userName").asText());
            projectRows.add(project);
            try {
                List<Map<String, Object>> definitions = new ArrayList<>(),
                        tasks = new ArrayList<>();
                List<Map<String, Object>> cachedTasks =
                        store.entities(s.id, "task_definition", code);
                for (JsonNode n :
                        pages(
                                s,
                                "/projects/" + code + "/process-definition",
                                Collections.emptyMap())) {
                    String workflow = n.path("code").asText();
                    Map<String, Object> row =
                            fields(
                                    n,
                                    "name",
                                    "version",
                                    "releaseState",
                                    "scheduleReleaseState",
                                    "userName",
                                    "updateTime",
                                    "timeout");
                    row.put("id", workflow);
                    row.put("definition_code", workflow);
                    row.put("project_code", code);
                    Map<String, Object> old = store.entity(s.id, "workflow_definition", workflow);
                    if (old != null
                            && Objects.equals(old.get("version"), row.get("version"))
                            && Objects.equals(old.get("updateTime"), row.get("updateTime"))
                            && old.get("detail_synced_at") != null
                            && parse(old.get("detail_synced_at").toString())
                                    .plusHours(1)
                                    .isAfter(MonitorTime.now())) {
                        for (Map<String, Object> task : cachedTasks)
                            if (workflow.equals(task.get("definition_code"))) tasks.add(task);
                        row.put("task_count", old.get("task_count"));
                        row.put("detail_synced_at", old.get("detail_synced_at"));
                    } else {
                        JsonNode detail =
                                api.get(
                                        s,
                                        "/projects/" + code + "/process-definition/" + workflow,
                                        Collections.emptyMap());
                        JsonNode list = detail.path("taskDefinitionList");
                        if (!list.isArray())
                            throw new IllegalStateException(
                                    "Unsupported definition detail: taskDefinitionList missing");
                        row.put("task_count", list.size());
                        row.put("detail_synced_at", MonitorTime.nowText());
                        for (JsonNode t : list) {
                            Map<String, Object> task =
                                    fields(
                                            t,
                                            "name",
                                            "taskType",
                                            "version",
                                            "timeoutFlag",
                                            "timeout",
                                            "failRetryTimes",
                                            "userName");
                            String taskCode = t.path("code").asText();
                            task.put("id", workflow + ":" + taskCode);
                            task.put("task_code", taskCode);
                            task.put("definition_code", workflow);
                            task.put("project_code", code);
                            tasks.add(task);
                        }
                    }
                    definitions.add(row);
                }
                store.transaction()
                        .execute(
                                tx -> {
                                    store.replace(s.id, "workflow_definition", code, definitions);
                                    store.replace(s.id, "task_definition", code, tasks);
                                    store.success(s.id, "inventory:" + code, MonitorTime.nowText());
                                    return null;
                                });
            } catch (Exception ex) {
                store.failure(s.id, "inventory:" + code, ex.getMessage());
                errors.add(code + ": " + ex.getMessage());
            }
        }
        for (Map<String, Object> old : store.entities(s.id, "project", null))
            if (!found.contains(old.get("id"))) {
                String code = old.get("id").toString();
                store.replace(s.id, "workflow_definition", code, Collections.emptyList());
                store.replace(s.id, "task_definition", code, Collections.emptyList());
                store.replace(s.id, "authorization", code, Collections.emptyList());
                store.jdbc()
                        .update(
                                "DELETE FROM ds_collection WHERE cluster_id=? AND lane IN (?,?,?)",
                                s.id,
                                "inventory:" + code,
                                "runtime:" + code,
                                "authorization:" + code);
            }
        store.replace(s.id, "project", null, projectRows);
        if (!errors.isEmpty())
            throw new IllegalStateException(
                    "Partial inventory failure: " + String.join("; ", errors));
    }

    void users(MonitorService s) {
        List<Map<String, Object>> users = new ArrayList<>();
        for (JsonNode n : pages(s, "/users/list-paging", Collections.emptyMap()))
            users.add(fields(n, "id", "userName", "state", "userType"));
        store.replace(s.id, "user", null, users);
        for (JsonNode project : projects(s)) {
            String code = project.path("code").asText();
            JsonNode data =
                    api.get(
                            s,
                            "/projects/authed-user",
                            Collections.singletonMap("projectCode", code));
            JsonNode rows = data.isArray() ? data : data.path("authedUser");
            if (!rows.isArray()) {
                store.failure(
                        s.id,
                        "authorization:" + code,
                        "User authorization API returned no accessible relationship list");
                continue;
            }
            List<Map<String, Object>> auth = new ArrayList<>();
            for (JsonNode n : rows) {
                Map<String, Object> row = fields(n, "userName");
                row.put("user_id", n.path("id").asText());
                row.put("id", code + ":" + n.path("id").asText());
                row.put("project_code", code);
                auth.add(row);
            }
            store.replace(s.id, "authorization", code, auth);
            store.success(s.id, "authorization:" + code, MonitorTime.nowText());
        }
    }

    void runtime(MonitorService s) {
        Map<String, Object> config = api.config(s);
        List<String> errors = new ArrayList<>();
        for (JsonNode project : projects(s)) {
            String code = project.path("code").asText(), lane = "runtime:" + code;
            Map<String, Object> checkpoint = store.collection(s.id, lane);
            LocalDateTime until = api.sourceNow(s);
            String watermark = Objects.toString(checkpoint.get("watermark"), "");
            String coverageStart =
                    Objects.toString(
                            store.collection(s.id, "runtime_coverage:" + code).get("watermark"),
                            "");
            // Existing installations get one successful full initial-window pass before absence
            // can be counted. A saved cursor alone does not prove the lower coverage boundary.
            if (coverageStart.isEmpty()) watermark = "";
            LocalDateTime since =
                    watermark.isEmpty()
                            ? until.minusHours(setting(config, "initial_window_hours", 24, 1, 744))
                            : parse(watermark).minusMinutes(10);
            if (!watermark.isEmpty() && since.plusHours(6).isBefore(until))
                until = since.plusHours(6);
            final LocalDateTime collectedUntil = until;
            final String coveredFrom = coverageStart.isEmpty() ? TIME.format(since) : coverageStart;
            Map<String, String> filters = new LinkedHashMap<>();
            filters.put("startDate", TIME.format(since));
            filters.put("endDate", TIME.format(until));
            try {
                Map<String, JsonNode> instances = new LinkedHashMap<>();
                for (JsonNode n : pages(s, "/projects/" + code + "/process-instances", filters))
                    instances.put(n.path("id").asText(), n);
                // The extended states come from the deployed fork's ExecutionStatus enum.
                List<String> liveStates =
                        "ld-2.0.8".equals(config.get("api_profile"))
                                ? Arrays.asList(
                                        "RUNNING_EXECUTION",
                                        "SUBMITTED_SUCCESS",
                                        "READY_PAUSE",
                                        "READY_STOP",
                                        "WAITING_THREAD",
                                        "WAITING_DEPEND",
                                        "DELAY_EXECUTION",
                                        "NEED_FAULT_TOLERANCE")
                                : Arrays.asList("RUNNING_EXECUTION", "SUBMITTED_SUCCESS");
                for (String active : liveStates)
                    for (JsonNode n :
                            pages(
                                    s,
                                    "/projects/" + code + "/process-instances",
                                    Collections.singletonMap("stateType", active)))
                        instances.put(n.path("id").asText(), n);
                // Active instances retain their identity even when their start falls outside the
                // incremental window.
                for (Map<String, Object> old : store.entities(s.id, "workflow_instance", code))
                    if (ACTIVE.contains(old.get("state"))
                            && !instances.containsKey(old.get("id"))) {
                        String id = old.get("id").toString();
                        instances.put(
                                id,
                                api.get(
                                        s,
                                        "/projects/" + code + "/process-instances/" + id,
                                        Collections.emptyMap()));
                    }
                List<Map<String, Object>> workflows = new ArrayList<>(), tasks = new ArrayList<>();
                int capturedFailures = 0;
                for (JsonNode listed : instances.values()) {
                    String id = listed.path("id").asText();
                    Map<String, Object> old = store.entity(s.id, "workflow_instance", id);
                    if (old != null
                            && !ACTIVE.contains(old.get("state"))
                            && Objects.equals(old.get("state"), listed.path("state").asText())
                            && Objects.equals(old.get("run_times"), listed.path("runTimes").asInt())
                            && Objects.equals(
                                    old.get("end_time"), listed.path("endTime").asText("")))
                        continue;
                    JsonNode detail =
                            api.get(
                                    s,
                                    "/projects/" + code + "/process-instances/" + id,
                                    Collections.emptyMap());
                    Map<String, Object> row = api.instance(detail);
                    row.put("project_code", code);
                    row.put("project_name", project.path("name").asText());
                    row.put("workflow_instance_id", id);
                    row.put("run_times", detail.path("runTimes").asInt());
                    String date = api.businessDate(s, detail, detail);
                    row.put("schedule_time", detail.path("scheduleTime").asText(""));
                    row.put("etl_date", date);
                    row.put(
                            "etl_date_source",
                            date.isEmpty()
                                    ? "UNKNOWN"
                                    : api.businessDate(s, detail).isEmpty()
                                            ? "verified_logical_schedule"
                                            : "workflow_parameters");
                    workflows.add(row);
                    JsonNode data =
                            api.get(
                                    s,
                                    "/projects/" + code + "/process-instances/" + id + "/tasks",
                                    Collections.emptyMap());
                    JsonNode list = data.isArray() ? data : data.path("taskList");
                    if (!list.isArray())
                        throw new IllegalStateException("Unsupported task instance response");
                    for (JsonNode n : list) {
                        Map<String, Object> task = api.instance(n);
                        task.put("project_code", code);
                        task.put("project_name", project.path("name").asText());
                        task.put("workflow_instance_id", id);
                        task.put("definition_code", row.get("definition_code"));
                        task.put("workflow_name", row.get("name"));
                        task.put("max_retry_times", n.path("maxRetryTimes").asInt());
                        String taskDate = api.businessDate(s, n, detail);
                        task.put("etl_date", taskDate.isEmpty() ? date : taskDate);
                        task.put(
                                "etl_date_source",
                                !taskDate.isEmpty()
                                        ? (api.businessDate(s, n).isEmpty()
                                                ? "verified_logical_schedule"
                                                : "task_parameters")
                                        : !date.isEmpty() ? "workflow_parameters" : "UNKNOWN");
                        task.put("log_available", !n.path("logPath").asText("").isEmpty());
                        tasks.add(task);
                    }
                    if (date.isEmpty() && list.size() > 0) {
                        Set<String> knownDates = new HashSet<>();
                        int known = 0;
                        for (Map<String, Object> task : tasks)
                            if (id.equals(task.get("workflow_instance_id"))
                                    && !Objects.toString(task.get("etl_date"), "").isEmpty()) {
                                knownDates.add(task.get("etl_date").toString());
                                known++;
                            }
                        if (known == list.size() && knownDates.size() == 1) {
                            row.put("etl_date", knownDates.iterator().next());
                            row.put("etl_date_source", "consistent_task_parameters");
                        }
                    }
                    List<String> reasons = new ArrayList<>();
                    for (Map<String, Object> task : tasks)
                        if (id.equals(task.get("workflow_instance_id"))
                                && "FAILURE".equals(task.get("state"))) {
                            String reason = "上游实例未提供失败原因，请查看任务日志";
                            if (Boolean.TRUE.equals(task.get("log_available"))
                                    && capturedFailures++ < 5)
                                try {
                                    Map<String, String> query = new LinkedHashMap<>();
                                    query.put("taskInstanceId", task.get("id").toString());
                                    query.put("skipLineNum", "0");
                                    query.put("limit", "100");
                                    JsonNode log = api.get(s, "/log/detail", query);
                                    String content = log.isTextual() ? log.asText() : "";
                                    for (String line : content.split("\\r?\\n"))
                                        if (line.matches(
                                                "(?i).*(error|exception|failed|exit code).*")) {
                                            reason = MonitorCheckExecution.safe(line);
                                            break;
                                        }
                                } catch (Exception ignored) {
                                }
                            task.put("failure_reason", reason);
                            reasons.add(reason);
                        }
                    if ("FAILURE".equals(row.get("state")))
                        row.put(
                                "failure_reason",
                                reasons.isEmpty()
                                        ? "上游实例未提供失败原因，请查看任务日志"
                                        : MonitorCheckExecution.safe(String.join("; ", reasons)));
                }
                store.transaction()
                        .execute(
                                tx -> {
                                    for (Map<String, Object> row : workflows)
                                        store.save(s.id, "workflow_instance", row);
                                    for (Map<String, Object> row : tasks)
                                        store.save(s.id, "task_instance", row);
                                    store.success(s.id, lane, TIME.format(collectedUntil));
                                    store.success(s.id, "runtime_coverage:" + code, coveredFrom);
                                    return null;
                                });
            } catch (Exception ex) {
                store.failure(s.id, lane, ex.getMessage());
                errors.add(code + ": " + ex.getMessage());
            }
        }
        if (!errors.isEmpty())
            throw new IllegalStateException(
                    "Partial runtime failure: " + String.join("; ", errors));
    }

    static Map<String, Object> fields(JsonNode n, String... keys) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : keys) {
            JsonNode value = n.path(key);
            row.put(
                    key,
                    value.isNull() || value.isMissingNode()
                            ? null
                            : value.isNumber()
                                    ? value.numberValue()
                                    : value.isBoolean() ? value.asBoolean() : value.asText());
        }
        return row;
    }

    static LocalDateTime parse(String value) {
        String text = value.replace('T', ' ');
        return LocalDateTime.parse(text.substring(0, Math.min(19, text.length())), TIME);
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }
}
