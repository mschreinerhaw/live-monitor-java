package com.live.monitor.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.entity.AlertRecord;
import com.live.monitor.entity.MonitorResult;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class RocksDbHistoryRepository implements Closeable {
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
        new TypeReference<Map<String, Object>>() {};
    private static final DateTimeFormatter TEXT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter KEY_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final LiveMonitorProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicLong resultSeq = new AtomicLong();
    private final AtomicLong alertSeq = new AtomicLong();
    private final AtomicLong metricSeq = new AtomicLong();
    private Options options;
    private RocksDB db;

    public RocksDbHistoryRepository(LiveMonitorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void open() throws Exception {
        RocksDB.loadLibrary();
        Path path = Paths.get(properties.getRocksdbPath()).toAbsolutePath().normalize();
        Files.createDirectories(path);
        options = new Options().setCreateIfMissing(true);
        db = RocksDB.open(options, path.toString());
        initializeSequences();
    }

    public synchronized MonitorResult saveMonitorResult(MonitorResult result) {
        return saveMonitorResult(result, null);
    }

    public synchronized MonitorResult saveMonitorResult(MonitorResult result, String migrationId) {
        ensureOpen();
        if (result.id == null) {
            result.id = resultSeq.incrementAndGet();
        } else {
            resultSeq.updateAndGet(current -> Math.max(current, result.id));
        }
        if (!StringUtils.hasText(result.checkedAt)) {
            result.checkedAt = nowText();
        }
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("id", result.id);
        value.put("service_id", result.serviceId);
        value.put("service_name", result.serviceName);
        value.put("service_type", result.serviceType);
        value.put("cluster_name", result.clusterName);
        value.put("status", result.status);
        value.put("response_time_ms", result.responseTimeMs);
        value.put("message", result.message);
        value.put("checked_at", result.checkedAt);
        putMigrationId(value, migrationId);
        put(key("check", String.valueOf(result.serviceId), keyTime(result.checkedAt), String.valueOf(result.id)), value);
        return result;
    }

    public synchronized List<MonitorResult> listMonitorResults(Long serviceId, int limit) {
        String prefix = "check:" + serviceId + ":";
        List<MonitorResult> rows = new ArrayList<MonitorResult>();
        try (RocksIterator iterator = db.newIterator()) {
            seekBeforePrefixEnd(iterator, prefix);
            while (iterator.isValid() && rows.size() < limit) {
                String key = text(iterator.key());
                if (!key.startsWith(prefix)) {
                    break;
                }
                rows.add(mapToMonitorResult(readMap(iterator.value())));
                iterator.prev();
            }
        }
        return rows;
    }

    public synchronized List<MonitorResult> listRecentMonitorResults(int limit) {
        List<MonitorResult> rows = new ArrayList<MonitorResult>();
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seekToLast();
            while (iterator.isValid() && rows.size() < limit) {
                if (text(iterator.key()).startsWith("check:")) {
                    rows.add(mapToMonitorResult(readMap(iterator.value())));
                }
                iterator.prev();
            }
        }
        return rows;
    }

    public synchronized Map<Long, MonitorResult> latestMonitorResultsBefore(String cutoff) {
        LocalDateTime cutoffTime = parseTime(cutoff);
        Map<Long, MonitorResult> latestByService = new HashMap<Long, MonitorResult>();
        Map<Long, LocalDateTime> latestTimeByService = new HashMap<Long, LocalDateTime>();
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                if (text(iterator.key()).startsWith("check:")) {
                    MonitorResult result = mapToMonitorResult(readMap(iterator.value()));
                    LocalDateTime checkedAt = parseTime(result.checkedAt);
                    if (result.serviceId != null && checkedAt.isBefore(cutoffTime)) {
                        LocalDateTime currentLatest = latestTimeByService.get(result.serviceId);
                        if (currentLatest == null || checkedAt.isAfter(currentLatest)) {
                            latestTimeByService.put(result.serviceId, checkedAt);
                            latestByService.put(result.serviceId, result);
                        }
                    }
                }
                iterator.next();
            }
        }
        return latestByService;
    }

    public synchronized AlertRecord saveAlertRecord(AlertRecord record) {
        return saveAlertRecord(record, null);
    }

    public synchronized AlertRecord saveAlertRecord(AlertRecord record, String migrationId) {
        ensureOpen();
        if (record.id == null) {
            record.id = alertSeq.incrementAndGet();
        } else {
            alertSeq.updateAndGet(current -> Math.max(current, record.id));
        }
        if (!StringUtils.hasText(record.createdAt)) {
            record.createdAt = nowText();
        }
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("id", record.id);
        value.put("service_id", record.serviceId);
        value.put("service_name", record.serviceName);
        value.put("service_type", record.serviceType);
        value.put("cluster_name", record.clusterName);
        value.put("alert_type", record.alertType);
        value.put("alert_content", record.alertContent);
        value.put("alert_status", record.alertStatus);
        value.put("created_at", record.createdAt);
        putMigrationId(value, migrationId);
        put(key("alert", keyTime(record.createdAt), String.valueOf(record.id), null), value);
        return record;
    }

    public synchronized List<AlertRecord> listAlerts(Long serviceId, int limit) {
        List<AlertRecord> rows = new ArrayList<AlertRecord>();
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seekToLast();
            while (iterator.isValid() && rows.size() < limit) {
                if (text(iterator.key()).startsWith("alert:")) {
                    AlertRecord record = mapToAlertRecord(readMap(iterator.value()));
                    if (serviceId == null || serviceId.equals(record.serviceId)) {
                        rows.add(record);
                    }
                }
                iterator.prev();
            }
        }
        return rows;
    }

    public synchronized int countAlertsBetween(String startInclusive, String endExclusive) {
        LocalDateTime startTime = parseTime(startInclusive);
        LocalDateTime endTime = parseTime(endExclusive);
        int count = 0;
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seek(bytes("alert:"));
            while (iterator.isValid() && text(iterator.key()).startsWith("alert:")) {
                AlertRecord record = mapToAlertRecord(readMap(iterator.value()));
                LocalDateTime createdAt = parseTime(record.createdAt);
                if (!createdAt.isBefore(startTime) && createdAt.isBefore(endTime)) {
                    count++;
                }
                iterator.next();
            }
        }
        return count;
    }

    public synchronized int deleteAllAlerts() {
        List<byte[]> keys = keysWithPrefix("alert:");
        for (byte[] key : keys) {
            delete(key);
        }
        return keys.size();
    }

    public synchronized Map<String, Object> saveHostMetric(
        Long hostId,
        Double cpuUsagePercent,
        Double loadAverage,
        Double memoryUsedPercent,
        Double diskUsedPercent
    ) {
        return saveHostMetric(null, hostId, cpuUsagePercent, loadAverage, memoryUsedPercent, diskUsedPercent, null, null);
    }

    public synchronized Map<String, Object> saveHostMetric(
        Long id,
        Long hostId,
        Double cpuUsagePercent,
        Double loadAverage,
        Double memoryUsedPercent,
        Double diskUsedPercent,
        String checkedAt,
        String migrationId
    ) {
        ensureOpen();
        Long metricId = id;
        if (metricId == null) {
            metricId = metricSeq.incrementAndGet();
        } else {
            final Long existingMetricId = metricId;
            metricSeq.updateAndGet(current -> Math.max(current, existingMetricId));
        }
        String time = StringUtils.hasText(checkedAt) ? checkedAt : nowText();
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("id", metricId);
        value.put("host_id", hostId);
        value.put("metric_name", "system");
        value.put("cpu_usage_percent", cpuUsagePercent);
        value.put("load_average", loadAverage);
        value.put("memory_used_percent", memoryUsedPercent);
        value.put("disk_used_percent", diskUsedPercent);
        value.put("checked_at", time);
        putMigrationId(value, migrationId);
        put(key("metric", String.valueOf(hostId), "system", keyTime(time) + ":" + metricId), value);
        return value;
    }

    public synchronized long countPrefix(String prefix) {
        long count = 0;
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seek(bytes(prefix));
            while (iterator.isValid() && text(iterator.key()).startsWith(prefix)) {
                count++;
                iterator.next();
            }
        }
        return count;
    }

    public synchronized void deleteByMigrationId(String migrationId) {
        if (!StringUtils.hasText(migrationId)) {
            return;
        }
        List<byte[]> keys = new ArrayList<byte[]>();
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                Map<String, Object> value = readMap(iterator.value());
                if (migrationId.equals(String.valueOf(value.get("migration_id")))) {
                    keys.add(copy(iterator.key()));
                }
                iterator.next();
            }
        }
        for (byte[] key : keys) {
            delete(key);
        }
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        if (db != null) {
            db.close();
            db = null;
        }
        if (options != null) {
            options.close();
            options = null;
        }
    }

    private void initializeSequences() {
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                Map<String, Object> value = readMap(iterator.value());
                String key = text(iterator.key());
                Long id = longValue(value.get("id"));
                if (id != null) {
                    if (key.startsWith("check:")) {
                        resultSeq.updateAndGet(current -> Math.max(current, id));
                    } else if (key.startsWith("alert:")) {
                        alertSeq.updateAndGet(current -> Math.max(current, id));
                    } else if (key.startsWith("metric:")) {
                        metricSeq.updateAndGet(current -> Math.max(current, id));
                    }
                }
                iterator.next();
            }
        }
    }

    private List<byte[]> keysWithPrefix(String prefix) {
        List<byte[]> keys = new ArrayList<byte[]>();
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seek(bytes(prefix));
            while (iterator.isValid() && text(iterator.key()).startsWith(prefix)) {
                keys.add(copy(iterator.key()));
                iterator.next();
            }
        }
        return keys;
    }

    private void seekBeforePrefixEnd(RocksIterator iterator, String prefix) {
        iterator.seek(bytes(prefix + "~"));
        if (!iterator.isValid()) {
            iterator.seekToLast();
        } else {
            iterator.prev();
        }
    }

    private void put(String key, Map<String, Object> value) {
        try {
            db.put(bytes(key), objectMapper.writeValueAsBytes(value));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to write RocksDB key " + key, ex);
        }
    }

    private void delete(byte[] key) {
        try {
            db.delete(key);
        } catch (RocksDBException ex) {
            throw new IllegalStateException("Failed to delete RocksDB key " + text(key), ex);
        }
    }

    private Map<String, Object> readMap(byte[] value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception ex) {
            return new HashMap<String, Object>();
        }
    }

    private MonitorResult mapToMonitorResult(Map<String, Object> value) {
        MonitorResult result = new MonitorResult();
        result.id = longValue(value.get("id"));
        result.serviceId = longValue(value.get("service_id"));
        result.serviceName = stringValue(value.get("service_name"));
        result.serviceType = stringValue(value.get("service_type"));
        result.clusterName = stringValue(value.get("cluster_name"));
        result.status = stringValue(value.get("status"));
        result.responseTimeMs = intValue(value.get("response_time_ms"));
        result.message = stringValue(value.get("message"));
        result.checkedAt = stringValue(value.get("checked_at"));
        return result;
    }

    private AlertRecord mapToAlertRecord(Map<String, Object> value) {
        AlertRecord record = new AlertRecord();
        record.id = longValue(value.get("id"));
        record.serviceId = longValue(value.get("service_id"));
        record.serviceName = stringValue(value.get("service_name"));
        record.serviceType = stringValue(value.get("service_type"));
        record.clusterName = stringValue(value.get("cluster_name"));
        record.alertType = stringValue(value.get("alert_type"));
        record.alertContent = stringValue(value.get("alert_content"));
        record.alertStatus = stringValue(value.get("alert_status"));
        record.createdAt = stringValue(value.get("created_at"));
        return record;
    }

    private String key(String first, String second, String third, String fourth) {
        StringBuilder builder = new StringBuilder(first).append(':').append(second);
        if (third != null) {
            builder.append(':').append(third);
        }
        if (fourth != null) {
            builder.append(':').append(fourth);
        }
        builder.append(':').append(UUID.randomUUID());
        return builder.toString();
    }

    private String keyTime(String value) {
        LocalDateTime time = parseTime(value);
        return KEY_TIME.format(time);
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
            try {
                return LocalDateTime.parse(value);
            } catch (Exception ignoredAgain) {
                return LocalDateTime.now();
            }
        }
    }

    private String nowText() {
        return TEXT_TIME.format(LocalDateTime.now());
    }

    private void putMigrationId(Map<String, Object> value, String migrationId) {
        if (StringUtils.hasText(migrationId)) {
            value.put("migration_id", migrationId);
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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private byte[] copy(byte[] value) {
        byte[] target = new byte[value.length];
        System.arraycopy(value, 0, target, 0, value.length);
        return target;
    }

    private void ensureOpen() {
        if (db == null) {
            throw new IllegalStateException("RocksDB is not open");
        }
    }
}
