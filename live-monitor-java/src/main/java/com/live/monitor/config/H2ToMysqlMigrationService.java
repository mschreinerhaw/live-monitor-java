package com.live.monitor.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Copies existing config data from the legacy H2 file database into MySQL the first time
 * the app is started against MySQL, so switching {@code spring.datasource} does not lose data.
 */
@Component
@DependsOn("schemaMigrationService")
public class H2ToMysqlMigrationService {
    private static final Logger log = LoggerFactory.getLogger(H2ToMysqlMigrationService.class);
    private static final String MARKER_ID = "h2-to-mysql-v1";
    private static final DateTimeFormatter BACKUP_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    // Only configuration tables are migrated. Monitoring / runtime data (service_latest_status,
    // monitor_check_event, alert_state, alert_notify_record, host_latest_metric, login_audit_log)
    // is intentionally NOT copied from the legacy H2 database.
    // Parent tables before child tables so foreign keys resolve during insert.
    private static final String[][] TABLES = {
        {"tuser", "id"},
        {"monitor_service", "id"},
        {"alert_policy", "id"},
        {"alert_channel", "id"},
        {"alert_group", "id"},
        {"group_policy_rel", "group_id,policy_id"},
        {"group_channel_rel", "group_id,channel_id"},
        {"service_alert_group", "service_id"},
        {"host_config", "id"},
        {"host_process_config", "id"}
    };

    private static final String[] AUTO_INCREMENT_TABLES = {
        "monitor_service", "alert_policy", "alert_channel", "alert_group",
        "host_config", "host_process_config"
    };

    private final LiveMonitorProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final DatabaseDialect databaseDialect;

    public H2ToMysqlMigrationService(
        LiveMonitorProperties properties,
        JdbcTemplate jdbcTemplate,
        TransactionTemplate transactionTemplate,
        DatabaseDialect databaseDialect
    ) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.databaseDialect = databaseDialect;
    }

    @PostConstruct
    public void migrateIfNeeded() {
        if (!databaseDialect.isMysql()) {
            log.info("Current config database is {}, skip H2 to MySQL migration", databaseDialect.productName());
            return;
        }
        if (migrationCompleted()) {
            log.info("H2 to MySQL migration marker exists, skip H2 migration");
            return;
        }

        Path h2DataFile = resolveH2DataFile();
        if (h2DataFile == null) {
            Path expectedMvFile = expectedH2MvFile();
            log.info(
                "Legacy H2 database not found. configured h2-legacy-path='{}', resolved absolute mv.db path='{}', working dir='{}'. Skip migration.",
                properties.getH2LegacyPath(),
                expectedMvFile,
                System.getProperty("user.dir")
            );
            return;
        }

        Path backupPrefix;
        try {
            backupPrefix = backupH2(h2DataFile);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to backup H2 database before migration", ex);
        }

        final Path backupPrefixFinal = backupPrefix;
        MigrationCounts counts = transactionTemplate.execute(status -> {
            String backupUrl = "jdbc:h2:file:" + backupPrefixFinal + ";MODE=LEGACY;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE;IFEXISTS=TRUE";
            try (Connection h2 = DriverManager.getConnection(
                backupUrl,
                properties.getH2LegacyUsername(),
                properties.getH2LegacyPassword()
            )) {
                MigrationCounts migrated = migrate(h2);
                writeMarker(h2DataFile, backupPrefixFinal, migrated);
                return migrated;
            } catch (SQLException ex) {
                status.setRollbackOnly();
                // 28000 = invalid authorization spec (wrong user/password)
                if ("28000".equals(ex.getSQLState())) {
                    throw new IllegalStateException(
                        "H2 to MySQL migration failed: cannot open legacy H2 file at " + backupPrefixFinal
                            + " with username='" + properties.getH2LegacyUsername() + "'. "
                            + "The credentials do not match those used when the H2 database was originally created. "
                            + "Please set LIVE_MONITOR_H2_LEGACY_USERNAME and LIVE_MONITOR_H2_LEGACY_PASSWORD to the correct values "
                            + "(or verify with: java -cp <h2.jar> org.h2.tools.Shell -url \"jdbc:h2:file:"
                            + backupPrefixFinal + ";MODE=LEGACY;IFEXISTS=TRUE\" -user <user> -password <pwd>).",
                        ex
                    );
                }
                throw new IllegalStateException("H2 to MySQL migration failed", ex);
            } catch (Exception ex) {
                status.setRollbackOnly();
                throw new IllegalStateException("H2 to MySQL migration failed", ex);
            }
        });
        archiveOriginalH2(h2DataFile);
        log.info("H2 to MySQL migration completed. config rows: {}, backup: {}", counts.configRows, backupPrefix);
    }

    private MigrationCounts migrate(Connection h2) throws SQLException {
        MigrationCounts counts = new MigrationCounts();
        for (String[] table : TABLES) {
            List<String> keyColumns = Arrays.asList(table[1].split(","));
            counts.configRows += copyTable(h2, table[0], keyColumns);
        }
        for (String table : AUTO_INCREMENT_TABLES) {
            restartAutoIncrement(table);
        }
        return counts;
    }

    private int copyTable(Connection h2, String tableName, List<String> keyColumns) throws SQLException {
        if (!h2TableExists(h2, tableName) || !mysqlTableExists(tableName)) {
            return 0;
        }
        Map<String, String> h2Columns = h2Columns(h2, tableName);
        Set<String> mysqlColumns = mysqlColumns(tableName);
        List<String> common = new ArrayList<String>();
        for (String column : h2Columns.keySet()) {
            if (mysqlColumns.contains(column)) {
                common.add(column);
            }
        }
        if (common.isEmpty()) {
            return 0;
        }
        List<String> updateColumns = new ArrayList<String>(common);
        updateColumns.removeAll(keyColumns);
        String insertSql = "INSERT INTO " + tableName + " (" + join(common) + ") VALUES (" + placeholders(common.size()) + ")"
            + (updateColumns.isEmpty() ? "" : " ON DUPLICATE KEY UPDATE " + updateAssignments(updateColumns));
        int copied = 0;
        try (Statement statement = h2.createStatement();
             ResultSet rows = statement.executeQuery("SELECT " + joinOriginal(h2Columns, common) + " FROM " + tableName)) {
            while (rows.next()) {
                Object[] args = new Object[common.size()];
                for (int i = 0; i < common.size(); i++) {
                    args[i] = rows.getObject(h2Columns.get(common.get(i)));
                }
                jdbcTemplate.update(insertSql, args);
                copied++;
            }
        }
        log.info("Migrated H2 table {} rows: {}", tableName, copied);
        return copied;
    }

    private void restartAutoIncrement(String tableName) {
        if (!mysqlTableExists(tableName)) {
            return;
        }
        Number next = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM " + tableName, Number.class);
        jdbcTemplate.execute("ALTER TABLE " + tableName + " AUTO_INCREMENT = " + next.longValue());
    }

    private void writeMarker(Path sourcePath, Path backupPath, MigrationCounts counts) {
        jdbcTemplate.update(
            "INSERT INTO migration_marker (id, source_path, backup_path, config_rows, rocksdb_rows, completed_at) " +
                "VALUES (?, ?, ?, ?, 0, CURRENT_TIMESTAMP) " +
                "ON DUPLICATE KEY UPDATE source_path = VALUES(source_path), backup_path = VALUES(backup_path), " +
                "config_rows = VALUES(config_rows), completed_at = CURRENT_TIMESTAMP",
            MARKER_ID,
            sourcePath.toString(),
            backupPath.toString(),
            counts.configRows
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

    private Path resolveH2DataFile() {
        Path mvFile = expectedH2MvFile();
        return Files.exists(mvFile) ? mvFile : null;
    }

    private Path expectedH2MvFile() {
        String configured = properties.getH2LegacyPath();
        Path base = Paths.get(configured).toAbsolutePath().normalize();
        if (configured != null && configured.toLowerCase(Locale.ROOT).endsWith(".mv.db")) {
            return base;
        }
        return Paths.get(base + ".mv.db");
    }

    private Path backupH2(Path sourceMvFile) throws Exception {
        Path backupRoot = Paths.get(properties.getH2LegacyBackupDir()).toAbsolutePath().normalize();
        Files.createDirectories(backupRoot);
        Path targetDir = backupRoot.resolve("h2-" + LocalDate.now().format(BACKUP_DATE));
        if (Files.exists(targetDir)) {
            targetDir = backupRoot.resolve("h2-" + LocalDateTime.now().format(BACKUP_TIME));
        }
        Files.createDirectories(targetDir);
        String baseName = baseName(sourceMvFile);
        Files.copy(sourceMvFile, targetDir.resolve(baseName + ".mv.db"), StandardCopyOption.REPLACE_EXISTING);
        copySibling(sourceMvFile, targetDir, baseName, ".trace.db");
        log.info("Backed up H2 database from {} to {}", sourceMvFile, targetDir);
        return targetDir.resolve(baseName);
    }

    private void copySibling(Path sourceMvFile, Path targetDir, String baseName, String suffix) throws Exception {
        String sourceFileName = sourceMvFile.getFileName().toString();
        Path sourceSibling = sourceMvFile.resolveSibling(sourceFileName.substring(0, sourceFileName.length() - ".mv.db".length()) + suffix);
        if (Files.exists(sourceSibling)) {
            Files.copy(sourceSibling, targetDir.resolve(baseName + suffix), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void archiveOriginalH2(Path sourceMvFile) {
        try {
            String sourceFileName = sourceMvFile.getFileName().toString();
            String baseName = sourceFileName.substring(0, sourceFileName.length() - ".mv.db".length());
            Files.deleteIfExists(sourceMvFile);
            Files.deleteIfExists(sourceMvFile.resolveSibling(baseName + ".trace.db"));
        } catch (Exception ex) {
            log.warn("H2 migration succeeded, but original H2 files could not be removed: {}", sourceMvFile, ex);
        }
    }

    private String baseName(Path mvFile) {
        String fileName = mvFile.getFileName().toString();
        return fileName.substring(0, fileName.length() - ".mv.db".length());
    }

    private boolean h2TableExists(Connection h2, String tableName) throws SQLException {
        try (PreparedStatement statement = h2.prepareStatement(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?"
        )) {
            statement.setString(1, tableName.toLowerCase(Locale.ROOT));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) > 0;
            }
        }
    }

    private Map<String, String> h2Columns(Connection h2, String tableName) throws SQLException {
        Map<String, String> columns = new LinkedHashMap<String, String>();
        try (PreparedStatement statement = h2.prepareStatement(
            "SELECT column_name FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position"
        )) {
            statement.setString(1, tableName.toLowerCase(Locale.ROOT));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String original = rows.getString("column_name");
                    columns.put(original.toLowerCase(Locale.ROOT), original);
                }
            }
        }
        return columns;
    }

    private boolean mysqlTableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = SCHEMA() AND LOWER(table_name) = ?",
            Integer.class,
            tableName.toLowerCase(Locale.ROOT)
        );
        return count != null && count > 0;
    }

    private Set<String> mysqlColumns(String tableName) {
        List<String> rows = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = SCHEMA() AND LOWER(table_name) = ?",
            String.class,
            tableName.toLowerCase(Locale.ROOT)
        );
        Set<String> columns = new HashSet<String>();
        for (String row : rows) {
            columns.add(row.toLowerCase(Locale.ROOT));
        }
        return columns;
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

    private String updateAssignments(List<String> columns) {
        List<String> assignments = new ArrayList<String>();
        for (String column : columns) {
            assignments.add(column + " = VALUES(" + column + ")");
        }
        return String.join(", ", assignments);
    }

    private static class MigrationCounts {
        int configRows;
    }
}
