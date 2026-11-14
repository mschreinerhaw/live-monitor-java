package com.live.monitor.service;

import com.live.monitor.dto.CheckResult;
import com.live.monitor.entity.MonitorService;
import java.util.Set;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Bounds checker execution independently of scheduler and HTTP caller threads. */
@Component
public class MonitorCheckExecution {
    private final ThreadPoolExecutor executor;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public MonitorCheckExecution(@Value("${live-monitor.check-execution-threads:16}") int threads) {
        executor = new ThreadPoolExecutor(Math.max(1, threads), Math.max(1, threads), 0, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(), r -> {
                Thread thread = new Thread(r, "monitor-bounded-check");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    public CheckResult run(MonitorService service, Callable<CheckResult> checker) {
        if (!running.add(service.id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Check already running for service " + service.id);
        FutureTask<CheckResult> task = new FutureTask<>(checker);
        try {
            executor.execute(() -> {
                try { task.run(); } finally { running.remove(service.id); }
            });
        } catch (RejectedExecutionException ex) {
            running.remove(service.id);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Monitor checker capacity exhausted; retry next tick");
        }
        double seconds = service.checkTimeoutSeconds == null ? 3 : service.checkTimeoutSeconds;
        long millis = (long) (Math.max(0.1, Math.min(300, Double.isFinite(seconds) ? seconds : 3)) * 1000);
        try {
            CheckResult result = task.get(millis, TimeUnit.MILLISECONDS);
            if (result == null) return failure("CHECKER_INTERNAL_ERROR", "Checker returned no result");
            if (result.status == null) result.status = "UNKNOWN";
            if ("UNKNOWN".equals(result.status) && result.eventType == null) {
                String message = result.message == null ? "" : result.message.toLowerCase(java.util.Locale.ROOT);
                result.eventType = message.contains("timeout") || message.contains("timed out") ? "CHECK_TIMEOUT"
                    : message.contains("auth") || message.contains("denied") ? "AUTHENTICATION_ERROR"
                    : message.contains("config") || message.contains("unsupported") || message.contains("invalid") || message.contains("empty") ? "CHECK_CONFIGURATION_ERROR"
                    : message.contains("connect") ? "CONNECTION_ERROR" : "CHECKER_INTERNAL_ERROR";
                result.message = result.eventType + ": " + safe(result.message);
            }
            return result;
        } catch (TimeoutException ex) {
            task.cancel(true);
            // The running guard stays until the checker actually exits, even if it ignores interruption.
            return failure("CHECK_TIMEOUT", "Check exceeded " + millis + " ms execution deadline");
        } catch (InterruptedException ex) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Check interrupted during shutdown");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            String category = cause instanceof SecurityException ? "AUTHENTICATION_ERROR"
                : cause instanceof IllegalArgumentException ? "CHECK_CONFIGURATION_ERROR"
                : cause instanceof java.net.ConnectException ? "CONNECTION_ERROR" : "CHECKER_INTERNAL_ERROR";
            return failure(category, cause.getClass().getSimpleName() + ": " + safe(cause.getMessage()));
        }
    }

    private CheckResult failure(String category, String detail) {
        return new CheckResult("UNKNOWN", 0, category + ": " + detail, category, null);
    }

    public static String safe(String message) {
        if (message == null) return "No error detail";
        String value = message.replaceAll("(?i)(authorization\\s*[:=]\\s*)(?:Bearer\\s+|Basic\\s+)?[^\\s,;]+", "$1[redacted]")
            .replaceAll("(?i)(password|passwd|secret|token|signature|sign|api_key|apikey|access_key)([\\s=:]+)[^\\s,;&]+", "$1$2[redacted]")
            .replaceAll("(?i)(https?://)[^\\s/@]+:[^\\s/@]+@", "$1[redacted]@");
        return value.substring(0, Math.min(1000, value.length()));
    }

    @PreDestroy public void stop() { executor.shutdownNow(); }
}
