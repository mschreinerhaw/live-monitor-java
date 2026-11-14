package com.live.monitor.service;

import com.live.monitor.entity.MonitorService;
import com.live.monitor.dto.CheckResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.springframework.web.server.ResponseStatusException;

class MonitorCheckExecutionTest {
    private MonitorService service(long id) {
        MonitorService s = new MonitorService(); s.id=id; s.checkTimeoutSeconds=0.1; return s;
    }

    @Test void deadlineAndGuardRemainWhenCheckerIgnoresInterruption() throws Exception {
        MonitorCheckExecution execution = new MonitorCheckExecution(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CheckResult result = execution.run(service(1), () -> {
                while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) {} }
                return new CheckResult("UP", 1, "late result");
            });
            assertEquals("UNKNOWN", result.status);
            assertEquals("CHECK_TIMEOUT", result.eventType);
            assertThrows(ResponseStatusException.class, () -> execution.run(service(1), () -> new CheckResult("UP",1,"duplicate")));
            assertEquals("UP", execution.run(service(2), () -> new CheckResult("UP",1,"other service")).status);
        } finally { release.countDown(); execution.stop(); }
    }

    @Test void checkerFailuresAreClassifiedAndOneHundredServicesContinue() {
        MonitorCheckExecution execution = new MonitorCheckExecution(4);
        try {
            assertEquals("CHECK_CONFIGURATION_ERROR", execution.run(service(1), () -> {throw new IllegalArgumentException("password=secret");}).eventType);
            assertEquals("AUTHENTICATION_ERROR", execution.run(service(2), () -> {throw new SecurityException("denied");}).eventType);
            for (int i=3;i<103;i++) assertEquals("UP", execution.run(service(i), () -> new CheckResult("UP",1,"ok")).status);
            assertFalse(MonitorCheckExecution.safe("password=abc token=xyz").contains("abc"));
        } finally { execution.stop(); }
    }
}
