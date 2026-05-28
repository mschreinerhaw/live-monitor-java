from __future__ import annotations

import time
import urllib.error
import urllib.request

from ..config import CHECK_TIMEOUT_SECONDS


def check_web(
    url: str,
    method: str = "GET",
    expected_status_code: int | None = None,
    response_keyword: str | None = None,
    timeout: float = CHECK_TIMEOUT_SECONDS,
) -> dict[str, object]:
    if not url:
        return {
            "status": "UNKNOWN",
            "response_time_ms": None,
            "message": "URL is required for web service checks",
        }

    normalized_method = (method or "GET").upper()
    started = time.perf_counter()
    request = urllib.request.Request(url, method=normalized_method, headers={"User-Agent": "LiveMonitor/1.0"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=timeout) as response:
            elapsed_ms = int((time.perf_counter() - started) * 1000)
            status_code = response.getcode()
            body = response.read(4096).decode("utf-8", errors="replace") if response_keyword else ""
            status_ok = status_code == expected_status_code if expected_status_code else 200 <= status_code < 400
            keyword_ok = not response_keyword or response_keyword in body
            ok = status_ok and keyword_ok
            message = f"HTTP {status_code}"
            if response_keyword:
                message = f"{message}, keyword {'matched' if keyword_ok else 'missing'}"
            return {
                "status": "UP" if ok else "DOWN",
                "response_time_ms": elapsed_ms,
                "message": message,
            }
    except urllib.error.HTTPError as exc:
        elapsed_ms = int((time.perf_counter() - started) * 1000)
        body = exc.read(4096).decode("utf-8", errors="replace") if response_keyword else ""
        status_ok = exc.code == expected_status_code if expected_status_code else 200 <= exc.code < 400
        keyword_ok = not response_keyword or response_keyword in body
        ok = status_ok and keyword_ok
        message = f"HTTP {exc.code}"
        if response_keyword:
            message = f"{message}, keyword {'matched' if keyword_ok else 'missing'}"
        exc.close()
        return {
            "status": "UP" if ok else "DOWN",
            "response_time_ms": elapsed_ms,
            "message": message,
        }
    except Exception as exc:
        elapsed_ms = int((time.perf_counter() - started) * 1000)
        return {
            "status": "DOWN",
            "response_time_ms": elapsed_ms,
            "message": f"{type(exc).__name__}: {exc}",
        }
