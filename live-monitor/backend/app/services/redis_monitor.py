from __future__ import annotations

import socket
import time

from ..config import CHECK_TIMEOUT_SECONDS


PING_COMMAND = b"*1\r\n$4\r\nPING\r\n"


def _encode_command(*parts: str) -> bytes:
    command = [f"*{len(parts)}\r\n".encode("utf-8")]
    for part in parts:
        encoded = part.encode("utf-8")
        command.append(f"${len(encoded)}\r\n".encode("utf-8"))
        command.append(encoded + b"\r\n")
    return b"".join(command)


def _read_response(client: socket.socket) -> bytes:
    return client.recv(512)


def check_redis(
    host: str,
    port: int,
    username: str | None = None,
    password: str | None = None,
    cluster_mode: bool = False,
    timeout: float = CHECK_TIMEOUT_SECONDS,
) -> dict[str, object]:
    if not host or not port:
        return {
            "status": "UNKNOWN",
            "response_time_ms": None,
            "message": "Host and port are required for Redis checks",
        }

    started = time.perf_counter()
    try:
        with socket.create_connection((host, int(port)), timeout=timeout) as client:
            client.settimeout(timeout)
            if password:
                auth_parts = ("AUTH", username, password) if username else ("AUTH", password)
                client.sendall(_encode_command(*auth_parts))
                auth_data = _read_response(client)
                if not auth_data.startswith(b"+OK"):
                    elapsed_ms = int((time.perf_counter() - started) * 1000)
                    return {
                        "status": "DOWN",
                        "response_time_ms": elapsed_ms,
                        "message": auth_data.decode("utf-8", errors="replace").strip() or "auth failed",
                    }
            client.sendall(PING_COMMAND)
            data = _read_response(client)
            if cluster_mode:
                client.sendall(_encode_command("CLUSTER", "INFO"))
                cluster_data = _read_response(client)
                cluster_ok = b"cluster_state:ok" in cluster_data.lower()
                elapsed_ms = int((time.perf_counter() - started) * 1000)
                return {
                    "status": "UP" if cluster_ok else "DOWN",
                    "response_time_ms": elapsed_ms,
                    "message": cluster_data.decode("utf-8", errors="replace").strip() or "empty cluster response",
                }
        elapsed_ms = int((time.perf_counter() - started) * 1000)
        ok = data.startswith(b"+PONG") or b"PONG" in data.upper()
        return {
            "status": "UP" if ok else "DOWN",
            "response_time_ms": elapsed_ms,
            "message": data.decode("utf-8", errors="replace").strip() or "empty response",
        }
    except Exception as exc:
        elapsed_ms = int((time.perf_counter() - started) * 1000)
        return {
            "status": "DOWN",
            "response_time_ms": elapsed_ms,
            "message": f"{type(exc).__name__}: {exc}",
        }
