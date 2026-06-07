"""Helpers for user-selected preferred tool routing bias."""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.core.schemas import RunContext, ToolDescriptor
from app.tools.registry import ToolRegistry, infer_output_modality

if TYPE_CHECKING:
    from app.core.intent_router import IntentResult


def preferred_tool_code(context: RunContext) -> str | None:
    code = getattr(context, "preferredToolCode", None)
    if code is None or not str(code).strip():
        return None
    return str(code).strip()


def resolve_preferred_tool(context: RunContext) -> ToolDescriptor | None:
    code = preferred_tool_code(context)
    if not code:
        return None
    return ToolRegistry(context).get(code)


def message_suggests_tool_use(message: str) -> bool:
    router = _intent_router()
    stripped = (message or "").strip()
    if not stripped:
        return False
    if router._is_short_chat(stripped.lower()):
        return False
    return router._looks_like_tool_request(stripped)


def inject_preferred_tool_hint(context: RunContext, result: IntentResult) -> IntentResult:
    """Attach preferred tool as routing hint without forcing infrastructure short-circuit."""
    from app.core.intent_router import Intent

    preferred = preferred_tool_code(context)
    if not preferred or not message_suggests_tool_use(context.message):
        return result
    if result.intent in {Intent.FILE_ANALYSIS, Intent.UNSUPPORTED, Intent.SECURITY_REJECTED}:
        return result
    if not resolve_preferred_tool(context):
        return result

    candidate_codes = [preferred, *[code for code in result.candidateToolCodes if code != preferred]][:3]
    return result.model_copy(
        update={
            "selectedToolCode": preferred if result.intent == Intent.TOOL_USE else result.selectedToolCode or preferred,
            "candidateToolCodes": candidate_codes,
        }
    )


def apply_preferred_tool_override(context: RunContext, result: IntentResult) -> IntentResult:
    """When LLM chose tool_use, prefer the user-selected tool if available."""
    from app.core.intent_router import Intent

    if result.intent != Intent.TOOL_USE:
        return result
    preferred = resolve_preferred_tool(context)
    if preferred is None:
        return result
    if result.selectedToolCode == preferred.toolCode:
        return result

    selected_descriptor = next(
        (tool for tool in context.availableTools if tool.toolCode == result.selectedToolCode),
        None,
    )
    preferred_modality = infer_output_modality(preferred)
    selected_modality = infer_output_modality(selected_descriptor) if selected_descriptor else None
    if selected_modality and preferred_modality and selected_modality != preferred_modality:
        return result

    candidate_codes = [preferred.toolCode, *[code for code in result.candidateToolCodes if code != preferred.toolCode]][:3]
    return result.model_copy(
        update={
            "selectedToolCode": preferred.toolCode,
            "candidateToolCodes": candidate_codes,
            "reason": f"{result.reason}; preferred_tool_applied",
        }
    )


def sort_tools_with_preferred(context: RunContext, tools: list[ToolDescriptor]) -> list[ToolDescriptor]:
    preferred = preferred_tool_code(context)
    if not preferred:
        return tools
    preferred_tools = [tool for tool in tools if tool.toolCode == preferred]
    others = [tool for tool in tools if tool.toolCode != preferred]
    return preferred_tools + others


_INTENT_ROUTER = None


def _intent_router():
    global _INTENT_ROUTER
    if _INTENT_ROUTER is None:
        from app.core.intent_router import IntentRouter

        _INTENT_ROUTER = IntentRouter()
    return _INTENT_ROUTER
