"""Map model chat_turn output to IntentResult."""

from __future__ import annotations

from typing import Any

from app.clients.model_client import ChatTurnResult
from app.routing.types import Intent, IntentResult
from app.tools.registry import ToolRegistry, resolve_canonical_tool_code


def map_chat_turn_to_intent(
    turn: ChatTurnResult,
    *,
    aliases: dict[str, Any],
    tools: list,
) -> IntentResult:
    available_tool_codes = {tool.toolCode for tool in tools}
    calls = list(getattr(turn, "tool_calls", []) or [])
    if not calls:
        content = (getattr(turn, "content", "") or "").strip()
        return IntentResult(
            intent=Intent.GENERAL_CHAT,
            confidence=0.85 if content else 0.6,
            decisionSource="unified_router",
            reason="unified_router_chat",
        )

    call = calls[0]
    name = (call.name or "").strip()
    arguments = call.arguments if isinstance(call.arguments, dict) else {}

    alias = aliases.get(name)
    tool_code = alias.tool.toolCode if alias is not None else None
    if not tool_code:
        tool_code = resolve_canonical_tool_code(name, tools) or name

    if tool_code in available_tool_codes:
        return IntentResult(
            intent=Intent.TOOL_USE,
            confidence=0.92,
            selectedToolCode=tool_code,
            candidateToolCodes=[tool_code],
            decisionSource="unified_router",
            reason="unified_router_tool_call",
            arguments=arguments,
        )

    return IntentResult(
        intent=Intent.GENERAL_CHAT,
        confidence=0.55,
        decisionSource="unified_router",
        reason=f"unified_router_unknown_tool:{name}",
    )
