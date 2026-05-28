from __future__ import annotations

import sqlite3
import json
from contextlib import contextmanager
from datetime import datetime, timezone
from typing import Any, Iterable

from .config import DATABASE_PATH
from .models import SCHEMA_SQL


SERVICE_FIELDS = [
    "service_name",
    "service_type",
    "cluster_name",
    "host",
    "port",
    "url",
    "http_method",
    "expected_status_code",
    "response_keyword",
    "check_timeout_seconds",
    "redis_username",
    "redis_password",
    "redis_cluster_mode",
    "zookeeper_check_mode",
    "zookeeper_check_command",
    "zookeeper_expected_nodes",
    "check_interval",
    "alert_config_id",
    "enabled",
]

ALERT_CONFIG_FIELDS = [
    "config_name",
    "alert_channel",
    "alert_email",
    "alert_mobile",
    "smtp_host",
    "smtp_port",
    "smtp_user",
    "smtp_password",
    "smtp_from",
    "smtp_use_tls",
    "sms_api_url",
    "sms_api_token",
    "sms_username",
    "sms_password",
    "sms_password_is_md5",
    "sms_password_md5",
    "sms_rstype",
    "sms_ext_code",
    "enabled",
]

ALERT_CHANNEL_CONFIG_FIELDS = [
    "alert_email",
    "alert_mobile",
    "smtp_host",
    "smtp_port",
    "smtp_user",
    "smtp_password",
    "smtp_from",
    "smtp_use_tls",
    "sms_api_url",
    "sms_api_token",
    "sms_username",
    "sms_password",
    "sms_password_is_md5",
    "sms_password_md5",
    "sms_rstype",
    "sms_ext_code",
    "webhook_url",
]

ALERT_POLICY_DEFAULTS = [
    ("DOWN 连续 3 次", "consecutive_down", "3"),
    ("响应时间 > 3 秒", "latency_gt_ms", "3000"),
    ("服务恢复", "recovered", "UP"),
]

SERVICE_MIGRATIONS = {
    "redis_username": "ALTER TABLE monitor_service ADD COLUMN redis_username TEXT",
    "redis_password": "ALTER TABLE monitor_service ADD COLUMN redis_password TEXT",
    "http_method": "ALTER TABLE monitor_service ADD COLUMN http_method TEXT DEFAULT 'GET'",
    "expected_status_code": "ALTER TABLE monitor_service ADD COLUMN expected_status_code INTEGER",
    "response_keyword": "ALTER TABLE monitor_service ADD COLUMN response_keyword TEXT",
    "check_timeout_seconds": "ALTER TABLE monitor_service ADD COLUMN check_timeout_seconds REAL",
    "redis_cluster_mode": "ALTER TABLE monitor_service ADD COLUMN redis_cluster_mode INTEGER DEFAULT 0",
    "zookeeper_check_mode": "ALTER TABLE monitor_service ADD COLUMN zookeeper_check_mode TEXT DEFAULT 'ruok'",
    "zookeeper_check_command": "ALTER TABLE monitor_service ADD COLUMN zookeeper_check_command TEXT DEFAULT 'ruok'",
    "zookeeper_expected_nodes": "ALTER TABLE monitor_service ADD COLUMN zookeeper_expected_nodes INTEGER",
    "alert_config_id": "ALTER TABLE monitor_service ADD COLUMN alert_config_id INTEGER",
    "smtp_host": "ALTER TABLE monitor_service ADD COLUMN smtp_host TEXT",
    "smtp_port": "ALTER TABLE monitor_service ADD COLUMN smtp_port INTEGER",
    "smtp_user": "ALTER TABLE monitor_service ADD COLUMN smtp_user TEXT",
    "smtp_password": "ALTER TABLE monitor_service ADD COLUMN smtp_password TEXT",
    "smtp_from": "ALTER TABLE monitor_service ADD COLUMN smtp_from TEXT",
    "smtp_use_tls": "ALTER TABLE monitor_service ADD COLUMN smtp_use_tls INTEGER DEFAULT 0",
    "sms_api_url": "ALTER TABLE monitor_service ADD COLUMN sms_api_url TEXT",
    "sms_api_token": "ALTER TABLE monitor_service ADD COLUMN sms_api_token TEXT",
    "sms_username": "ALTER TABLE monitor_service ADD COLUMN sms_username TEXT",
    "sms_password": "ALTER TABLE monitor_service ADD COLUMN sms_password TEXT",
    "sms_password_is_md5": "ALTER TABLE monitor_service ADD COLUMN sms_password_is_md5 INTEGER DEFAULT 1",
    "sms_password_md5": "ALTER TABLE monitor_service ADD COLUMN sms_password_md5 TEXT",
    "sms_rstype": "ALTER TABLE monitor_service ADD COLUMN sms_rstype TEXT DEFAULT 'text'",
    "sms_ext_code": "ALTER TABLE monitor_service ADD COLUMN sms_ext_code TEXT",
}

SERVICE_SECRET_FIELDS = {"redis_password"}
ALERT_CONFIG_MIGRATIONS = {
    "sms_api_url": "ALTER TABLE alert_config ADD COLUMN sms_api_url TEXT",
    "sms_api_token": "ALTER TABLE alert_config ADD COLUMN sms_api_token TEXT",
    "sms_username": "ALTER TABLE alert_config ADD COLUMN sms_username TEXT",
    "sms_password": "ALTER TABLE alert_config ADD COLUMN sms_password TEXT",
    "sms_password_is_md5": "ALTER TABLE alert_config ADD COLUMN sms_password_is_md5 INTEGER DEFAULT 1",
    "sms_password_md5": "ALTER TABLE alert_config ADD COLUMN sms_password_md5 TEXT",
    "sms_rstype": "ALTER TABLE alert_config ADD COLUMN sms_rstype TEXT DEFAULT 'text'",
    "sms_ext_code": "ALTER TABLE alert_config ADD COLUMN sms_ext_code TEXT",
}

ALERT_CONFIG_SECRET_FIELDS = {"smtp_password", "sms_api_token", "sms_password", "sms_password_md5"}
ALERT_CHANNEL_SECRET_FIELDS = ALERT_CONFIG_SECRET_FIELDS
SECRET_FIELDS = SERVICE_SECRET_FIELDS | ALERT_CONFIG_SECRET_FIELDS | ALERT_CHANNEL_SECRET_FIELDS


def _row_to_dict(row: sqlite3.Row | None, include_secrets: bool = False) -> dict[str, Any] | None:
    if row is None:
        return None
    item = dict(row)
    if "enabled" in item:
        item["enabled"] = bool(item["enabled"])
    if "smtp_use_tls" in item:
        item["smtp_use_tls"] = bool(item["smtp_use_tls"])
    if "sms_password_is_md5" in item:
        item["sms_password_is_md5"] = bool(item["sms_password_is_md5"])
    if "redis_cluster_mode" in item:
        item["redis_cluster_mode"] = bool(item["redis_cluster_mode"])
    if not include_secrets:
        for field in SECRET_FIELDS:
            if field in item:
                item[field] = None
    return item


def _alert_config_row_to_dict(
    row: sqlite3.Row | None,
    include_secrets: bool = False,
) -> dict[str, Any] | None:
    item = _row_to_dict(row, include_secrets=include_secrets)
    if item is None:
        return None
    if "enabled" in item:
        item["enabled"] = bool(item["enabled"])
    if "smtp_use_tls" in item:
        item["smtp_use_tls"] = bool(item["smtp_use_tls"])
    if not include_secrets:
        for field in ALERT_CONFIG_SECRET_FIELDS:
            if field in item:
                item[field] = None
    return item


def _alert_channel_row_to_dict(
    row: sqlite3.Row | None,
    include_secrets: bool = False,
) -> dict[str, Any] | None:
    item = _row_to_dict(row, include_secrets=True)
    if item is None:
        return None
    raw_config = item.pop("config_json", None)
    try:
        config = json.loads(raw_config or "{}")
    except json.JSONDecodeError:
        config = {}
    if not isinstance(config, dict):
        config = {}
    item.update(config)
    if "enabled" in item:
        item["enabled"] = bool(item["enabled"])
    if "smtp_use_tls" in item:
        item["smtp_use_tls"] = bool(item["smtp_use_tls"])
    if "sms_password_is_md5" in item:
        item["sms_password_is_md5"] = bool(item["sms_password_is_md5"])
    if not include_secrets:
        for field in ALERT_CHANNEL_SECRET_FIELDS:
            if field in item:
                item[field] = None
    return item


def _alert_policy_row_to_dict(row: sqlite3.Row | None) -> dict[str, Any] | None:
    item = _row_to_dict(row, include_secrets=True)
    if item is None:
        return None
    if "enabled" in item:
        item["enabled"] = bool(item["enabled"])
    return item


def _alert_group_row_to_dict(row: sqlite3.Row | None) -> dict[str, Any] | None:
    item = _row_to_dict(row, include_secrets=True)
    if item is None:
        return None
    if "enabled" in item:
        item["enabled"] = bool(item["enabled"])
    return item


def _service_row_to_dict(
    row: sqlite3.Row | None,
    include_secrets: bool = False,
) -> dict[str, Any] | None:
    item = _row_to_dict(row, include_secrets=include_secrets)
    if item is None:
        return None
    if "alert_config_enabled" in item and item["alert_config_enabled"] is not None:
        item["alert_config_enabled"] = bool(item["alert_config_enabled"])
    if "alert_group_enabled" in item and item["alert_group_enabled"] is not None:
        item["alert_group_enabled"] = bool(item["alert_group_enabled"])
    for field in SERVICE_SECRET_FIELDS:
        if not include_secrets and field in item:
            item[field] = None
    return item


@contextmanager
def get_connection() -> Iterable[sqlite3.Connection]:
    DATABASE_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DATABASE_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db() -> None:
    with get_connection() as conn:
        conn.executescript(SCHEMA_SQL)
        existing_columns = {
            row["name"] for row in conn.execute("PRAGMA table_info(monitor_service)").fetchall()
        }
        for column, sql in SERVICE_MIGRATIONS.items():
            if column not in existing_columns:
                conn.execute(sql)
        existing_alert_columns = {
            row["name"] for row in conn.execute("PRAGMA table_info(alert_config)").fetchall()
        }
        for column, sql in ALERT_CONFIG_MIGRATIONS.items():
            if column not in existing_alert_columns:
                conn.execute(sql)
        conn.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_monitor_service_alert_config
            ON monitor_service(alert_config_id)
            """
        )
        _ensure_column(conn, "alert_group", "description", "ALTER TABLE alert_group ADD COLUMN description TEXT")
        _ensure_column(conn, "alert_group", "legacy_config_id", "ALTER TABLE alert_group ADD COLUMN legacy_config_id INTEGER")
        _ensure_column(conn, "alert_channel", "legacy_config_id", "ALTER TABLE alert_channel ADD COLUMN legacy_config_id INTEGER")
        _seed_default_alert_policies(conn)
        _migrate_legacy_service_alerts(conn)
        _migrate_alert_configs_to_groups(conn)


def _ensure_column(
    conn: sqlite3.Connection,
    table_name: str,
    column_name: str,
    sql: str,
) -> None:
    existing_columns = {row["name"] for row in conn.execute(f"PRAGMA table_info({table_name})").fetchall()}
    if column_name not in existing_columns:
        conn.execute(sql)


def _seed_default_alert_policies(conn: sqlite3.Connection) -> None:
    for policy_name, trigger_type, trigger_value in ALERT_POLICY_DEFAULTS:
        exists = conn.execute(
            "SELECT id FROM alert_policy WHERE trigger_type = ? AND trigger_value = ?",
            (trigger_type, trigger_value),
        ).fetchone()
        if exists is None:
            conn.execute(
                """
                INSERT INTO alert_policy (policy_name, trigger_type, trigger_value, enabled)
                VALUES (?, ?, ?, 1)
                """,
                (policy_name, trigger_type, trigger_value),
            )


def _migrate_legacy_service_alerts(conn: sqlite3.Connection) -> None:
    rows = conn.execute(
        """
        SELECT *
        FROM monitor_service
        WHERE alert_config_id IS NULL
          AND (
            COALESCE(alert_email, '') <> ''
            OR COALESCE(alert_mobile, '') <> ''
          )
        """
    ).fetchall()
    for row in rows:
        alert_channel = "sms" if row["alert_mobile"] else "email"
        cursor = conn.execute(
            """
            INSERT INTO alert_config (
                config_name,
                alert_channel,
                alert_email,
                alert_mobile,
                smtp_host,
                smtp_port,
                smtp_user,
                smtp_password,
                smtp_from,
                smtp_use_tls,
                sms_api_url,
                sms_api_token,
                sms_username,
                sms_password,
                sms_password_is_md5,
                sms_password_md5,
                sms_rstype,
                sms_ext_code,
                enabled
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            """,
            (
                f"{row['service_name']} 告警",
                alert_channel,
                row["alert_email"],
                row["alert_mobile"],
                row["smtp_host"],
                row["smtp_port"],
                row["smtp_user"],
                row["smtp_password"],
                row["smtp_from"],
                row["smtp_use_tls"],
                row["sms_api_url"],
                row["sms_api_token"],
                row["sms_username"],
                row["sms_password"],
                row["sms_password_is_md5"],
                row["sms_password_md5"],
                row["sms_rstype"],
                row["sms_ext_code"],
            ),
        )
        conn.execute(
            "UPDATE monitor_service SET alert_config_id = ? WHERE id = ?",
            (cursor.lastrowid, row["id"]),
        )


def _default_down_policy_ids(conn: sqlite3.Connection) -> list[int]:
    rows = conn.execute(
        """
        SELECT id
        FROM alert_policy
        WHERE trigger_type = 'consecutive_down'
        ORDER BY id
        """
    ).fetchall()
    return [int(row["id"]) for row in rows]


def _alert_config_to_channel_config(row: sqlite3.Row) -> dict[str, Any]:
    return {field: row[field] for field in ALERT_CHANNEL_CONFIG_FIELDS if field in row.keys()}


def _migrate_alert_configs_to_groups(conn: sqlite3.Connection) -> None:
    configs = conn.execute("SELECT * FROM alert_config").fetchall()
    default_policy_ids = _default_down_policy_ids(conn)
    for config in configs:
        channel = conn.execute(
            "SELECT id FROM alert_channel WHERE legacy_config_id = ?",
            (config["id"],),
        ).fetchone()
        if channel is None:
            cursor = conn.execute(
                """
                INSERT INTO alert_channel (
                    channel_name,
                    channel_type,
                    config_json,
                    enabled,
                    legacy_config_id
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                (
                    config["config_name"],
                    config["alert_channel"],
                    json.dumps(_alert_config_to_channel_config(config), ensure_ascii=False),
                    config["enabled"],
                    config["id"],
                ),
            )
            channel_id = int(cursor.lastrowid)
        else:
            channel_id = int(channel["id"])
            conn.execute(
                """
                UPDATE alert_channel
                SET channel_name = ?, channel_type = ?, config_json = ?, enabled = ?
                WHERE id = ?
                """,
                (
                    config["config_name"],
                    config["alert_channel"],
                    json.dumps(_alert_config_to_channel_config(config), ensure_ascii=False),
                    config["enabled"],
                    channel_id,
                ),
            )

        group = conn.execute(
            "SELECT id FROM alert_group WHERE legacy_config_id = ?",
            (config["id"],),
        ).fetchone()
        if group is None:
            cursor = conn.execute(
                """
                INSERT INTO alert_group (group_name, description, enabled, legacy_config_id)
                VALUES (?, ?, ?, ?)
                """,
                (config["config_name"], "由旧告警配置自动迁移", config["enabled"], config["id"]),
            )
            group_id = int(cursor.lastrowid)
        else:
            group_id = int(group["id"])
            conn.execute(
                """
                UPDATE alert_group
                SET group_name = ?, enabled = ?
                WHERE id = ?
                """,
                (config["config_name"], config["enabled"], group_id),
            )

        conn.execute(
            "INSERT OR IGNORE INTO group_channel_rel (group_id, channel_id) VALUES (?, ?)",
            (group_id, channel_id),
        )
        for policy_id in default_policy_ids:
            conn.execute(
                "INSERT OR IGNORE INTO group_policy_rel (group_id, policy_id) VALUES (?, ?)",
                (group_id, policy_id),
            )
        conn.execute(
            """
            INSERT OR REPLACE INTO service_alert_group (service_id, group_id)
            SELECT id, ?
            FROM monitor_service
            WHERE alert_config_id = ?
            """,
            (group_id, config["id"]),
        )


def create_service(payload: dict[str, Any]) -> dict[str, Any]:
    fields = SERVICE_FIELDS
    values = [payload.get(field) for field in fields]
    values[-1] = 1 if values[-1] else 0
    values[fields.index("redis_cluster_mode")] = 1 if payload.get("redis_cluster_mode") else 0
    placeholders = ", ".join(["?"] * len(fields))
    with get_connection() as conn:
        cursor = conn.execute(
            f"INSERT INTO monitor_service ({', '.join(fields)}) VALUES ({placeholders})",
            values,
        )
        service_id = cursor.lastrowid
        alert_group_id = payload.get("alert_group_id")
        if alert_group_id:
            conn.execute(
                "INSERT OR REPLACE INTO service_alert_group (service_id, group_id) VALUES (?, ?)",
                (service_id, int(alert_group_id)),
            )
    service = get_service(service_id)
    assert service is not None
    return service


def update_service(service_id: int, payload: dict[str, Any]) -> dict[str, Any] | None:
    fields = SERVICE_FIELDS
    assignments = ", ".join(
        [
            f"{field} = COALESCE(?, {field})" if field in SECRET_FIELDS else f"{field} = ?"
            for field in fields
        ]
    )
    values = [payload.get(field) for field in fields]
    values[-1] = 1 if values[-1] else 0
    values[fields.index("redis_cluster_mode")] = 1 if payload.get("redis_cluster_mode") else 0
    values.append(service_id)
    with get_connection() as conn:
        cursor = conn.execute(
            f"UPDATE monitor_service SET {assignments} WHERE id = ?",
            values,
        )
        if cursor.rowcount == 0:
            return None
        if "alert_group_id" in payload:
            alert_group_id = payload.get("alert_group_id")
            if alert_group_id:
                conn.execute(
                    "INSERT OR REPLACE INTO service_alert_group (service_id, group_id) VALUES (?, ?)",
                    (service_id, int(alert_group_id)),
                )
            else:
                conn.execute("DELETE FROM service_alert_group WHERE service_id = ?", (service_id,))
    return get_service(service_id)


def list_alert_configs(include_disabled: bool = True) -> list[dict[str, Any]]:
    where = "" if include_disabled else "WHERE enabled = 1"
    with get_connection() as conn:
        rows = conn.execute(
            f"""
            SELECT *
            FROM alert_config
            {where}
            ORDER BY enabled DESC, config_name
            """
        ).fetchall()
    return [_alert_config_row_to_dict(row) for row in rows if row is not None]


def get_alert_config(config_id: int, include_secrets: bool = False) -> dict[str, Any] | None:
    with get_connection() as conn:
        row = conn.execute(
            "SELECT * FROM alert_config WHERE id = ?",
            (config_id,),
        ).fetchone()
    return _alert_config_row_to_dict(row, include_secrets=include_secrets)


def _alert_config_values(payload: dict[str, Any], existing: dict[str, Any] | None = None) -> list[Any]:
    values = [payload.get(field) for field in ALERT_CONFIG_FIELDS]
    values[ALERT_CONFIG_FIELDS.index("smtp_use_tls")] = 1 if payload.get("smtp_use_tls") else 0
    values[ALERT_CONFIG_FIELDS.index("sms_password_is_md5")] = (
        1 if payload.get("sms_password_is_md5") else 0
    )
    values[ALERT_CONFIG_FIELDS.index("enabled")] = 1 if payload.get("enabled") else 0
    if existing and payload.get("smtp_password") is None:
        values[ALERT_CONFIG_FIELDS.index("smtp_password")] = existing.get("smtp_password")
    if existing and payload.get("sms_api_token") is None:
        values[ALERT_CONFIG_FIELDS.index("sms_api_token")] = existing.get("sms_api_token")
    if existing and payload.get("sms_password") is None:
        values[ALERT_CONFIG_FIELDS.index("sms_password")] = existing.get("sms_password")
    if existing and payload.get("sms_password_md5") is None:
        values[ALERT_CONFIG_FIELDS.index("sms_password_md5")] = existing.get("sms_password_md5")
    return values


def create_alert_config(payload: dict[str, Any]) -> dict[str, Any]:
    fields = ALERT_CONFIG_FIELDS
    values = _alert_config_values(payload)
    placeholders = ", ".join(["?"] * len(fields))
    with get_connection() as conn:
        cursor = conn.execute(
            f"INSERT INTO alert_config ({', '.join(fields)}) VALUES ({placeholders})",
            values,
        )
        config_id = cursor.lastrowid
        _migrate_alert_configs_to_groups(conn)
    config = get_alert_config(config_id)
    assert config is not None
    return config


def update_alert_config(config_id: int, payload: dict[str, Any]) -> dict[str, Any] | None:
    existing = get_alert_config(config_id, include_secrets=True)
    if existing is None:
        return None
    fields = ALERT_CONFIG_FIELDS
    assignments = ", ".join([f"{field} = ?" for field in fields])
    values = _alert_config_values(payload, existing=existing)
    values.append(config_id)
    with get_connection() as conn:
        cursor = conn.execute(
            f"UPDATE alert_config SET {assignments} WHERE id = ?",
            values,
        )
        if cursor.rowcount == 0:
            return None
        _migrate_alert_configs_to_groups(conn)
    return get_alert_config(config_id)


def delete_alert_config(config_id: int) -> bool:
    with get_connection() as conn:
        conn.execute(
            "UPDATE monitor_service SET alert_config_id = NULL WHERE alert_config_id = ?",
            (config_id,),
        )
        group = conn.execute(
            "SELECT id FROM alert_group WHERE legacy_config_id = ?",
            (config_id,),
        ).fetchone()
        if group is not None:
            conn.execute("DELETE FROM service_alert_group WHERE group_id = ?", (group["id"],))
            conn.execute("DELETE FROM alert_group WHERE id = ?", (group["id"],))
        conn.execute("DELETE FROM alert_channel WHERE legacy_config_id = ?", (config_id,))
        cursor = conn.execute("DELETE FROM alert_config WHERE id = ?", (config_id,))
        return cursor.rowcount > 0


def update_service_alert_config(
    service_id: int,
    alert_config_id: int | None,
) -> dict[str, Any] | None:
    with get_connection() as conn:
        cursor = conn.execute(
            "UPDATE monitor_service SET alert_config_id = ? WHERE id = ?",
            (alert_config_id, service_id),
        )
        if cursor.rowcount == 0:
            return None
        if alert_config_id:
            group = conn.execute(
                "SELECT id FROM alert_group WHERE legacy_config_id = ?",
                (alert_config_id,),
            ).fetchone()
            if group is not None:
                conn.execute(
                    "INSERT OR REPLACE INTO service_alert_group (service_id, group_id) VALUES (?, ?)",
                    (service_id, group["id"]),
                )
        else:
            conn.execute("DELETE FROM service_alert_group WHERE service_id = ?", (service_id,))
    return get_service(service_id)


def update_alert_settings(
    service_id: int,
    payload: dict[str, Any],
) -> dict[str, Any] | None:
    service = get_service(service_id)
    if service is None:
        return None
    config_id = service.get("alert_config_id")
    if config_id:
        update_alert_config(int(config_id), payload)
    else:
        config = create_alert_config(payload)
        update_service_alert_config(service_id, int(config["id"]))
    return get_service(service_id)


def list_alert_policies(include_disabled: bool = True) -> list[dict[str, Any]]:
    where = "" if include_disabled else "WHERE enabled = 1"
    with get_connection() as conn:
        rows = conn.execute(
            f"""
            SELECT *
            FROM alert_policy
            {where}
            ORDER BY enabled DESC, id
            """
        ).fetchall()
    return [_alert_policy_row_to_dict(row) for row in rows if row is not None]


def get_alert_policy(policy_id: int) -> dict[str, Any] | None:
    with get_connection() as conn:
        row = conn.execute("SELECT * FROM alert_policy WHERE id = ?", (policy_id,)).fetchone()
    return _alert_policy_row_to_dict(row)


def list_alert_channels(include_disabled: bool = True) -> list[dict[str, Any]]:
    where = "" if include_disabled else "WHERE enabled = 1"
    with get_connection() as conn:
        rows = conn.execute(
            f"""
            SELECT *
            FROM alert_channel
            {where}
            ORDER BY enabled DESC, channel_name
            """
        ).fetchall()
    return [_alert_channel_row_to_dict(row) for row in rows if row is not None]


def get_alert_channel(channel_id: int, include_secrets: bool = False) -> dict[str, Any] | None:
    with get_connection() as conn:
        row = conn.execute("SELECT * FROM alert_channel WHERE id = ?", (channel_id,)).fetchone()
    return _alert_channel_row_to_dict(row, include_secrets=include_secrets)


def _channel_config_payload(payload: dict[str, Any], existing: dict[str, Any] | None = None) -> dict[str, Any]:
    config = {field: payload.get(field) for field in ALERT_CHANNEL_CONFIG_FIELDS if field in payload}
    config["smtp_use_tls"] = bool(payload.get("smtp_use_tls"))
    config["sms_password_is_md5"] = bool(payload.get("sms_password_is_md5", True))
    if existing:
        for field in ALERT_CHANNEL_SECRET_FIELDS:
            if payload.get(field) is None and field in existing:
                config[field] = existing.get(field)
    return config


def create_alert_channel(payload: dict[str, Any]) -> dict[str, Any]:
    with get_connection() as conn:
        cursor = conn.execute(
            """
            INSERT INTO alert_channel (channel_name, channel_type, config_json, enabled)
            VALUES (?, ?, ?, ?)
            """,
            (
                payload.get("channel_name"),
                payload.get("channel_type"),
                json.dumps(_channel_config_payload(payload), ensure_ascii=False),
                1 if payload.get("enabled") else 0,
            ),
        )
        channel_id = int(cursor.lastrowid)
    channel = get_alert_channel(channel_id)
    assert channel is not None
    return channel


def update_alert_channel(channel_id: int, payload: dict[str, Any]) -> dict[str, Any] | None:
    existing = get_alert_channel(channel_id, include_secrets=True)
    if existing is None:
        return None
    with get_connection() as conn:
        cursor = conn.execute(
            """
            UPDATE alert_channel
            SET channel_name = ?, channel_type = ?, config_json = ?, enabled = ?
            WHERE id = ?
            """,
            (
                payload.get("channel_name"),
                payload.get("channel_type"),
                json.dumps(_channel_config_payload(payload, existing=existing), ensure_ascii=False),
                1 if payload.get("enabled") else 0,
                channel_id,
            ),
        )
        if cursor.rowcount == 0:
            return None
    return get_alert_channel(channel_id)


def delete_alert_channel(channel_id: int) -> bool:
    with get_connection() as conn:
        cursor = conn.execute("DELETE FROM alert_channel WHERE id = ?", (channel_id,))
        return cursor.rowcount > 0


def _group_rel_ids(conn: sqlite3.Connection, table_name: str, id_column: str, group_id: int) -> list[int]:
    rows = conn.execute(
        f"SELECT {id_column} FROM {table_name} WHERE group_id = ? ORDER BY {id_column}",
        (group_id,),
    ).fetchall()
    return [int(row[id_column]) for row in rows]


def _sync_group_rel(
    conn: sqlite3.Connection,
    table_name: str,
    id_column: str,
    group_id: int,
    ids: list[int],
) -> None:
    conn.execute(f"DELETE FROM {table_name} WHERE group_id = ?", (group_id,))
    for item_id in ids:
        conn.execute(
            f"INSERT OR IGNORE INTO {table_name} (group_id, {id_column}) VALUES (?, ?)",
            (group_id, int(item_id)),
        )


def list_alert_groups(include_disabled: bool = True, include_secrets: bool = False) -> list[dict[str, Any]]:
    where = "" if include_disabled else "WHERE enabled = 1"
    with get_connection() as conn:
        rows = conn.execute(
            f"""
            SELECT *
            FROM alert_group
            {where}
            ORDER BY enabled DESC, group_name
            """
        ).fetchall()
    groups: list[dict[str, Any]] = []
    for row in rows:
        base = _alert_group_row_to_dict(row)
        if base is None:
            continue
        group = get_alert_group(int(base["id"]), include_secrets=include_secrets)
        if group is not None:
            groups.append(group)
    return groups


def get_alert_group(group_id: int, include_secrets: bool = False) -> dict[str, Any] | None:
    with get_connection() as conn:
        row = conn.execute("SELECT * FROM alert_group WHERE id = ?", (group_id,)).fetchone()
        group = _alert_group_row_to_dict(row)
        if group is None:
            return None
        policy_rows = conn.execute(
            """
            SELECT p.*
            FROM alert_policy p
            JOIN group_policy_rel rel ON rel.policy_id = p.id
            WHERE rel.group_id = ?
            ORDER BY p.id
            """,
            (group_id,),
        ).fetchall()
        channel_rows = conn.execute(
            """
            SELECT c.*
            FROM alert_channel c
            JOIN group_channel_rel rel ON rel.channel_id = c.id
            WHERE rel.group_id = ?
            ORDER BY c.channel_name
            """,
            (group_id,),
        ).fetchall()
        service_count = conn.execute(
            "SELECT COUNT(*) AS count FROM service_alert_group WHERE group_id = ?",
            (group_id,),
        ).fetchone()
    group["policy_ids"] = [int(policy["id"]) for policy in policy_rows]
    group["channel_ids"] = [int(channel["id"]) for channel in channel_rows]
    group["policies"] = [_alert_policy_row_to_dict(row) for row in policy_rows if row is not None]
    group["channels"] = [
        _alert_channel_row_to_dict(row, include_secrets=include_secrets)
        for row in channel_rows
        if row is not None
    ]
    group["service_count"] = int(service_count["count"] if service_count else 0)
    return group


def _alert_group_values(payload: dict[str, Any]) -> tuple[str, str | None, int]:
    return (
        str(payload.get("group_name") or "").strip(),
        payload.get("description") or None,
        1 if payload.get("enabled") else 0,
    )


def create_alert_group(payload: dict[str, Any]) -> dict[str, Any]:
    policy_ids = [int(item) for item in payload.get("policy_ids") or []]
    channel_ids = [int(item) for item in payload.get("channel_ids") or []]
    with get_connection() as conn:
        cursor = conn.execute(
            """
            INSERT INTO alert_group (group_name, description, enabled)
            VALUES (?, ?, ?)
            """,
            _alert_group_values(payload),
        )
        group_id = int(cursor.lastrowid)
        _sync_group_rel(conn, "group_policy_rel", "policy_id", group_id, policy_ids)
        _sync_group_rel(conn, "group_channel_rel", "channel_id", group_id, channel_ids)
    group = get_alert_group(group_id)
    assert group is not None
    return group


def update_alert_group(group_id: int, payload: dict[str, Any]) -> dict[str, Any] | None:
    policy_ids = [int(item) for item in payload.get("policy_ids") or []]
    channel_ids = [int(item) for item in payload.get("channel_ids") or []]
    with get_connection() as conn:
        cursor = conn.execute(
            """
            UPDATE alert_group
            SET group_name = ?, description = ?, enabled = ?
            WHERE id = ?
            """,
            (*_alert_group_values(payload), group_id),
        )
        if cursor.rowcount == 0:
            return None
        _sync_group_rel(conn, "group_policy_rel", "policy_id", group_id, policy_ids)
        _sync_group_rel(conn, "group_channel_rel", "channel_id", group_id, channel_ids)
    return get_alert_group(group_id)


def delete_alert_group(group_id: int) -> bool:
    with get_connection() as conn:
        group = conn.execute(
            "SELECT legacy_config_id FROM alert_group WHERE id = ?",
            (group_id,),
        ).fetchone()
        if group is not None and group["legacy_config_id"] is not None:
            conn.execute(
                "UPDATE monitor_service SET alert_config_id = NULL WHERE alert_config_id = ?",
                (group["legacy_config_id"],),
            )
        cursor = conn.execute("DELETE FROM alert_group WHERE id = ?", (group_id,))
        return cursor.rowcount > 0


def update_service_alert_group(
    service_id: int,
    alert_group_id: int | None,
) -> dict[str, Any] | None:
    with get_connection() as conn:
        exists = conn.execute("SELECT id FROM monitor_service WHERE id = ?", (service_id,)).fetchone()
        if exists is None:
            return None
        if alert_group_id:
            conn.execute(
                "INSERT OR REPLACE INTO service_alert_group (service_id, group_id) VALUES (?, ?)",
                (service_id, alert_group_id),
            )
            conn.execute(
                """
                UPDATE monitor_service
                SET alert_config_id = (
                    SELECT legacy_config_id FROM alert_group WHERE id = ?
                )
                WHERE id = ?
                """,
                (alert_group_id, service_id),
            )
        else:
            conn.execute("DELETE FROM service_alert_group WHERE service_id = ?", (service_id,))
            conn.execute("UPDATE monitor_service SET alert_config_id = NULL WHERE id = ?", (service_id,))
    return get_service(service_id)


def delete_service(service_id: int) -> bool:
    with get_connection() as conn:
        cursor = conn.execute("DELETE FROM monitor_service WHERE id = ?", (service_id,))
        return cursor.rowcount > 0


def list_services(include_disabled: bool = False) -> list[dict[str, Any]]:
    where = "" if include_disabled else "WHERE s.enabled = 1"
    with get_connection() as conn:
        rows = conn.execute(
            f"""
            SELECT
                s.*,
                sag.group_id AS alert_group_id,
                ag.group_name AS alert_group_name,
                ag.enabled AS alert_group_enabled,
                ac.config_name AS alert_config_name,
                ac.alert_channel AS alert_channel,
                ac.enabled AS alert_config_enabled,
                COALESCE(r.status, 'UNKNOWN') AS last_status,
                r.response_time_ms AS last_response_time_ms,
                r.message AS last_message,
                r.checked_at AS last_checked_at
            FROM monitor_service s
            LEFT JOIN service_alert_group sag ON sag.service_id = s.id
            LEFT JOIN alert_group ag ON ag.id = sag.group_id
            LEFT JOIN alert_config ac ON ac.id = s.alert_config_id
            LEFT JOIN monitor_result r ON r.id = (
                SELECT id
                FROM monitor_result
                WHERE service_id = s.id
                ORDER BY checked_at DESC, id DESC
                LIMIT 1
            )
            {where}
            ORDER BY s.cluster_name IS NULL, s.cluster_name, s.service_name
            """
        ).fetchall()
    return [_service_row_to_dict(row) for row in rows if row is not None]


def get_service(service_id: int, include_secrets: bool = False) -> dict[str, Any] | None:
    with get_connection() as conn:
        row = conn.execute(
            """
            SELECT
                s.*,
                sag.group_id AS alert_group_id,
                ag.group_name AS alert_group_name,
                ag.enabled AS alert_group_enabled,
                ac.config_name AS alert_config_name,
                ac.alert_channel AS alert_channel,
                ac.enabled AS alert_config_enabled,
                COALESCE(r.status, 'UNKNOWN') AS last_status,
                r.response_time_ms AS last_response_time_ms,
                r.message AS last_message,
                r.checked_at AS last_checked_at
            FROM monitor_service s
            LEFT JOIN service_alert_group sag ON sag.service_id = s.id
            LEFT JOIN alert_group ag ON ag.id = sag.group_id
            LEFT JOIN alert_config ac ON ac.id = s.alert_config_id
            LEFT JOIN monitor_result r ON r.id = (
                SELECT id
                FROM monitor_result
                WHERE service_id = s.id
                ORDER BY checked_at DESC, id DESC
                LIMIT 1
            )
            WHERE s.id = ?
            """,
            (service_id,),
        ).fetchone()
    service = _service_row_to_dict(row, include_secrets=include_secrets)
    if include_secrets and service and service.get("alert_group_id"):
        alert_group = get_alert_group(int(service["alert_group_id"]), include_secrets=True)
        if alert_group and alert_group.get("enabled"):
            service["alert_group"] = alert_group
            service["alert_policies"] = [
                policy for policy in alert_group.get("policies", []) if policy and policy.get("enabled")
            ]
            service["alert_channels"] = [
                channel for channel in alert_group.get("channels", []) if channel and channel.get("enabled")
            ]
        return service
    if include_secrets and service and service.get("alert_config_id"):
        alert_config = get_alert_config(int(service["alert_config_id"]), include_secrets=True)
        if alert_config and alert_config.get("enabled"):
            for field in ALERT_CONFIG_FIELDS:
                if field in alert_config:
                    service[field] = alert_config[field]
    return service


def get_latest_status(service_id: int) -> str | None:
    with get_connection() as conn:
        row = conn.execute(
            """
            SELECT status
            FROM monitor_result
            WHERE service_id = ?
            ORDER BY checked_at DESC, id DESC
            LIMIT 1
            """,
            (service_id,),
        ).fetchone()
    return row["status"] if row else None


def record_result(
    service_id: int,
    status: str,
    response_time_ms: int | None,
    message: str,
) -> dict[str, Any]:
    with get_connection() as conn:
        cursor = conn.execute(
            """
            INSERT INTO monitor_result (service_id, status, response_time_ms, message)
            VALUES (?, ?, ?, ?)
            """,
            (service_id, status, response_time_ms, message),
        )
        result_id = cursor.lastrowid
        row = conn.execute(
            "SELECT * FROM monitor_result WHERE id = ?",
            (result_id,),
        ).fetchone()
    result = _row_to_dict(row)
    assert result is not None
    return result


def list_results(service_id: int, limit: int = 100) -> list[dict[str, Any]]:
    with get_connection() as conn:
        rows = conn.execute(
            """
            SELECT *
            FROM monitor_result
            WHERE service_id = ?
            ORDER BY checked_at DESC, id DESC
            LIMIT ?
            """,
            (service_id, limit),
        ).fetchall()
    return [_row_to_dict(row) for row in rows if row is not None]


def list_recent_results(limit: int = 20) -> list[dict[str, Any]]:
    with get_connection() as conn:
        rows = conn.execute(
            """
            SELECT r.*, s.service_name, s.service_type, s.cluster_name
            FROM monitor_result r
            JOIN monitor_service s ON s.id = r.service_id
            WHERE r.id = (
                SELECT id
                FROM monitor_result
                WHERE service_id = r.service_id
                ORDER BY checked_at DESC, id DESC
                LIMIT 1
            )
            ORDER BY r.checked_at DESC, r.id DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
    return [_row_to_dict(row) for row in rows if row is not None]


def list_recent_alerts(limit: int = 20) -> list[dict[str, Any]]:
    with get_connection() as conn:
        rows = conn.execute(
            """
            SELECT a.*, s.service_name, s.service_type, s.cluster_name
            FROM alert_record a
            JOIN monitor_service s ON s.id = a.service_id
            WHERE a.id = (
                SELECT id
                FROM alert_record
                WHERE service_id = a.service_id
                ORDER BY created_at DESC, id DESC
                LIMIT 1
            )
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
    return [_row_to_dict(row) for row in rows if row is not None]


def create_alert_record(
    service_id: int,
    alert_type: str,
    alert_content: str,
    alert_status: str,
) -> dict[str, Any]:
    with get_connection() as conn:
        cursor = conn.execute(
            """
            INSERT INTO alert_record (service_id, alert_type, alert_content, alert_status)
            VALUES (?, ?, ?, ?)
            """,
            (service_id, alert_type, alert_content, alert_status),
        )
        alert_id = cursor.lastrowid
        row = conn.execute(
            "SELECT * FROM alert_record WHERE id = ?",
            (alert_id,),
        ).fetchone()
    alert = _row_to_dict(row)
    assert alert is not None
    return alert


def list_alerts(service_id: int | None = None, limit: int = 20) -> list[dict[str, Any]]:
    params: tuple[Any, ...]
    where = ""
    if service_id is None:
        params = (limit,)
    else:
        where = "WHERE a.service_id = ?"
        params = (service_id, limit)
    with get_connection() as conn:
        rows = conn.execute(
            f"""
            SELECT a.*, s.service_name, s.service_type, s.cluster_name
            FROM alert_record a
            JOIN monitor_service s ON s.id = a.service_id
            {where}
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT ?
            """,
            params,
        ).fetchall()
    return [_row_to_dict(row) for row in rows if row is not None]


def get_dashboard() -> dict[str, Any]:
    services = list_services(include_disabled=False)
    counts = {"total": len(services), "up": 0, "down": 0, "unknown": 0}
    for service in services:
        status = service.get("last_status") or "UNKNOWN"
        if status == "UP":
            counts["up"] += 1
        elif status == "DOWN":
            counts["down"] += 1
        else:
            counts["unknown"] += 1

    return {
        "summary": counts,
        "services": services,
        "recent_alerts": list_recent_alerts(limit=10),
        "recent_results": list_recent_results(limit=10),
        "server_time": datetime.now(timezone.utc).isoformat(),
    }
