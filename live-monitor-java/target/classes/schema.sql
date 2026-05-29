CREATE TABLE IF NOT EXISTS monitor_service (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    service_name TEXT NOT NULL,
    service_type TEXT NOT NULL,
    cluster_name TEXT,
    host TEXT,
    port INTEGER,
    url TEXT,
    http_method TEXT DEFAULT 'GET',
    expected_status_code INTEGER,
    response_keyword TEXT,
    check_timeout_seconds REAL,
    redis_username TEXT,
    redis_password TEXT,
    redis_cluster_mode INTEGER DEFAULT 0,
    zookeeper_check_mode TEXT DEFAULT 'ruok',
    zookeeper_check_command TEXT DEFAULT 'ruok',
    zookeeper_expected_nodes INTEGER,
    check_interval INTEGER DEFAULT 60,
    alert_config_id INTEGER,
    enabled INTEGER DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS monitor_result (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    service_id INTEGER NOT NULL,
    status TEXT NOT NULL,
    response_time_ms INTEGER,
    message TEXT,
    checked_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(service_id) REFERENCES monitor_service(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS alert_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    service_id INTEGER NOT NULL,
    alert_type TEXT,
    alert_content TEXT,
    alert_status TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(service_id) REFERENCES monitor_service(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS alert_policy (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    policy_name TEXT NOT NULL,
    trigger_type TEXT NOT NULL,
    trigger_value TEXT,
    enabled INTEGER DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS alert_channel (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_name TEXT NOT NULL,
    channel_type TEXT NOT NULL,
    config_json TEXT,
    enabled INTEGER DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS alert_group (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_name TEXT NOT NULL,
    description TEXT,
    enabled INTEGER DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS group_policy_rel (
    group_id INTEGER NOT NULL,
    policy_id INTEGER NOT NULL,
    PRIMARY KEY (group_id, policy_id),
    FOREIGN KEY(group_id) REFERENCES alert_group(id) ON DELETE CASCADE,
    FOREIGN KEY(policy_id) REFERENCES alert_policy(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS group_channel_rel (
    group_id INTEGER NOT NULL,
    channel_id INTEGER NOT NULL,
    PRIMARY KEY (group_id, channel_id),
    FOREIGN KEY(group_id) REFERENCES alert_group(id) ON DELETE CASCADE,
    FOREIGN KEY(channel_id) REFERENCES alert_channel(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS service_alert_group (
    service_id INTEGER PRIMARY KEY,
    group_id INTEGER NOT NULL,
    FOREIGN KEY(service_id) REFERENCES monitor_service(id) ON DELETE CASCADE,
    FOREIGN KEY(group_id) REFERENCES alert_group(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS host_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    host_name TEXT NOT NULL,
    ip TEXT NOT NULL,
    ssh_port INTEGER DEFAULT 22,
    ssh_user TEXT,
    ssh_password_cipher TEXT,
    private_key_cipher TEXT,
    enabled INTEGER DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS host_process_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    host_id INTEGER NOT NULL,
    process_name TEXT NOT NULL,
    match_keyword TEXT NOT NULL,
    enabled INTEGER DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(host_id) REFERENCES host_config(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_monitor_result_service_time
ON monitor_result(service_id, checked_at DESC);

CREATE INDEX IF NOT EXISTS idx_alert_record_service_time
ON alert_record(service_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_service_alert_group_group
ON service_alert_group(group_id);

INSERT OR IGNORE INTO alert_policy (id, policy_name, trigger_type, trigger_value, enabled)
VALUES (1, 'DOWN 连续 3 次', 'consecutive_down', '3', 1);

INSERT OR IGNORE INTO alert_policy (id, policy_name, trigger_type, trigger_value, enabled)
VALUES (2, '响应时间 > 3 秒', 'latency_gt_ms', '3000', 1);

INSERT OR IGNORE INTO alert_policy (id, policy_name, trigger_type, trigger_value, enabled)
VALUES (3, '服务恢复', 'recovered', 'UP', 1);
