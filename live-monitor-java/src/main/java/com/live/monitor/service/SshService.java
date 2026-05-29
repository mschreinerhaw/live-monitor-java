package com.live.monitor.service;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.live.monitor.entity.HostConfig;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SshService {
    private final CryptoService cryptoService;

    public SshService(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    public String exec(HostConfig host, String command, int timeoutMillis) {
        Session session = null;
        ChannelExec channel = null;
        try {
            JSch jsch = new JSch();
            String privateKey = cryptoService.decrypt(host.privateKeyCipher);
            if (StringUtils.hasText(privateKey)) {
                jsch.addIdentity(
                    "live-monitor-" + host.id,
                    privateKey.getBytes(StandardCharsets.UTF_8),
                    null,
                    null
                );
            }
            session = jsch.getSession(host.sshUser, host.ip, host.sshPort == null ? 22 : host.sshPort);
            String password = cryptoService.decrypt(host.sshPasswordCipher);
            if (StringUtils.hasText(password)) {
                session.setPassword(password);
            }
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect(timeoutMillis);

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setErrStream(new ByteArrayOutputStream());
            InputStream in = channel.getInputStream();
            channel.connect(timeoutMillis);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (!channel.isClosed() && System.currentTimeMillis() < deadline) {
                while (in.available() > 0) {
                    int read = in.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    out.write(buffer, 0, read);
                }
                Thread.sleep(50L);
            }
            while (in.available() > 0) {
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception ex) {
            return ex.getClass().getSimpleName() + ": " + ex.getMessage();
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }
}
