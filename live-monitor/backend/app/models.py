SERVICE_TYPES = {"web", "redis", "zookeeper"}
STATUSES = {"UP", "DOWN", "UNKNOWN"}


SCHEMA_SQL = """
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
    alert_email TEXT,
    alert_mobile TEXT,
    smtp_host TEXT,
    smtp_port INTEGER,
    smtp_user TEXT,
    smtp_password TEXT,
    smtp_from TEXT,
    smtp_use_tls INTEGER DEFAULT 0,
    sms_api_url TEXT,
    sms_api_token TEXT,
    sms_username TEXT,
    sms_password TEXT,
    sms_password_is_md5 INTEGER DEFAULT 1,
    sms_password_md5 TEXT,
    sms_rstype TEXT DEFAULT 'text',
    sms_ext_code TEXT,
    enabled INTEGER DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS alert_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    config_name TEXT NOT NULL,
    alert_channel TEXT NOT NULL DEFAULT 'email',
    alert_email TEXT,
    alert_mobile TEXT,
    smtp_host TEXT,
    smtp_port INTEGER,
    smtp_user TEXT,
    smtp_password TEXT,
    smtp_from TEXT,
    smtp_use_tls INTEGER DEFAULT 0,
    sms_api_url TEXT,
    sms_api_token TEXT,
    sms_username TEXT,
    sms_password TEXT,
    sms_password_is_md5 INTEGER DEFAULT 1,
    sms_password_md5 TEXT,
    sms_rstype TEXT DEFAULT 'text',
    sms_ext_code TEXT,
    enabled INTEGER DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS alert_group (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_name TEXT NOT NULL,
    description TEXT,
    enabled INTEGER DEFAULT 1,
    legacy_config_id INTEGER UNIQUE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
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
    legacy_config_id INTEGER UNIQUE,
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

CREATE INDEX IF NOT EXISTS idx_monitor_result_service_time
ON monitor_result(service_id, checked_at DESC);

CREATE INDEX IF NOT EXISTS idx_alert_record_service_time
ON alert_record(service_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_service_alert_group_group
ON service_alert_group(group_id);
"""
