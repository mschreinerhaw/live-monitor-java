package com.live.monitor.dolphin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.entity.HostConfig;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.HostMapper;
import com.live.monitor.service.DolphinSchedulerService;
import com.live.monitor.service.SshService;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Service
public class DolphinResources {
    final DolphinSchedulerService api;
    final DolphinStore store;
    final ObjectMapper json;
    final HostMapper hosts;
    final SshService ssh;

    public DolphinResources(
            DolphinSchedulerService api,
            DolphinStore store,
            ObjectMapper json,
            HostMapper hosts,
            SshService ssh) {
        this.api = api;
        this.store = store;
        this.json = json;
        this.hosts = hosts;
        this.ssh = ssh;
    }

    public void collect(MonitorService s) throws Exception {
        List<Map<String, Object>> nodes = new ArrayList<>();
        int timeout =
                DolphinCollector.setting(api.config(s), "heartbeat_timeout_seconds", 120, 30, 3600);
        for (String role : Arrays.asList("masters", "workers")) {
            JsonNode list = api.get(s, "/monitor/" + role, Collections.emptyMap());
            if (!list.isArray()) throw new IllegalStateException("Unsupported node list: " + role);
            Set<String> seen = new HashSet<>();
            for (JsonNode n : list) {
                String host = n.path("host").asText(),
                        id = role + ":" + host + ":" + n.path("port").asText();
                seen.add(id);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("role", role);
                row.put("host", host);
                row.put("port", n.path("port").asText());
                row.put("heartbeat", n.path("lastHeartbeatTime").asText());
                JsonNode info = n.path("resInfo");
                if (info.isTextual()) info = json.readTree(info.asText());
                row.put("cpu_percent", ratio(info, "cpuUsage"));
                row.put("host_memory_percent", ratio(info, "memoryUsage"));
                row.put("load_average", numeric(info, "loadAverage"));
                row.put("disk_available_gb", numeric(info, "diskAvailable"));
                row.put(
                        "available_physical_memory_gb",
                        numeric(info, "availablePhysicalMemorySize"));
                row.put("waiting_tasks", numeric(info, "workerWaitingTaskCount"));
                row.put("execution_threads", numeric(info, "workerExecThreadCount"));
                row.put("pid", info.path("processId").asLong());
                row.put(
                        "metric_source",
                        "DolphinScheduler resInfo; ratios normalized to percent; disk in GB");
                String heartbeat = n.path("lastHeartbeatTime").asText("");
                boolean stale = true;
                try {
                    stale =
                            Duration.between(DolphinCollector.parse(heartbeat), api.sourceNow(s))
                                            .getSeconds()
                                    > timeout;
                } catch (Exception ignored) {
                }
                row.put("online", !stale);
                row.put("heartbeat_stale", stale);
                row.put("source_clock_skew_seconds", api.sourceClockSkewMillis(s) / 1000.0);
                row.put("offline_confirmations", stale ? priorMisses(s.id, id) + 1 : 0);
                row.put("jvm_available", false);
                row.put(
                        "jvm_unavailable_reason",
                        "No enabled Live Monitor SSH host mapping or jstat unavailable");
                if (Boolean.TRUE.equals(api.config(s).get("collect_jvm")))
                    collectJvm(row, host, info.path("processId").asLong());
                String instanceKind =
                        "masters".equals(role) ? "workflow_instance" : "task_instance";
                long active =
                        store.entities(s.id, instanceKind, null).stream()
                                .filter(
                                        r ->
                                                "RUNNING_EXECUTION".equals(r.get("state"))
                                                        && Objects.toString(r.get("host"), "")
                                                                .startsWith(host + ":"))
                                .count();
                row.put(
                        "running_instances",
                        store.collection(s.id, "runtime").get("last_success") == null
                                ? null
                                : active);
                row.put(
                        "running_instances_source",
                        "Persisted instance host association; check runtime collection freshness");
                Map<String, Object> previous = store.entity(s.id, "node", id);
                if (previous != null
                        && previous.get("pid") instanceof Number
                        && ((Number) previous.get("pid")).longValue()
                                == ((Number) row.get("pid")).longValue()
                        && previous.get("gc_time_seconds") instanceof Number
                        && row.get("gc_time_seconds") instanceof Number) {
                    double delta =
                            ((Number) row.get("gc_time_seconds")).doubleValue()
                                    - ((Number) previous.get("gc_time_seconds")).doubleValue();
                    if (delta >= 0) row.put("gc_time_delta_seconds", delta);
                }
                nodes.add(row);
            }
            for (Map<String, Object> old : store.entities(s.id, "node", null))
                if (role.equals(old.get("role")) && !seen.contains(old.get("id"))) {
                    Map<String, Object> missing = new LinkedHashMap<>(old);
                    missing.put("online", false);
                    missing.put(
                            "offline_confirmations",
                            priorMisses(s.id, old.get("id").toString()) + 1);
                    missing.put(
                            "metric_source",
                            "Absent from successful registry response; resource values are"
                                    + " historical");
                    missing.put("metrics_stale", true);
                    nodes.add(missing);
                }
        }
        JsonNode databases = api.get(s, "/monitor/databases", Collections.emptyMap());
        if (!databases.isArray())
            throw new IllegalStateException("Unsupported database monitor response");
        List<Map<String, Object>> db = new ArrayList<>();
        int index = 0;
        for (JsonNode n : databases) {
            Map<String, Object> row =
                    DolphinCollector.fields(
                            n,
                            "dbType",
                            "state",
                            "maxConnections",
                            "maxUsedConnections",
                            "threadsConnections",
                            "threadsRunningConnections",
                            "date");
            row.put("id", "db:" + (index++));
            db.add(row);
        }
        store.transaction()
                .execute(
                        tx -> {
                            store.replace(s.id, "node", null, nodes);
                            store.replace(s.id, "database", null, db);
                            for (Map<String, Object> row : nodes)
                                store.sample(s.id, row.get("id").toString(), row);
                            return null;
                        });
    }

    int priorMisses(long cluster, String id) {
        Map<String, Object> previous = store.entity(cluster, "node", id);
        return previous == null
                ? 0
                : ((Number) previous.getOrDefault("offline_confirmations", 0)).intValue();
    }

    void collectJvm(Map<String, Object> row, String address, long pid) {
        if (pid <= 0) return;
        HostConfig host = hosts.findEnabledByAddress(address);
        if (host == null) return;
        // Resolve the running JVM's own jstat. No service-control commands are executed.
        String command =
                "p="
                        + pid
                        + "; j=$(readlink -f /proc/$p/exe); home=${j%/bin/java}; if ["
                        + " \"${home##*/}\" = jre ]; then home=${home%/jre}; fi;"
                        + " tool=$home/bin/jstat; if [ -x \"$tool\" ]; then LC_ALL=C \"$tool\" -gc"
                        + " $p 1 1; else LC_ALL=C jstat -gc $p 1 1; fi; cmd=$home/bin/jcmd; if [ -x"
                        + " \"$cmd\" ]; then if [ \"$(id -u)\" = 0 ] && [ \"$(stat -c %u"
                        + " /proc/$p)\" != 0 ] && command -v runuser >/dev/null 2>&1; then LC_ALL=C"
                        + " runuser -u \"$(stat -c %U /proc/$p)\" -- \"$cmd\" $p VM.flags"
                        + " 2>/dev/null; else LC_ALL=C \"$cmd\" $p VM.flags 2>/dev/null; fi | grep"
                        + " -o 'MaxHeapSize=[0-9]*'; fi";
        try {
            String output = ssh.exec(host, command, 5000);
            Map<String, Double> stats = jstat(output);
            if (stats.isEmpty()) return;
            Double used = sum(stats, "S0U", "S1U", "EU", "OU"),
                    committed = sum(stats, "S0C", "S1C", "EC", "OC");
            if (used == null || committed == null || committed <= 0) return;
            java.util.regex.Matcher maximum =
                    java.util.regex.Pattern.compile("MaxHeapSize=([0-9]+)").matcher(output);
            row.put("jvm_max_heap_available", false);
            if (maximum.find()) {
                double max = Double.parseDouble(maximum.group(1));
                if (max > 0) {
                    row.put("heap_max_bytes", max);
                    row.put("heap_max_percent", used * 1024 * 100 / max);
                    row.put("jvm_max_heap_available", true);
                }
            }
            row.put("heap_used_bytes", used * 1024);
            row.put("heap_committed_bytes", committed * 1024);
            row.put("heap_committed_percent", used * 100 / committed);
            row.put("gc_young_count", stats.get("YGC"));
            row.put("gc_full_count", stats.get("FGC"));
            row.put("gc_time_seconds", stats.get("GCT"));
            row.put("jvm_available", true);
            row.put(
                    "jvm_source",
                    "JDK jstat -gc via existing SSH; heap percentage uses committed heap, not max"
                            + " heap; GCT is cumulative collection time");
            row.remove("jvm_unavailable_reason");
        } catch (Exception ex) {
            row.put("jvm_unavailable_reason", "JVM metrics unavailable; host metrics remain valid");
        }
    }

    public static Map<String, Double> jstat(String output) {
        Map<String, Double> values = new LinkedHashMap<>();
        String[] lines = output.trim().split("\\r?\\n");
        for (int i = 0; i + 1 < lines.length; i++) {
            if (!lines[i].contains("S0C") || !lines[i].contains("GCT")) continue;
            String[] header = lines[i].trim().split("\\s+"),
                    data = lines[i + 1].trim().split("\\s+");
            if (header.length != data.length) return values;
            for (int j = 0; j < header.length; j++)
                try {
                    double v = Double.parseDouble(data[j]);
                    if (Double.isFinite(v)) values.put(header[j], v);
                } catch (Exception ignored) {
                }
            break;
        }
        return values;
    }

    static Double sum(Map<String, Double> values, String... names) {
        double total = 0;
        for (String name : names) {
            if (!values.containsKey(name)) return null;
            total += values.get(name);
        }
        return total;
    }

    static Double numeric(JsonNode node, String key) {
        JsonNode v = node.path(key);
        return v.isNumber() && Double.isFinite(v.asDouble()) && v.asDouble() >= 0
                ? v.asDouble()
                : null;
    }

    static Double ratio(JsonNode node, String key) {
        Double value = numeric(node, key);
        return value == null || value > 1 ? null : value * 100;
    }
}
