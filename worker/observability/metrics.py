import re
import time
from contextlib import contextmanager

from prometheus_client import Counter, Gauge, Histogram, start_http_server

_ID_SEGMENT = re.compile(r"/([0-9a-fA-F-]{6,}|\d+)(?=/|$)")

TASKS_STARTED = Counter("worker_tasks_started_total", "Worker tasks started.", ["tool_code", "tool_type"])
TASKS_COMPLETED = Counter("worker_tasks_completed_total", "Worker tasks completed.", ["tool_code", "tool_type", "status", "error_code"])
TASK_DURATION = Histogram("worker_task_duration_seconds", "Worker task duration.", ["tool_code", "tool_type", "status"])
ACTIVE_TASKS = Gauge("worker_active_tasks", "Worker active tasks.", ["tool_code", "tool_type"])
CLAIMS = Counter("worker_tasks_claim_total", "Worker task claim results.", ["result", "reason"])
BACKEND_REQUESTS = Counter("worker_backend_requests_total", "Worker backend requests.", ["method", "path", "status"])
BACKEND_DURATION = Histogram("worker_backend_request_duration_seconds", "Worker backend request duration.", ["method", "path"])
STATUS_UPDATES = Counter("worker_task_status_updates_total", "Worker task status callbacks.", ["status"])
LEASE_RENEW = Counter("worker_lease_renew_total", "Worker lease renew results.", ["status"])


def normalize(value: object | None, fallback: str = "unknown", limit: int = 80) -> str:
    if value is None:
        return fallback
    text = str(value).strip()
    return (text or fallback)[:limit]


def normalize_path(path: str | None) -> str:
    if not path:
        return "unknown"
    return _ID_SEGMENT.sub("/{id}", path.split("?", 1)[0])[:160]


def start_metrics_server(enabled: bool, host: str, port: int) -> None:
    if enabled:
        start_http_server(port, addr=host)


def record_claim(result: str, reason: str | None = None) -> None:
    CLAIMS.labels(normalize(result, limit=24), normalize(reason, "none", 64)).inc()


def record_backend_request(method: str, path: str, status: str, duration: float) -> None:
    BACKEND_REQUESTS.labels(normalize(method, "UNKNOWN", 16).upper(), normalize_path(path), normalize(status, limit=32)).inc()
    BACKEND_DURATION.labels(normalize(method, "UNKNOWN", 16).upper(), normalize_path(path)).observe(max(duration, 0.0))


def record_status_update(status: str) -> None:
    STATUS_UPDATES.labels(normalize(status, limit=32)).inc()


def record_lease_renew(status: str) -> None:
    LEASE_RENEW.labels(normalize(status, limit=32)).inc()


@contextmanager
def task_timer(context: dict | None):
    tool_code = normalize((context or {}).get("toolCode"), "unknown", 80)
    tool_type = normalize((context or {}).get("toolType"), "unknown", 40)
    start = time.perf_counter()
    status = "success"
    error_code = "none"
    TASKS_STARTED.labels(tool_code, tool_type).inc()
    ACTIVE_TASKS.labels(tool_code, tool_type).inc()
    try:
        yield
    except Exception as exc:
        status = "failed"
        error_code = normalize(getattr(exc, "error_code", None) or exc.__class__.__name__, "unknown", 64)
        raise
    finally:
        ACTIVE_TASKS.labels(tool_code, tool_type).dec()
        TASKS_COMPLETED.labels(tool_code, tool_type, status, error_code).inc()
        TASK_DURATION.labels(tool_code, tool_type, status).observe(time.perf_counter() - start)
