from __future__ import annotations

from app.config import settings
from app.core.budget_guard import BudgetGuard
from app.core.schemas import RunContext


def runtime_budget_guard_for_context(context: RunContext, base: BudgetGuard) -> BudgetGuard:
    """Create a per-run guard from backend runtime settings without mutating shared engine state."""
    runtime = context.runtimeSettings
    return BudgetGuard(
        max_model_calls=(
            base.max_model_calls
            if runtime is None or runtime.maxModelCalls is None
            else runtime_int(context, "maxModelCalls", base.max_model_calls, 1, 50)
        ),
        max_tool_calls=(
            base.max_tool_calls
            if runtime is None or runtime.maxToolCalls is None
            else runtime_int(context, "maxToolCalls", base.max_tool_calls, 1, 50)
        ),
        model_call_cost=base.model_call_cost,
        default_consumed_credits=base.default_consumed_credits,
    )


def runtime_settings_event_payload(
    context: RunContext,
    *,
    guard: BudgetGuard,
    tool_timeout_seconds: int,
    tool_poll_interval_seconds: float,
    product_tool_loop_max_calls: int,
) -> dict:
    runtime = context.runtimeSettings
    return {
        "maxModelCalls": guard.max_model_calls,
        "maxToolCalls": guard.max_tool_calls,
        "toolExecutionTimeoutSeconds": runtime_int(
            context,
            "toolExecutionTimeoutSeconds",
            tool_timeout_seconds,
            1,
            3600,
        ),
        "imageToolExecutionTimeoutSeconds": runtime_int(
            context,
            "imageToolExecutionTimeoutSeconds",
            settings.agent_image_tool_execution_timeout_seconds,
            1,
            3600,
        ),
        "videoToolExecutionTimeoutSeconds": runtime_int(
            context,
            "videoToolExecutionTimeoutSeconds",
            settings.agent_video_tool_execution_timeout_seconds,
            1,
            7200,
        ),
        "toolPollIntervalSeconds": runtime_float(
            context,
            "toolPollIntervalSeconds",
            tool_poll_interval_seconds,
            0.2,
            30.0,
        ),
        "toolStreamRelayEnabled": runtime_bool(
            context,
            "toolStreamRelayEnabled",
            settings.agent_tool_stream_relay_enabled,
        ),
        "productToolLoopEnabled": runtime_bool(
            context,
            "productToolLoopEnabled",
            settings.agent_product_tool_loop_enabled,
        ),
        "productToolLoopMaxCalls": runtime_int(
            context,
            "productToolLoopMaxCalls",
            product_tool_loop_max_calls,
            1,
            20,
        ),
        "productToolLoopFallbackToRouter": runtime_bool(
            context,
            "productToolLoopFallbackToRouter",
            settings.agent_product_tool_loop_fallback_to_router,
        ),
        "source": "backend" if runtime is not None else "environment",
    }


def runtime_int(context: RunContext | None, field: str, fallback: int, min_value: int, max_value: int) -> int:
    runtime = getattr(context, "runtimeSettings", None) if context is not None else None
    value = getattr(runtime, field, None) if runtime is not None else None
    if value is None:
        value = fallback
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = int(fallback)
    return max(min_value, min(parsed, max_value))


def runtime_float(context: RunContext | None, field: str, fallback: float, min_value: float, max_value: float) -> float:
    runtime = getattr(context, "runtimeSettings", None) if context is not None else None
    value = getattr(runtime, field, None) if runtime is not None else None
    if value is None:
        value = fallback
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        parsed = float(fallback)
    return max(min_value, min(parsed, max_value))


def runtime_bool(context: RunContext | None, field: str, fallback: bool) -> bool:
    runtime = getattr(context, "runtimeSettings", None) if context is not None else None
    value = getattr(runtime, field, None) if runtime is not None else None
    return fallback if value is None else bool(value)
