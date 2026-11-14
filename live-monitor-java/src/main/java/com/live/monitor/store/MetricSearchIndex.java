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
import java.util.concurrent.Callable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
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
    private volatile boolean healthy = true;
    private volatile boolean closing;
    private volatile boolean rebuildRequired;
    private volatile Thread indexThread;
    private Path rebuildMarker;
    // Never execute Lucene file-channel IO on cancellable checker/HTTP threads.
    private final ThreadPoolExecutor indexExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(256), runnable -> {
            Thread thread = new Thread(runnable, "metric-search-index-io");
            thread.setDaemon(true);
            indexThread = thread;
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    private <T> T isolated(Callable<T> action, T fallback) {
        if (!healthy || closing) return fallback;
        try {
            Future<T> future = indexExecutor.submit(action);
            return future.get();
        } catch (InterruptedException ex) {
            // Leave the queued operation running. Cancelling it would close Lucene's NIO channels.
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception ex) {
            disableIndex(ex);
            return fallback;
        }
    }

    private void disableIndex(Exception ex) {
        if (healthy) log.warn("Metric search index unavailable; host collection continues using RocksDB history. Restart to reopen the index.", ex);
        healthy = false;
        markRebuildRequired();
    }

    private void markRebuildRequired() {
        rebuildRequired = true;
        try { if (rebuildMarker != null) Files.write(rebuildMarker, new byte[] {1}); }
        catch (IOException markerError) { log.warn("Could not mark metric index for rebuild", markerError); }
    }

    public MetricSearchIndex(LiveMonitorProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public synchronized void open() throws IOException {
        Path path = Paths.get(properties.getLuceneIndexPath()).toAbsolutePath().normalize().resolve("metrics");
        Files.createDirectories(path);
        rebuildMarker = path.getParent().resolve("metrics.rebuild-required");
        directory = FSDirectory.open(path);
        IndexWriterConfig config = new IndexWriterConfig(new KeywordAnalyzer());
        config.setOpenMode(Files.exists(rebuildMarker) ? IndexWriterConfig.OpenMode.CREATE : IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        writer = new IndexWriter(directory, config);
        // Ensure the index exists on disk before opening a reader.
        writer.commit();
        // Keep the marker until a clean shutdown, so an interrupted rebuild is retried.
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
        if (closing) return;
        closing = true;
        if (maintenance != null) maintenance.shutdown();
        try {
            Future<?> closed = indexExecutor.submit(this::closeResources);
            indexExecutor.shutdown();
            closed.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warn("Lucene index shutdown is still pending or failed", ex);
        }
    }

    private void closeResources() {
        try {
            if (maintenance != null) {
                maintenance = null;
            }
            if (searcherManager != null) {
                searcherManager.close();
                searcherManager = null;
            }
            if (writer != null) {
                try { if (writer.isOpen()) writer.close(); }
                finally { writer = null; }
            }
            if (directory != null) {
                directory.close();
                directory = null;
            }
            if (healthy && !rebuildRequired && rebuildMarker != null) Files.deleteIfExists(rebuildMarker);
        } catch (Exception ex) {
            log.warn("Failed to cleanly close Lucene metric index", ex);
        }
    }

    /** Number of live documents in the index. */
    public int numDocs() {
        if (!healthy || closing) return 0;
        if (Thread.currentThread() != indexThread) return isolated(this::numDocs, 0);
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
        if (!healthy) return;
        if (closing && Thread.currentThread() != indexThread) { markRebuildRequired(); return; }
        if (Thread.currentThread() != indexThread) {
            isolated(() -> { indexMetric(id, hostId, cpuUsagePercent, loadAverage, memoryUsedPercent,
                diskUsedPercent, diskMetricsJson, checkedAt); return null; }, null);
            return;
        }
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
            disableIndex(ex);
        }
    }

    /**
     * Return up to {@code limit} metric documents for {@code hostId} whose
     * {@code checked_at_ms} is within the last {@code days} days, ordered
     * newest first.
     */
    public List<Map<String, Object>> searchByHost(long hostId, int days, int limit) {
        if (!healthy || closing) return new ArrayList<>();
        if (Thread.currentThread() != indexThread) return isolated(() -> searchByHost(hostId, days, limit), new ArrayList<>());
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
            disableIndex(ex);
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
    public void clear() {
        if (!healthy || closing) return;
        if (Thread.currentThread() != indexThread) { isolated(() -> { clear(); return null; }, null); return; }
        if (writer == null) {
            return;
        }
        try {
            writer.deleteAll();
            writer.commit();
            searcherManager.maybeRefresh();
            log.info("Cleared Lucene metric index");
        } catch (IOException ex) {
            disableIndex(ex);
        }
    }

    /**
     * Remove indexed documents whose {@code checked_at_ms} is strictly before {@code cutoffMs}.
     * Returns the (approximate) number of docs removed based on writer stats before/after.
     */
    public int purgeBefore(long cutoffMs) {
        if (!healthy || closing) return 0;
        if (Thread.currentThread() != indexThread) return isolated(() -> purgeBefore(cutoffMs), 0);
        if (writer == null) {
            return 0;
        }
        int before = writer.getDocStats().numDocs;
        try {
            writer.deleteDocuments(LongPoint.newRangeQuery("checked_at_ms", Long.MIN_VALUE, cutoffMs - 1));
            writer.commit();
            safeMaybeRefresh();
        } catch (IOException ex) {
            disableIndex(ex);
            return 0;
        }
        int after = writer.getDocStats().numDocs;
        return Math.max(0, before - after);
    }

    /** Force a commit + reader refresh (best-effort). */
    public void flush() {
        if (Thread.currentThread() != indexThread) { isolated(() -> { flush(); return null; }, null); return; }
        safeCommit();
        safeMaybeRefresh();
    }

    private void safeMaybeRefresh() {
        if (!healthy || closing) return;
        if (Thread.currentThread() != indexThread) { isolated(() -> { safeMaybeRefresh(); return null; }, null); return; }
        try {
            if (searcherManager != null) {
                searcherManager.maybeRefresh();
            }
        } catch (Exception ex) {
            disableIndex(ex);
        }
    }

    private void safeCommit() {
        if (!healthy || closing) return;
        if (Thread.currentThread() != indexThread) { isolated(() -> { safeCommit(); return null; }, null); return; }
        try {
            if (writer != null && pendingChanges) {
                writer.commit();
                pendingChanges = false;
            }
        } catch (Exception ex) {
            disableIndex(ex);
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
