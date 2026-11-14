package com.live.monitor.store;

import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.util.MonitorTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.lang.reflect.Field;
import org.apache.lucene.index.IndexWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MetricSearchIndexTest {
    @TempDir Path temp;
    LiveMonitorProperties properties() {
        LiveMonitorProperties p=new LiveMonitorProperties();
        p.setLuceneIndexPath(temp.resolve("index").toString());
        p.setRocksdbPath(temp.resolve("rocks").toString());return p;
    }
    @Test void interruptedCallerCannotCloseSharedWriter() throws Exception {
        MetricSearchIndex index=new MetricSearchIndex(properties());index.open();
        try {
            Thread.currentThread().interrupt();
            try { index.indexMetric(1L,2L,1D,0D,20D,30D,null,MonitorTime.nowText()); }
            finally { assertTrue(Thread.interrupted()); }
            index.flush();
            assertEquals(1,index.searchByHost(2,1,10).size());
            index.indexMetric(2L,2L,2D,0D,20D,30D,null,MonitorTime.nowText());index.flush();
            assertEquals(2,index.searchByHost(2,1,10).size());
        } finally { index.close(); }
    }
    @Test void closedWriterDoesNotFailCollectionAndHistoryFallsBackToRocksThenRebuilds() throws Exception {
        LiveMonitorProperties p=properties();MetricSearchIndex index=new MetricSearchIndex(p);index.open();
        RocksDbHistoryRepository history=new RocksDbHistoryRepository(p,new ObjectMapper(),index);history.open();
        try {
            history.saveHostMetric(null,2L,1D,0D,20D,30D,null,MonitorTime.nowText(),null);
            Field field=MetricSearchIndex.class.getDeclaredField("writer");field.setAccessible(true);
            ((IndexWriter)field.get(index)).rollback();
            assertDoesNotThrow(()->history.saveHostMetric(null,2L,2D,0D,20D,30D,null,MonitorTime.nowText(),null));
            assertEquals(2,history.listHostMetrics(2L,1,10).size());
            assertTrue(index.isEmpty());
        } finally { history.close();index.close(); }
        MetricSearchIndex reopened=new MetricSearchIndex(p);reopened.open();
        RocksDbHistoryRepository recovered=new RocksDbHistoryRepository(p,new ObjectMapper(),reopened);recovered.open();
        try { assertEquals(2,reopened.searchByHost(2L,1,10).size()); }
        finally { recovered.close();reopened.close(); }
    }
}
