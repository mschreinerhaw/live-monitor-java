package com.live.monitor.service;

import com.live.monitor.dto.CheckResult;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ZookeeperMonitorService {
    public CheckResult check(String host, Integer port, String mode, String command, Integer expectedNodes, double timeoutSeconds) {
        if (!StringUtils.hasText(host) || port == null) {
            return new CheckResult("UNKNOWN", null, "Host and port are required for ZooKeeper checks");
        }
        long started = System.nanoTime();
        int timeoutMs = (int) (timeoutSeconds * 1000);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            if ("port".equalsIgnoreCase(mode)) {
                return new CheckResult("UP", elapsedMs(started), "port open");
            }
            String cmd = StringUtils.hasText(command) ? command.trim() : "ruok";
            if (cmd.length() > 16) {
                cmd = cmd.substring(0, 16);
            }
            OutputStream out = socket.getOutputStream();
            out.write(cmd.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[2048];
            int read = in.read(buffer);
            String text = read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
            boolean ok;
            if ("mntr".equals(cmd) && expectedNodes != null) {
                int followers = expectedNodes - 1;
                ok = text.contains("zk_synced_followers\t" + followers) || text.contains("zk_followers\t" + followers);
            } else {
                ok = "ruok".equals(cmd) ? "imok".equals(text) : StringUtils.hasText(text);
            }
            return new CheckResult(ok ? "UP" : "DOWN", elapsedMs(started), StringUtils.hasText(text) ? text : "empty response");
        } catch (Exception ex) {
            return new CheckResult("DOWN", elapsedMs(started), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private int elapsedMs(long started) {
        return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
