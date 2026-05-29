package com.live.monitor.service;

import com.live.monitor.dto.CheckResult;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PortMonitorService {
    public CheckResult check(String host, Integer port, double timeoutSeconds) {
        if (!StringUtils.hasText(host) || port == null) {
            return new CheckResult("UNKNOWN", null, "Host and port are required for port checks");
        }
        long started = System.nanoTime();
        int timeoutMs = Math.max(1, (int) (timeoutSeconds * 1000));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return new CheckResult("UP", elapsedMs(started), "port open");
        } catch (Exception ex) {
            return new CheckResult("DOWN", elapsedMs(started), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private int elapsedMs(long started) {
        return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
