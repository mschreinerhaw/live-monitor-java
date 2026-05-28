from __future__ import annotations

import hashlib
import json
import re
import smtplib
import urllib.request
from email.message import EmailMessage
from typing import Any
from urllib.parse import quote_from_bytes, urlencode

from .. import database
from ..config import (
    SMS_API_TOKEN,
    SMS_API_URL,
    SMS_EXT_CODE,
    SMS_PASSWORD,
    SMS_PASSWORD_IS_MD5,
    SMS_PASSWORD_MD5,
    SMS_RSTYPE,
    SMS_USERNAME,
    SMTP_FROM,
    SMTP_HOST,
    SMTP_PASSWORD,
    SMTP_PORT,
    SMTP_USE_TLS,
    SMTP_USER,
)


def _send_email(
    service: dict[str, Any],
    to_address: str,
    subject: str,
    content: str,
) -> str:
    smtp_host = str(service.get("smtp_host") or SMTP_HOST or "")
    smtp_port = int(service.get("smtp_port") or SMTP_PORT)
    smtp_user = str(service.get("smtp_user") or SMTP_USER or "")
    smtp_password = str(service.get("smtp_password") or SMTP_PASSWORD or "")
    smtp_from = str(service.get("smtp_from") or SMTP_FROM or smtp_user or to_address)
    smtp_use_tls = bool(service.get("smtp_use_tls") or SMTP_USE_TLS)

    if not smtp_host:
        return "success"

    message = EmailMessage()
    message["From"] = smtp_from
    message["To"] = to_address
    message["Subject"] = subject
    message.set_content(content)

    with smtplib.SMTP(smtp_host, smtp_port, timeout=10) as smtp:
        if smtp_use_tls:
            smtp.starttls()
        if smtp_user:
            smtp.login(smtp_user, smtp_password)
        smtp.send_message(message)
    return "success"


def _coalesce_bool(value: Any, fallback: bool = False) -> bool:
    if value is None:
        return fallback
    if isinstance(value, bool):
        return value
    if isinstance(value, int):
        return bool(value)
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


def _md5_upper(text: str) -> str:
    return hashlib.md5(text.encode("utf-8")).hexdigest().upper()


def _resolve_sms_gateway_password(service: dict[str, Any]) -> str:
    password_is_md5 = _coalesce_bool(service.get("sms_password_is_md5"), SMS_PASSWORD_IS_MD5)
    if password_is_md5:
        return str(service.get("sms_password_md5") or SMS_PASSWORD_MD5 or "").strip().upper()
    return _md5_upper(str(service.get("sms_password") or SMS_PASSWORD or ""))


def _parse_sms_gateway_result(text: str) -> dict[str, Any]:
    raw = (text or "").strip()
    if "_" in raw:
        code, detail = raw.split("_", 1)
    else:
        code, detail = raw, ""
    code = code.strip()
    return {
        "raw": raw,
        "code": code,
        "detail": detail.strip(),
        "success": code == "0",
    }


def _split_sms_mobiles(raw: str) -> list[str]:
    parts = re.split(r"[,;\s\uFF0C\uFF1B]+", str(raw or "").strip())
    mobiles: list[str] = []
    seen = set()
    for part in parts:
        mobile = re.sub(r"\s+", "", part.strip())
        if not mobile or mobile in seen:
            continue
        seen.add(mobile)
        mobiles.append(mobile)
    return mobiles


def _send_sms_gateway(service: dict[str, Any], mobile: str, content: str) -> str:
    sms_api_url = str(service.get("sms_api_url") or SMS_API_URL or "")
    sms_username = str(service.get("sms_username") or SMS_USERNAME or "")
    sms_rstype = str(service.get("sms_rstype") or SMS_RSTYPE or "text")
    sms_ext_code = str(service.get("sms_ext_code") or SMS_EXT_CODE or "")

    params: dict[str, Any] = {
        "command": "sendMD5",
        "username": sms_username,
        "pwd": _resolve_sms_gateway_password(service),
        "mobiles": mobile,
        "content": content.encode("gbk"),
        "rstype": sms_rstype,
    }
    if sms_ext_code.strip():
        params["extCode"] = sms_ext_code.strip()

    query_parts = []
    for key, value in params.items():
        if isinstance(value, bytes):
            query_parts.append(f"{key}={quote_from_bytes(value)}")
        else:
            query_parts.append(urlencode({key: value}))
    separator = "&" if "?" in sms_api_url else "?"
    url = f"{sms_api_url}{separator}{'&'.join(query_parts)}"

    request = urllib.request.Request(url, method="GET")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=10) as response:
        body = response.read()
        text = body.decode("gbk", errors="replace")
        parsed = _parse_sms_gateway_result(text)
        return "success" if parsed["success"] else "failed"


def _send_sms_json_api(service: dict[str, Any], mobile: str, content: str) -> str:
    sms_api_url = str(service.get("sms_api_url") or SMS_API_URL or "")
    sms_api_token = str(service.get("sms_api_token") or SMS_API_TOKEN or "")
    payload = json.dumps({"mobile": mobile, "content": content}).encode("utf-8")
    request = urllib.request.Request(
        sms_api_url,
        data=payload,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {sms_api_token}" if sms_api_token else "",
        },
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=10) as response:
        return "success" if 200 <= response.getcode() < 300 else "failed"


def _send_sms(service: dict[str, Any], mobile: str, content: str) -> str:
    sms_api_url = str(service.get("sms_api_url") or SMS_API_URL or "")
    sms_username = str(service.get("sms_username") or SMS_USERNAME or "")
    mobiles = _split_sms_mobiles(mobile)
    if not sms_api_url:
        return "success"
    if not mobiles:
        return "failed"
    if sms_username:
        statuses = [
            _send_sms_gateway(service, ",".join(mobiles[index : index + 100]), content)
            for index in range(0, len(mobiles), 100)
        ]
        return "success" if statuses and all(status == "success" for status in statuses) else "failed"

    statuses = [_send_sms_json_api(service, item, content) for item in mobiles]
    return "success" if statuses and all(status == "success" for status in statuses) else "failed"


def _send_webhook(channel: dict[str, Any], content: str) -> str:
    webhook_url = str(channel.get("webhook_url") or channel.get("sms_api_url") or "")
    if not webhook_url:
        return "success"
    channel_type = channel.get("channel_type")
    payload = (
        {"msgtype": "text", "text": {"content": content}}
        if channel_type == "dingtalk"
        else {"content": content}
    )
    request = urllib.request.Request(
        webhook_url,
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=10) as response:
        return "success" if 200 <= response.getcode() < 300 else "failed"


def _channel_to_delivery_config(service: dict[str, Any], channel: dict[str, Any]) -> dict[str, Any]:
    delivery = dict(service)
    delivery.update(channel)
    delivery["alert_channel"] = channel.get("channel_type")
    return delivery


def dispatch_alert(
    service: dict[str, Any],
    content: str,
    policy: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    channels = service.get("alert_channels") or []
    if not channels:
        channels = [
            {
                "channel_type": service.get("alert_channel"),
                "alert_email": service.get("alert_email"),
                "alert_mobile": service.get("alert_mobile"),
                "smtp_host": service.get("smtp_host"),
                "smtp_port": service.get("smtp_port"),
                "smtp_user": service.get("smtp_user"),
                "smtp_password": service.get("smtp_password"),
                "smtp_from": service.get("smtp_from"),
                "smtp_use_tls": service.get("smtp_use_tls"),
                "sms_api_url": service.get("sms_api_url"),
                "sms_api_token": service.get("sms_api_token"),
                "sms_username": service.get("sms_username"),
                "sms_password": service.get("sms_password"),
                "sms_password_is_md5": service.get("sms_password_is_md5"),
                "sms_password_md5": service.get("sms_password_md5"),
                "sms_rstype": service.get("sms_rstype"),
                "sms_ext_code": service.get("sms_ext_code"),
            }
        ]

    for channel in channels:
        channel_type = channel.get("channel_type") or channel.get("alert_channel")
        delivery = _channel_to_delivery_config(service, channel)
        record_content = content
        if policy:
            record_content = f"[{policy.get('policy_name') or 'Alert'}] {content}"

        if channel_type in {None, "email"} and channel.get("alert_email"):
            status = "failed"
            email_content = record_content
            try:
                status = _send_email(delivery, str(channel["alert_email"]), "Live Monitor Alert", record_content)
            except Exception as exc:
                email_content = f"{record_content}. Email error: {type(exc).__name__}: {exc}"
            records.append(database.create_alert_record(service["id"], "email", email_content, status))

        if channel_type in {None, "sms"} and channel.get("alert_mobile"):
            status = "failed"
            sms_content = record_content
            try:
                status = _send_sms(delivery, str(channel["alert_mobile"]), record_content)
            except Exception as exc:
                sms_content = f"{record_content}. SMS error: {type(exc).__name__}: {exc}"
            records.append(database.create_alert_record(service["id"], "sms", sms_content, status))

        if channel_type in {"webhook", "dingtalk"} and (channel.get("webhook_url") or channel.get("sms_api_url")):
            status = "failed"
            webhook_content = record_content
            try:
                status = _send_webhook(channel, record_content)
            except Exception as exc:
                webhook_content = f"{record_content}. Webhook error: {type(exc).__name__}: {exc}"
            records.append(database.create_alert_record(service["id"], str(channel_type), webhook_content, status))

    return records


def dispatch_down_alert(service: dict[str, Any], result: dict[str, Any]) -> list[dict[str, Any]]:
    content = (
        f"Service {service['service_name']} is DOWN. "
        f"Type: {service['service_type']}. Message: {result.get('message') or '-'}"
    )
    return dispatch_alert(service, content)
