from typing import Any


MIN_ASYNC_VIDEO_TIMEOUT_SECONDS = 900


def resolve_video_timeout_seconds(model_config: dict[str, Any] | None) -> int:
    config = model_config or {}
    raw = config.get("timeoutSeconds") or config.get("timeout_seconds")
    try:
        configured = int(raw)
    except (TypeError, ValueError):
        configured = 0
    return max(MIN_ASYNC_VIDEO_TIMEOUT_SECONDS, configured)
