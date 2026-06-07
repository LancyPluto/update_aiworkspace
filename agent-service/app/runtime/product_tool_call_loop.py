from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any

from app.clients.model_client import ChatToolCall
from app.core.event_types import (
    TOOL_CALL_EXECUTED,
    TOOL_CALL_LOOP_COMPLETED,
    TOOL_CALL_LOOP_STARTED,
    TOOL_CALL_REJECTED,
    TOOL_CALL_REQUESTED,
)
from app.core.intent_router import Intent, IntentResult
from app.core.preferred_tool_bias import apply_preferred_tool_override, resolve_preferred_tool, sort_tools_with_preferred
from app.core.schemas import ChatMessage, RunContext, RunEventCreate, ToolDescriptor
from app.tools.registry import infer_output_modality, requested_output_modality


PRODUCT_TOOL_LOOP_SYSTEM_PROMPT = (
    "You are choosing whether to call one platform AI product tool for the user's latest request. "
    "Use a tool when the user asks to create, generate, transform, analyze with, or otherwise operate a listed AI tool. "
    "Do not call tools for greetings, identity questions, meta questions about previous turns, or explanations of failures. "
    "If a tool is needed, call exactly one tool with arguments derived from the user request and recent context. "
    "If no tool is needed, answer normally without a tool call."
)


@dataclass(slots=True)
class ProductToolCallLoopResult:
    intent: IntentResult | None = None
    answer: str = ""
    tool_call_count: int = 0
    rejected: bool = False
    rejection_reason: str | None = None


@dataclass(slots=True)
class _ToolAlias:
    alias: str
    tool: ToolDescriptor


@dataclass(slots=True)
class ProductToolCallLoopExecutor:
    backend: Any
    model: Any
    max_tool_calls: int = 1

    async def run(
        self,
        context: RunContext,
        *,
        max_tool_calls: int | None = None,
        workspace_memory_context: str = "",
    ) -> ProductToolCallLoopResult:
        chat_turn = getattr(self.model, "chat_turn", None)
        if not callable(chat_turn):
            raise TypeError("model does not support chat_turn")

        tool_defs, aliases = self._tool_definitions(context.availableTools, context)
        if not tool_defs:
            return ProductToolCallLoopResult()

        await self._event(
            context.runId,
            TOOL_CALL_LOOP_STARTED,
            {
                "kind": "product",
                "tools": [item["function"]["name"] for item in tool_defs],
                "toolCodes": [alias.tool.toolCode for alias in aliases.values()],
            },
        )
        messages = self._messages(context, workspace_memory_context=workspace_memory_context)
        turn = await chat_turn(messages, tools=tool_defs, tool_choice="auto")
        call_limit = max(1, int(max_tool_calls if max_tool_calls is not None else self.max_tool_calls))
        calls = list(getattr(turn, "tool_calls", []) or [])[:call_limit]
        if not calls:
            await self._event(
                context.runId,
                TOOL_CALL_LOOP_COMPLETED,
                {
                    "kind": "product",
                    "iterations": 1,
                    "executedToolCalls": 0,
                    "finishReason": getattr(turn, "finish_reason", None),
                },
            )
            return ProductToolCallLoopResult(answer=getattr(turn, "content", "") or "")

        call = calls[0]
        await self._event(
            context.runId,
            TOOL_CALL_REQUESTED,
            {
                "kind": "product",
                "id": call.id,
                "name": call.name,
                "arguments": _redact_large(call.arguments),
            },
        )
        selected = aliases.get(call.name)
        if selected is None:
            selected = next((alias for alias in aliases.values() if alias.tool.toolCode == call.name), None)
            if selected is None:
                return await self._reject(
                    context,
                    call,
                    "tool_not_available",
                    {
                        "availableToolAliases": sorted(aliases.keys())[:30],
                        "availableToolCodes": [alias.tool.toolCode for alias in aliases.values()][:30],
                    },
                )

        requested_modality = requested_output_modality(context.message)
        selected_modality = infer_output_modality(selected.tool)
        if requested_modality and selected_modality and requested_modality != selected_modality:
            return await self._reject(
                context,
                call,
                "output_modality_mismatch",
                {
                    "requestedOutputModality": requested_modality,
                    "selectedOutputModality": selected_modality,
                    "selectedToolCode": selected.tool.toolCode,
                },
            )

        intent = IntentResult(
            intent=Intent.TOOL_USE,
            confidence=0.9,
            selectedToolCode=selected.tool.toolCode,
            candidateToolCodes=[selected.tool.toolCode],
            decisionSource="tool_call_loop",
            reason="product_tool_call_selected",
            arguments=call.arguments if isinstance(call.arguments, dict) else {},
        )
        from app.core.preferred_tool_bias import apply_preferred_tool_override

        intent = apply_preferred_tool_override(context, intent)
        await self._event(
            context.runId,
            TOOL_CALL_EXECUTED,
            {
                "kind": "product",
                "id": call.id,
                "name": call.name,
                "success": True,
                "selectedToolCode": selected.tool.toolCode,
                "usedRawToolCode": call.name == selected.tool.toolCode,
                "expectedToolAlias": selected.alias,
                "arguments": _redact_large(intent.arguments),
            },
        )
        await self._event(
            context.runId,
            TOOL_CALL_LOOP_COMPLETED,
            {
                "kind": "product",
                "iterations": 1,
                "executedToolCalls": 1,
                "finishReason": getattr(turn, "finish_reason", None),
            },
        )
        return ProductToolCallLoopResult(intent=intent, tool_call_count=1)

    async def _reject(
        self,
        context: RunContext,
        call: ChatToolCall,
        reason: str,
        extra: dict[str, Any] | None = None,
    ) -> ProductToolCallLoopResult:
        payload = {
            "kind": "product",
            "id": call.id,
            "name": call.name,
            "reason": reason,
        }
        if extra:
            payload.update(extra)
        await self._event(context.runId, TOOL_CALL_REJECTED, payload)
        await self._event(
            context.runId,
            TOOL_CALL_LOOP_COMPLETED,
            {"kind": "product", "iterations": 1, "executedToolCalls": 0, "finishReason": "rejected"},
        )
        return ProductToolCallLoopResult(rejected=True, rejection_reason=reason)

    def _messages(self, context: RunContext, *, workspace_memory_context: str = "") -> list[ChatMessage]:
        messages = [ChatMessage(role="system", content=PRODUCT_TOOL_LOOP_SYSTEM_PROMPT)]
        if workspace_memory_context.strip():
            messages.append(
                ChatMessage(
                    role="system",
                    content=(
                        "Workspace long-term memory for this run. Use it when choosing product tools and "
                        "when filling low-risk tool arguments such as quality, aspect ratio, count, style, "
                        "or other user preferences. Current user instruction still has highest priority.\n\n"
                        f"{workspace_memory_context.strip()}"
                    ),
                )
            )
        if context.recentToolCalls:
            messages.append(
                ChatMessage(
                    role="system",
                    content="Recent tool calls:\n" + json.dumps(
                        [
                            {
                                "id": call.id,
                                "toolCode": call.toolCode,
                                "argumentsJson": call.argumentsJson,
                                "resourceType": call.resourceType,
                                "mediaUrls": call.mediaUrls,
                            }
                            for call in context.recentToolCalls[:5]
                        ],
                        ensure_ascii=False,
                    ),
                )
            )
        preferred = resolve_preferred_tool(context)
        if preferred is not None:
            messages.append(
                ChatMessage(
                    role="system",
                    content=(
                        f"The user explicitly selected preferred tool {preferred.toolCode} ({preferred.toolName}). "
                        "If a tool call is needed for this request, prefer that tool over alternatives."
                    ),
                )
            )
        messages.extend(context.history[-8:])
        messages.append(ChatMessage(role="user", content=context.message))
        return messages

    def _tool_definitions(
        self,
        tools: list[ToolDescriptor],
        context: RunContext | None = None,
    ) -> tuple[list[dict[str, Any]], dict[str, _ToolAlias]]:
        ordered = sort_tools_with_preferred(context, tools) if context is not None else tools
        aliases: dict[str, _ToolAlias] = {}
        definitions: list[dict[str, Any]] = []
        used_aliases: set[str] = set()
        for tool in ordered[:30]:
            alias = _alias_for_tool_code(tool.toolCode)
            base_alias = alias
            index = 2
            while alias in used_aliases:
                alias = f"{base_alias}_{index}"
                index += 1
            used_aliases.add(alias)
            aliases[alias] = _ToolAlias(alias=alias, tool=tool)
            definitions.append(
                {
                    "type": "function",
                    "function": {
                        "name": alias,
                        "description": _tool_description(tool),
                        "parameters": _tool_parameters(tool),
                    },
                }
            )
        return definitions, aliases

    async def _event(self, run_id: int, event_type: str, payload: dict[str, Any]) -> None:
        try:
            await self.backend.append_event(run_id, RunEventCreate(eventType=event_type, eventJson=payload))
        except Exception:
            pass


def _alias_for_tool_code(tool_code: str) -> str:
    sanitized = re.sub(r"[^A-Za-z0-9_]", "_", tool_code.strip())
    sanitized = re.sub(r"_+", "_", sanitized).strip("_")
    if not sanitized:
        sanitized = "tool"
    if not re.match(r"^[A-Za-z_]", sanitized):
        sanitized = f"tool_{sanitized}"
    return f"agent_tool__{sanitized}"[:64]


def _tool_description(tool: ToolDescriptor) -> str:
    parts = [
        f"Tool code: {tool.toolCode}",
        f"Tool name: {tool.toolName or tool.toolCode}",
    ]
    if tool.description:
        parts.append(f"Description: {tool.description}")
    modality = infer_output_modality(tool)
    if modality:
        parts.append(f"Output modality: {modality}")
    if tool.hints:
        parts.append(f"Agent hints: {json.dumps(tool.hints, ensure_ascii=False)}")
    return "\n".join(parts)[:1000]


def _tool_parameters(tool: ToolDescriptor) -> dict[str, Any]:
    schema = tool.inputSchema if isinstance(tool.inputSchema, dict) else {}
    properties = schema.get("properties")
    if schema.get("type") == "object" and isinstance(properties, dict) and properties:
        return schema
    return {
        "type": "object",
        "required": ["userRequest"],
        "properties": {
            "userRequest": {
                "type": "string",
                "description": "The user's latest request, preserving important subject, style, and constraints.",
            }
        },
    }


def _redact_large(value: Any) -> Any:
    text = json.dumps(value, ensure_ascii=False, default=str)
    if len(text) <= 1200:
        return value
    return {"preview": text[:1200], "truncated": True}
