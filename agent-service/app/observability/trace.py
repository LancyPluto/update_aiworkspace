import contextvars
import re
import uuid

TRACE_ID_HEADER = "X-Request-Id"
TRACE_ID_KEY = "traceId"
SAFE_TRACE_ID = re.compile(r"[A-Za-z0-9_-]{1,64}")

_trace_id_var: contextvars.ContextVar[str | None] = contextvars.ContextVar("trace_id", default=None)


def current_trace_id() -> str | None:
    return _trace_id_var.get()


def resolve_trace_id(candidate: str | None) -> str:
    if candidate and SAFE_TRACE_ID.fullmatch(candidate):
        return candidate
    return str(uuid.uuid4())


def set_trace_id(trace_id: str | None) -> contextvars.Token[str | None]:
    return _trace_id_var.set(trace_id)


def reset_trace_id(token: contextvars.Token[str | None]) -> None:
    _trace_id_var.reset(token)
