import re
import time

from prometheus_client import Counter, Histogram

_ID_SEGMENT = re.compile(r"/([0-9a-fA-F-]{6,}|\d+)(?=/|$)")

HTTP_REQUESTS = Counter(
    "agent_service_http_requests_total",
    "Agent service HTTP requests.",
    ["method", "path", "status"],
)
HTTP_DURATION = Histogram(
    "agent_service_http_request_duration_seconds",
    "Agent service HTTP request duration.",
    ["method", "path"],
)
RUNS_STARTED = Counter(
    "agent_service_runs_started_total",
    "Agent run executions started.",
    ["entrypoint"],
)
RUNS_COMPLETED = Counter(
    "agent_service_runs_completed_total",
    "Agent run executions completed.",
    ["entrypoint", "status", "error_code", "engine"],
)
RUN_DURATION = Histogram(
    "agent_service_run_duration_seconds",
    "Agent run execution duration.",
    ["entrypoint", "status", "engine"],
)
BACKEND_REQUESTS = Counter(
    "agent_service_backend_requests_total",
    "Backend requests made by agent service.",
    ["method", "path", "status"],
)
BACKEND_DURATION = Histogram(
    "agent_service_backend_request_duration_seconds",
    "Backend request duration made by agent service.",
    ["method", "path"],
)
TOOL_CALLS = Counter(
    "agent_service_tool_calls_total",
    "Agent tool calls completed via backend callback.",
    ["tool_code", "status"],
)


def normalize_path(path: str | None) -> str:
    if not path:
        return "unknown"
    normalized = _ID_SEGMENT.sub("/{id}", path.split("?", 1)[0])
    return normalized[:160]


def normalize(value: str | None, fallback: str = "unknown", limit: int = 80) -> str:
    if not value:
        return fallback
    value = str(value).strip()
    return (value or fallback)[:limit]


def record_http_request(method: str, path: str, status: int, duration: float) -> None:
    normalized_path = normalize_path(path)
    HTTP_REQUESTS.labels(normalize(method, "UNKNOWN", 16).upper(), normalized_path, str(status)).inc()
    HTTP_DURATION.labels(normalize(method, "UNKNOWN", 16).upper(), normalized_path).observe(max(duration, 0.0))




def record_run_started(entrypoint: str) -> None:
    RUNS_STARTED.labels(normalize(entrypoint, limit=40)).inc()


def record_run_completed(entrypoint: str, status: str, error_code: str = "none", engine: str = "unknown", duration: float = 0.0) -> None:
    RUNS_COMPLETED.labels(
        normalize(entrypoint, limit=40),
        normalize(status, limit=24),
        normalize(error_code, "none", 64),
        normalize(engine, limit=64),
    ).inc()
    RUN_DURATION.labels(normalize(entrypoint, limit=40), normalize(status, limit=24), normalize(engine, limit=64)).observe(max(duration, 0.0))

def record_backend_request(method: str, path: str, status: str, duration: float) -> None:
    normalized_path = normalize_path(path)
    BACKEND_REQUESTS.labels(normalize(method, "UNKNOWN", 16).upper(), normalized_path, normalize(status, limit=32)).inc()
    BACKEND_DURATION.labels(normalize(method, "UNKNOWN", 16).upper(), normalized_path).observe(max(duration, 0.0))

def record_tool_call(tool_code: str | None, status: str) -> None:
    TOOL_CALLS.labels(normalize(tool_code, "unknown", 80), normalize(status, limit=24)).inc()

