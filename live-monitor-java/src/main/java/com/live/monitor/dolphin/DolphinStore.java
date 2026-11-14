package com.live.monitor.dolphin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.util.MonitorTime;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import javax.annotation.PostConstruct;

/**
 * SQL configuration and durable collection checkpoints. Parameters and secrets never enter this
 * store.
 */
@Repository
public class DolphinStore {
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    final TransactionTemplate tx;

    public DolphinStore(
            JdbcTemplate jdbc,
            ObjectMapper json,
            org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx =
                new TransactionTemplate(manager) {
                    @Override
                    public <T> T execute(
                            org.springframework.transaction.support.TransactionCallback<T> action) {
                        boolean nested =
                                org.springframework.transaction.support
                                        .TransactionSynchronizationManager
                                        .isActualTransactionActive();
                        for (int attempt = 0; ; attempt++) {
                            try {
                                return super.execute(action);
                            } catch (org.springframework.dao.TransientDataAccessException ex) {
                                if (nested || attempt >= 2) throw ex;
                                try {
                                    Thread.sleep(50L * (attempt + 1));
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw ex;
                                }
                            }
                        }
                    }
                };
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public TransactionTemplate transaction() {
        return tx;
    }

    @PostConstruct
    public void migrate() {
        new ResourceDatabasePopulator(new ClassPathResource("db/dolphin-enterprise.sql"))
                .execute(Objects.requireNonNull(jdbc.getDataSource()));
        jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Void>)
                        connection -> {
                            boolean exists = false;
                            try (java.sql.ResultSet r =
                                    connection
                                            .getMetaData()
                                            .getIndexInfo(
                                                    connection.getCatalog(),
                                                    null,
                                                    "ds_entity",
                                                    false,
                                                    false)) {
                                while (r.next())
                                    if ("ds_entity_project_idx"
                                            .equalsIgnoreCase(r.getString("INDEX_NAME")))
                                        exists = true;
                            }
                            if (!exists)
                                try (java.sql.Statement statement = connection.createStatement()) {
                                    statement.execute(
                                            "CREATE INDEX ds_entity_project_idx ON"
                                                    + " ds_entity(cluster_id,kind,project_code)");
                                }
                            return null;
                        });
    }

    public String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid monitor data", ex);
        }
    }

    public Map<String, Object> decode(String value) {
        try {
            return json.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("Unreadable persisted monitor data", ex);
        }
    }

    public List<Long> groups(String value) {
        try {
            return json.readValue(value, new TypeReference<List<Long>>() {});
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> entities(long cluster, String kind, String project) {
        String sql = "SELECT payload,observed_at FROM ds_entity WHERE cluster_id=? AND kind=?";
        Object[] args =
                project == null
                        ? new Object[] {cluster, kind}
                        : new Object[] {cluster, kind, project};
        if (project != null) sql += " AND project_code=?";
        return jdbc.query(
                sql,
                (r, n) -> {
                    Map<String, Object> row = decode(r.getString(1));
                    row.put("observed_at", r.getString(2));
                    return row;
                },
                args);
    }

    public Map<String, Object> entity(long cluster, String kind, String id) {
        List<String> rows =
                jdbc.queryForList(
                        "SELECT payload FROM ds_entity WHERE cluster_id=? AND kind=? AND"
                                + " object_id=?",
                        String.class,
                        cluster,
                        kind,
                        id);
        return rows.isEmpty() ? null : decode(rows.get(0));
    }

    public void save(long cluster, String kind, Map<String, Object> row) {
        String id = Objects.toString(row.get("id"), "");
        if (id.isEmpty()) throw new IllegalArgumentException("Missing stable object ID");
        String project = Objects.toString(row.get("project_code"), ""),
                workflow = Objects.toString(row.get("definition_code"), ""),
                instance = Objects.toString(row.get("workflow_instance_id"), ""),
                state = Objects.toString(row.get("state"), ""),
                date = Objects.toString(row.get("etl_date"), "");
        String now = MonitorTime.nowText(), payload = encode(row);
        int count =
                jdbc.update(
                        "UPDATE ds_entity SET"
                            + " project_code=?,workflow_code=?,workflow_instance_id=?,state=?,etl_date=?,payload=?,observed_at=?"
                            + " WHERE cluster_id=? AND kind=? AND object_id=?",
                        project,
                        workflow,
                        instance,
                        state,
                        date,
                        payload,
                        now,
                        cluster,
                        kind,
                        id);
        if (count == 0)
            jdbc.update(
                    "INSERT INTO"
                        + " ds_entity(cluster_id,kind,object_id,project_code,workflow_code,workflow_instance_id,state,etl_date,payload,observed_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                    cluster,
                    kind,
                    id,
                    project,
                    workflow,
                    instance,
                    state,
                    date,
                    payload,
                    now);
    }

    public void replace(long cluster, String kind, String project, List<Map<String, Object>> rows) {
        tx.execute(
                s -> {
                    if (project == null)
                        jdbc.update(
                                "DELETE FROM ds_entity WHERE cluster_id=? AND kind=?",
                                cluster,
                                kind);
                    else
                        jdbc.update(
                                "DELETE FROM ds_entity WHERE cluster_id=? AND kind=? AND"
                                        + " project_code=?",
                                cluster,
                                kind,
                                project);
                    for (Map<String, Object> row : rows) save(cluster, kind, row);
                    return null;
                });
    }

    public Map<String, Object> collection(long cluster, String lane) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM ds_collection WHERE cluster_id=? AND lane=?", cluster, lane);
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    public List<Map<String, Object>> collections(long cluster) {
        return jdbc.queryForList(
                "SELECT * FROM ds_collection WHERE cluster_id=? ORDER BY lane", cluster);
    }

    public void success(long cluster, String lane, String watermark) {
        checkpoint(cluster, lane, watermark, null);
    }

    public void attempt(long cluster, String lane) {
        tx.execute(
                s -> {
                    if (collection(cluster, lane).isEmpty())
                        jdbc.update(
                                "INSERT INTO ds_collection(cluster_id,lane,failures) VALUES(?,?,0)",
                                cluster,
                                lane);
                    jdbc.update(
                            "UPDATE ds_collection SET last_attempt=? WHERE cluster_id=? AND lane=?",
                            MonitorTime.nowText(),
                            cluster,
                            lane);
                    return null;
                });
    }

    public void failure(long cluster, String lane, String error) {
        checkpoint(cluster, lane, null, com.live.monitor.service.MonitorCheckExecution.safe(error));
    }

    private void checkpoint(long cluster, String lane, String watermark, String error) {
        tx.execute(
                s -> {
                    String now = MonitorTime.nowText();
                    Map<String, Object> old = collection(cluster, lane);
                    if (old.isEmpty())
                        jdbc.update(
                                "INSERT INTO ds_collection(cluster_id,lane,failures) VALUES(?,?,0)",
                                cluster,
                                lane);
                    if (error == null)
                        jdbc.update(
                                "UPDATE ds_collection SET"
                                    + " last_attempt=?,last_success=?,watermark=?,failures=0,error_message=NULL"
                                    + " WHERE cluster_id=? AND lane=?",
                                now,
                                now,
                                watermark,
                                cluster,
                                lane);
                    else
                        jdbc.update(
                                "UPDATE ds_collection SET"
                                    + " last_attempt=?,failures=failures+1,error_message=? WHERE"
                                    + " cluster_id=? AND lane=?",
                                now,
                                error,
                                cluster,
                                lane);
                    return null;
                });
    }

    public List<Map<String, Object>> configs(long cluster) {
        return jdbc.queryForList("SELECT * FROM ds_project_config WHERE cluster_id=?", cluster);
    }

    public Map<String, Object> config(long cluster, String project) {
        return configs(cluster).stream()
                .filter(r -> project.equals(r.get("project_code")))
                .findFirst()
                .orElse(new LinkedHashMap<>());
    }

    public void saveConfig(
            long cluster,
            String project,
            String primary,
            String backup,
            List<Long> groups,
            boolean enabled) {
        tx.execute(
                s -> {
                    if (config(cluster, project).isEmpty())
                        jdbc.update(
                                "INSERT INTO ds_project_config(cluster_id,project_code)"
                                        + " VALUES(?,?)",
                                cluster,
                                project);
                    jdbc.update(
                            "UPDATE ds_project_config SET"
                                + " primary_owner=?,backup_owner=?,group_ids=?,alert_enabled=?,updated_at=?"
                                + " WHERE cluster_id=? AND project_code=?",
                            primary,
                            backup,
                            encode(groups),
                            enabled ? 1 : 0,
                            MonitorTime.nowText(),
                            cluster,
                            project);
                    return null;
                });
    }

    public List<Map<String, Object>> rules(long cluster) {
        return jdbc.queryForList("SELECT * FROM ds_alert_rule WHERE cluster_id=?", cluster);
    }

    public void saveRule(long cluster, Map<String, Object> r) {
        tx.execute(
                s -> {
                    jdbc.update(
                            "DELETE FROM ds_alert_rule WHERE cluster_id=? AND scope_type=? AND"
                                    + " scope_id=? AND rule_type=?",
                            cluster,
                            r.get("scope_type"),
                            r.get("scope_id"),
                            r.get("rule_type"));
                    jdbc.update(
                            "INSERT INTO"
                                + " ds_alert_rule(cluster_id,scope_type,scope_id,rule_type,enabled,threshold_value,business_date,deadline)"
                                + " VALUES(?,?,?,?,?,?,?,?)",
                            cluster,
                            r.get("scope_type"),
                            r.get("scope_id"),
                            r.get("rule_type"),
                            Boolean.FALSE.equals(r.get("enabled")) ? 0 : 1,
                            r.get("threshold_value"),
                            r.get("business_date"),
                            r.get("deadline"));
                    return null;
                });
    }

    public List<Map<String, Object>> incidents(long cluster) {
        return jdbc.queryForList(
                "SELECT * FROM ds_incident WHERE cluster_id=? ORDER BY opened_at DESC", cluster);
    }

    public List<Map<String, Object>> members(long cluster, String key) {
        return jdbc.queryForList(
                "SELECT member_kind,member_id FROM ds_incident_member WHERE cluster_id=? AND"
                    + " incident_key=?",
                cluster,
                key);
    }

    public void sample(long cluster, String key, Map<String, Object> node) {
        jdbc.update(
                "INSERT INTO ds_node_sample(cluster_id,node_key,sampled_at,payload)"
                        + " VALUES(?,?,?,?)",
                cluster,
                key,
                MonitorTime.nowText(),
                encode(node));
    }

    public List<Map<String, Object>> samples(long cluster, String key, int hours) {
        String cutoff = MonitorTime.now().minusHours(hours).format(MonitorTime.TEXT_TIME);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String table : Arrays.asList("ds_node_sample", "ds_node_hourly"))
            rows.addAll(
                    jdbc.query(
                            "SELECT payload,sampled_at FROM "
                                    + table
                                    + " WHERE cluster_id=? AND node_key=? AND sampled_at>=? ORDER"
                                    + " BY sampled_at",
                            (r, n) -> {
                                Map<String, Object> row = decode(r.getString(1));
                                row.put("sampled_at", r.getString(2));
                                return row;
                            },
                            cluster,
                            key,
                            cutoff));
        rows.sort(Comparator.comparing(r -> r.get("sampled_at").toString()));
        return rows;
    }

    public void retain(long cluster, int days) {
        String cutoff = MonitorTime.now().minusDays(days).format(MonitorTime.TEXT_TIME);
        archive(cluster);
        jdbc.update(
                "DELETE FROM ds_node_sample WHERE cluster_id=? AND sampled_at<?", cluster, cutoff);
        jdbc.update(
                "DELETE FROM ds_node_hourly WHERE cluster_id=? AND sampled_at<?", cluster, cutoff);
        jdbc.update(
                "DELETE FROM ds_entity WHERE cluster_id=? AND kind IN"
                    + " ('workflow_instance','task_instance') AND observed_at<? AND state NOT IN"
                    + " ('RUNNING_EXECUTION','SUBMITTED_SUCCESS','DELAY_EXECUTION','SERIAL_WAIT','READY_PAUSE','READY_STOP','WAITING_THREAD','WAITING_DEPEND','NEED_FAULT_TOLERANCE')",
                cluster,
                cutoff);
        jdbc.update(
                "DELETE FROM ds_incident WHERE cluster_id=? AND active=0 AND resolved_at<?",
                cluster,
                cutoff);
    }

    void archive(long cluster) {
        jdbc.update(
                "DELETE FROM ds_incident_member WHERE cluster_id=? AND NOT EXISTS (SELECT 1 FROM"
                    + " ds_incident i WHERE i.cluster_id=ds_incident_member.cluster_id AND"
                    + " i.incident_key=ds_incident_member.incident_key)",
                cluster);
        String cutoff =
                MonitorTime.now()
                        .minusHours(48)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0)
                        .format(MonitorTime.TEXT_TIME);
        List<Map<String, Object>> source =
                jdbc.queryForList(
                        "SELECT node_key,sampled_at,payload FROM ds_node_sample WHERE cluster_id=?"
                                + " AND sampled_at<? ORDER BY sampled_at",
                        cluster,
                        cutoff);
        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> row : source)
            buckets.computeIfAbsent(
                            row.get("node_key")
                                    + "|"
                                    + row.get("sampled_at").toString().substring(0, 13),
                            k -> new ArrayList<>())
                    .add(row);
        for (List<Map<String, Object>> bucket : buckets.values())
            tx.execute(
                    status -> {
                        Map<String, Object> last = bucket.get(bucket.size() - 1),
                                aggregate = decode(last.get("payload").toString());
                        String
                                hour =
                                        last.get("sampled_at").toString().substring(0, 13)
                                                + ":00:00.000",
                                node = last.get("node_key").toString();
                        for (String metric :
                                Arrays.asList(
                                        "cpu_percent",
                                        "host_memory_percent",
                                        "heap_committed_percent",
                                        "heap_used_bytes",
                                        "heap_committed_bytes",
                                        "disk_available_gb",
                                        "load_average",
                                        "waiting_tasks",
                                        "running_instances")) {
                            double total = 0;
                            int count = 0;
                            for (Map<String, Object> point : bucket) {
                                Map<String, Object> observed =
                                        decode(point.get("payload").toString());
                                if (Boolean.TRUE.equals(observed.get("metrics_stale"))) continue;
                                Object value = observed.get(metric);
                                if (value instanceof Number) {
                                    total += ((Number) value).doubleValue();
                                    count++;
                                }
                            }
                            aggregate.put(metric, count == 0 ? null : total / count);
                        }
                        aggregate.put("aggregation", "hourly_average");
                        aggregate.put("sample_count", bucket.size());
                        jdbc.update(
                                "DELETE FROM ds_node_hourly WHERE cluster_id=? AND node_key=? AND"
                                        + " sampled_at=?",
                                cluster,
                                node,
                                hour);
                        jdbc.update(
                                "INSERT INTO ds_node_hourly(cluster_id,node_key,sampled_at,payload)"
                                        + " VALUES(?,?,?,?)",
                                cluster,
                                node,
                                hour,
                                encode(aggregate));
                        for (Map<String, Object> point : bucket)
                            jdbc.update(
                                    "DELETE FROM ds_node_sample WHERE cluster_id=? AND node_key=?"
                                            + " AND sampled_at=?",
                                    cluster,
                                    node,
                                    point.get("sampled_at"));
                        return null;
                    });
    }
}
