package com.live.monitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "live-monitor")
public class LiveMonitorProperties {
    private int schedulerTickSeconds = 5;
    private int schedulerPoolSize = 2;
    private int monitorWorkerThreads = 4;
    private int monitorWorkerQueueCapacity = 100;
    private double defaultTimeoutSeconds = 3;
    private int databaseResultMaxRows = 5;
    private String secretKey = "change-this-dev-key";
    private String rocksdbPath = "./data/rocksdb";
    private String sqlitePath = "./data/live_monitor.db";
    private String sqliteBackupDir = "./data/backup";

    public int getSchedulerTickSeconds() {
        return schedulerTickSeconds;
    }

    public void setSchedulerTickSeconds(int schedulerTickSeconds) {
        this.schedulerTickSeconds = Math.max(1, schedulerTickSeconds);
    }

    public int getSchedulerPoolSize() {
        return schedulerPoolSize;
    }

    public void setSchedulerPoolSize(int schedulerPoolSize) {
        this.schedulerPoolSize = Math.max(1, schedulerPoolSize);
    }

    public int getMonitorWorkerThreads() {
        return monitorWorkerThreads;
    }

    public void setMonitorWorkerThreads(int monitorWorkerThreads) {
        this.monitorWorkerThreads = Math.max(1, monitorWorkerThreads);
    }

    public int getMonitorWorkerQueueCapacity() {
        return monitorWorkerQueueCapacity;
    }

    public void setMonitorWorkerQueueCapacity(int monitorWorkerQueueCapacity) {
        this.monitorWorkerQueueCapacity = Math.max(0, monitorWorkerQueueCapacity);
    }

    public double getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(double defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public int getDatabaseResultMaxRows() {
        return databaseResultMaxRows;
    }

    public void setDatabaseResultMaxRows(int databaseResultMaxRows) {
        this.databaseResultMaxRows = Math.max(1, Math.min(10, databaseResultMaxRows));
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getRocksdbPath() {
        return rocksdbPath;
    }

    public void setRocksdbPath(String rocksdbPath) {
        this.rocksdbPath = rocksdbPath;
    }

    public String getSqlitePath() {
        return sqlitePath;
    }

    public void setSqlitePath(String sqlitePath) {
        this.sqlitePath = sqlitePath;
    }

    public String getSqliteBackupDir() {
        return sqliteBackupDir;
    }

    public void setSqliteBackupDir(String sqliteBackupDir) {
        this.sqliteBackupDir = sqliteBackupDir;
    }
}
