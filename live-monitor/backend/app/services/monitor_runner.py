from __future__ import annotations

from typing import Any

from .redis_monitor import check_redis
from .web_monitor import check_web
from .zookeeper_monitor import check_zookeeper


def run_check(service: dict[str, Any]) -> dict[str, Any]:
    service_type = service.get("service_type")
    if service_type == "web":
        return check_web(
            str(service.get("url") or ""),
            method=str(service.get("http_method") or "GET"),
            expected_status_code=service.get("expected_status_code"),
            response_keyword=service.get("response_keyword") or None,
            timeout=float(service.get("check_timeout_seconds") or 3),
        )
    if service_type == "redis":
        return check_redis(
            str(service.get("host") or ""),
            int(service.get("port") or 0),
            username=service.get("redis_username") or None,
            password=service.get("redis_password") or None,
            cluster_mode=bool(service.get("redis_cluster_mode")),
            timeout=float(service.get("check_timeout_seconds") or 3),
        )
    if service_type == "zookeeper":
        return check_zookeeper(
            str(service.get("host") or ""),
            int(service.get("port") or 0),
            check_mode=str(service.get("zookeeper_check_mode") or "ruok"),
            check_command=str(service.get("zookeeper_check_command") or "ruok"),
            expected_nodes=service.get("zookeeper_expected_nodes"),
            timeout=float(service.get("check_timeout_seconds") or 3),
        )
    return {
        "status": "UNKNOWN",
        "response_time_ms": None,
        "message": f"Unsupported service type: {service_type}",
    }
