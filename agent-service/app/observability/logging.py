import logging

from app.observability.trace import current_trace_id


class TraceIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.trace_id = current_trace_id() or "-"
        return True


def configure_logging(level: str = "INFO") -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s [traceId=%(trace_id)s] %(name)s - %(message)s",
    )
    root_logger = logging.getLogger()
    has_trace_filter = any(isinstance(existing, TraceIdFilter) for existing in root_logger.filters)
    if not has_trace_filter:
        root_logger.addFilter(TraceIdFilter())
