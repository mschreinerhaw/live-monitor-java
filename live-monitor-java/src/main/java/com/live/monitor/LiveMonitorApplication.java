package com.live.monitor;

import java.nio.file.Files;
import java.nio.file.Paths;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan("com.live.monitor.mapper")
@SpringBootApplication
public class LiveMonitorApplication {
    public static void main(String[] args) {
        ensureDataDirectory();
        SpringApplication.run(LiveMonitorApplication.class, args);
    }

    private static void ensureDataDirectory() {
        try {
            Files.createDirectories(Paths.get("data"));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create data directory", ex);
        }
    }
}
