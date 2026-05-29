package com.live.monitor.service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public class SystemMetricsService {
    private final AtomicLong trafficBytes = new AtomicLong();
    private long lastTrafficBytes;
    private long lastSampleNanos = System.nanoTime();

    public void recordRequest(String method, String uri, String query) {
        int queryLength = query == null ? 0 : query.length();
        int uriLength = uri == null ? 0 : uri.length();
        trafficBytes.addAndGet(320L + method.length() + uriLength + queryLength);
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("cpu", percent(cpuLoad()));
        result.put("memory", memoryPercent());
        result.put("disk", diskPercent());
        result.put("network_kbps", networkKbps());
        result.put("time", System.currentTimeMillis());
        return result;
    }

    private double cpuLoad() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            double load = ((com.sun.management.OperatingSystemMXBean) bean).getSystemCpuLoad();
            if (load >= 0) {
                return load;
            }
        }
        double loadAverage = bean.getSystemLoadAverage();
        int cores = Math.max(1, bean.getAvailableProcessors());
        return loadAverage < 0 ? 0 : Math.min(1, loadAverage / cores);
    }

    private double memoryPercent() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean osBean = (com.sun.management.OperatingSystemMXBean) bean;
            long total = osBean.getTotalPhysicalMemorySize();
            long free = osBean.getFreePhysicalMemorySize();
            if (total > 0) {
                return percent(1 - (free / (double) total));
            }
        }
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        return total <= 0 ? 0 : percent(1 - (free / (double) total));
    }

    private double diskPercent() {
        long total = 0;
        long free = 0;
        File[] roots = File.listRoots();
        if (roots != null) {
            for (File root : roots) {
                total += Math.max(0, root.getTotalSpace());
                free += Math.max(0, root.getUsableSpace());
            }
        }
        return total <= 0 ? 0 : percent(1 - (free / (double) total));
    }

    private double networkKbps() {
        long now = System.nanoTime();
        long current = trafficBytes.get();
        long byteDelta = Math.max(0, current - lastTrafficBytes);
        double seconds = Math.max(0.001, (now - lastSampleNanos) / 1_000_000_000.0);
        lastTrafficBytes = current;
        lastSampleNanos = now;
        return Math.round((byteDelta / 1024.0 / seconds) * 10.0) / 10.0;
    }

    private double percent(double ratio) {
        return Math.round(Math.max(0, Math.min(1, ratio)) * 1000.0) / 10.0;
    }
}
