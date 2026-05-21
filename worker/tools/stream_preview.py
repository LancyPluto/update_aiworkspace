"""Streaming preview helpers for text tool tasks."""

STREAM_PREVIEW_PREFIX = "STREAM_PREVIEW:"
STREAM_PREVIEW_MAX_CHARS = 6000


def build_stream_progress_message(text: str) -> str:
    normalized = (text or "").strip()
    if not normalized:
        return ""
    if len(normalized) > STREAM_PREVIEW_MAX_CHARS:
        normalized = normalized[-STREAM_PREVIEW_MAX_CHARS:]
    return f"{STREAM_PREVIEW_PREFIX}{normalized}"


def extract_stream_preview(progress_message: str | None) -> str:
    if not progress_message:
        return ""
    message = progress_message.strip()
    if not message.startswith(STREAM_PREVIEW_PREFIX):
        return ""
    return message[len(STREAM_PREVIEW_PREFIX) :].strip()
