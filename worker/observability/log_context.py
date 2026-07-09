import logging
from contextlib import contextmanager
from contextvars import ContextVar


_TRACE_ID: ContextVar[str] = ContextVar("trace_id", default="-")
_INSTALLED = False


def install_trace_id_log_record_factory() -> None:
    global _INSTALLED
    if _INSTALLED:
        return
    previous_factory = logging.getLogRecordFactory()

    def factory(*args, **kwargs):
        record = previous_factory(*args, **kwargs)
        record.traceId = _TRACE_ID.get() or "-"
        return record

    logging.setLogRecordFactory(factory)
    _INSTALLED = True


@contextmanager
def log_trace_context(trace_id: str | None):
    token = _TRACE_ID.set(trace_id or "-")
    try:
        yield
    finally:
        _TRACE_ID.reset(token)
