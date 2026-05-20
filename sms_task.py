#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
SMS task sender.

Rules:
1. Read tasks from single table `sms_task`.
2. If task is marked as "pending" (send_status = 0), send SMS.
3. Write back task status and result after send.

Dependencies:
    pip install pymysql requests
"""

import hashlib
import logging
import re
import sys
import datetime
import threading
from typing import Any, Dict, List, Optional, Sequence, Tuple
from urllib.parse import quote_from_bytes, urlencode

import pymysql
import requests


# =========================
# Global Config (edit here)
# =========================

# MySQL
MYSQL_HOST = "192.168.195.223"
MYSQL_PORT = 3306
MYSQL_USER = "root"
MYSQL_PASSWORD = "liveData#12345"
MYSQL_DATABASE = "sms"

# Single table config
TABLE_NAME = "sms_task"
ID_COLUMN = "id"
BIZ_KEY_COLUMN = "biz_key"
CONTENT_COLUMN = "content"
MOBILE_COLUMN = "mobile"  # External writer should populate this column.
SEND_STATUS_COLUMN = "send_status"
RETRY_COUNT_COLUMN = "retry_count"
MAX_RETRY_COLUMN = "max_retry"
LAST_RESULT_COLUMN = "last_result"
LOG_TABLE_NAME = "sms_task_log"

# SMS gateway
SMS_API_URL = "http://127.0.0.1/smsSendServlet.htm"
SMS_USERNAME = "xxzx"
SMS_PASSWORD = ""
SMS_PASSWORD_IS_MD5 = True
SMS_PASSWORD_MD5 = "751CB3F4AA17C36186F4856C8982BF27"
SMS_RSTYPE = "text"
SMS_EXT_CODE = ""

# Mobile source mode:
# 1) db_only: only use mobile numbers from table column `mobile`
# 2) db_fallback_config: if table `mobile` is empty, use PROGRAM_MOBILES
MOBILE_SOURCE_MODE = "db_fallback_config"
PROGRAM_MOBILES = "18211092191"

# Runtime
SMS_BATCH_SIZE = 100
SMS_HTTP_TIMEOUT = 20
SMS_RESPONSE_LOG_MAX_LEN = 500
MAX_TASKS_PER_RUN = 1000
LOG_FILE = "sms_task.log"
DB_LOG_ENABLED = True
# Only errors are persisted to DB log table.
DB_LOG_LEVEL = "ERROR"
DB_LOG_MESSAGE_MAX_LEN = 4000

# Task statuses (sms_task.send_status)
TASK_STATUS_PENDING = 0
TASK_STATUS_SENDING = 1
TASK_STATUS_PARTIAL_SUCCESS = 2
TASK_STATUS_ALL_SUCCESS = 3
TASK_STATUS_ALL_FAILED = 4

# Pending filters.
# Per your requirement, only records marked "pending" are sent.
TASK_PICK_STATUSES = (TASK_STATUS_PENDING,)


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
    handlers=[logging.StreamHandler(), logging.FileHandler(LOG_FILE, encoding="utf-8")],
)


def get_conn():
    return pymysql.connect(
        host=MYSQL_HOST,
        port=MYSQL_PORT,
        user=MYSQL_USER,
        password=MYSQL_PASSWORD,
        database=MYSQL_DATABASE,
        charset="utf8mb4",
        autocommit=False,
        cursorclass=pymysql.cursors.DictCursor,
    )


def get_server_conn():
    # Connect without selecting database, used to create database if missing.
    return pymysql.connect(
        host=MYSQL_HOST,
        port=MYSQL_PORT,
        user=MYSQL_USER,
        password=MYSQL_PASSWORD,
        charset="utf8mb4",
        autocommit=True,
        cursorclass=pymysql.cursors.DictCursor,
    )


class SmsDBLogHandler(logging.Handler):
    """
    Write runtime logs to DB table `sms_task_log`.
    """

    _local = threading.local()

    def emit(self, record):
        if getattr(SmsDBLogHandler._local, "busy", False):
            return
        if record.levelno < logging.ERROR:
            return

        conn = None
        try:
            SmsDBLogHandler._local.busy = True
            formatted = self.format(record)
            message_text = shorten_text(formatted, DB_LOG_MESSAGE_MAX_LEN)
            log_time = datetime.datetime.fromtimestamp(record.created)
            task_id_val = getattr(record, "task_id", None)
            if task_id_val in (None, ""):
                match = re.search(r"\btask_id=(\d+)\b", formatted)
                if match:
                    task_id_val = int(match.group(1))
            else:
                try:
                    task_id_val = int(task_id_val)
                except Exception:
                    task_id_val = None

            conn = get_conn()
            sql = """
            INSERT INTO {log_table}
            (log_time, task_id, log_level, logger_name, module, func_name, line_no, message)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            """.format(log_table=safe_ident(LOG_TABLE_NAME))
            with conn.cursor() as cur:
                cur.execute(
                    sql,
                    (
                        log_time,
                        task_id_val,
                        str(record.levelname),
                        str(record.name),
                        str(record.module),
                        str(record.funcName),
                        int(record.lineno),
                        message_text,
                    ),
                )
            conn.commit()
        except Exception as exc:
            if conn is not None:
                try:
                    conn.rollback()
                except Exception:
                    pass
            try:
                sys.stderr.write("db log handler error: {}\n".format(exc))
            except Exception:
                pass
        finally:
            if conn is not None:
                try:
                    conn.close()
                except Exception:
                    pass
            SmsDBLogHandler._local.busy = False


def setup_db_log_handler() -> None:
    if not DB_LOG_ENABLED:
        return

    root_logger = logging.getLogger()
    for h in root_logger.handlers:
        if isinstance(h, SmsDBLogHandler):
            return

    level_name = str(DB_LOG_LEVEL or "ERROR").upper()
    handler_level = getattr(logging, level_name, logging.ERROR)
    db_handler = SmsDBLogHandler()
    db_handler.setLevel(handler_level)
    db_handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    root_logger.addHandler(db_handler)
    logging.info("database log handler enabled: table=%s level=%s", LOG_TABLE_NAME, level_name)


def md5_upper(text: str) -> str:
    return hashlib.md5(text.encode("utf-8")).hexdigest().upper()


def resolve_sms_password_for_gateway() -> str:
    """
    Gateway `pwd` must be MD5 uppercase.
    If password is already MD5, use it directly; otherwise hash plain password.
    """
    if SMS_PASSWORD_IS_MD5:
        return str(SMS_PASSWORD_MD5 or "").strip().upper()
    return md5_upper(str(SMS_PASSWORD or ""))


def normalize_mobile(mobile: str) -> str:
    return re.sub(r"\s+", "", str(mobile or "").strip())


def split_mobiles(raw: str) -> List[str]:
    text = str(raw or "").strip()
    if not text:
        return []
    # Supports separators: comma/semicolon/whitespace and full-width comma/semicolon.
    parts = re.split(r"[,;\s\uFF0C\uFF1B]+", text)
    uniques = []
    seen = set()
    for p in parts:
        mobile = normalize_mobile(p)
        if not mobile:
            continue
        if mobile in seen:
            continue
        seen.add(mobile)
        uniques.append(mobile)
    return uniques


def shorten_text(text: str, max_len: int) -> str:
    t = str(text or "")
    if len(t) <= max_len:
        return t
    return t[:max_len] + "...(truncated)"


def resolve_task_mobiles(task_mobile_raw: str):
    """
    Return tuple: (mobiles, source)
    source: db | config_fallback | db_only_empty | config_empty
    """
    db_mobile_text = str(task_mobile_raw or "").strip()
    db_mobiles = split_mobiles(db_mobile_text)
    if db_mobiles:
        return db_mobiles, "db"

    mode = str(MOBILE_SOURCE_MODE or "").strip().lower()
    if mode == "db_fallback_config":
        config_mobiles = split_mobiles(PROGRAM_MOBILES)
        if config_mobiles:
            return config_mobiles, "config_fallback"
        return [], "config_empty"
    if mode != "db_only":
        logging.warning("unknown MOBILE_SOURCE_MODE=%s, fallback to db_only", mode)

    return [], "db_only_empty"


def is_valid_mobile(mobile: str) -> bool:
    # Mainland China mobile number basic check: starts with 1 and has 11 digits.
    return bool(re.fullmatch(r"1\d{10}", mobile))


def _build_in_sql(values: Sequence[int]) -> str:
    if not values:
        raise ValueError("IN clause values cannot be empty")
    return ",".join(["%s"] * len(values))


def safe_ident(name: str) -> str:
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name or ""):
        raise ValueError("unsafe sql identifier: {}".format(name))
    return "`{}`".format(name)


def ensure_database_and_table() -> None:
    db_name = safe_ident(MYSQL_DATABASE)
    table = safe_ident(TABLE_NAME)
    log_table = safe_ident(LOG_TABLE_NAME)
    id_col = safe_ident(ID_COLUMN)
    biz_key_col = safe_ident(BIZ_KEY_COLUMN)
    mobile_col = safe_ident(MOBILE_COLUMN)
    content_col = safe_ident(CONTENT_COLUMN)
    send_status_col = safe_ident(SEND_STATUS_COLUMN)
    retry_count_col = safe_ident(RETRY_COUNT_COLUMN)
    max_retry_col = safe_ident(MAX_RETRY_COLUMN)
    last_result_col = safe_ident(LAST_RESULT_COLUMN)

    # 1) Ensure database exists.
    server_conn = get_server_conn()
    try:
        with server_conn.cursor() as cur:
            cur.execute(
                "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = %s",
                (MYSQL_DATABASE,),
            )
            exists = cur.fetchone()
            if not exists:
                cur.execute("CREATE DATABASE {} DEFAULT CHARSET=utf8mb4".format(db_name))
    finally:
        server_conn.close()

    # 2) Ensure table exists.
    conn = get_conn()
    try:
        create_sql = """
        CREATE TABLE {table} (
            {id_col} BIGINT PRIMARY KEY AUTO_INCREMENT,
            {biz_key_col} VARCHAR(100) NOT NULL COMMENT 'business unique key',
            {mobile_col} TEXT NOT NULL COMMENT 'mobile numbers, split by comma/semicolon/space',
            {content_col} TEXT NOT NULL COMMENT 'sms content',
            {send_status_col} TINYINT NOT NULL DEFAULT 0 COMMENT '0 pending,1 sending,2 partial,3 success,4 failed',
            {retry_count_col} INT NOT NULL DEFAULT 0 COMMENT 'retry count',
            {max_retry_col} INT NOT NULL DEFAULT 3 COMMENT 'max retry',
            {last_result_col} TEXT NULL COMMENT 'latest gateway result',
            remark VARCHAR(500) NULL COMMENT 'remark',
            create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            UNIQUE KEY uk_biz_key ({biz_key_col}),
            KEY idx_send_status ({send_status_col}),
            KEY idx_create_time (create_time)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='sms task table'
        """.format(
            table=table,
            id_col=id_col,
            biz_key_col=biz_key_col,
            mobile_col=mobile_col,
            content_col=content_col,
            send_status_col=send_status_col,
            retry_count_col=retry_count_col,
            max_retry_col=max_retry_col,
            last_result_col=last_result_col,
        )
        create_log_sql = """
        CREATE TABLE {log_table} (
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            log_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            task_id BIGINT NULL COMMENT 'related sms_task.id',
            log_level VARCHAR(20) NOT NULL COMMENT 'log level',
            logger_name VARCHAR(100) NOT NULL COMMENT 'logger name',
            module VARCHAR(100) NULL COMMENT 'python module',
            func_name VARCHAR(100) NULL COMMENT 'function name',
            line_no INT NULL COMMENT 'line number',
            message TEXT NOT NULL COMMENT 'log message',
            create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            KEY idx_log_time (log_time),
            KEY idx_task_id (task_id),
            KEY idx_log_level (log_level)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='sms runtime log table'
        """.format(log_table=log_table)
        with conn.cursor() as cur:
            cur.execute(
                "SELECT TABLE_NAME FROM information_schema.TABLES "
                "WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s",
                (MYSQL_DATABASE, TABLE_NAME),
            )
            table_exists = cur.fetchone()
            if not table_exists:
                cur.execute(create_sql)

            cur.execute(
                "SELECT TABLE_NAME FROM information_schema.TABLES "
                "WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s",
                (MYSQL_DATABASE, LOG_TABLE_NAME),
            )
            log_table_exists = cur.fetchone()
            if not log_table_exists:
                cur.execute(create_log_sql)
            else:
                cur.execute(
                    "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                    "WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s AND COLUMN_NAME = %s",
                    (MYSQL_DATABASE, LOG_TABLE_NAME, "task_id"),
                )
                task_id_col_exists = cur.fetchone()
                if not task_id_col_exists:
                    cur.execute(
                        "ALTER TABLE {log_table} "
                        "ADD COLUMN task_id BIGINT NULL COMMENT 'related sms_task.id' AFTER log_time, "
                        "ADD KEY idx_task_id (task_id)".format(log_table=log_table)
                    )
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def parse_text_result(result_text: str) -> Dict[str, Any]:
    text = (result_text or "").strip()
    code_mapping = {
        "0": "success",
        "1": "illegal login",
        "3": "insufficient balance",
        "5": "bad parameters",
        "9": "submit failed",
        "10": "too many mobiles (over 100)",
    }

    if "_" in text:
        code, detail = text.split("_", 1)
    else:
        code, detail = text, ""

    code = code.strip()
    detail = detail.strip()
    return {
        "raw": text,
        "code": code,
        "detail": detail,
        "success": code == "0",
        "message": code_mapping.get(code, "unknown code: {}".format(code)),
    }


def _extract_xml_value(xml_text: str, tag: str) -> str:
    """
    Extract tag value from gateway xml.
    Supports minor tag typo from vendor docs: <result>...</reslut>.
    """
    text = xml_text or ""
    tag_pattern = re.escape(tag)
    normal_pattern = r"<{0}>\s*(.*?)\s*</{0}>".format(tag_pattern)
    normal = re.search(normal_pattern, text, flags=re.IGNORECASE | re.DOTALL)
    if normal:
        return normal.group(1).strip()

    if tag.lower() == "result":
        typo_close = re.search(
            r"<result>\s*(.*?)\s*</reslut>",
            text,
            flags=re.IGNORECASE | re.DOTALL,
        )
        if typo_close:
            return typo_close.group(1).strip()

        typo_open = re.search(
            r"<reslut>\s*(.*?)\s*</result>",
            text,
            flags=re.IGNORECASE | re.DOTALL,
        )
        if typo_open:
            return typo_open.group(1).strip()

    return ""


def parse_xml_result(result_text: str) -> Dict[str, Any]:
    text = (result_text or "").strip()
    code_mapping = {
        "0": "success",
        "1": "illegal login",
        "3": "insufficient balance",
        "5": "bad parameters",
        "9": "submit failed",
        "10": "too many mobiles (over 100)",
    }

    code = _extract_xml_value(text, "result")
    detail = _extract_xml_value(text, "sequence")
    return {
        "raw": text,
        "code": code,
        "detail": detail,
        "success": code == "0",
        "message": code_mapping.get(code, "unknown code: {}".format(code)),
    }


def parse_gateway_result(result_text: str) -> Dict[str, Any]:
    text = (result_text or "").strip()
    if text.startswith("<"):
        return parse_xml_result(text)
    return parse_text_result(text)


def split_mobile_sequence(detail: str) -> Dict[str, str]:
    """
    Parse SMS vendor detail:
    seq1:13800138000,seq2:13900139000
    => {"13800138000": "seq1:13800138000", ...}
    """
    result = {}
    if not detail:
        return result

    for item in [x.strip() for x in detail.split(",") if x.strip()]:
        if ":" not in item:
            continue
        _, mobile = item.rsplit(":", 1)
        result[mobile.strip()] = item
    return result


def send_sms_batch(mobiles: List[str], content: str) -> Dict[str, Any]:
    if not mobiles:
        return {
            "raw": "",
            "code": None,
            "detail": "",
            "success": False,
            "message": "empty mobile list",
        }
    if len(mobiles) > 100:
        return {
            "raw": "",
            "code": "10",
            "detail": "",
            "success": False,
            "message": "batch size exceeds 100",
        }

    params = {
        "command": "sendMD5",
        "username": SMS_USERNAME,
        "pwd": resolve_sms_password_for_gateway(),
        "mobiles": ",".join(mobiles),
        "content": content.encode("gbk"),
        "rstype": SMS_RSTYPE,
    }
    if SMS_EXT_CODE.strip():
        params["extCode"] = SMS_EXT_CODE.strip()

    # Build query manually to keep GBK bytes correctly encoded.
    query_parts = []
    for key, value in params.items():
        if isinstance(value, bytes):
            query_parts.append("{}={}".format(key, quote_from_bytes(value)))
        else:
            query_parts.append(urlencode({key: value}))
    url = "{}?{}".format(SMS_API_URL, "&".join(query_parts))

    logging.info(
        "sms http request: api=%s mobile_count=%s mobiles=%s rstype=%s timeout=%s",
        SMS_API_URL,
        len(mobiles),
        ",".join(mobiles),
        SMS_RSTYPE,
        SMS_HTTP_TIMEOUT,
    )

    try:
        resp = requests.get(url, timeout=SMS_HTTP_TIMEOUT)
        resp.raise_for_status()
        raw_text = str(resp.text or "")
        logging.info(
            "sms http response: status=%s body=%s",
            resp.status_code,
            shorten_text(raw_text, SMS_RESPONSE_LOG_MAX_LEN),
        )
        parsed = parse_gateway_result(raw_text)
        logging.info(
            "sms parsed result: code=%s success=%s message=%s detail=%s",
            parsed.get("code"),
            parsed.get("success"),
            parsed.get("message"),
            shorten_text(parsed.get("detail", ""), SMS_RESPONSE_LOG_MAX_LEN),
        )
        return parsed
    except requests.RequestException as exc:
        logging.error(
            "sms http exception: api=%s mobile_count=%s err=%s",
            SMS_API_URL,
            len(mobiles),
            str(exc),
        )
        return {
            "raw": str(exc),
            "code": None,
            "detail": "",
            "success": False,
            "message": "http request failed: {}".format(exc),
        }


def fetch_one_pending_task_for_update(conn) -> Optional[Dict[str, Any]]:
    in_sql = _build_in_sql(TASK_PICK_STATUSES)
    table = safe_ident(TABLE_NAME)
    id_col = safe_ident(ID_COLUMN)
    biz_key_col = safe_ident(BIZ_KEY_COLUMN)
    content_col = safe_ident(CONTENT_COLUMN)
    mobile_col = safe_ident(MOBILE_COLUMN)
    send_status_col = safe_ident(SEND_STATUS_COLUMN)
    retry_count_col = safe_ident(RETRY_COUNT_COLUMN)
    max_retry_col = safe_ident(MAX_RETRY_COLUMN)

    sql = """
    SELECT
        {id_col} AS id,
        {biz_key_col} AS biz_key,
        {content_col} AS content,
        {mobile_col} AS mobile,
        {send_status_col} AS send_status,
        {retry_count_col} AS retry_count,
        {max_retry_col} AS max_retry
    FROM {table}
    WHERE {send_status_col} IN ({in_sql})
      AND {retry_count_col} < {max_retry_col}
    ORDER BY {id_col} ASC
    LIMIT 1
    FOR UPDATE
    """.format(
        id_col=id_col,
        biz_key_col=biz_key_col,
        content_col=content_col,
        mobile_col=mobile_col,
        send_status_col=send_status_col,
        retry_count_col=retry_count_col,
        max_retry_col=max_retry_col,
        table=table,
        in_sql=in_sql,
    )
    args = list(TASK_PICK_STATUSES)
    with conn.cursor() as cur:
        cur.execute(sql, args)
        return cur.fetchone()


def mark_task_sending(conn, task_id: int) -> None:
    table = safe_ident(TABLE_NAME)
    send_status_col = safe_ident(SEND_STATUS_COLUMN)
    id_col = safe_ident(ID_COLUMN)
    sql = "UPDATE {table} SET {send_status_col} = %s WHERE {id_col} = %s".format(
        table=table,
        send_status_col=send_status_col,
        id_col=id_col,
    )
    with conn.cursor() as cur:
        cur.execute(sql, (TASK_STATUS_SENDING, task_id))


def finish_task(conn, task_id: int, task_status: int, result_text: str) -> None:
    table = safe_ident(TABLE_NAME)
    send_status_col = safe_ident(SEND_STATUS_COLUMN)
    retry_count_col = safe_ident(RETRY_COUNT_COLUMN)
    last_result_col = safe_ident(LAST_RESULT_COLUMN)
    id_col = safe_ident(ID_COLUMN)
    sql = """
    UPDATE {table}
    SET {send_status_col} = %s,
        {retry_count_col} = {retry_count_col} + 1,
        {last_result_col} = %s
    WHERE {id_col} = %s
    """.format(
        table=table,
        send_status_col=send_status_col,
        retry_count_col=retry_count_col,
        last_result_col=last_result_col,
        id_col=id_col,
    )
    with conn.cursor() as cur:
        cur.execute(sql, (task_status, result_text, task_id))


def claim_one_task() -> Optional[Dict[str, Any]]:
    conn = get_conn()
    try:
        task = fetch_one_pending_task_for_update(conn)
        if not task:
            conn.rollback()
            return None

        mark_task_sending(conn, task["id"])
        conn.commit()
        return task
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def judge_final_status(success_count: int, fail_count: int) -> int:
    if success_count > 0 and fail_count == 0:
        return TASK_STATUS_ALL_SUCCESS
    if success_count == 0:
        return TASK_STATUS_ALL_FAILED
    return TASK_STATUS_PARTIAL_SUCCESS


def process_task(task: Dict[str, Any]) -> None:
    task_id = task["id"]
    content = str(task.get("content") or "")
    raw_mobile = str(task.get("mobile") or "")
    mobiles, mobile_source = resolve_task_mobiles(raw_mobile)
    logging.info(
        "start task: id=%s biz_key=%s mobile_source=%s",
        task_id,
        task["biz_key"],
        mobile_source,
    )

    valid_mobiles = [m for m in mobiles if is_valid_mobile(m)]
    invalid_mobiles = [m for m in mobiles if not is_valid_mobile(m)]

    success_count = 0
    fail_count = len(invalid_mobiles)
    details = []
    details.append("mobile_source={}".format(mobile_source))
    if not mobiles:
        details.append("no mobiles to send")
        logging.error(
            "task_id=%s biz_key=%s no mobiles resolved, source=%s",
            task_id,
            task["biz_key"],
            mobile_source,
        )
    if invalid_mobiles:
        details.append("invalid mobiles={}".format(",".join(invalid_mobiles)))
        logging.error(
            "task_id=%s biz_key=%s invalid mobiles=%s",
            task_id,
            task["biz_key"],
            ",".join(invalid_mobiles),
        )

    # Send by batch (vendor limit: up to 100 per call)
    batch_no = 0
    for i in range(0, len(valid_mobiles), SMS_BATCH_SIZE):
        batch_no += 1
        batch = valid_mobiles[i : i + SMS_BATCH_SIZE]
        if not batch:
            continue

        logging.info(
            "task_id=%s batch_no=%s send start: mobile_count=%s mobiles=%s",
            task_id,
            batch_no,
            len(batch),
            ",".join(batch),
        )
        result = send_sms_batch(batch, content)
        raw = str(result.get("raw") or "")
        logging.info(
            "task_id=%s batch_no=%s send end: success=%s code=%s message=%s raw=%s",
            task_id,
            batch_no,
            bool(result.get("success", False)),
            result.get("code"),
            result.get("message"),
            shorten_text(raw, SMS_RESPONSE_LOG_MAX_LEN),
        )
        if bool(result.get("success", False)):
            mobile_to_seq = split_mobile_sequence(str(result.get("detail") or ""))
            if mobile_to_seq:
                batch_success = 0
                batch_fail = 0
                for m in batch:
                    if m in mobile_to_seq:
                        batch_success += 1
                    else:
                        batch_fail += 1
                success_count += batch_success
                fail_count += batch_fail
                details.append(
                    "batch_ok={0},batch_fail={1},raw={2}".format(
                        batch_success,
                        batch_fail,
                        raw,
                    )
                )
            else:
                success_count += len(batch)
                details.append("batch_ok={0},raw={1}".format(len(batch), raw))
        else:
            fail_count += len(batch)
            details.append("batch_fail={0},raw={1}".format(len(batch), raw))
            logging.error(
                "task_id=%s batch_no=%s gateway returned failure: code=%s message=%s raw=%s",
                task_id,
                batch_no,
                result.get("code"),
                result.get("message"),
                shorten_text(raw, SMS_RESPONSE_LOG_MAX_LEN),
            )
            # Gateway-level failure: stop subsequent batches this round.
            break

    final_status = judge_final_status(success_count, fail_count)
    summary = "; ".join(details).strip()
    if not summary:
        summary = "success={0},fail={1}".format(success_count, fail_count)
    summary = summary[:2000]
    logging.info("task_id=%s final summary=%s", task_id, summary)
    if final_status == TASK_STATUS_ALL_FAILED:
        logging.error(
            "task_id=%s biz_key=%s final_status=ALL_FAILED summary=%s",
            task_id,
            task["biz_key"],
            summary,
        )

    final_conn = get_conn()
    try:
        finish_task(final_conn, task_id, final_status, summary)
        final_conn.commit()
        logging.info(
            "finish task: id=%s status=%s success=%s fail=%s",
            task_id,
            final_status,
            success_count,
            fail_count,
        )
    except Exception as exc:
        final_conn.rollback()
        logging.exception("task_id=%s finish exception: %s", task_id, exc)
        raise
    finally:
        final_conn.close()


def run_once() -> int:
    """
    Process all currently pending tasks in one run.
    Returns number of claimed tasks.
    """
    handled = 0
    while handled < MAX_TASKS_PER_RUN:
        task = claim_one_task()
        if not task:
            break
        process_task(task)
        handled += 1

    return handled


def main() -> int:
    ensure_database_and_table()
    setup_db_log_handler()
    logging.info("sms task program started")
    handled = run_once()
    if handled == 0:
        logging.info("no pending sms tasks")
    else:
        logging.info("completed this run, task_count=%s", handled)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        logging.exception("fatal error, exit with -1: %s", exc)
        sys.exit(-1)
