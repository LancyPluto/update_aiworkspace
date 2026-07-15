import logging

from app.core.event_types import CONVERSATION_SUMMARY_UPDATED
from app.core.schemas import ChatMessage, RunContext, RunEventCreate
from app.observability.model_request_audit import model_audit_scope
from app.runtime.context_manager import ContextManager, ROLLING_SUMMARY_PROMPT, token_middle_truncate

logger = logging.getLogger(__name__)


async def ensure_rolling_conversation_summary(
    *,
    context: RunContext,
    context_manager: ContextManager,
    model,
    backend,
) -> RunContext:
    """Merge evicted history into the durable rolling conversation summary."""
    split = context_manager.split_history(context.history)
    candidates = _summary_candidates(split.summary_candidates)
    if not candidates:
        return context

    existing = (context.conversationSummary or "").strip()
    candidate_text = _format_messages_for_summary(candidates)
    if not candidate_text:
        return context

    summary = await _build_summary(
        model=model,
        existing_summary=existing,
        candidate_text=candidate_text,
        context_manager=context_manager,
    )
    if not summary or summary == existing:
        return context

    await _persist_summary(context, backend, summary, len(candidates))
    return context.model_copy(update={"conversationSummary": summary})


def _summary_candidates(messages: list[ChatMessage]) -> list[ChatMessage]:
    result: list[ChatMessage] = []
    for message in messages:
        role = (message.role or "").strip().lower()
        content = (message.content or "").strip()
        if not content:
            continue
        if role in {"system", "tool"} and not _has_user_visible_value(content):
            continue
        result.append(message)
    return result


def _has_user_visible_value(content: str) -> bool:
    lowered = content.lower()
    return not (
        lowered.startswith("<!-- frozen memory snapshot")
        or "<sessionstate" in lowered
        or lowered.startswith("<conversation_summary>")
    )


def _format_messages_for_summary(messages: list[ChatMessage]) -> str:
    lines: list[str] = []
    for message in messages:
        role = (message.role or "user").strip().lower()
        if role in {"assistant", "ai"}:
            label = "assistant"
        elif role == "tool":
            label = "tool"
        elif role == "system":
            label = "system"
        else:
            label = "user"
        content = token_middle_truncate(message.content or "", 500)
        if content.strip():
            lines.append(f"{label}: {content.strip()}")
    return "\n\n".join(lines)


async def _build_summary(
    *,
    model,
    existing_summary: str,
    candidate_text: str,
    context_manager: ContextManager,
) -> str:
    prompt = (
        f"{ROLLING_SUMMARY_PROMPT}\n\n"
        f"旧摘要：\n{existing_summary or '（无）'}\n\n"
        f"新增旧消息：\n{candidate_text}"
    )
    try:
        with model_audit_scope("memory.summary"):
            if hasattr(model, "chat"):
                summary = await model.chat([ChatMessage(role="system", content=prompt)])
            else:
                turn = await model.chat_turn([ChatMessage(role="system", content=prompt)], tools=None)
                summary = getattr(turn, "content", "")
    except Exception:
        logger.exception("Failed to build rolling conversation summary; using deterministic fallback")
        summary = _fallback_summary(existing_summary, candidate_text)
    summary = _strip_summary_wrapper(summary)
    if not summary:
        summary = _fallback_summary(existing_summary, candidate_text)
    return token_middle_truncate(
        summary,
        context_manager.summary_token_limit,
        context_manager.head_ratio,
        model_name=context_manager.model_name,
    ).strip()


def _strip_summary_wrapper(summary: str | None) -> str:
    text = (summary or "").strip()
    if text.startswith("<Conversation_Summary>") and text.endswith("</Conversation_Summary>"):
        text = text.removeprefix("<Conversation_Summary>").removesuffix("</Conversation_Summary>")
    return text.strip()


def _fallback_summary(existing_summary: str, candidate_text: str) -> str:
    parts: list[str] = []
    if existing_summary.strip():
        parts.append(existing_summary.strip())
    parts.append(
        "【关键进展】\n"
        + token_middle_truncate(candidate_text.strip(), 1200)
    )
    return "\n\n".join(parts).strip()


async def _persist_summary(context: RunContext, backend, summary: str, candidate_count: int) -> None:
    try:
        if hasattr(backend, "update_conversation_summary"):
            await backend.update_conversation_summary(context.runId, summary)
        await backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=CONVERSATION_SUMMARY_UPDATED,
                eventText="conversation summary updated",
                eventJson={
                    "summaryCandidateMessages": candidate_count,
                    "summaryLength": len(summary),
                },
            ),
        )
    except Exception:
        logger.exception("Failed to persist rolling conversation summary, runId=%s", context.runId)
