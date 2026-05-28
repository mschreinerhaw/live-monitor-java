from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from . import database
from .config import FRONTEND_DIR, SCHEDULER_TICK_SECONDS
from .models import SERVICE_TYPES
from .schemas import (
    AlertChannelCreate,
    AlertChannelUpdate,
    AlertConfigCreate,
    AlertConfigUpdate,
    AlertGroupCreate,
    AlertGroupUpdate,
    AlertSettingsUpdate,
    ServiceAlertConfigUpdate,
    ServiceAlertGroupUpdate,
    ServiceCreate,
    ServiceUpdate,
)
from .services.monitor_runner import run_check
from .utils.alerting import dispatch_alert, dispatch_down_alert


logger = logging.getLogger("live-monitor")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


def _model_to_dict(model: Any) -> dict[str, Any]:
    if hasattr(model, "model_dump"):
        return model.model_dump()
    return model.dict()


def _validate_service_payload(payload: dict[str, Any]) -> None:
    service_type = payload.get("service_type")
    if service_type not in SERVICE_TYPES:
        raise HTTPException(status_code=422, detail="service_type must be web, redis, or zookeeper")
    if payload.get("zookeeper_check_mode") not in {None, "ruok", "port"}:
        raise HTTPException(status_code=422, detail="zookeeper_check_mode must be ruok or port")
    if payload.get("http_method") not in {None, "GET", "HEAD", "POST"}:
        raise HTTPException(status_code=422, detail="http_method must be GET, HEAD, or POST")
    if payload.get("zookeeper_check_command") not in {None, "ruok", "stat", "mntr", "srvr"}:
        raise HTTPException(status_code=422, detail="zookeeper_check_command must be ruok, stat, mntr, or srvr")
    if service_type == "web" and not payload.get("url"):
        raise HTTPException(status_code=422, detail="url is required for web services")
    if service_type in {"redis", "zookeeper"}:
        if not payload.get("host") or not payload.get("port"):
            raise HTTPException(status_code=422, detail="host and port are required for middleware services")
    alert_config_id = payload.get("alert_config_id")
    if alert_config_id and database.get_alert_config(int(alert_config_id)) is None:
        raise HTTPException(status_code=422, detail="alert_config_id not found")
    alert_group_id = payload.get("alert_group_id")
    if alert_group_id and database.get_alert_group(int(alert_group_id)) is None:
        raise HTTPException(status_code=422, detail="alert_group_id not found")


def _validate_alert_config_payload(payload: dict[str, Any]) -> None:
    if payload.get("alert_channel") not in {"email", "sms"}:
        raise HTTPException(status_code=422, detail="alert_channel must be email or sms")


def _validate_alert_channel_payload(payload: dict[str, Any]) -> None:
    if payload.get("channel_type") not in {"email", "sms", "webhook", "dingtalk"}:
        raise HTTPException(status_code=422, detail="channel_type must be email, sms, webhook, or dingtalk")


def _validate_alert_group_payload(payload: dict[str, Any]) -> None:
    for policy_id in payload.get("policy_ids") or []:
        if database.get_alert_policy(int(policy_id)) is None:
            raise HTTPException(status_code=422, detail=f"policy_id {policy_id} not found")
    for channel_id in payload.get("channel_ids") or []:
        if database.get_alert_channel(int(channel_id)) is None:
            raise HTTPException(status_code=422, detail=f"channel_id {channel_id} not found")


def _parse_sqlite_time(value: str | None) -> datetime | None:
    if not value:
        return None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S"):
        try:
            return datetime.strptime(value[:19], fmt).replace(tzinfo=timezone.utc)
        except ValueError:
            continue
    return None


def _is_due(service: dict[str, Any]) -> bool:
    last_checked_at = _parse_sqlite_time(service.get("last_checked_at"))
    if last_checked_at is None:
        return True
    interval = int(service.get("check_interval") or 60)
    elapsed = (datetime.now(timezone.utc) - last_checked_at).total_seconds()
    return elapsed >= interval


def _policy_int_value(policy: dict[str, Any], default: int) -> int:
    try:
        return max(1, int(policy.get("trigger_value") or default))
    except (TypeError, ValueError):
        return default


def _policy_triggered(
    service: dict[str, Any],
    result: dict[str, Any],
    previous_status: str | None,
    policy: dict[str, Any],
) -> bool:
    trigger_type = policy.get("trigger_type")
    if trigger_type == "consecutive_down":
        threshold = _policy_int_value(policy, 3)
        rows = database.list_results(int(service["id"]), limit=threshold + 1)
        if len(rows) < threshold:
            return False
        current_window = rows[:threshold]
        already_alerted_window = rows[threshold:] and rows[threshold].get("status") == "DOWN"
        return all(row.get("status") == "DOWN" for row in current_window) and not already_alerted_window
    if trigger_type == "latency_gt_ms":
        threshold = _policy_int_value(policy, 3000)
        current = result.get("response_time_ms")
        if current is None or int(current) <= threshold:
            return False
        rows = database.list_results(int(service["id"]), limit=2)
        previous = rows[1].get("response_time_ms") if len(rows) > 1 else None
        return previous is None or int(previous) <= threshold
    if trigger_type == "recovered":
        return result.get("status") == "UP" and previous_status == "DOWN"
    return False


def _alert_content(service: dict[str, Any], result: dict[str, Any], policy: dict[str, Any]) -> str:
    response_time = result.get("response_time_ms")
    return (
        f"Service {service['service_name']} triggered {policy.get('policy_name') or 'alert'}. "
        f"Status: {result.get('status')}. Type: {service['service_type']}. "
        f"Response: {response_time if response_time is not None else '-'} ms. "
        f"Message: {result.get('message') or '-'}"
    )


def _dispatch_policy_alerts(
    service: dict[str, Any],
    result: dict[str, Any],
    previous_status: str | None,
) -> list[dict[str, Any]]:
    policies = service.get("alert_policies") or []
    if not policies:
        if result["status"] == "DOWN" and previous_status == "UP":
            return dispatch_down_alert(service, result)
        return []
    records: list[dict[str, Any]] = []
    for policy in policies:
        if not policy.get("enabled", True):
            continue
        if _policy_triggered(service, result, previous_status, policy):
            records.extend(dispatch_alert(service, _alert_content(service, result, policy), policy))
    return records


def check_and_store(service_id: int) -> dict[str, Any]:
    service = database.get_service(service_id, include_secrets=True)
    if service is None:
        raise HTTPException(status_code=404, detail="service not found")

    previous_status = database.get_latest_status(service_id)
    result_data = run_check(service)
    result = database.record_result(
        service_id=service_id,
        status=str(result_data.get("status") or "UNKNOWN"),
        response_time_ms=result_data.get("response_time_ms"),
        message=str(result_data.get("message") or ""),
    )

    _dispatch_policy_alerts(service, result, previous_status)
    return result


async def scheduler_loop() -> None:
    while True:
        try:
            services = database.list_services(include_disabled=False)
            for service in services:
                if _is_due(service):
                    await asyncio.to_thread(check_and_store, service["id"])
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("scheduler tick failed")
        await asyncio.sleep(SCHEDULER_TICK_SECONDS)


@asynccontextmanager
async def lifespan(app: FastAPI):
    database.init_db()
    task = asyncio.create_task(scheduler_loop())
    try:
        yield
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass


app = FastAPI(title="Live Monitor", version="1.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

if FRONTEND_DIR.exists():
    app.mount("/css", StaticFiles(directory=FRONTEND_DIR / "css"), name="css")
    app.mount("/js", StaticFiles(directory=FRONTEND_DIR / "js"), name="js")
    app.mount("/vendor", StaticFiles(directory=FRONTEND_DIR / "vendor"), name="vendor")
    if (FRONTEND_DIR / "assets").exists():
        app.mount("/assets", StaticFiles(directory=FRONTEND_DIR / "assets"), name="assets")


def _frontend_file(name: str) -> FileResponse:
    path = FRONTEND_DIR / name
    if not path.exists():
        raise HTTPException(status_code=404, detail="frontend file not found")
    return FileResponse(path)


@app.get("/")
def index() -> FileResponse:
    return _frontend_file("index.html")


@app.get("/dashboard")
def dashboard_page() -> FileResponse:
    return _frontend_file("index.html")


@app.get("/service.html")
def service_page() -> FileResponse:
    return _frontend_file("service.html")


@app.get("/add_service.html")
def add_service_page() -> FileResponse:
    return _frontend_file("add_service.html")


@app.get("/services/new")
def new_service_page() -> FileResponse:
    return _frontend_file("add_service.html")


@app.get("/services/{service_id}/edit")
def edit_service_page(service_id: int) -> FileResponse:
    return _frontend_file("add_service.html")


@app.get("/services/{service_id}")
def service_detail_page(service_id: int) -> FileResponse:
    return _frontend_file("service.html")


@app.get("/alert_settings.html")
def alert_settings_page_legacy() -> FileResponse:
    return _frontend_file("alert_settings.html")


@app.get("/alerts/settings")
def alert_settings_page() -> FileResponse:
    return _frontend_file("alert_settings.html")


@app.get("/api/health")
def health() -> dict[str, str]:
    return {"status": "ok", "time": datetime.now(timezone.utc).isoformat()}


@app.get("/api/dashboard")
def dashboard() -> dict[str, Any]:
    return database.get_dashboard()


@app.get("/api/services")
def services(include_disabled: bool = False) -> list[dict[str, Any]]:
    return database.list_services(include_disabled=include_disabled)


@app.post("/api/services", status_code=201)
def create_service(payload: ServiceCreate) -> dict[str, Any]:
    data = _model_to_dict(payload)
    _validate_service_payload(data)
    return database.create_service(data)


@app.post("/api/services/test")
async def test_service(payload: ServiceCreate) -> dict[str, Any]:
    data = _model_to_dict(payload)
    _validate_service_payload(data)
    return await asyncio.to_thread(run_check, data)


@app.get("/api/services/{service_id}")
def get_service(service_id: int) -> dict[str, Any]:
    service = database.get_service(service_id)
    if service is None:
        raise HTTPException(status_code=404, detail="service not found")
    return service


@app.put("/api/services/{service_id}")
def update_service(service_id: int, payload: ServiceUpdate) -> dict[str, Any]:
    data = _model_to_dict(payload)
    _validate_service_payload(data)
    service = database.update_service(service_id, data)
    if service is None:
        raise HTTPException(status_code=404, detail="service not found")
    return service


@app.get("/api/alert-configs")
def alert_configs(include_disabled: bool = True) -> list[dict[str, Any]]:
    return database.list_alert_configs(include_disabled=include_disabled)


@app.get("/api/alert-policies")
def alert_policies(include_disabled: bool = True) -> list[dict[str, Any]]:
    return database.list_alert_policies(include_disabled=include_disabled)


@app.get("/api/alert-channels")
def alert_channels(include_disabled: bool = True) -> list[dict[str, Any]]:
    return database.list_alert_channels(include_disabled=include_disabled)


@app.post("/api/alert-channels", status_code=201)
def create_alert_channel(payload: AlertChannelCreate) -> dict[str, Any]:
    data = _model_to_dict(payload)
    _validate_alert_channel_payload(data)
    return database.create_alert_channel(data)


@app.put("/api/alert-channels/{channel_id}")
def update_alert_channel(channel_id: int, payload: AlertChannelUpdate) -> dict[str, Any]:
    data = _model_to_dict(payload)
    _validate_alert_channel_payload(data)
    channel = database.update_alert_channel(channel_id, data)
    if channel is None:
        raise HTTPException(status_code=404, detail="alert channel not found")
    return channel


@app.delete("/api/alert-channels/{channel_id}", status_code=204)
def delete_alert_channel(channel_id: int) -> None:
    if not database.delete_alert_channel(channel_id):
        raise HTTPException(status_code=404, detail="alert channel not found")


@app.get("/api/alert-groups")
def alert_groups(include_disabled: bool = True) -> list[dict[str, Any]]:
    return database.list_alert_groups(include_disabled=include_disabled)


@app.post("/api/alert-groups", status_code=201)
def create_alert_group(payload: AlertGroupCreate) -> dict[str, Any]:
    data = _model_to_dict(payload)
    _validate_alert_group_payload(data)
    return database.create_alert_group(data)


@app.get("/api/alert-groups/{group_id}")
def get_alert_group(group_id: int) -> dict[str, Any]:
    group = database.get_alert_group(group_id)
    if group is None:
        raise HTTPException(status_code=404, detail="alert group not found")
    return group


@app.put("/api/alert-groups/{group_id}")
def update_alert_group(group_id: int, payload: AlertGroupUpdate) -> dict[str, Any]:
    data = _model_to_dict(payload)
    _validate_alert_group_payload(data)
    group = database.update_alert_group(group_id, data)
    if group is None:
        raise HTTPException(status_code=404, detail="alert group not found")
    return group


@app.delete("/api/alert-groups/{group_id}", status_code=204)
def delete_alert_group(group_id: int) -> None:
    if not database.delete_alert_group(group_id):
        raise HTTPException(status_code=404, detail="alert group not found")


@app.post("/api/alert-configs", status_code=201)
def create_alert_config(payload: AlertConfigCreate) -> dict[str, Any]:
    data = _model_to_dict(payload)
    _validate_alert_config_payload(data)
    return database.create_alert_config(data)


@app.get("/api/alert-configs/{config_id}")
def get_alert_config(config_id: int) -> dict[str, Any]:
    config = database.get_alert_config(config_id)
    if config is None:
        raise HTTPException(status_code=404, detail="alert config not found")
    return config


@app.put("/api/alert-configs/{config_id}")
def update_alert_config(config_id: int, payload: AlertConfigUpdate) -> dict[str, Any]:
    data = _model_to_dict(payload)
    _validate_alert_config_payload(data)
    config = database.update_alert_config(config_id, data)
    if config is None:
        raise HTTPException(status_code=404, detail="alert config not found")
    return config


@app.delete("/api/alert-configs/{config_id}", status_code=204)
def delete_alert_config(config_id: int) -> None:
    if not database.delete_alert_config(config_id):
        raise HTTPException(status_code=404, detail="alert config not found")


@app.put("/api/services/{service_id}/alert-config")
def update_service_alert_config(
    service_id: int,
    payload: ServiceAlertConfigUpdate,
) -> dict[str, Any]:
    alert_config_id = payload.alert_config_id
    if alert_config_id and database.get_alert_config(alert_config_id) is None:
        raise HTTPException(status_code=422, detail="alert_config_id not found")
    service = database.update_service_alert_config(service_id, alert_config_id)
    if service is None:
        raise HTTPException(status_code=404, detail="service not found")
    return service


@app.put("/api/services/{service_id}/alert-group")
def update_service_alert_group(
    service_id: int,
    payload: ServiceAlertGroupUpdate,
) -> dict[str, Any]:
    alert_group_id = payload.alert_group_id
    if alert_group_id and database.get_alert_group(alert_group_id) is None:
        raise HTTPException(status_code=422, detail="alert_group_id not found")
    service = database.update_service_alert_group(service_id, alert_group_id)
    if service is None:
        raise HTTPException(status_code=404, detail="service not found")
    return service


@app.put("/api/services/{service_id}/alert-settings")
def update_service_alert_settings(service_id: int, payload: AlertSettingsUpdate) -> dict[str, Any]:
    data = _model_to_dict(payload)
    _validate_alert_config_payload(data)
    service = database.update_alert_settings(service_id, data)
    if service is None:
        raise HTTPException(status_code=404, detail="service not found")
    return service


@app.delete("/api/services/{service_id}", status_code=204)
def delete_service(service_id: int) -> None:
    if not database.delete_service(service_id):
        raise HTTPException(status_code=404, detail="service not found")


@app.post("/api/services/{service_id}/check")
async def run_service_check(service_id: int) -> dict[str, Any]:
    return await asyncio.to_thread(check_and_store, service_id)


@app.get("/api/services/{service_id}/results")
def service_results(service_id: int, limit: int = Query(100, ge=1, le=500)) -> list[dict[str, Any]]:
    if database.get_service(service_id) is None:
        raise HTTPException(status_code=404, detail="service not found")
    return database.list_results(service_id, limit=limit)


@app.get("/api/services/{service_id}/alerts")
def service_alerts(service_id: int, limit: int = Query(50, ge=1, le=200)) -> list[dict[str, Any]]:
    if database.get_service(service_id) is None:
        raise HTTPException(status_code=404, detail="service not found")
    return database.list_alerts(service_id=service_id, limit=limit)


@app.get("/api/alerts")
def alerts(limit: int = Query(50, ge=1, le=200)) -> list[dict[str, Any]]:
    return database.list_alerts(limit=limit)
