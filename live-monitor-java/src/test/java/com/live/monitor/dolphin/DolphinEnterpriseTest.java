package com.live.monitor.dolphin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.MonitorServiceMapper;
import com.live.monitor.service.CryptoService;
import com.live.monitor.service.DolphinSchedulerService;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

class DolphinEnterpriseTest {
    @Test
    void transientFailureRetriesEntireSnapshotAndRollsBackNestedWrites() {
        java.util.concurrent.atomic.AtomicInteger attempts =
                new java.util.concurrent.atomic.AtomicInteger();
        store.transaction()
                .execute(
                        status -> {
                            Map<String, Object> row = new HashMap<>();
                            row.put("id", "retry");
                            row.put("project_code", "123");
                            store.replace(
                                    7, "retry_fixture", "123", Collections.singletonList(row));
                            if (attempts.incrementAndGet() == 1)
                                throw new org.springframework.dao.CannotAcquireLockException(
                                        "injected deadlock");
                            store.success(7, "retry_fixture", "cursor-after-commit");
                            return null;
                        });
        assertEquals(2, attempts.get());
        assertEquals(1, store.entities(7, "retry_fixture", "123").size());
        assertEquals("cursor-after-commit", store.collection(7, "retry_fixture").get("watermark"));
    }

    @Test
    void concurrentBusinessDatesRemainIndependentAcrossWorkflowAndTaskQueries() throws Exception {
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.List<java.util.concurrent.Callable<Void>> jobs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final int day = i;
                jobs.add(
                        () -> {
                            Map<String, Object> workflow = new HashMap<>();
                            workflow.put("id", Integer.toString(10 + day));
                            workflow.put("project_code", "123");
                            workflow.put("definition_code", "77");
                            workflow.put("etl_date", "2026-11-" + (13 + day));
                            workflow.put("state", "RUNNING_EXECUTION");
                            workflow.put("start_time", "2020-01-01 00:00:00");
                            store.save(7, "workflow_instance", workflow);
                            Map<String, Object> task = new HashMap<>(workflow);
                            task.put("id", Integer.toString(20 + day));
                            task.put("workflow_instance_id", workflow.get("id"));
                            task.put("task_code", "9");
                            store.save(7, "task_instance", task);
                            return null;
                        });
            }
            for (java.util.concurrent.Future<Void> job : pool.invokeAll(jobs)) job.get();
        } finally {
            pool.shutdownNow();
        }
        store.success(7, "runtime", com.live.monitor.util.MonitorTime.nowText());
        store.success(7, "runtime:123", com.live.monitor.util.MonitorTime.nowText());
        DolphinEnterpriseController controller =
                new DolphinEnterpriseController(
                        store, api, collector, mock(com.live.monitor.mapper.AlertMapper.class));
        Map<?, ?> first =
                (Map<?, ?>)
                        controller.instances(
                                7,
                                "workflow_instance",
                                "123",
                                "77",
                                null,
                                null,
                                "2026-11-13",
                                null,
                                null,
                                1,
                                20);
        assertEquals(1, first.get("total"));
        assertEquals("10", ((Map<?, ?>) ((List<?>) first.get("items")).get(0)).get("id"));
        Map<?, ?> instance = (Map<?, ?>) ((List<?>) first.get("items")).get(0);
        assertTrue(((Number) instance.get("duration_seconds")).longValue() > 0);
        assertEquals(1, ((List<?>) instance.get("running_nodes")).size());
        Map<String, Object> definition = new HashMap<>();
        definition.put("id", "77");
        definition.put("project_code", "123");
        store.save(7, "workflow_definition", definition);
        assertEquals(
                "2020-01-01 00:00:00",
                ((Map<?, ?>)
                                ((List<?>) controller.definitions(7, "123", "workflow_definition"))
                                        .get(0))
                        .get("last_collected_run"));
        Map<?, ?> second =
                (Map<?, ?>)
                        controller.instances(
                                7,
                                "task_instance",
                                "123",
                                "77",
                                "9",
                                null,
                                "2026-11-14",
                                null,
                                null,
                                1,
                                20);
        assertEquals(1, second.get("total"));
        assertEquals(
                "11",
                ((Map<?, ?>) ((List<?>) second.get("items")).get(0)).get("workflow_instance_id"));
        assertEquals(2, ((List<?>) controller.dates(7, "123")).size());
    }

    @Test
    void incidentEngineFailureLeavesDurableFailureAuditAndRecoversCollectionState() {
        com.live.monitor.alert.AlertOutbox outbox = mock(com.live.monitor.alert.AlertOutbox.class);
        DolphinAlerts engine = mock(DolphinAlerts.class);
        doThrow(new IllegalStateException("engine unavailable"))
                .when(engine)
                .evaluate(service, "runtime");
        DolphinCollector checked =
                new DolphinCollector(
                        api,
                        store,
                        mock(MonitorServiceMapper.class),
                        mock(DolphinResources.class),
                        engine);
        checked.setAuditOutbox(outbox);
        try {
            checked.collect(service, "runtime");
            assertEquals(
                    1, ((Number) store.collection(7, "alerts:runtime").get("failures")).intValue());
            verify(outbox)
                    .recordBlocked(
                            eq(7L),
                            eq("ds_engine:runtime"),
                            eq("enterprise_engine"),
                            contains("engine unavailable"));
            doNothing().when(engine).evaluate(service, "runtime");
            checked.collect(service, "runtime");
            assertEquals(
                    0, ((Number) store.collection(7, "alerts:runtime").get("failures")).intValue());
        } finally {
            checked.close();
        }
    }

    @Test
    void springRepositoryProxySupportsCollectionTransactionsAndDashboard() throws Exception {
        org.springframework.aop.framework.ProxyFactory factory =
                new org.springframework.aop.framework.ProxyFactory(store);
        factory.setProxyTargetClass(true);
        DolphinStore proxied = (DolphinStore) factory.getProxy();
        DolphinCollector managed =
                new DolphinCollector(
                        api,
                        proxied,
                        mock(MonitorServiceMapper.class),
                        mock(DolphinResources.class),
                        mock(DolphinAlerts.class));
        try {
            managed.collect(service, "inventory");
            managed.collect(service, "runtime");
            assertNotNull(proxied.collection(7, "inventory").get("last_success"));
            assertNotNull(proxied.collection(7, "runtime").get("last_success"));
            DolphinEnterpriseController controller =
                    new DolphinEnterpriseController(
                            proxied, api, managed, mock(com.live.monitor.mapper.AlertMapper.class));
            assertEquals(1, ((Map<?, ?>) controller.dashboard(7)).get("project_count"));
        } finally {
            managed.close();
        }
    }

    HttpServer server;
    ObjectMapper json = new ObjectMapper();
    DolphinStore store;
    DolphinCollector collector;
    DolphinSchedulerService api;
    MonitorService service;
    String status = "FAILURE";
    boolean fail;
    boolean removed;
    boolean missingNode;
    int fixtureClockSkewMinutes;
    int definitionReads;

    @BeforeEach
    void setup() throws Exception {
        DriverManagerDataSource source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                        new org.springframework.core.io.ClassPathResource("schema-h2.sql"))
                .execute(source);
        store =
                new DolphinStore(
                        new JdbcTemplate(source), json, new DataSourceTransactionManager(source));
        store.migrate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ds",
                x -> {
                    String path = x.getRequestURI().getPath(), data = "null";
                    int code = 0;
                    if (fail) {
                        byte[] bytes = "unavailable".getBytes(StandardCharsets.UTF_8);
                        x.sendResponseHeaders(503, bytes.length);
                        x.getResponseBody().write(bytes);
                        x.close();
                        return;
                    }
                    if (path.endsWith("/projects"))
                        data =
                                page(
                                        removed
                                                ? "[]"
                                                : "[{\"code\":123,\"name\":\"project\",\"userName\":\"owner\"}]");
                    else if (path.endsWith("/process-definition"))
                        data =
                                page(
                                        "[{\"code\":77,\"name\":\"workflow\",\"version\":1,\"releaseState\":\"ONLINE\",\"scheduleReleaseState\":\"OFFLINE\"}]");
                    else if (path.endsWith("/process-definition/77")) {
                        definitionReads++;
                        data =
                                "{\"taskDefinitionList\":[{\"code\":9,\"name\":\"task\",\"taskType\":\"SHELL\"}]}";
                    } else if (path.endsWith("/process-instances"))
                        data = page("[{\"id\":10,\"state\":\"" + status + "\",\"runTimes\":1}]");
                    else if (path.endsWith("/process-instances/10"))
                        data =
                                "{\"id\":10,\"name\":\"run\",\"state\":\""
                                        + status
                                        + "\",\"runTimes\":1,\"processDefinitionCode\":77,\"globalParams\":\"[{\\\"prop\\\":\\\"dataDate\\\",\\\"value\\\":\\\"20261113\\\"},{\\\"prop\\\":\\\"password\\\",\\\"value\\\":\\\"never-store-me\\\"}]\"}";
                    else if (path.endsWith("/tasks"))
                        data =
                                "{\"taskList\":[{\"id\":20,\"taskCode\":9,\"name\":\"task\",\"state\":\""
                                        + status
                                        + "\",\"taskParams\":\"{\\\"localParams\\\":[{\\\"prop\\\":\\\"dataDate\\\",\\\"value\\\":\\\"20261112\\\"}]}\"}]}";
                    else if (path.endsWith("/users/list-paging"))
                        data =
                                page(
                                        "[{\"id\":4,\"userName\":\"user\",\"userPassword\":\"never-store-me\"}]");
                    else if (path.endsWith("/authed-user"))
                        data = "[{\"id\":4,\"userName\":\"user\"}]";
                    else if (path.endsWith("/monitor/masters") || path.endsWith("/monitor/workers"))
                        data =
                                missingNode
                                        ? "[]"
                                        : "[{\"host\":\"node\",\"port\":5678,\"lastHeartbeatTime\":\""
                                                + com.live.monitor.util.MonitorTime.formatText(
                                                        com.live.monitor.util.MonitorTime.now()
                                                                .minusMinutes(
                                                                        fixtureClockSkewMinutes))
                                                + "\",\"resInfo\":{\"cpuUsage\":0.2,\"memoryUsage\":0.6,\"diskAvailable\":20.5,\"processId\":123}}]";
                    else if (path.endsWith("/monitor/databases"))
                        data = "[{\"dbType\":\"mysql\",\"state\":\"YES\"}]";
                    else code = 999;
                    byte[] bytes =
                            ("{\"code\":" + code + ",\"data\":" + data + "}")
                                    .getBytes(StandardCharsets.UTF_8);
                    x.sendResponseHeaders(200, bytes.length);
                    x.getResponseBody().write(bytes);
                    x.close();
                });
        server.start();
        MonitorServiceMapper mapper = mock(MonitorServiceMapper.class);
        service = new MonitorService();
        service.id = 7L;
        service.serviceName = "cluster";
        service.serviceType = "dolphinscheduler";
        service.enabled = true;
        service.configJson =
                "{\"base_url\":\"http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/ds\",\"api_retries\":0}";
        CryptoService crypto = new CryptoService(new LiveMonitorProperties());
        service.secretConfigJson = crypto.encrypt("{\"token\":\"fixture\"}");
        when(mapper.findById(7L)).thenReturn(service);
        api = new DolphinSchedulerService(json, crypto, mapper);
        collector =
                new DolphinCollector(
                        api,
                        store,
                        mapper,
                        mock(DolphinResources.class),
                        mock(DolphinAlerts.class));
    }

    String page(String rows) {
        try {
            return "{\"total\":" + json.readTree(rows).size() + ",\"totalList\":" + rows + "}";
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @AfterEach
    void close() {
        collector.close();
        server.stop(0);
    }

    @Test
    void inventoryCountsTasksSeparatelyAndDoesNotOverwriteManualOwners() {
        store.saveConfig(7, "123", "primary", "backup", Arrays.asList(2L), true);
        collector.collect(service, "inventory");
        assertEquals(1, store.entities(7, "project", null).size());
        assertEquals(1, store.entities(7, "workflow_definition", "123").size());
        assertEquals(1, store.entities(7, "task_definition", "123").size());
        collector.collect(service, "inventory");
        assertEquals(1, definitionReads);
        assertEquals("primary", store.config(7, "123").get("primary_owner"));
        removed = true;
        collector.collect(service, "inventory");
        assertTrue(store.entities(7, "project", null).isEmpty());
        assertTrue(store.entities(7, "workflow_definition", null).isEmpty());
        assertEquals("primary", store.config(7, "123").get("primary_owner"));
    }

    @Test
    void runtimeResolvesBusinessDatesAndUpdatesSameStableIdAfterRetry() {
        collector.collect(service, "runtime");
        assertEquals("2026-11-13", store.entity(7, "workflow_instance", "10").get("etl_date"));
        assertEquals("2026-11-12", store.entity(7, "task_instance", "20").get("etl_date"));
        assertFalse(
                store.encode(store.entities(7, "workflow_instance", null))
                        .contains("never-store-me"));
        status = "SUCCESS";
        collector.collect(service, "runtime");
        assertEquals(1, store.entities(7, "workflow_instance", null).size());
        assertEquals("SUCCESS", store.entity(7, "task_instance", "20").get("state"));
    }

    @Test
    void failedCollectionKeepsPriorDataAndCursor() {
        collector.collect(service, "runtime");
        Object cursor = store.collection(7, "runtime:123").get("watermark");
        fail = true;
        collector.collect(service, "runtime");
        assertEquals(cursor, store.collection(7, "runtime:123").get("watermark"));
        assertEquals(1, store.entities(7, "workflow_instance", null).size());
        assertEquals(1, ((Number) store.collection(7, "runtime").get("failures")).intValue());
    }

    @Test
    void usersNeverPersistPasswords() {
        collector.collect(service, "users");
        assertEquals(1, store.entities(7, "user", null).size());
        assertFalse(store.encode(store.entities(7, "user", null)).contains("never-store-me"));
        assertEquals(1, store.entities(7, "authorization", "123").size());
    }

    @Test
    void nestedAliasesDoNotTreatExpressionsOrExecutionTimeAsDates() throws Exception {
        service.configJson = service.configJson.replace("}", ",\"etl_date_keys\":\"RQ,@rq\"}");
        assertEquals(
                "2026-11-12",
                api.businessDate(
                        service,
                        json.readTree(
                                "{\"taskParams\":{\"localParams\":[{\"prop\":\"RQ\",\"value\":\"20261112\"}]}}")));
        assertEquals(
                "",
                api.businessDate(
                        service,
                        json.readTree(
                                "{\"startTime\":\"2026-11-14"
                                    + " 00:00:00\",\"globalParams\":[{\"prop\":\"RQ\",\"value\":\"${system.biz.curdate}\"}]}")));
    }

    @Test
    void jstatKeepsHeapAndHostMemorySeparate() {
        Map<String, Double> values =
                DolphinResources.jstat(
                        "S0C S1C S0U S1U EC EU OC OU YGC GCT\n10 10 2 0 80 20 100 60 3 0.5");
        assertEquals(200D, DolphinResources.sum(values, "S0C", "S1C", "EC", "OC"));
        assertEquals(82D, DolphinResources.sum(values, "S0U", "S1U", "EU", "OU"));
        assertEquals(0.5D, values.get("GCT"));
    }

    @Test
    void jvmUsesParentJdkForNestedJreAndSeparatesMaximumFromCommittedHeap() throws Exception {
        com.live.monitor.mapper.HostMapper hosts = mock(com.live.monitor.mapper.HostMapper.class);
        com.live.monitor.entity.HostConfig host = new com.live.monitor.entity.HostConfig();
        when(hosts.findEnabledByAddress("node")).thenReturn(host);
        com.live.monitor.service.SshService ssh = mock(com.live.monitor.service.SshService.class);
        when(ssh.exec(eq(host), anyString(), eq(5000)))
                .thenReturn(
                        "S0C S1C S0U S1U EC EU OC OU YGC FGC GCT\n"
                                + "10 10 2 0 80 20 100 60 3 0 0.5\n"
                                + "MaxHeapSize=409600");
        DolphinResources resources = new DolphinResources(api, store, json, hosts, ssh);
        Map<String, Object> row = new HashMap<>();
        row.put("host_memory_percent", 60D);
        resources.collectJvm(row, "node", 123);
        assertEquals(20.5D, row.get("heap_max_percent"));
        assertEquals(41D, row.get("heap_committed_percent"));
        assertEquals(60D, row.get("host_memory_percent"));
        assertEquals(true, row.get("jvm_max_heap_available"));
        verify(ssh).exec(eq(host), contains("home=${home%/jre}"), eq(5000));
        verify(ssh).exec(eq(host), contains("runuser -u"), eq(5000));
        Map<String, Object> config = api.config(service);
        config.put("collect_jvm", true);
        service.configJson = json.writeValueAsString(config);
        resources.collect(service);
        when(ssh.exec(eq(host), anyString(), eq(5000)))
                .thenReturn(
                        "S0C S1C S0U S1U EC EU OC OU YGC FGC GCT\n"
                                + "10 10 2 0 80 20 100 60 3 0 0.75\n"
                                + "MaxHeapSize=409600");
        resources.collect(service);
        assertEquals(
                0.25D, store.entity(7, "node", "masters:node:5678").get("gc_time_delta_seconds"));
    }

    @Test
    void resourcesNormalizeRatiosAndConfirmAbsentNodesWithoutInventingJvm() throws Exception {
        DolphinResources resources =
                new DolphinResources(
                        api,
                        store,
                        json,
                        mock(com.live.monitor.mapper.HostMapper.class),
                        mock(com.live.monitor.service.SshService.class));
        resources.collect(service);
        Map<String, Object> master = store.entity(7, "node", "masters:node:5678");
        assertEquals(20D, master.get("cpu_percent"));
        assertEquals(60D, master.get("host_memory_percent"));
        assertEquals(false, master.get("jvm_available"));
        assertNull(master.get("heap_committed_percent"));
        missingNode = true;
        resources.collect(service);
        assertEquals(1, store.entity(7, "node", "masters:node:5678").get("offline_confirmations"));
        resources.collect(service);
        assertEquals(2, store.entity(7, "node", "masters:node:5678").get("offline_confirmations"));
        fail = true;
        assertThrows(Exception.class, () -> resources.collect(service));
        assertEquals(2, store.entity(7, "node", "masters:node:5678").get("offline_confirmations"));
        assertFalse(store.samples(7, "masters:node:5678", 1).isEmpty());
    }

    @Test
    void resourceArchivePreservesHourlyAveragesAndSourceCount() {
        String hour =
                com.live.monitor.util.MonitorTime.now()
                        .minusHours(72)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0)
                        .format(com.live.monitor.util.MonitorTime.TEXT_TIME);
        for (int i = 1; i <= 2; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", "node");
            row.put("cpu_percent", i * 20D);
            String time = hour.substring(0, 14) + "0" + i + ":00.000";
            store.jdbc.update(
                    "INSERT INTO ds_node_sample(cluster_id,node_key,sampled_at,payload)"
                            + " VALUES(7,'node',?,?)",
                    time,
                    store.encode(row));
        }
        store.retain(7, 90);
        List<Map<String, Object>> rows = store.samples(7, "node", 168);
        assertEquals(1, rows.size());
        assertEquals(30D, rows.get(0).get("cpu_percent"));
        assertEquals(2, rows.get(0).get("sample_count"));
        assertEquals("hourly_average", rows.get(0).get("aggregation"));
    }

    @Test
    void verifiedForkSystemDatesUseLogicalScheduleNotExecutionTime() throws Exception {
        Map<String, Object> config = api.config(service);
        config.put("api_profile", "ld-2.0.8");
        config.put("resolve_verified_system_dates", true);
        config.put("etl_date_keys", "RQ");
        service.configJson = json.writeValueAsString(config);
        com.fasterxml.jackson.databind.JsonNode task =
                json.readTree(
                        "{\"localParams\":[{\"prop\":\"RQ\",\"value\":\"${system.biz.curdate}\"}]}");
        assertEquals(
                "2026-11-12",
                api.businessDate(
                        service,
                        task,
                        json.readTree(
                                "{\"scheduleTime\":\"2026-11-12"
                                        + " 23:00:00\",\"startTime\":\"2026-11-14"
                                        + " 01:00:00\",\"commandType\":\"COMPLEMENT_DATA\"}")));
        assertEquals(
                "",
                api.businessDate(
                        service, task, json.readTree("{\"startTime\":\"2026-11-14 01:00:00\"}")));
        assertEquals(
                "2026-11-11",
                api.businessDate(
                        service,
                        json.readTree(task.toString().replace("biz.curdate", "biz.date")),
                        json.readTree("{\"scheduleTime\":\"2026-11-12 23:00:00\"}")));
    }

    @Test
    void dashboardDistinguishesUncollectedFromEmptyAndApiEnforcesAdminAndScope() throws Exception {
        com.live.monitor.mapper.AlertMapper groups =
                mock(com.live.monitor.mapper.AlertMapper.class);
        DolphinEnterpriseController controller =
                new DolphinEnterpriseController(store, api, collector, groups);
        Map<?, ?> initial = (Map<?, ?>) controller.dashboard(7);
        assertNull(initial.get("project_count"));
        assertNull(initial.get("workflow_statistics"));
        javax.servlet.http.HttpSession session = mock(javax.servlet.http.HttpSession.class);
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.sync(7, session));
        com.live.monitor.entity.TUser admin = new com.live.monitor.entity.TUser();
        admin.userId = "admin";
        when(session.getAttribute(com.live.monitor.controller.AuthController.SESSION_USER))
                .thenReturn(admin);
        Map<String, Object> config = api.config(service);
        config.put("project_codes", "123");
        service.configJson = json.writeValueAsString(config);
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.authorizations(7, "999"));
        collector.collect(service, "inventory");
        assertEquals(1, ((Map<?, ?>) controller.dashboard(7)).get("project_count"));
        String safe =
                DolphinEnterpriseController.redactLog(
                        "password=very-long-sensitive-value token=another-token");
        assertFalse(safe.contains("very-long-sensitive"));
        assertTrue(safe.contains("[redacted]"));
    }

    @Test
    void sourceClockSkewDoesNotBecomeFalseOffline() throws Exception {
        fixtureClockSkewMinutes = 10;
        DolphinSchedulerService shifted = spy(api);
        doReturn(com.live.monitor.util.MonitorTime.now().minusMinutes(10))
                .when(shifted)
                .sourceNow(service);
        doReturn(-600000L).when(shifted).sourceClockSkewMillis(service);
        DolphinResources resources =
                new DolphinResources(
                        shifted,
                        store,
                        json,
                        mock(com.live.monitor.mapper.HostMapper.class),
                        mock(com.live.monitor.service.SshService.class));
        resources.collect(service);
        Map<String, Object> row = store.entity(7, "node", "masters:node:5678");
        assertEquals(true, row.get("online"));
        assertEquals(-600D, row.get("source_clock_skew_seconds"));
        assertEquals(0, row.get("offline_confirmations"));
    }
}
