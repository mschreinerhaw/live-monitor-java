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
    private int databaseQueryTimeoutSeconds = 3;
    private boolean embedTokenLongTerm = false;
    private String secretKey = "change-this-dev-key";
    private String rocksdbPath = "./data/rocksdb";
    private String luceneIndexPath = "./data/search-lucene";
    private int metricRetentionDays = 30;
    private int alertNotifyRetentionDays = 90;
    private String sqlitePath = "./data/live_monitor.db";
    private String sqliteBackupDir = "./data/backup";
    private String h2LegacyPath = "./data/h2/live_monitor";
    private String h2LegacyUsername = "sa";
    private String h2LegacyPassword = "live_monitor";
    private String h2LegacyBackupDir = "./data/backup";

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

    public int getDatabaseQueryTimeoutSeconds() {
        return databaseQueryTimeoutSeconds;
    }

    public void setDatabaseQueryTimeoutSeconds(int databaseQueryTimeoutSeconds) {
        this.databaseQueryTimeoutSeconds = Math.max(1, Math.min(60, databaseQueryTimeoutSeconds));
    }

    public boolean isEmbedTokenLongTerm() {
        return embedTokenLongTerm;
    }

    public void setEmbedTokenLongTerm(boolean embedTokenLongTerm) {
        this.embedTokenLongTerm = embedTokenLongTerm;
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

    public String getLuceneIndexPath() {
        return luceneIndexPath;
    }

    public void setLuceneIndexPath(String luceneIndexPath) {
        this.luceneIndexPath = luceneIndexPath;
    }

    public int getMetricRetentionDays() {
        return metricRetentionDays;
    }

    public void setMetricRetentionDays(int metricRetentionDays) {
        this.metricRetentionDays = Math.max(1, metricRetentionDays);
    }

    public int getAlertNotifyRetentionDays() {
        return alertNotifyRetentionDays;
    }

    public void setAlertNotifyRetentionDays(int alertNotifyRetentionDays) {
        this.alertNotifyRetentionDays = Math.max(1, alertNotifyRetentionDays);
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

    public String getH2LegacyPath() {
        return h2LegacyPath;
    }

    public void setH2LegacyPath(String h2LegacyPath) {
        this.h2LegacyPath = h2LegacyPath;
    }

    public String getH2LegacyUsername() {
        return h2LegacyUsername;
    }

    public void setH2LegacyUsername(String h2LegacyUsername) {
        this.h2LegacyUsername = h2LegacyUsername;
    }

    public String getH2LegacyPassword() {
        return h2LegacyPassword;
    }

    public void setH2LegacyPassword(String h2LegacyPassword) {
        this.h2LegacyPassword = h2LegacyPassword;
    }

    public String getH2LegacyBackupDir() {
        return h2LegacyBackupDir;
    }

    public void setH2LegacyBackupDir(String h2LegacyBackupDir) {
        this.h2LegacyBackupDir = h2LegacyBackupDir;
    }
}
