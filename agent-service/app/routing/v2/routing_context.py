"""Assemble ChatMessage[] for unified semantic routing."""

from __future__ import annotations

import json

from app.config import settings
from app.core.preferred_tool_bias import resolve_preferred_tool
from app.core.schemas import ChatMessage, RunContext
from app.routing.attachment_signals import attachment_signal_payload, build_attachment_signal
from app.core.attachment_catalog import build_reference_plan, reference_mentions_payload, user_message_for_llm
from app.routing.context_builder import capability_flags_payload, default_capability_flags
from app.routing.v2.thread_state import RoutingThreadState, format_thread_state_block
from app.runtime.context_manager import ContextManager, middle_truncate
from app.runtime.tool_disclosure import format_tool_catalog


UNIFIED_ROUTER_SYSTEM_PROMPT = (
    "You are the semantic router for an AI tool marketplace agent. "
    "Read the full conversation and decide whether to call exactly one platform tool or answer in plain chat. "
    "Call a tool when the user wants to create, generate, transform, or operate a listed AI product. "
    "Do not call tools for greetings, meta questions, or session recap unless a tool is explicitly needed. "
    "When an active routing thread exists, treat short user replies as parameter completion for that tool. "
    "When ready media attachments exist and the user wants generation/editing, use them as reference inputs. "
    "If file analysis capability is disabled, route document-reading requests to plain chat explaining unsupported. "
    "Prefer tools in the disclosed shortlist; use expand_tool if you need a catalog tool's full schema."
)


def _clip_history_content(content: str) -> str:
    limit = max(200, int(getattr(settings, "agent_router_history_clip", 800)))
    return middle_truncate(content or "", limit)


def _normalize_role(role: str) -> str:
    normalized = (role or "").strip().lower()
    if normalized in {"assistant", "ai"}:
        return "assistant"
    if normalized in {"user", "human"}:
        return "user"
    if normalized == "system":
        return "system"
    return "user"


def build_routing_messages(
    context: RunContext,
    *,
    thread_state: RoutingThreadState,
    workspace_memory_context: str = "",
) -> list[ChatMessage]:
    messages: list[ChatMessage] = [ChatMessage(role="system", content=UNIFIED_ROUTER_SYSTEM_PROMPT)]

    flags = default_capability_flags()
    signal = build_attachment_signal(context)
    facts = {
        "attachmentSignals": attachment_signal_payload(signal),
        "capabilityFlags": capability_flags_payload(flags),
    }
    plan = build_reference_plan(context)
    if plan.mentions:
        facts["referenceMentions"] = reference_mentions_payload(plan)
        facts["explicitReferenceCount"] = len(plan.ordered_urls)
    messages.append(ChatMessage(role="system", content=f"Routing facts:\n{json.dumps(facts, ensure_ascii=False)}"))

    thread_block = format_thread_state_block(thread_state)
    if thread_block:
        messages.append(ChatMessage(role="system", content=thread_block))

    if settings.agent_tool_disclosure_enabled and context.availableTools:
        catalog = format_tool_catalog(context.availableTools)
        if catalog:
            messages.append(ChatMessage(role="system", content=catalog))

    if workspace_memory_context.strip():
        clipped = middle_truncate(workspace_memory_context.strip(), 1200)
        messages.append(
            ChatMessage(
                role="system",
                content=(
                    "Workspace memory (use for low-risk argument defaults; user instruction wins):\n"
                    f"{clipped}"
                ),
            )
        )

    preferred = resolve_preferred_tool(context)
    if preferred is not None:
        messages.append(
            ChatMessage(
                role="system",
                content=(
                    f"User explicitly selected preferred tool {preferred.toolCode} "
                    f"({preferred.toolName}). Prefer it when a tool call is needed."
                ),
            )
        )

    cm = ContextManager.from_settings(settings)
    for item in cm.build_history(context.history):
        role = _normalize_role(item.role)
        content = _clip_history_content(item.content or "")
        if content:
            messages.append(ChatMessage(role=role, content=content))

    messages.append(ChatMessage(role="user", content=user_message_for_llm(context)))
    return messages
