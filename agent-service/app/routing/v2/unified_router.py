"""Single-call Function Calling semantic router."""

from __future__ import annotations

import logging
from typing import Any

from app.config import settings
from app.core.event_types import ROUTER_FALLBACK, ROUTER_SELECTED, ROUTER_STARTED
from app.core.schemas import RunContext, RunEventCreate
from app.observability.model_request_audit import model_audit_scope
from app.routing.types import Intent, IntentResult
from app.routing.v2.router_result import map_chat_turn_to_intent
from app.routing.v2.router_tools import build_router_tool_defs
from app.routing.v2.routing_context import build_routing_messages
from app.routing.v2.thread_state import build_thread_state
from app.runtime.tool_disclosure import EXPAND_TOOL
from app.tools.registry import ToolRegistry, resolve_canonical_tool_code

LOGGER = logging.getLogger(__name__)


class UnifiedSemanticRouter:
    """One chat_turn with disclosed tools — replaces JSON LLMClassifier + ToolResolver."""

    def __init__(self, backend_client, model_client) -> None:
        self.backend = backend_client
        self.model = model_client

    async def route(
        self,
        context: RunContext,
        *,
        workspace_memory_context: str = "",
    ) -> IntentResult | None:
        if not context.availableTools:
            return None
        chat_turn = getattr(self.model, "chat_turn", None)
        if not callable(chat_turn):
            LOGGER.warning("unified router unavailable: model lacks chat_turn runId=%s", context.runId)
            return None

        thread_state = build_thread_state(context)
        messages = build_routing_messages(
            context,
            thread_state=thread_state,
            workspace_memory_context=workspace_memory_context,
        )
        tool_defs, aliases = build_router_tool_defs(context.availableTools, context)
        if not tool_defs:
            return None

        prompt_bytes = sum(len((m.content or "").encode("utf-8")) for m in messages)
        await self._emit_started(context, prompt_bytes=prompt_bytes, tool_count=len(tool_defs))

        try:
            with model_audit_scope("route.selection", 1):
                turn = await chat_turn(messages, tools=tool_defs, tool_choice="auto")
        except Exception as exc:
            LOGGER.warning("unified router failed runId=%s error=%s", context.runId, exc)
            await self._emit_fallback(context, str(exc), prompt_bytes=prompt_bytes)
            return None

        calls = list(getattr(turn, "tool_calls", []) or [])
        if calls and calls[0].name == EXPAND_TOOL:
            code = str((calls[0].arguments or {}).get("toolCode") or "").strip()
            if code and ToolRegistry(context).get(code):
                tool_defs, aliases = build_router_tool_defs(
                    context.availableTools,
                    context,
                    expanded_codes={code},
                )
                with model_audit_scope("route.disclosure_retry", 2):
                    turn = await chat_turn(messages, tools=tool_defs, tool_choice="auto")

        result = map_chat_turn_to_intent(turn, aliases=aliases, tools=context.availableTools)

        if result.intent == Intent.TOOL_USE and result.selectedToolCode:
            resolved = resolve_canonical_tool_code(result.selectedToolCode, context.availableTools)
            if resolved:
                result = result.model_copy(update={"selectedToolCode": resolved, "candidateToolCodes": [resolved]})
            await self._emit_selected(context, result)
            return result

        if result.intent == Intent.GENERAL_CHAT and thread_state.has_active_task:
            pending_code = thread_state.active_tool_code
            available_codes = {tool.toolCode for tool in context.availableTools}
            if pending_code and pending_code in available_codes:
                return IntentResult(
                    intent=Intent.TOOL_USE,
                    confidence=0.88,
                    selectedToolCode=pending_code,
                    candidateToolCodes=[pending_code],
                    decisionSource="unified_router",
                    reason="unified_router_thread_continuation",
                    arguments={"userRequest": context.message},
                )

        await self._emit_selected(context, result)
        return result

    async def _emit_started(self, context: RunContext, *, prompt_bytes: int, tool_count: int) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=ROUTER_STARTED,
                eventText="Unified semantic router started",
                eventJson={
                    "kind": "unified_fc",
                    "promptBytes": prompt_bytes,
                    "estimatedRouterTokens": prompt_bytes // 4,
                    "disclosedToolCount": tool_count,
                },
            ),
        )

    async def _emit_selected(self, context: RunContext, result: IntentResult) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=ROUTER_SELECTED,
                eventText=result.selectedToolCode or result.intent.value,
                eventJson={
                    "kind": "unified_fc",
                    "intent": result.intent.value,
                    "confidence": result.confidence,
                    "selectedToolCode": result.selectedToolCode,
                    "candidateToolCodes": result.candidateToolCodes,
                    "reason": result.reason,
                    "decisionSource": result.decisionSource,
                },
            ),
        )

    async def _emit_fallback(self, context: RunContext, error: str, *, prompt_bytes: int) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=ROUTER_FALLBACK,
                eventText="Unified router failed",
                eventJson={
                    "kind": "unified_fc",
                    "reason": "router_exception",
                    "error": error,
                    "promptBytes": prompt_bytes,
                    "failureClass": "connection",
                },
            ),
        )
