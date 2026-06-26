from __future__ import annotations

import logging
from typing import Any

from app.config import settings
from app.core.preferred_tool_bias import apply_preferred_tool_override, resolve_preferred_tool, sort_tools_with_preferred
from app.core.schemas import ChatMessage, RunContext
from app.routing.semantic_tool_recall import recall_tool_codes
from app.routing.types import Intent, IntentResult
from app.runtime.product_tool_call_loop import (
    PRODUCT_TOOL_LOOP_SYSTEM_PROMPT,
    _ToolAlias,
    build_tool_definitions,
)
from app.runtime.tool_disclosure import EXPAND_TOOL, expand_tool_definition
from app.tools.registry import ToolRegistry, infer_output_modality, requested_output_modality, resolve_canonical_tool_code

LOGGER = logging.getLogger(__name__)

TOOL_RESOLVER_SYSTEM_PROMPT = (
    PRODUCT_TOOL_LOOP_SYSTEM_PROMPT
    + " When attachments are present, treat ready images/videos as reference inputs for generation or editing tools."
)


class ToolResolver:
    """Layer 3 function-calling tool selection — refines or verifies LLM classifier tool_use decisions."""

    HIGH_CONFIDENCE_THRESHOLD = 0.88

    def __init__(self, model_client) -> None:
        self.model = model_client

    async def resolve(
        self,
        context: RunContext,
        classifier_result: IntentResult,
        *,
        workspace_memory_context: str = "",
        verify_only: bool = False,
    ) -> IntentResult | None:
        if classifier_result.intent != Intent.TOOL_USE:
            return None

        if verify_only and classifier_result.selectedToolCode and classifier_result.confidence >= self.HIGH_CONFIDENCE_THRESHOLD:
            return classifier_result

        chat_turn = getattr(self.model, "chat_turn", None)
        if not callable(chat_turn):
            LOGGER.warning("tool resolver skipped: model does not support chat_turn")
            return classifier_result if classifier_result.selectedToolCode else None

        recalled_codes = set(recall_tool_codes(context, limit=settings.agent_router_candidate_limit))
        if classifier_result.selectedToolCode:
            recalled_codes.add(classifier_result.selectedToolCode)
        for code in classifier_result.candidateToolCodes:
            recalled_codes.add(code)

        tools = [
            tool
            for tool in sort_tools_with_preferred(context, context.availableTools)
            if tool.toolCode in recalled_codes or not recalled_codes
        ]
        if not tools:
            tools = list(context.availableTools)

        tool_defs, aliases = build_tool_definitions(tools, context)
        if settings.agent_tool_disclosure_enabled:
            tool_defs = [*tool_defs, expand_tool_definition()]

        messages = self._messages(context, workspace_memory_context=workspace_memory_context)
        try:
            turn = await chat_turn(messages, tools=tool_defs, tool_choice="auto")
        except Exception as exc:
            LOGGER.warning("tool resolver failed runId=%s error=%s", context.runId, exc)
            return classifier_result if classifier_result.selectedToolCode else None

        calls = list(getattr(turn, "tool_calls", []) or [])[:1]
        if not calls:
            return classifier_result if classifier_result.selectedToolCode else None

        call = calls[0]
        if call.name == EXPAND_TOOL:
            code = str((call.arguments or {}).get("toolCode") or "").strip()
            if code and ToolRegistry(context).get(code):
                expanded_tools = [tool for tool in context.availableTools if tool.toolCode == code or tool in tools]
                if not expanded_tools:
                    expanded_tools = context.availableTools
                tool_defs, aliases = build_tool_definitions(expanded_tools, context)
                turn = await chat_turn(messages, tools=tool_defs, tool_choice="auto")
                calls = list(getattr(turn, "tool_calls", []) or [])[:1]
                if not calls:
                    return classifier_result if classifier_result.selectedToolCode else None
                call = calls[0]

        selected = self._resolve_alias(call, aliases, context)
        if selected is None:
            return classifier_result if classifier_result.selectedToolCode else None

        requested_modality = requested_output_modality(context.message)
        selected_modality = infer_output_modality(selected.tool)
        if requested_modality and selected_modality and requested_modality != selected_modality:
            return classifier_result if classifier_result.selectedToolCode else None

        intent = IntentResult(
            intent=Intent.TOOL_USE,
            confidence=max(classifier_result.confidence, 0.9),
            selectedToolCode=selected.tool.toolCode,
            candidateToolCodes=[selected.tool.toolCode, *classifier_result.candidateToolCodes][:3],
            decisionSource="tool_resolver",
            reason="tool_resolver_selected",
            arguments=call.arguments if isinstance(call.arguments, dict) else {},
            attachmentUsage=classifier_result.attachmentUsage,
            followupPatch=classifier_result.followupPatch,
            missingFields=classifier_result.missingFields,
            requiresConfirmation=classifier_result.requiresConfirmation,
        )
        return apply_preferred_tool_override(context, intent)

    def _resolve_alias(
        self,
        call: Any,
        aliases: dict[str, _ToolAlias],
        context: RunContext,
    ) -> _ToolAlias | None:
        selected = aliases.get(call.name)
        if selected is not None:
            return selected
        selected = next((alias for alias in aliases.values() if alias.tool.toolCode == call.name), None)
        if selected is not None:
            return selected
        resolved_code = resolve_canonical_tool_code(call.name, context.availableTools)
        if resolved_code:
            from app.runtime.product_tool_call_loop import _alias_for_tool_code

            return aliases.get(_alias_for_tool_code(resolved_code))
        return None

    def _messages(self, context: RunContext, *, workspace_memory_context: str = "") -> list[ChatMessage]:
        from app.runtime.context_manager import ContextManager

        messages = [ChatMessage(role="system", content=TOOL_RESOLVER_SYSTEM_PROMPT)]
        if workspace_memory_context.strip():
            messages.append(
                ChatMessage(
                    role="system",
                    content=f"Workspace memory:\n{workspace_memory_context.strip()}",
                )
            )
        preferred = resolve_preferred_tool(context)
        if preferred is not None:
            messages.append(
                ChatMessage(
                    role="system",
                    content=f"User selected preferred tool {preferred.toolCode} ({preferred.toolName}).",
                )
            )
        messages.extend(
            ContextManager.from_settings(settings, context.runtimeSettings).build_context_messages(
                context.history,
                context.conversationSummary,
            )
        )
        messages.append(ChatMessage(role="user", content=context.message))
        return messages
