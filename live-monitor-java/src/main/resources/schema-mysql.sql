CREATE TABLE IF NOT EXISTS tuser (
    id DECIMAL(12,0) NOT NULL,
    userid VARCHAR(50) DEFAULT NULL,
    password VARCHAR(512) DEFAULT NULL,
    name VARCHAR(30) DEFAULT NULL,
    grade DECIMAL(12,0) DEFAULT NULL,
    lastlogin TIMESTAMP NULL DEFAULT NULL,
    logins DECIMAL(12,0) DEFAULT NULL,
    chgpwdtime TIMESTAMP NULL DEFAULT NULL,
    chgpwdlimit DECIMAL(12,0) DEFAULT NULL,
    status DECIMAL(12,0) DEFAULT NULL,
    iplimit VARCHAR(20) DEFAULT NULL,
    certno VARCHAR(60) DEFAULT NULL,
    orgid DECIMAL(10,0) DEFAULT NULL,
    photo LONGBLOB,
    locktime TIMESTAMP NULL DEFAULT NULL,
    retrycount INT DEFAULT NULL,
    lasttrytime TIMESTAMP NULL DEFAULT NULL,
    userattribute INT DEFAULT NULL,
    PRIMARY KEY (id)
);

INSERT INTO tuser (
    id, userid, password, name, grade, lastlogin, logins, chgpwdtime, chgpwdlimit, status,
    iplimit, certno, orgid, photo, locktime, retrycount, lasttrytime, userattribute
)
SELECT 1, 'admin', '000000', 'admin', 1, NULL, 0, NULL, NULL, 1, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
WHERE NOT EXISTS (SELECT 1 FROM tuser WHERE id = 1);

CREATE TABLE IF NOT EXISTS login_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(50) NOT NULL,
    user_name VARCHAR(30),
    action VARCHAR(20) NOT NULL,
    ip_address VARCHAR(64),
    event_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS monitor_service (
    id BIGINT NOT NULL AUTO_INCREMENT,
    service_name VARCHAR(255) NOT NULL,
    service_category VARCHAR(64) NOT NULL DEFAULT 'middleware',
    service_type VARCHAR(64) NOT NULL,
    cluster_name VARCHAR(255),
    monitor_reason VARCHAR(1000),
    endpoint VARCHAR(1024),
    host VARCHAR(255),
    port INT,
    check_mode VARCHAR(64) NOT NULL DEFAULT 'ping',
    check_command LONGTEXT,
    expected_result LONGTEXT,
    check_timeout_seconds DOUBLE,
    config_json LONGTEXT NOT NULL,
    secret_config_json LONGTEXT NOT NULL,
    check_interval INT DEFAULT 60,
    alert_config_id BIGINT,
    enabled INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS service_latest_status (
    service_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_time_ms INT,
    message LONGTEXT,
    checked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (service_id),
    CONSTRAINT fk_service_latest_status_service FOREIGN KEY(service_id) REFERENCES monitor_service(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS monitor_check_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    service_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_time_ms INT,
    message LONGTEXT,
    alert_type VARCHAR(64),
    checked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    consumed INT DEFAULT 0,
    consumed_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_monitor_check_event_service FOREIGN KEY(service_id) REFERENCES monitor_service(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS alert_policy (
    id BIGINT NOT NULL AUTO_INCREMENT,
    policy_name VARCHAR(255) NOT NULL,
    trigger_type VARCHAR(64) NOT NULL,
    trigger_value VARCHAR(255),
    enabled INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS alert_channel (
    id BIGINT NOT NULL AUTO_INCREMENT,
    channel_name VARCHAR(255) NOT NULL,
    channel_type VARCHAR(64) NOT NULL,
    config_json LONGTEXT,
    enabled INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS alert_group (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_name VARCHAR(255) NOT NULL,
    description LONGTEXT,
    enabled INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS group_policy_rel (
    group_id BIGINT NOT NULL,
    policy_id BIGINT NOT NULL,
    PRIMARY KEY (group_id, policy_id),
    CONSTRAINT fk_group_policy_rel_group FOREIGN KEY(group_id) REFERENCES alert_group(id) ON DELETE CASCADE,
    CONSTRAINT fk_group_policy_rel_policy FOREIGN KEY(policy_id) REFERENCES alert_policy(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS group_channel_rel (
    group_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    PRIMARY KEY (group_id, channel_id),
    CONSTRAINT fk_group_channel_rel_group FOREIGN KEY(group_id) REFERENCES alert_group(id) ON DELETE CASCADE,
    CONSTRAINT fk_group_channel_rel_channel FOREIGN KEY(channel_id) REFERENCES alert_channel(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS service_alert_group (
    service_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    PRIMARY KEY (service_id),
    CONSTRAINT fk_service_alert_group_service FOREIGN KEY(service_id) REFERENCES monitor_service(id) ON DELETE CASCADE,
    CONSTRAINT fk_service_alert_group_group FOREIGN KEY(group_id) REFERENCES alert_group(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS alert_state (
    service_id BIGINT NOT NULL,
    alert_key VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL,
    fail_count INT DEFAULT 0,
    recover_count INT DEFAULT 0,
    active_policy_id BIGINT,
    active_trigger_type VARCHAR(64),
    last_status VARCHAR(32),
    last_message LONGTEXT,
    last_event_at TIMESTAMP NULL DEFAULT NULL,
    last_alert_at TIMESTAMP NULL DEFAULT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (service_id, alert_key),
    CONSTRAINT fk_alert_state_service FOREIGN KEY(service_id) REFERENCES monitor_service(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS alert_notify_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    service_id BIGINT NOT NULL,
    alert_key VARCHAR(128) NOT NULL,
    alert_record_id BIGINT,
    alert_type VARCHAR(64),
    notify_status VARCHAR(32),
    notify_message LONGTEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_alert_notify_record_service FOREIGN KEY(service_id) REFERENCES monitor_service(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS host_config (
    id BIGINT NOT NULL AUTO_INCREMENT,
    host_name VARCHAR(255) NOT NULL,
    ip VARCHAR(255) NOT NULL,
    ssh_port INT DEFAULT 22,
    ssh_user VARCHAR(255),
    ssh_password_cipher LONGTEXT,
    private_key_cipher LONGTEXT,
    monitor_service_id BIGINT,
    cluster_name VARCHAR(255) DEFAULT 'Server Host',
    remark VARCHAR(1000),
    cpu_threshold_percent DOUBLE DEFAULT 85,
    memory_threshold_percent DOUBLE DEFAULT 85,
    disk_threshold_percent DOUBLE DEFAULT 85,
    cpu_alert_enabled INT DEFAULT 1,
    memory_alert_enabled INT DEFAULT 1,
    disk_alert_enabled INT DEFAULT 1,
    resource_alert_duration_enabled INT DEFAULT 1,
    resource_recover_duration_enabled INT DEFAULT 1,
    resource_alert_cooldown_enabled INT DEFAULT 1,
    resource_alert_duration_seconds INT DEFAULT 180,
    resource_recover_duration_seconds INT DEFAULT 180,
    resource_alert_cooldown_seconds INT DEFAULT 600,
    check_interval INT DEFAULT 60,
    enabled INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_host_config_monitor_service FOREIGN KEY(monitor_service_id) REFERENCES monitor_service(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS host_process_config (
    id BIGINT NOT NULL AUTO_INCREMENT,
    host_id BIGINT NOT NULL,
    process_name VARCHAR(255) NOT NULL,
    match_keyword LONGTEXT NOT NULL,
    check_command LONGTEXT,
    enabled INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_host_process_config_host FOREIGN KEY(host_id) REFERENCES host_config(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS host_latest_metric (
    host_id BIGINT NOT NULL,
    cpu_usage_percent DOUBLE,
    load_average DOUBLE,
    memory_used_percent DOUBLE,
    disk_used_percent DOUBLE,
    cpu_core_count INT,
    memory_total_mb DOUBLE,
    disk_mount_count INT,
    disk_metrics_json LONGTEXT,
    physical_disk_metrics_json LONGTEXT,
    checked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (host_id),
    CONSTRAINT fk_host_latest_metric_host FOREIGN KEY(host_id) REFERENCES host_config(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS migration_marker (
    id VARCHAR(128) NOT NULL,
    source_path VARCHAR(1024),
    backup_path VARCHAR(1024),
    config_rows BIGINT DEFAULT 0,
    rocksdb_rows BIGINT DEFAULT 0,
    completed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

INSERT INTO alert_policy (id, policy_name, trigger_type, trigger_value, enabled, created_at)
VALUES (1, 'DOWN consecutive 3 times', 'consecutive_down', '3', 1, CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE
    policy_name = VALUES(policy_name),
    trigger_type = VALUES(trigger_type),
    trigger_value = VALUES(trigger_value),
    enabled = VALUES(enabled);

INSERT INTO alert_policy (id, policy_name, trigger_type, trigger_value, enabled, created_at)
VALUES (2, 'Latency > 3 seconds', 'latency_gt_ms', '3000', 1, CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE
    policy_name = VALUES(policy_name),
    trigger_type = VALUES(trigger_type),
    trigger_value = VALUES(trigger_value),
    enabled = VALUES(enabled);

INSERT INTO alert_policy (id, policy_name, trigger_type, trigger_value, enabled, created_at)
VALUES (3, 'Service recovered', 'recovered', 'UP', 1, CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE
    policy_name = VALUES(policy_name),
    trigger_type = VALUES(trigger_type),
    trigger_value = VALUES(trigger_value),
    enabled = VALUES(enabled);
