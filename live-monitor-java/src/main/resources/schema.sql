CREATE TABLE IF NOT EXISTS tuser (
    ID decimal(12,0) NOT NULL,
    UserID varchar(50) DEFAULT NULL,
    Password varchar(128) DEFAULT NULL,
    Name varchar(30) DEFAULT NULL,
    Grade decimal(12,0) DEFAULT NULL,
    LastLogin datetime DEFAULT NULL,
    Logins decimal(12,0) DEFAULT NULL,
    ChgPwdTime datetime DEFAULT NULL,
    ChgPwdLimit decimal(12,0) DEFAULT NULL,
    Status decimal(12,0) DEFAULT NULL,
    IPLimit varchar(20) DEFAULT NULL,
    CertNo varchar(60) DEFAULT NULL,
    OrgID decimal(10,0) DEFAULT NULL,
    Photo blob,
    LockTime datetime DEFAULT NULL,
    RetryCount int DEFAULT NULL,
    LastTryTime datetime DEFAULT NULL,
    UserAttribute int DEFAULT NULL,
    PRIMARY KEY (ID)
);

INSERT OR IGNORE INTO tuser (ID, UserID, Password, Name, Grade, Status, Logins)
VALUES (1, 'admin', '000000', 'Administrator', 1, 1, 0);

CREATE TABLE IF NOT EXISTS monitor_service (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    service_name TEXT NOT NULL,
    service_category TEXT NOT NULL DEFAULT 'middleware',
    service_type TEXT NOT NULL,
    cluster_name TEXT,
    endpoint TEXT,
    host TEXT,
    port INTEGER,
    check_mode TEXT NOT NULL DEFAULT 'ping',
    check_command TEXT,
    expected_result TEXT,
    check_timeout_seconds REAL,
    config_json TEXT NOT NULL DEFAULT '{}',
    secret_config_json TEXT NOT NULL DEFAULT '{}',
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
    monitor_service_id INTEGER,
    cluster_name TEXT DEFAULT '服务器主机',
    cpu_threshold_percent REAL DEFAULT 85,
    disk_threshold_percent REAL DEFAULT 85,
    check_interval INTEGER DEFAULT 60,
    enabled INTEGER DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(monitor_service_id) REFERENCES monitor_service(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS host_metric (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    host_id INTEGER NOT NULL,
    cpu_usage_percent REAL,
    load_average REAL,
    memory_used_percent REAL,
    disk_used_percent REAL,
    checked_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(host_id) REFERENCES host_config(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS host_process_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    host_id INTEGER NOT NULL,
    process_name TEXT NOT NULL,
    match_keyword TEXT NOT NULL,
    check_command TEXT,
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

CREATE INDEX IF NOT EXISTS idx_monitor_service_type_enabled
ON monitor_service(service_type, enabled);

CREATE INDEX IF NOT EXISTS idx_host_metric_host_time
ON host_metric(host_id, checked_at DESC);

INSERT OR IGNORE INTO alert_policy (id, policy_name, trigger_type, trigger_value, enabled)
VALUES (1, 'DOWN consecutive 3 times', 'consecutive_down', '3', 1);

INSERT OR IGNORE INTO alert_policy (id, policy_name, trigger_type, trigger_value, enabled)
VALUES (2, 'Latency > 3 seconds', 'latency_gt_ms', '3000', 1);

INSERT OR IGNORE INTO alert_policy (id, policy_name, trigger_type, trigger_value, enabled)
VALUES (3, 'Service recovered', 'recovered', 'UP', 1);

UPDATE alert_policy
SET policy_name = 'DOWN consecutive 3 times', trigger_type = 'consecutive_down', trigger_value = '3', enabled = 1
WHERE id = 1;

UPDATE alert_policy
SET policy_name = 'Latency > 3 seconds', trigger_type = 'latency_gt_ms', trigger_value = '3000', enabled = 1
WHERE id = 2;

UPDATE alert_policy
SET policy_name = 'Service recovered', trigger_type = 'recovered', trigger_value = 'UP', enabled = 1
WHERE id = 3;
