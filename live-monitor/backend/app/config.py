from __future__ import annotations

import os
from pathlib import Path


BACKEND_DIR = Path(__file__).resolve().parents[1]
PROJECT_DIR = BACKEND_DIR.parent
FRONTEND_DIR = PROJECT_DIR / "frontend"

DATABASE_PATH = Path(os.getenv("MONITOR_DB_PATH", BACKEND_DIR / "live_monitor.db"))
CHECK_TIMEOUT_SECONDS = float(os.getenv("MONITOR_CHECK_TIMEOUT_SECONDS", "3"))
SCHEDULER_TICK_SECONDS = int(os.getenv("MONITOR_SCHEDULER_TICK_SECONDS", "5"))

SMTP_HOST = os.getenv("MONITOR_SMTP_HOST", "")
SMTP_PORT = int(os.getenv("MONITOR_SMTP_PORT", "25"))
SMTP_USER = os.getenv("MONITOR_SMTP_USER", "")
SMTP_PASSWORD = os.getenv("MONITOR_SMTP_PASSWORD", "")
SMTP_FROM = os.getenv("MONITOR_SMTP_FROM", SMTP_USER)
SMTP_USE_TLS = os.getenv("MONITOR_SMTP_USE_TLS", "0").lower() in {"1", "true", "yes", "on"}

SMS_API_URL = os.getenv("MONITOR_SMS_API_URL", "")
SMS_API_TOKEN = os.getenv("MONITOR_SMS_API_TOKEN", "")
SMS_USERNAME = os.getenv("MONITOR_SMS_USERNAME", "")
SMS_PASSWORD = os.getenv("MONITOR_SMS_PASSWORD", "")
SMS_PASSWORD_MD5 = os.getenv("MONITOR_SMS_PASSWORD_MD5", "")
_sms_password_is_md5 = os.getenv("MONITOR_SMS_PASSWORD_IS_MD5")
SMS_PASSWORD_IS_MD5 = (
    _sms_password_is_md5.lower() in {"1", "true", "yes", "on"}
    if _sms_password_is_md5 is not None
    else bool(SMS_PASSWORD_MD5)
)
SMS_RSTYPE = os.getenv("MONITOR_SMS_RSTYPE", "text")
SMS_EXT_CODE = os.getenv("MONITOR_SMS_EXT_CODE", "")
