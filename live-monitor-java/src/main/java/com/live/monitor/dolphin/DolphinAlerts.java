package com.live.monitor.dolphin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.alert.AlertService;
import com.live.monitor.entity.*;
import com.live.monitor.mapper.AlertMapper;
import com.live.monitor.mapper.MonitorServiceMapper;
import com.live.monitor.service.DolphinSchedulerService;
import com.live.monitor.util.MonitorTime;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class DolphinAlerts {
    final DolphinStore store;
    final DolphinSchedulerService api;
    final AlertService alerts;
    final MonitorServiceMapper services;
    final AlertMapper groups;
    final ObjectMapper json;

    public DolphinAlerts(
            DolphinStore store,
            DolphinSchedulerService api,
            AlertService alerts,
            MonitorServiceMapper services,
            AlertMapper groups,
            ObjectMapper json) {
        this.store = store;
        this.api = api;
        this.alerts = alerts;
        this.services = services;
        this.groups = groups;
        this.json = json;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10000)
    public void watchdog() {
        for (MonitorService cluster : services.list(0))
            if ("dolphinscheduler".equals(cluster.serviceType))
                try {
                    Map<String, Object> config = api.config(cluster);
                    if (!Boolean.TRUE.equals(config.get("enterprise_enabled"))
                            || !Boolean.TRUE.equals(config.get("enterprise_alerts_enabled")))
                        continue;
                    for (String lane :
                            Arrays.asList("inventory", "runtime", "resources", "users")) {
                        Map<String, Object> collection = store.collection(cluster.id, lane);
                        String last = text(collection, "last_success"),
                                attempt = text(collection, "last_attempt");
                        if (last.isEmpty() && attempt.isEmpty()) continue;
                        int interval =
                                DolphinCollector.setting(
                                        config,
                                        lane + "_interval_seconds",
                                        lane.equals("resources")
                                                ? 15
                                                : lane.equals("runtime") ? 30 : 300,
                                        1,
                                        86400);
                        boolean stale =
                                Duration.between(
                                                        DolphinCollector.parse(
                                                                last.isEmpty() ? attempt : last),
                                                        MonitorTime.now())
                                                .getSeconds()
                                        > (last.isEmpty() ? 600 : Math.max(120, interval * 3));
                        String key = hash("stale:" + lane);
                        Map<String, Object> old =
                                store.incidents(cluster.id).stream()
                                        .filter(i -> key.equals(i.get("incident_key")))
                                        .findFirst()
                                        .orElse(null);
                        if (stale
                                && (integer(collection, "failures") == 0
                                        || old != null && integer(old, "active") == 1)) {
                            Map<String, Map<String, Object>> issues = new LinkedHashMap<>();
                            issue(
                                    issues,
                                    "stale:" + lane,
                                    "",
                                    "COLLECTION_STALE",
                                    "采集数据过期：" + lane + "，最后成功 " + (last.isEmpty() ? "无" : last));
                            emit(cluster, issues.get(key), true);
                        } else if (!stale
                                && integer(collection, "failures") == 0
                                && old != null
                                && integer(old, "active") == 1) emit(cluster, old, false);
                    }
                } catch (Exception ex) {
                    org.slf4j.LoggerFactory.getLogger(DolphinAlerts.class)
                            .warn(
                                    "DolphinScheduler stale-data watchdog failed for cluster {}",
                                    cluster.id,
                                    ex);
                }
    }

    public void evaluate(MonitorService cluster, String lane) {
        Map<String, Object> c = api.config(cluster);
        if (!Boolean.TRUE.equals(c.get("enterprise_alerts_enabled"))) return;
        Map<String, Map<String, Object>> issues = new LinkedHashMap<>();
        Map<String, Object> state = store.collection(cluster.id, lane);
        if (((Number) state.getOrDefault("failures", 0)).intValue()
                >= DolphinCollector.setting(c, "collection_failure_threshold", 2, 1, 100))
            issue(
                    issues,
                    "collection:" + lane,
                    "",
                    "COLLECTION_INTERRUPTED",
                    "采集异常：" + lane + "，" + state.get("error_message"));
        boolean fresh =
                ((Number) state.getOrDefault("failures", 0)).intValue() == 0
                        && state.get("last_success") != null;
        if (fresh && "resources".equals(lane)) {
            for (Map<String, Object> node : store.entities(cluster.id, "node", null)) {
                String id = node.get("id").toString();
                if (!Boolean.TRUE.equals(node.get("online"))
                        && ((Number) node.getOrDefault("offline_confirmations", 0)).intValue() >= 2)
                    issue(
                            issues,
                            "node:" + id,
                            "",
                            "NODE_OFFLINE",
                            node.get("role")
                                    + " 节点离线或心跳过期："
                                    + node.get("host")
                                    + "，最后心跳 "
                                    + node.get("heartbeat"));
                if (!Boolean.TRUE.equals(node.get("online"))) continue;
                for (String[] metric :
                        new String[][] {
                            {"cpu_percent", "CPU_HIGH", "CPU"},
                            {"host_memory_percent", "MEMORY_HIGH", "主机内存"},
                            {"heap_max_percent", "JVM_HIGH", "JVM 最大堆使用率"}
                        }) {
                    double threshold = threshold(cluster.id, "", "", id, metric[1], 85);
                    Object value = node.get(metric[0]);
                    if (value instanceof Number && ((Number) value).doubleValue() > threshold) {
                        List<Map<String, Object>> history = store.samples(cluster.id, id, 1);
                        int required =
                                DolphinCollector.setting(
                                        c, "resource_confirmation_samples", 3, 1, 100);
                        boolean sustained = history.size() >= required;
                        for (int i = Math.max(0, history.size() - required);
                                i < history.size();
                                i++) {
                            Object v = history.get(i).get(metric[0]);
                            if (!(v instanceof Number) || ((Number) v).doubleValue() <= threshold)
                                sustained = false;
                        }
                        if (sustained && threshold >= 0)
                            issue(
                                    issues,
                                    "resource:" + id + ":" + metric[1],
                                    "",
                                    metric[1],
                                    id + " " + metric[2] + " " + value + "% > " + threshold
                                            + "%，持续 " + required + " 次采样");
                    }
                }
                Object disk = node.get("disk_available_gb");
                double minimum = threshold(cluster.id, "", "", id, "DISK_LOW", 5);
                if (disk instanceof Number && ((Number) disk).doubleValue() < minimum)
                    issue(
                            issues,
                            "resource:" + id + ":DISK_LOW",
                            "",
                            "DISK_LOW",
                            id + " 可用磁盘 " + disk + " GB < " + minimum + " GB");
            }
        }
        if ("runtime".equals(lane)) {
            List<Map<String, Object>> nodes = store.entities(cluster.id, "node", null);
            List<Map<String, Object>> offline = new ArrayList<>();
            for (Map<String, Object> n : nodes)
                if (!Boolean.TRUE.equals(n.get("online"))
                        && ((Number) n.getOrDefault("offline_confirmations", 0)).intValue() >= 2)
                    offline.add(n);
            boolean rootFailure = !offline.isEmpty() && isFresh(cluster, "resources");
            int suppressed = 0;
            List<Map<String, Object>>
                    instances = store.entities(cluster.id, "workflow_instance", null),
                    tasks = store.entities(cluster.id, "task_instance", null);
            for (Map<String, Object> row : instances) {
                if (!isProjectFresh(cluster, text(row, "project_code"))) continue;
                if (failed(row)) {
                    if (rootFailure) {
                        suppressed++;
                        continue;
                    }
                    businessIssue(cluster, issues, row, "WORKFLOW_FAILURE", "工作流最终失败");
                }
                double timeout =
                        threshold(
                                cluster.id,
                                text(row, "project_code"),
                                text(row, "definition_code"),
                                "",
                                "TIMEOUT",
                                10800);
                if (timeout >= 0
                        && DolphinCollector.ACTIVE.contains(row.get("state"))
                        && duration(row, api.sourceNow(cluster)) > timeout)
                    businessIssue(cluster, issues, row, "TIMEOUT", "工作流执行超时");
            }
            for (Map<String, Object> row : tasks) {
                if (!isProjectFresh(cluster, text(row, "project_code"))) continue;
                if (failed(row)) {
                    int retry = integer(row, "retry_times"), max = integer(row, "max_retry_times");
                    if (retry < max) continue;
                    if (rootFailure) {
                        suppressed++;
                        continue;
                    }
                    businessIssue(cluster, issues, row, "TASK_FAILURE", "任务最终失败");
                }
                double timeout =
                        threshold(
                                cluster.id,
                                text(row, "project_code"),
                                text(row, "definition_code"),
                                text(row, "task_code"),
                                "TIMEOUT",
                                10800);
                if (timeout >= 0
                        && DolphinCollector.ACTIVE.contains(row.get("state"))
                        && duration(row, api.sourceNow(cluster)) > timeout)
                    businessIssue(cluster, issues, row, "TIMEOUT", "任务执行超时");
            }
            if (suppressed > 0)
                issue(
                        issues,
                        "root:cluster",
                        "",
                        "CLUSTER_ROOT_CAUSE",
                        "节点故障关联聚合："
                                + offline.size()
                                + " 个离线节点，"
                                + suppressed
                                + " 个失败实例；请先检查集群资源。关联为时间相关性推断。");
            for (Map<String, Object> p : store.entities(cluster.id, "project", null)) {
                String code = p.get("id").toString();
                long queued =
                        tasks.stream()
                                .filter(
                                        r ->
                                                code.equals(r.get("project_code"))
                                                        && Arrays.asList(
                                                                        "SUBMITTED_SUCCESS",
                                                                        "DELAY_EXECUTION",
                                                                        "SERIAL_WAIT",
                                                                        "WAITING_THREAD",
                                                                        "WAITING_DEPEND")
                                                                .contains(r.get("state")))
                                .count();
                double limit = threshold(cluster.id, code, "", "", "BACKLOG", 100);
                if (limit >= 0 && queued > limit)
                    issue(
                            issues,
                            "backlog:" + code,
                            code,
                            "BACKLOG",
                            "项目 " + p.get("name") + " 等待任务 " + queued + " > " + limit);
            }
            for (Map<String, Object> rule : store.rules(cluster.id))
                if ("ETL_DEADLINE".equals(rule.get("rule_type"))
                        && enabled(rule)
                        && rule.get("deadline") != null
                        && DolphinCollector.parse(rule.get("deadline").toString())
                                .isBefore(MonitorTime.now())) {
                    String project =
                            "project".equals(rule.get("scope_type")) ? text(rule, "scope_id") : "";
                    String definition =
                            "workflow".equals(rule.get("scope_type")) ? text(rule, "scope_id") : "";
                    String taskCode =
                            "task".equals(rule.get("scope_type")) ? text(rule, "scope_id") : "";
                    if (project.isEmpty()
                            ? !isFresh(cluster, "runtime")
                            : !isProjectFresh(cluster, project)) continue;
                    List<Map<String, Object>> rows = taskCode.isEmpty() ? instances : tasks;
                    List<Map<String, Object>> matching = new ArrayList<>();
                    for (Map<String, Object> row : rows)
                        if ((project.isEmpty() || project.equals(row.get("project_code")))
                                && (definition.isEmpty()
                                        || definition.equals(row.get("definition_code")))
                                && (taskCode.isEmpty() || taskCode.equals(row.get("task_code")))
                                && Objects.equals(rule.get("business_date"), row.get("etl_date")))
                            matching.add(row);
                    // Latest retry per definition/task determines completion; earlier failed
                    // attempts remain history.
                    Map<String, Map<String, Object>> latest = new HashMap<>();
                    for (Map<String, Object> row : matching) {
                        String key =
                                text(row, taskCode.isEmpty() ? "definition_code" : "task_code");
                        Map<String, Object> prev = latest.get(key);
                        if (prev == null
                                || text(row, "start_time").compareTo(text(prev, "start_time")) > 0)
                            latest.put(key, row);
                    }
                    boolean missingExpected = false;
                    if (!project.isEmpty())
                        for (Map<String, Object> expectedDefinition :
                                store.entities(cluster.id, "workflow_definition", project))
                            if ("ONLINE".equals(expectedDefinition.get("releaseState"))
                                    && !latest.containsKey(expectedDefinition.get("id")))
                                missingExpected = true;
                    if (latest.isEmpty()
                            || missingExpected
                            || latest.values().stream().anyMatch(r -> !success(r)))
                        issue(
                                issues,
                                "deadline:" + rule.get("scope_type") + ":" + rule.get("scope_id"),
                                project,
                                "ETL_DEADLINE",
                                "业务日期 "
                                        + rule.get("business_date")
                                        + " 未按截止时间完成，范围 "
                                        + rule.get("scope_type")
                                        + ":"
                                        + rule.get("scope_id")
                                        + "；未知业务日期不会当作成功");
                }
        }
        Set<String> categories = new HashSet<>(Arrays.asList("COLLECTION_INTERRUPTED"));
        if ("resources".equals(lane))
            categories.addAll(
                    Arrays.asList(
                            "NODE_OFFLINE", "CPU_HIGH", "MEMORY_HIGH", "JVM_HIGH", "DISK_LOW"));
        if ("runtime".equals(lane))
            categories.addAll(
                    Arrays.asList(
                            "WORKFLOW_FAILURE",
                            "TASK_FAILURE",
                            "TIMEOUT",
                            "BACKLOG",
                            "ETL_DEADLINE",
                            "CLUSTER_ROOT_CAUSE"));
        if ("runtime".equals(lane)) {
            aggregate(cluster, issues);
            categories.addAll(
                    Arrays.asList(
                            "AGGREGATED_TIMEOUT",
                            "AGGREGATED_TASK_FAILURE",
                            "AGGREGATED_WORKFLOW_FAILURE"));
        }
        for (Map<String, Object> issue : issues.values()) emit(cluster, issue, true);
        for (Map<String, Object> old : store.incidents(cluster.id))
            if (integer(old, "active") == 1
                    && categories.contains(old.get("category"))
                    && !issues.containsKey(old.get("incident_key"))) {
                if ("COLLECTION_INTERRUPTED".equals(old.get("category"))
                        && !text(old, "message").contains("采集异常：" + lane + "，")) continue;
                if (!fresh
                        && (text(old, "project_code").isEmpty()
                                || !isProjectFresh(cluster, text(old, "project_code")))) continue;
                // Never call an inaccessible or removed project "recovered". Wait for a successful
                // observation.
                if (!text(old, "project_code").isEmpty()
                        && !isProjectFresh(cluster, text(old, "project_code"))) continue;
                if (!observedRecovery(cluster, old)) continue;
                if ("runtime".equals(lane)
                        && issues.values().stream()
                                .anyMatch(i -> "CLUSTER_ROOT_CAUSE".equals(i.get("category")))
                        && Arrays.asList("WORKFLOW_FAILURE", "TASK_FAILURE")
                                .contains(old.get("category"))) continue;
                emit(cluster, old, false);
            }
    }

    boolean observedRecovery(MonitorService cluster, Map<String, Object> incident) {
        String category = text(incident, "category");
        if (category.startsWith("AGGREGATED_")) {
            List<Map<String, Object>> members =
                    store.members(cluster.id, text(incident, "incident_key"));
            if (members.isEmpty()) return false;
            for (Map<String, Object> member : members) {
                Map<String, Object> row =
                        store.entity(
                                cluster.id, text(member, "member_kind"), text(member, "member_id"));
                if (row == null) return false;
                if (category.equals("AGGREGATED_TIMEOUT")
                        ? DolphinCollector.ACTIVE.contains(row.get("state"))
                        : !success(row)) return false;
            }
            return true;
        }
        if (!Arrays.asList("WORKFLOW_FAILURE", "TASK_FAILURE", "TIMEOUT").contains(category))
            return true;
        for (String kind : Arrays.asList("workflow_instance", "task_instance"))
            for (Map<String, Object> row : store.entities(cluster.id, kind, null)) {
                String prefix = kind.equals("workflow_instance") ? "workflow:" : "task:";
                if (hash(category + ":" + prefix + row.get("id"))
                        .equals(incident.get("incident_key")))
                    return "TIMEOUT".equals(category)
                            ? !DolphinCollector.ACTIVE.contains(row.get("state"))
                            : success(row);
            }
        return false;
    }

    boolean isFresh(MonitorService s, String lane) {
        Map<String, Object> state = store.collection(s.id, lane);
        int interval =
                DolphinCollector.setting(
                        api.config(s),
                        lane.split(":")[0] + "_interval_seconds",
                        lane.startsWith("resources") ? 15 : 30,
                        1,
                        86400);
        long tolerance = Math.max(120, interval * 3L);
        if (lane.startsWith("runtime") && state.get("watermark") != null) {
            java.time.LocalDateTime source = api.sourceNow(s);
            if (source == null) source = MonitorTime.now();
            if (DolphinCollector.parse(state.get("watermark").toString())
                    .plusSeconds(tolerance)
                    .isBefore(source)) return false;
        }
        return integer(state, "failures") == 0
                && state.get("last_success") != null
                && DolphinCollector.parse(state.get("last_success").toString())
                        .plusSeconds(tolerance)
                        .isAfter(MonitorTime.now());
    }

    boolean isProjectFresh(MonitorService s, String project) {
        return isFresh(s, "runtime:" + project);
    }

    void businessIssue(
            MonitorService s,
            Map<String, Map<String, Object>> issues,
            Map<String, Object> row,
            String type,
            String label) {
        String project = text(row, "project_code"),
                workflow = text(row, "definition_code"),
                task = text(row, "task_code");
        if (threshold(s.id, project, workflow, task, type, 1) < 0) return;
        issue(
                issues,
                type + ":" + (task.isEmpty() ? "workflow:" : "task:") + row.get("id"),
                project,
                type,
                label
                        + "；项目 "
                        + project
                        + "；工作流 "
                        + workflow
                        + "；实例 "
                        + row.get("id")
                        + "；ETL_Date "
                        + (text(row, "etl_date").isEmpty() ? "未知" : row.get("etl_date"))
                        + "；状态 "
                        + row.get("state")
                        + "；节点 "
                        + row.get("host")
                        + "；失败原因 "
                        + text(row, "failure_reason"));
        Map<String, Object> detail =
                issues.get(
                        hash(
                                type
                                        + ":"
                                        + (task.isEmpty() ? "workflow:" : "task:")
                                        + row.get("id")));
        detail.put("member_kind", task.isEmpty() ? "workflow_instance" : "task_instance");
        detail.put("member_id", row.get("id"));
    }

    void aggregate(MonitorService cluster, Map<String, Map<String, Object>> issues) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> issue : issues.values())
            if (Arrays.asList("TIMEOUT", "TASK_FAILURE", "WORKFLOW_FAILURE")
                    .contains(issue.get("category")))
                grouped.computeIfAbsent(
                                issue.get("project_code") + ":" + issue.get("category"),
                                k -> new ArrayList<>())
                        .add(issue);
        List<Map<String, Object>> existing = store.incidents(cluster.id);
        for (List<Map<String, Object>> members : grouped.values()) {
            Map<String, Object> first = members.get(0);
            String category = text(first, "category"),
                    project = text(first, "project_code"),
                    identity = "aggregate:" + category + ":" + project,
                    key = hash(identity);
            boolean continuing =
                    existing.stream()
                            .anyMatch(
                                    i ->
                                            key.equals(i.get("incident_key"))
                                                    && integer(i, "active") == 1);
            int threshold =
                    category.equals("TIMEOUT")
                            ? 1
                            : DolphinCollector.setting(
                                    api.config(cluster),
                                    "incident_aggregation_threshold",
                                    10,
                                    2,
                                    1000);
            if (members.size() < threshold && !continuing) continue;
            List<String> examples = new ArrayList<>();
            for (Map<String, Object> member : members) {
                issues.remove(member.get("incident_key"));
                if (examples.size() < 8)
                    examples.add(member.get("member_kind") + ":" + member.get("member_id"));
            }
            issue(
                    issues,
                    identity,
                    project,
                    "AGGREGATED_" + category,
                    "项目 "
                            + project
                            + " 聚合 "
                            + category
                            + "："
                            + members.size()
                            + " 个异常工作流/任务实例；示例 "
                            + String.join(", ", examples)
                            + "。完整成员保存在事件中，历史运行状态来自调度元数据，监控不会修改任务。");
            issues.get(key).put("members", members);
        }
    }

    public double threshold(
            long cluster,
            String project,
            String workflow,
            String task,
            String type,
            double fallback) {
        Map<String, Object> selected = null;
        int rank = -1;
        for (Map<String, Object> r : store.rules(cluster))
            if (type.equals(r.get("rule_type"))) {
                String scope = text(r, "scope_type"), id = text(r, "scope_id");
                int candidate =
                        "platform".equals(scope)
                                ? 0
                                : "project".equals(scope) && id.equals(project)
                                        ? 1
                                        : "workflow".equals(scope) && id.equals(workflow)
                                                ? 2
                                                : "task".equals(scope) && id.equals(task) ? 3 : -1;
                if (candidate > rank) {
                    rank = candidate;
                    selected = r;
                }
            }
        if (selected == null) return fallback;
        if (!enabled(selected)) return -1;
        Object value = selected.get("threshold_value");
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    void issue(
            Map<String, Map<String, Object>> issues,
            String key,
            String project,
            String category,
            String message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("incident_key", hash(key));
        row.put("project_code", project);
        row.put("category", category);
        row.put("message", message);
        issues.put(row.get("incident_key").toString(), row);
    }

    void emit(MonitorService cluster, Map<String, Object> issue, boolean active) {
        String project = text(issue, "project_code"), key = text(issue, "incident_key");
        Map<String, Object> pc = store.config(cluster.id, project);
        if (active && !pc.isEmpty() && integer(pc, "alert_enabled") == 0) return;
        List<Long> targets = new ArrayList<>();
        boolean ownerKnown = !text(pc, "primary_owner").trim().isEmpty();
        if (ownerKnown) targets.addAll(store.groups(text(pc, "group_ids")));
        if (targets.isEmpty()) {
            Object configured = api.config(cluster).get("default_ops_group_ids");
            if (configured instanceof List)
                for (Object v : (List<?>) configured) targets.add(Long.valueOf(v.toString()));
            if (targets.isEmpty())
                for (ServiceAlertGroupBinding b :
                        services.listServiceAlertGroups(Collections.singletonList(cluster.id)))
                    targets.add(b.groupId);
        }
        if (!active && issue.get("group_ids") != null)
            targets = store.groups(issue.get("group_ids").toString());
        final List<Long> route = targets;
        store.transaction()
                .execute(
                        tx -> {
                            MonitorService scoped = new MonitorService();
                            scoped.id = cluster.id;
                            scoped.serviceName =
                                    cluster.serviceName
                                            + " / "
                                            + (project.isEmpty() ? "平台运维" : project);
                            scoped.serviceType = "dolphinscheduler";
                            scoped.endpoint = cluster.endpoint;
                            scoped.serviceAlertEnabled = true;
                            scoped.serviceConsecutiveFailures = 1;
                            scoped.serviceRecoverSuccesses = 1;
                            scoped.alertGroupIds = route;
                            scoped.alertGroupId = route.isEmpty() ? null : route.get(0);
                            scoped.alertGroupEnabled =
                                    scoped.alertGroupId != null
                                            && groups.findGroup(scoped.alertGroupId) != null
                                            && Boolean.TRUE.equals(
                                                    groups.findGroup(scoped.alertGroupId).enabled);
                            scoped.configJson =
                                    "{\"unknown_alert_enabled\":true,\"unknown_consecutive_failures\":1,\"alert_repeat_interval_seconds\":0}";
                            MonitorResult result = new MonitorResult();
                            result.serviceId = cluster.id;
                            result.status =
                                    active
                                            ? (text(issue, "category").startsWith("COLLECTION_")
                                                    ? "UNKNOWN"
                                                    : "DOWN")
                                            : "UP";
                            result.responseTimeMs = 0;
                            result.checkedAt = MonitorTime.nowText();
                            result.eventType = "DS_" + issue.get("category");
                            result.message =
                                    (active ? "异常：" : "恢复：")
                                            + issue.get("message")
                                            + "；主要责任人 "
                                            + (ownerKnown ? pc.get("primary_owner") : "未配置，转交默认运维组")
                                            + "；备用责任人 "
                                            + text(pc, "backup_owner");
                            if (active && issue.get("members") instanceof List) {
                                for (Object value : (List<?>) issue.get("members")) {
                                    Map<String, Object> member = (Map<String, Object>) value;
                                    try {
                                        store.jdbc()
                                                .update(
                                                        "INSERT INTO"
                                                            + " ds_incident_member(cluster_id,incident_key,member_kind,member_id)"
                                                            + " VALUES(?,?,?,?)",
                                                        cluster.id,
                                                        key,
                                                        member.get("member_kind"),
                                                        member.get("member_id"));
                                    } catch (
                                            org.springframework.dao.DuplicateKeyException
                                                    duplicate) {
                                        /* membership already recorded */
                                    }
                                    String original =
                                            hash(
                                                    text(member, "category")
                                                            + ":"
                                                            + ("workflow_instance"
                                                                            .equals(
                                                                                    member.get(
                                                                                            "member_kind"))
                                                                    ? "workflow:"
                                                                    : "task:")
                                                            + member.get("member_id"));
                                    store.jdbc()
                                            .update(
                                                    "UPDATE ds_incident SET active=2 WHERE"
                                                        + " cluster_id=? AND incident_key=? AND"
                                                        + " active=1",
                                                    cluster.id,
                                                    original);
                                }
                            }
                            alerts.publishScopedCheckEvent(scoped, result, key);
                            if (!active && text(issue, "category").startsWith("AGGREGATED_")) {
                                String category =
                                        text(issue, "category").substring("AGGREGATED_".length());
                                for (Map<String, Object> member : store.members(cluster.id, key)) {
                                    String original =
                                            hash(
                                                    category
                                                            + ":"
                                                            + ("workflow_instance"
                                                                            .equals(
                                                                                    member.get(
                                                                                            "member_kind"))
                                                                    ? "workflow:"
                                                                    : "task:")
                                                            + member.get("member_id"));
                                    store.jdbc()
                                            .update(
                                                    "UPDATE ds_incident SET active=0,resolved_at=?"
                                                        + " WHERE cluster_id=? AND incident_key=?"
                                                        + " AND active=2",
                                                    MonitorTime.nowText(),
                                                    cluster.id,
                                                    original);
                                    store.jdbc()
                                            .update(
                                                    "UPDATE alert_state SET"
                                                        + " state='RECOVERED',fail_count=0,recover_count=1,active_policy_id=NULL,active_trigger_type=NULL,last_status='UP',last_message=?,last_event_at=?"
                                                        + " WHERE service_id=? AND alert_key LIKE"
                                                        + " ?",
                                                    "Recovered through verified aggregate " + key,
                                                    MonitorTime.nowText(),
                                                    cluster.id,
                                                    "%ds:" + original + ":availability");
                                }
                            }
                            boolean present =
                                    store.jdbc()
                                                    .queryForObject(
                                                            "SELECT COUNT(*) FROM ds_incident WHERE"
                                                                    + " cluster_id=? AND"
                                                                    + " incident_key=?",
                                                            Integer.class,
                                                            cluster.id,
                                                            key)
                                            > 0;
                            store.jdbc()
                                    .update(
                                            "UPDATE ds_incident SET"
                                                + " category=?,message=?,group_ids=?,active=?,resolved_at=?"
                                                + " WHERE cluster_id=? AND incident_key=?",
                                            issue.get("category"),
                                            issue.get("message"),
                                            store.encode(route),
                                            active ? 1 : 0,
                                            active ? null : MonitorTime.nowText(),
                                            cluster.id,
                                            key);
                            if (!present)
                                store.jdbc()
                                        .update(
                                                "INSERT INTO"
                                                    + " ds_incident(cluster_id,incident_key,project_code,category,message,group_ids,active,opened_at,resolved_at)"
                                                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                                                cluster.id,
                                                key,
                                                project,
                                                issue.get("category"),
                                                issue.get("message"),
                                                store.encode(route),
                                                active ? 1 : 0,
                                                MonitorTime.nowText(),
                                                active ? null : MonitorTime.nowText());
                            return null;
                        });
    }

    static boolean enabled(Map<String, Object> row) {
        Object value = row.get("enabled");
        return value instanceof Boolean
                ? (Boolean) value
                : value instanceof Number && ((Number) value).intValue() != 0;
    }

    static boolean failed(Map<String, Object> row) {
        return "FAILURE".equals(row.get("state"));
    }

    static boolean success(Map<String, Object> row) {
        return Arrays.asList("SUCCESS", "FORCED_SUCCESS").contains(row.get("state"));
    }

    static int integer(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(Objects.toString(v, "0"));
        } catch (Exception ex) {
            return 0;
        }
    }

    static String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }

    static double duration(Map<String, Object> row, java.time.LocalDateTime now) {
        try {
            return Duration.between(
                            DolphinCollector.parse(text(row, "start_time")),
                            text(row, "end_time").isEmpty()
                                    ? now
                                    : DolphinCollector.parse(text(row, "end_time")))
                    .getSeconds();
        } catch (Exception ex) {
            return 0;
        }
    }

    static String hash(String value) {
        try {
            byte[] bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte b : bytes) s.append(String.format("%02x", b & 255));
            return s.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
