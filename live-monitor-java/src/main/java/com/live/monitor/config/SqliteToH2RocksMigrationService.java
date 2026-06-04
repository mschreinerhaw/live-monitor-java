package com.live.monitor.config;

import com.live.monitor.entity.AlertRecord;
import com.live.monitor.entity.MonitorResult;
import com.live.monitor.store.RocksDbHistoryRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Component
@DependsOn("schemaMigrationService")
public class SqliteToH2RocksMigrationService {
    private static final Logger log = LoggerFactory.getLogger(SqliteToH2RocksMigrationService.class);
    private static final String MARKER_ID = "sqlite-to-h2-rocksdb-v1";
    private static final DateTimeFormatter BACKUP_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter TEXT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final LiveMonitorProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final RocksDbHistoryRepository historyRepository;
    private final DatabaseDialect databaseDialect;

    public SqliteToH2RocksMigrationService(
        LiveMonitorProperties properties,
        JdbcTemplate jdbcTemplate,
        TransactionTemplate transactionTemplate,
        RocksDbHistoryRepository historyRepository,
        DatabaseDialect databaseDialect
    ) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.historyRepository = historyRepository;
        this.databaseDialect = databaseDialect;
    }

    @PostConstruct
    public void migrateIfNeeded() {
        if (!databaseDialect.isH2()) {
            log.info("Current config database is {}, skip SQLite to H2 migration", databaseDialect.productName());
            return;
        }
        if (migrationCompleted()) {
            log.info("SQLite migration marker exists, skip SQLite");
            return;
        }

        Path sqlitePath = Paths.get(properties.getSqlitePath()).toAbsolutePath().normalize();
        if (!Files.exists(sqlitePath)) {
            log.info("Legacy SQLite database not found at {}, skip migration", sqlitePath);
            return;
        }

        String migrationId = MARKER_ID + "-" + UUID.randomUUID();
        Path backupPath;
        try {
            backupPath = backupSqlite(sqlitePath);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to backup SQLite database before migration", ex);
        }

        long checkBefore = historyRepository.countPrefix("check:");
        long alertBefore = historyRepository.countPrefix("alert:");
        long metricBefore = historyRepository.countPrefix("metric:");
        try {
            MigrationCounts counts = transactionTemplate.execute(status -> {
                try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + backupPath.toString())) {
                    MigrationCounts migrated = migrate(sqlite, migrationId);
                    verifyRocksDbCounts(migrated, checkBefore, alertBefore, metricBefore);
                    writeMarker(sqlitePath, backupPath, migrated);
                    return migrated;
                } catch (Exception ex) {
                    status.setRollbackOnly();
                    throw new IllegalStateException("SQLite to H2 + RocksDB migration failed", ex);
                }
            });
            archiveOriginalSqlite(sqlitePath);
            log.info(
                "SQLite migration completed. config rows: {}, rocksdb rows: {}, backup: {}",
                counts.configRows,
                counts.rocksdbRows(),
                backupPath
            );
        } catch (RuntimeException ex) {
            historyRepository.deleteByMigrationId(migrationId);
            throw ex;
        }
    }

    private MigrationCounts migrate(Connection sqlite, String migrationId) throws Exception {
        MigrationCounts counts = new MigrationCounts();
        counts.configRows += copyTable(sqlite, "tuser", "id");
        counts.configRows += copyTable(sqlite, "monitor_service", "id");
        counts.configRows += copyTable(sqlite, "alert_policy", "id");
        counts.configRows += copyTable(sqlite, "alert_channel", "id");
        counts.configRows += copyTable(sqlite, "alert_group", "id");
        counts.configRows += copyTable(sqlite, "group_policy_rel", "group_id, policy_id");
        counts.configRows += copyTable(sqlite, "group_channel_rel", "group_id, channel_id");
        counts.configRows += copyTable(sqlite, "service_alert_group", "service_id");
        counts.configRows += copyTable(sqlite, "host_config", "id");
        counts.configRows += copyTable(sqlite, "host_process_config", "id");
        restartIdentity("monitor_service");
        restartIdentity("alert_policy");
        restartIdentity("alert_channel");
        restartIdentity("alert_group");
        restartIdentity("host_config");
        restartIdentity("host_process_config");

        counts.monitorResults = migrateMonitorResults(sqlite, migrationId);
        counts.alertRecords = migrateAlertRecords(sqlite, migrationId);
        counts.hostMetrics = migrateHostMetrics(sqlite, migrationId);
        return counts;
    }

    private int copyTable(Connection sqlite, String tableName, String keyColumns) throws SQLException {
        if (!sqliteTableExists(sqlite, tableName) || !h2TableExists(tableName)) {
            return 0;
        }
        Map<String, String> sqliteColumns = sqliteColumns(sqlite, tableName);
        Set<String> h2Columns = h2Columns(tableName);
        List<String> common = new ArrayList<String>();
        for (String column : sqliteColumns.keySet()) {
            if (h2Columns.contains(column)) {
                common.add(column);
            }
        }
        if (common.isEmpty()) {
            return 0;
        }
        String sql = "MERGE INTO " + tableName + " (" + join(common) + ") KEY(" + keyColumns + ") VALUES (" + placeholders(common.size()) + ")";
        int copied = 0;
        try (Statement statement = sqlite.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT " + joinOriginal(sqliteColumns, common) + " FROM " + tableName)) {
            while (resultSet.next()) {
                Object[] args = new Object[common.size()];
                for (int i = 0; i < common.size(); i++) {
                    args[i] = resultSet.getObject(sqliteColumns.get(common.get(i)));
                }
                jdbcTemplate.update(sql, args);
                copied++;
            }
        }
        log.info("Migrated SQLite table {} rows: {}", tableName, copied);
        return copied;
    }

    private int migrateMonitorResults(Connection sqlite, String migrationId) throws SQLException {
        if (!sqliteTableExists(sqlite, "monitor_result")) {
            return 0;
        }
        Map<Long, LatestResult> latestByService = new HashMap<Long, LatestResult>();
        int migrated = 0;
        try (Statement statement = sqlite.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM monitor_result ORDER BY checked_at, id")) {
            while (rows.next()) {
                MonitorResult result = new MonitorResult();
                result.id = longValue(rows.getObject("id"));
                result.serviceId = longValue(rows.getObject("service_id"));
                result.status = stringValue(rows.getObject("status"));
                result.responseTimeMs = intValue(rows.getObject("response_time_ms"));
                result.message = stringValue(rows.getObject("message"));
                result.checkedAt = normalizeTime(rows.getObject("checked_at"));
                historyRepository.saveMonitorResult(result, migrationId);
                updateLatest(latestByService, result);
                migrated++;
            }
        }
        for (LatestResult latest : latestByService.values()) {
            if (!h2RowExists("monitor_service", latest.serviceId)) {
                continue;
            }
            jdbcTemplate.update(
                "MERGE INTO service_latest_status KEY(service_id) VALUES (?, ?, ?, ?, ?)",
                latest.serviceId,
                latest.status,
                latest.responseTimeMs,
                latest.message,
                Timestamp.valueOf(latest.checkedAt)
            );
        }
        log.info("Migrated monitor_result rows to RocksDB: {}", migrated);
        return migrated;
    }

    private int migrateAlertRecords(Connection sqlite, String migrationId) throws SQLException {
        if (!sqliteTableExists(sqlite, "alert_record")) {
            return 0;
        }
        int migrated = 0;
        try (Statement statement = sqlite.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM alert_record ORDER BY created_at, id")) {
            while (rows.next()) {
                AlertRecord record = new AlertRecord();
                record.id = longValue(rows.getObject("id"));
                record.serviceId = longValue(rows.getObject("service_id"));
                record.alertType = stringValue(rows.getObject("alert_type"));
                record.alertContent = stringValue(rows.getObject("alert_content"));
                record.alertStatus = stringValue(rows.getObject("alert_status"));
                record.createdAt = normalizeTime(rows.getObject("created_at"));
                historyRepository.saveAlertRecord(record, migrationId);
                migrated++;
            }
        }
        log.info("Migrated alert_record rows to RocksDB: {}", migrated);
        return migrated;
    }

    private int migrateHostMetrics(Connection sqlite, String migrationId) throws SQLException {
        if (!sqliteTableExists(sqlite, "host_metric")) {
            return 0;
        }
        Map<Long, LatestMetric> latestByHost = new HashMap<Long, LatestMetric>();
        int migrated = 0;
        try (Statement statement = sqlite.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM host_metric ORDER BY checked_at, id")) {
            while (rows.next()) {
                Long hostId = longValue(rows.getObject("host_id"));
                String checkedAt = normalizeTime(rows.getObject("checked_at"));
                Double cpu = doubleValue(rows.getObject("cpu_usage_percent"));
                Double load = doubleValue(rows.getObject("load_average"));
                Double memory = doubleValue(rows.getObject("memory_used_percent"));
                Double disk = doubleValue(rows.getObject("disk_used_percent"));
                historyRepository.saveHostMetric(
                    longValue(rows.getObject("id")),
                    hostId,
                    cpu,
                    load,
                    memory,
                    disk,
                    checkedAt,
                    migrationId
                );
                updateLatestMetric(latestByHost, hostId, checkedAt, cpu, load, memory, disk);
                migrated++;
            }
        }
        for (LatestMetric latest : latestByHost.values()) {
            if (!h2RowExists("host_config", latest.hostId)) {
                continue;
            }
            jdbcTemplate.update(
                "MERGE INTO host_latest_metric " +
                    "(host_id, cpu_usage_percent, load_average, memory_used_percent, disk_used_percent, " +
                    "cpu_core_count, memory_total_mb, disk_mount_count, disk_metrics_json, physical_disk_metrics_json, checked_at) " +
                    "KEY(host_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                latest.hostId,
                latest.cpu,
                latest.load,
                latest.memory,
                latest.disk,
                null,
                null,
                null,
                null,
                null,
                Timestamp.valueOf(latest.checkedAt)
            );
        }
        log.info("Migrated host_metric rows to RocksDB: {}", migrated);
        return migrated;
    }

    private void verifyRocksDbCounts(MigrationCounts counts, long checkBefore, long alertBefore, long metricBefore) {
        long checkDelta = historyRepository.countPrefix("check:") - checkBefore;
        long alertDelta = historyRepository.countPrefix("alert:") - alertBefore;
        long metricDelta = historyRepository.countPrefix("metric:") - metricBefore;
        if (checkDelta != counts.monitorResults || alertDelta != counts.alertRecords || metricDelta != counts.hostMetrics) {
            throw new IllegalStateException(
                "RocksDB count verification failed. expected check/alert/metric " +
                    counts.monitorResults + "/" + counts.alertRecords + "/" + counts.hostMetrics +
                    ", actual " + checkDelta + "/" + alertDelta + "/" + metricDelta
            );
        }
    }

    private void writeMarker(Path sourcePath, Path backupPath, MigrationCounts counts) {
        jdbcTemplate.update(
            "MERGE INTO migration_marker KEY(id) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
            MARKER_ID,
            sourcePath.toString(),
            backupPath.toString(),
            counts.configRows,
            counts.rocksdbRows()
        );
    }

    private boolean migrationCompleted() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM migration_marker WHERE id = ?",
            Integer.class,
            MARKER_ID
        );
        return count != null && count > 0;
    }

    private Path backupSqlite(Path source) throws Exception {
        Path backupDir = Paths.get(properties.getSqliteBackupDir()).toAbsolutePath().normalize();
        Files.createDirectories(backupDir);
        String name = "sqlite-" + LocalDate.now().format(BACKUP_DATE) + ".db";
        Path target = backupDir.resolve(name);
        if (Files.exists(target)) {
            target = backupDir.resolve("sqlite-" + LocalDateTime.now().format(BACKUP_TIME) + ".db");
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        copySibling(source, target, "-wal");
        copySibling(source, target, "-shm");
        log.info("Backed up SQLite database from {} to {}", source, target);
        return target;
    }

    private void copySibling(Path source, Path target, String suffix) throws Exception {
        Path sourceSibling = Paths.get(source.toString() + suffix);
        if (Files.exists(sourceSibling)) {
            Files.copy(sourceSibling, Paths.get(target.toString() + suffix), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void archiveOriginalSqlite(Path source) {
        try {
            Files.deleteIfExists(source);
            Files.deleteIfExists(Paths.get(source.toString() + "-wal"));
            Files.deleteIfExists(Paths.get(source.toString() + "-shm"));
        } catch (Exception ex) {
            log.warn("SQLite migration succeeded, but original SQLite files could not be removed: {}", source, ex);
        }
    }

    private boolean sqliteTableExists(Connection sqlite, String tableName) throws SQLException {
        try (java.sql.PreparedStatement statement = sqlite.prepareStatement(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?"
        )) {
            statement.setString(1, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) > 0;
            }
        }
    }

    private boolean h2TableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = SCHEMA() AND table_name = ?",
            Integer.class,
            tableName.toLowerCase(Locale.ROOT)
        );
        return count != null && count > 0;
    }

    private Map<String, String> sqliteColumns(Connection sqlite, String tableName) throws SQLException {
        Map<String, String> columns = new LinkedHashMap<String, String>();
        try (Statement statement = sqlite.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rows.next()) {
                String original = rows.getString("name");
                columns.put(original.toLowerCase(Locale.ROOT), original);
            }
        }
        return columns;
    }

    private Set<String> h2Columns(String tableName) {
        List<String> rows = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = SCHEMA() AND table_name = ?",
            String.class,
            tableName.toLowerCase(Locale.ROOT)
        );
        Set<String> columns = new HashSet<String>();
        for (String row : rows) {
            columns.add(row.toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    private boolean h2RowExists(String tableName, Long id) {
        if (id == null || !h2TableExists(tableName)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + tableName + " WHERE id = ?",
            Integer.class,
            id
        );
        return count != null && count > 0;
    }

    private void restartIdentity(String tableName) {
        if (!h2TableExists(tableName)) {
            return;
        }
        Number next = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM " + tableName, Number.class);
        jdbcTemplate.execute("ALTER TABLE " + tableName + " ALTER COLUMN id RESTART WITH " + next.longValue());
    }

    private void updateLatest(Map<Long, LatestResult> latestByService, MonitorResult result) {
        if (result.serviceId == null) {
            return;
        }
        LocalDateTime checkedAt = parseTime(result.checkedAt);
        LatestResult existing = latestByService.get(result.serviceId);
        if (existing == null || checkedAt.isAfter(existing.checkedAt)) {
            LatestResult latest = new LatestResult();
            latest.serviceId = result.serviceId;
            latest.status = result.status;
            latest.responseTimeMs = result.responseTimeMs;
            latest.message = result.message;
            latest.checkedAt = checkedAt;
            latestByService.put(result.serviceId, latest);
        }
    }

    private void updateLatestMetric(
        Map<Long, LatestMetric> latestByHost,
        Long hostId,
        String checkedAtText,
        Double cpu,
        Double load,
        Double memory,
        Double disk
    ) {
        if (hostId == null) {
            return;
        }
        LocalDateTime checkedAt = parseTime(checkedAtText);
        LatestMetric existing = latestByHost.get(hostId);
        if (existing == null || checkedAt.isAfter(existing.checkedAt)) {
            LatestMetric latest = new LatestMetric();
            latest.hostId = hostId;
            latest.checkedAt = checkedAt;
            latest.cpu = cpu;
            latest.load = load;
            latest.memory = memory;
            latest.disk = disk;
            latestByHost.put(hostId, latest);
        }
    }

    private String normalizeTime(Object value) {
        return TEXT_TIME.format(parseTime(stringValue(value)));
    }

    private LocalDateTime parseTime(String value) {
        if (!StringUtils.hasText(value)) {
            return LocalDateTime.now();
        }
        String text = value.trim().replace('T', ' ');
        if (text.length() == 19) {
            text = text + ".000";
        }
        if (text.length() > 23) {
            text = text.substring(0, 23);
        }
        try {
            return LocalDateTime.parse(text, TEXT_TIME);
        } catch (Exception ignored) {
            return LocalDateTime.now();
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            try {
                return Long.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            try {
                return Integer.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            try {
                return Double.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String join(List<String> values) {
        return String.join(", ", values);
    }

    private String joinOriginal(Map<String, String> originalByLower, List<String> values) {
        List<String> originals = new ArrayList<String>();
        for (String value : values) {
            originals.add(originalByLower.get(value));
        }
        return String.join(", ", originals);
    }

    private String placeholders(int count) {
        List<String> values = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            values.add("?");
        }
        return String.join(", ", values);
    }

    private static class MigrationCounts {
        int configRows;
        int monitorResults;
        int alertRecords;
        int hostMetrics;

        int rocksdbRows() {
            return monitorResults + alertRecords + hostMetrics;
        }
    }

    private static class LatestResult {
        Long serviceId;
        String status;
        Integer responseTimeMs;
        String message;
        LocalDateTime checkedAt;
    }

    private static class LatestMetric {
        Long hostId;
        Double cpu;
        Double load;
        Double memory;
        Double disk;
        LocalDateTime checkedAt;
    }
}
