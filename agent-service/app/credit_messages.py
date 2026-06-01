from __future__ import annotations

from typing import Any

from app.clients.backend_client import BackendBusinessError


def format_credit_insufficient_message(
    *,
    available: int | None = None,
    required: int | None = None,
    tool_code: str | None = None,
    fallback: str | None = None,
) -> str:
    if available is not None and required is not None:
        if tool_code:
            return (
                f"可用算力不足：当前 {available}，调用「{tool_code}」至少需要 {required}。"
                "请前往「会员与算力」充值后再试。"
            )
        return f"可用算力不足：当前 {available}，至少需要 {required}。请前往「会员与算力」充值后再试。"
    if fallback:
        return fallback
    return "可用算力不足，请前往「会员与算力」充值后再试。"


def credit_message_from_backend_error(exc: BackendBusinessError, tool_code: str | None = None) -> str:
    data = exc.data if isinstance(exc.data, dict) else {}
    available = _as_int(data.get("availableCredits"))
    required = _as_int(data.get("requiredCredits"))
    resolved_tool = tool_code or data.get("toolCode")
    return format_credit_insufficient_message(
        available=available,
        required=required,
        tool_code=str(resolved_tool) if resolved_tool else None,
        fallback=str(exc) if str(exc) else None,
    )


def _as_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None
