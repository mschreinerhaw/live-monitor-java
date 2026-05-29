package com.live.monitor.service;

import com.live.monitor.dto.CheckResult;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RedisMonitorService {
    public CheckResult check(String host, Integer port, String username, String password, Boolean clusterMode, double timeoutSeconds) {
        if (!StringUtils.hasText(host) || port == null) {
            return new CheckResult("UNKNOWN", null, "Host and port are required for Redis checks");
        }
        long started = System.nanoTime();
        RedisURI uri = buildUri(host, port, username, password, timeoutSeconds);
        try {
            if (Boolean.TRUE.equals(clusterMode)) {
                RedisClusterClient client = RedisClusterClient.create(uri);
                try (StatefulRedisClusterConnection<String, String> connection = client.connect()) {
                    String info = connection.sync().clusterInfo();
                    boolean ok = info != null && info.toLowerCase().contains("cluster_state:ok");
                    return new CheckResult(ok ? "UP" : "DOWN", elapsedMs(started), info);
                } finally {
                    client.shutdown();
                }
            }
            RedisClient client = RedisClient.create(uri);
            try (StatefulRedisConnection<String, String> connection = client.connect()) {
                String pong = connection.sync().ping();
                boolean ok = "PONG".equalsIgnoreCase(pong);
                return new CheckResult(ok ? "UP" : "DOWN", elapsedMs(started), pong);
            } finally {
                client.shutdown();
            }
        } catch (Exception ex) {
            return new CheckResult("DOWN", elapsedMs(started), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private RedisURI buildUri(String host, int port, String username, String password, double timeoutSeconds) {
        RedisURI.Builder builder = RedisURI.builder()
            .withHost(host)
            .withPort(port)
            .withTimeout(Duration.ofMillis((long) (timeoutSeconds * 1000)));
        if (StringUtils.hasText(password)) {
            if (StringUtils.hasText(username)) {
                builder.withAuthentication(username, password.toCharArray());
            } else {
                builder.withPassword(password.toCharArray());
            }
        }
        return builder.build();
    }

    private int elapsedMs(long started) {
        return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
