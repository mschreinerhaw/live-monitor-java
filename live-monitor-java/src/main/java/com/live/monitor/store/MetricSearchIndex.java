package com.live.monitor.store;

import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.util.MonitorTime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Lucene-backed secondary index for host metrics stored in RocksDB.
 *
 * <p>RocksDB remains the source of truth. This index accelerates the common
 * "give me the last N system metrics for host X within the last M days" query
 * used by dashboards, so we don't have to iterate + JSON-decode a large slice of
 * RocksDB on every request.</p>
 *
 * <p>Storage layout of each document:</p>
 * <ul>
 *   <li>{@code host_id} — LongPoint + StoredField (for filtering by host)</li>
 *   <li>{@code checked_at_ms} — LongPoint + NumericDocValues + StoredField
 *       (epoch millis; used for range filtering and reverse-chronological sort)</li>
 *   <li>{@code id} — StoredField</li>
 *   <li>{@code checked_at} — StoredField (original text timestamp)</li>
 *   <li>{@code cpu_usage_percent / load_average / memory_used_percent /
 *       disk_used_percent} — StoredField (numeric)</li>
 *   <li>{@code disk_metrics_json} — StoredField (raw JSON)</li>
 * </ul>
 */
@Component
public class MetricSearchIndex {
    private static final Logger log = LoggerFactory.getLogger(MetricSearchIndex.class);
    private static final DateTimeFormatter TEXT_TIME = MonitorTime.TEXT_TIME;

    private final LiveMonitorProperties properties;

    private Directory directory;
    private IndexWriter writer;
    private SearcherManager searcherManager;
    private ScheduledExecutorService maintenance;
    private volatile boolean pendingChanges = false;

    public MetricSearchIndex(LiveMonitorProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public synchronized void open() throws IOException {
        Path path = Paths.get(properties.getLuceneIndexPath()).toAbsolutePath().normalize().resolve("metrics");
        Files.createDirectories(path);
        directory = FSDirectory.open(path);
        IndexWriterConfig config = new IndexWriterConfig(new KeywordAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        writer = new IndexWriter(directory, config);
        // Ensure the index exists on disk before opening a reader.
        writer.commit();
        searcherManager = new SearcherManager(writer, true, true, null);
        maintenance = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "metric-search-index-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        // Periodically refresh readers so queries see recent additions, and
        // commit to disk so writes survive restart.
        maintenance.scheduleWithFixedDelay(this::safeMaybeRefresh, 2, 2, TimeUnit.SECONDS);
        maintenance.scheduleWithFixedDelay(this::safeCommit, 60, 60, TimeUnit.SECONDS);
        log.info("Lucene metric index opened at {} (docs={})", path, numDocs());
    }

    @PreDestroy
    public synchronized void close() {
        try {
            if (maintenance != null) {
                maintenance.shutdownNow();
                maintenance = null;
            }
            if (searcherManager != null) {
                searcherManager.close();
                searcherManager = null;
            }
            if (writer != null) {
                writer.commit();
                writer.close();
                writer = null;
            }
            if (directory != null) {
                directory.close();
                directory = null;
            }
        } catch (Exception ex) {
            log.warn("Failed to cleanly close Lucene metric index", ex);
        }
    }

    /** Number of live documents in the index. */
    public int numDocs() {
        return writer == null ? 0 : writer.getDocStats().numDocs;
    }

    public boolean isEmpty() {
        return numDocs() == 0;
    }

    /**
     * Index (or replace) a single host metric document. Uses the metric id as
     * a Term key so re-indexing an existing id updates the document.
     */
    public void indexMetric(
        Long id,
        Long hostId,
        Double cpuUsagePercent,
        Double loadAverage,
        Double memoryUsedPercent,
        Double diskUsedPercent,
        String diskMetricsJson,
        String checkedAt
    ) {
        if (writer == null || id == null || hostId == null) {
            return;
        }
        long checkedAtMs = toEpochMillis(checkedAt);
        Document doc = new Document();
        String idKey = String.valueOf(id);
        doc.add(new StringField("id_key", idKey, Field.Store.NO));
        doc.add(new StoredField("id", id));
        doc.add(new LongPoint("host_id", hostId));
        doc.add(new StoredField("host_id", hostId));
        doc.add(new LongPoint("checked_at_ms", checkedAtMs));
        doc.add(new NumericDocValuesField("checked_at_ms", checkedAtMs));
        doc.add(new StoredField("checked_at_ms", checkedAtMs));
        if (StringUtils.hasText(checkedAt)) {
            doc.add(new StoredField("checked_at", checkedAt));
        }
        addNumeric(doc, "cpu_usage_percent", cpuUsagePercent);
        addNumeric(doc, "load_average", loadAverage);
        addNumeric(doc, "memory_used_percent", memoryUsedPercent);
        addNumeric(doc, "disk_used_percent", diskUsedPercent);
        if (StringUtils.hasText(diskMetricsJson)) {
            doc.add(new StoredField("disk_metrics_json", diskMetricsJson));
        }
        try {
            writer.updateDocument(new Term("id_key", idKey), doc);
            pendingChanges = true;
        } catch (IOException ex) {
            log.warn("Failed to index metric id={} hostId={}", id, hostId, ex);
        }
    }

    /**
     * Return up to {@code limit} metric documents for {@code hostId} whose
     * {@code checked_at_ms} is within the last {@code days} days, ordered
     * newest first.
     */
    public List<Map<String, Object>> searchByHost(long hostId, int days, int limit) {
        if (searcherManager == null) {
            return new ArrayList<>();
        }
        int maxRows = Math.max(1, Math.min(limit, 10000));
        long cutoffMs = MonitorTime.now().minusDays(Math.max(1, days))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        BooleanQuery.Builder query = new BooleanQuery.Builder();
        query.add(LongPoint.newExactQuery("host_id", hostId), BooleanClause.Occur.MUST);
        query.add(LongPoint.newRangeQuery("checked_at_ms", cutoffMs, Long.MAX_VALUE), BooleanClause.Occur.MUST);
        Sort sort = new Sort(new SortField("checked_at_ms", SortField.Type.LONG, true));

        List<Map<String, Object>> rows = new ArrayList<>();
        IndexSearcher searcher = null;
        try {
            safeMaybeRefresh();
            searcher = searcherManager.acquire();
            TopFieldDocs top = searcher.search(query.build(), maxRows, sort);
            for (ScoreDoc sd : top.scoreDocs) {
                Document doc = searcher.doc(sd.doc);
                rows.add(documentToMap(doc));
            }
        } catch (IOException ex) {
            log.warn("Lucene search failed for hostId={}, falling back to caller", hostId, ex);
            return new ArrayList<>();
        } finally {
            if (searcher != null) {
                try {
                    searcherManager.release(searcher);
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
        // listHostMetrics contract returns oldest→newest; reverse.
        java.util.Collections.reverse(rows);
        return rows;
    }

    /**
     * Delete every document from the index. Used before a full rebuild.
     */
    public synchronized void clear() {
        if (writer == null) {
            return;
        }
        try {
            writer.deleteAll();
            writer.commit();
            searcherManager.maybeRefresh();
            log.info("Cleared Lucene metric index");
        } catch (IOException ex) {
            log.warn("Failed to clear Lucene metric index", ex);
        }
    }

    /**
     * Remove indexed documents whose {@code checked_at_ms} is strictly before {@code cutoffMs}.
     * Returns the (approximate) number of docs removed based on writer stats before/after.
     */
    public synchronized int purgeBefore(long cutoffMs) {
        if (writer == null) {
            return 0;
        }
        int before = writer.getDocStats().numDocs;
        try {
            writer.deleteDocuments(LongPoint.newRangeQuery("checked_at_ms", Long.MIN_VALUE, cutoffMs - 1));
            writer.commit();
            safeMaybeRefresh();
        } catch (IOException ex) {
            log.warn("Failed to purge Lucene metric index before {}", cutoffMs, ex);
            return 0;
        }
        int after = writer.getDocStats().numDocs;
        return Math.max(0, before - after);
    }

    /** Force a commit + reader refresh (best-effort). */
    public synchronized void flush() {
        safeCommit();
        safeMaybeRefresh();
    }

    private void safeMaybeRefresh() {
        try {
            if (searcherManager != null) {
                searcherManager.maybeRefresh();
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private void safeCommit() {
        try {
            if (writer != null && pendingChanges) {
                writer.commit();
                pendingChanges = false;
            }
        } catch (Exception ex) {
            log.warn("Lucene metric index commit failed", ex);
        }
    }

    private void addNumeric(Document doc, String name, Double value) {
        if (value == null) {
            return;
        }
        doc.add(new StoredField(name, value));
        // Also index as DoublePoint so we can range-query later if needed.
        doc.add(new DoublePoint(name + "_p", value));
    }

    private Map<String, Object> documentToMap(Document doc) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (IndexableField field : doc.getFields()) {
            String name = field.name();
            if ("id".equals(name) || "host_id".equals(name) || "checked_at_ms".equals(name)) {
                Number n = field.numericValue();
                if (n != null) {
                    map.put(name, n.longValue());
                    continue;
                }
            }
            if ("cpu_usage_percent".equals(name) || "load_average".equals(name)
                || "memory_used_percent".equals(name) || "disk_used_percent".equals(name)) {
                Number n = field.numericValue();
                if (n != null) {
                    map.put(name, n.doubleValue());
                    continue;
                }
            }
            String s = field.stringValue();
            if (s != null) {
                map.put(name, s);
            }
        }
        map.put("metric_name", "system");
        return map;
    }

    private long toEpochMillis(String checkedAt) {
        if (!StringUtils.hasText(checkedAt)) {
            return System.currentTimeMillis();
        }
        String text = checkedAt.trim().replace('T', ' ');
        if (text.length() == 19) {
            text = text + ".000";
        }
        if (text.length() > 23) {
            text = text.substring(0, 23);
        }
        try {
            LocalDateTime dt = LocalDateTime.parse(text, TEXT_TIME);
            return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ex) {
            return System.currentTimeMillis();
        }
    }
}
