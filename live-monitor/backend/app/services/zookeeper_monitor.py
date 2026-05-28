from __future__ import annotations

import socket
import time

from ..config import CHECK_TIMEOUT_SECONDS


def check_zookeeper(
    host: str,
    port: int,
    check_mode: str = "ruok",
    check_command: str = "ruok",
    expected_nodes: int | None = None,
    timeout: float = CHECK_TIMEOUT_SECONDS,
) -> dict[str, object]:
    if not host or not port:
        return {
            "status": "UNKNOWN",
            "response_time_ms": None,
            "message": "Host and port are required for ZooKeeper checks",
        }

    started = time.perf_counter()
    try:
        with socket.create_connection((host, int(port)), timeout=timeout) as client:
            client.settimeout(timeout)
            if check_mode == "port":
                elapsed_ms = int((time.perf_counter() - started) * 1000)
                return {
                    "status": "UP",
                    "response_time_ms": elapsed_ms,
                    "message": "port open",
                }
            command = (check_command or "ruok").strip()[:16] or "ruok"
            client.sendall(command.encode("utf-8"))
            data = client.recv(2048)
        elapsed_ms = int((time.perf_counter() - started) * 1000)
        text = data.decode("utf-8", errors="replace").strip()
        if command == "mntr" and expected_nodes:
            nodes_ok = f"zk_synced_followers\t{expected_nodes - 1}" in text or f"zk_followers\t{expected_nodes - 1}" in text
            return {
                "status": "UP" if nodes_ok else "DOWN",
                "response_time_ms": elapsed_ms,
                "message": text or "empty response",
            }
        return {
            "status": "UP" if (command == "ruok" and text == "imok") or (command != "ruok" and bool(text)) else "DOWN",
            "response_time_ms": elapsed_ms,
            "message": text or "empty response",
        }
    except Exception as exc:
        elapsed_ms = int((time.perf_counter() - started) * 1000)
        return {
            "status": "DOWN",
            "response_time_ms": elapsed_ms,
            "message": f"{type(exc).__name__}: {exc}",
        }
