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
        ExecResult result = execResult(host, command, timeoutMillis);
        if (StringUtils.hasText(result.stdout)) {
            return result.stdout;
        }
        return result.stderr == null ? "" : result.stderr;
    }

    public ExecResult execResult(HostConfig host, String command, int timeoutMillis) {
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
            config.put("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ssh-dss");
            config.put("PubkeyAcceptedAlgorithms", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ssh-dss");
            config.put("PubkeyAcceptedKeyTypes", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ssh-dss");
            config.put("kex", "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1,diffie-hellman-group1-sha1");
            config.put("cipher.s2c", "chacha20-poly1305@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr,aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-cbc,aes192-cbc,aes128-cbc,3des-cbc");
            config.put("cipher.c2s", "chacha20-poly1305@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr,aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-cbc,aes192-cbc,aes128-cbc,3des-cbc");
            config.put("mac.s2c", "hmac-sha2-512,hmac-sha2-256,hmac-sha1");
            config.put("mac.c2s", "hmac-sha2-512,hmac-sha2-256,hmac-sha1");
            session.setConfig(config);
            session.connect(timeoutMillis);

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            channel.setErrStream(err);
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
            if (!channel.isClosed()) {
                channel.disconnect();
            }
            while (in.available() > 0) {
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
            }
            return new ExecResult(
                new String(out.toByteArray(), StandardCharsets.UTF_8).trim(),
                new String(err.toByteArray(), StandardCharsets.UTF_8).trim(),
                channel.getExitStatus(),
                false
            );
        } catch (Exception ex) {
            return new ExecResult("", ex.getClass().getSimpleName() + ": " + ex.getMessage(), null, true);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    public static class ExecResult {
        public final String stdout;
        public final String stderr;
        public final Integer exitStatus;
        public final boolean error;

        public ExecResult(String stdout, String stderr, Integer exitStatus, boolean error) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitStatus = exitStatus;
            this.error = error;
        }

        public String combinedOutput() {
            if (StringUtils.hasText(stdout) && StringUtils.hasText(stderr)) {
                return stdout + "\n" + stderr;
            }
            if (StringUtils.hasText(stdout)) {
                return stdout;
            }
            return stderr == null ? "" : stderr;
        }
    }
}
