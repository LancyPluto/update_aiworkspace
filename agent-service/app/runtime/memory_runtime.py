from __future__ import annotations

import logging
import json
import re
from typing import Any

from app.config import settings
from app.core.event_types import (
    MEMORY_CANDIDATE_CREATED,
    MEMORY_CONSOLIDATED,
    MEMORY_CURATOR_STARTED,
    MEMORY_REJECTED,
    MEMORY_RETRIEVED,
)
from app.core.schemas import ChatMessage, RunContext, RunEventCreate, SessionSearchItem, WorkspaceMemoryItem
from app.runtime.memory_curator import MemoryCuratorService, build_memory_metadata
from app.tools.memory_tool import MemoryTool, _safety_rejection_reason

LOGGER = logging.getLogger(__name__)


AUTO_PROFILE_TITLE = "用户画像与偏好摘要"


class WorkspaceMemoryRuntime:
    def __init__(self, backend, curator: MemoryCuratorService | None = None, model_client=None) -> None:
        self.backend = backend
        self.curator = curator or MemoryCuratorService()
        self.model_client = model_client

    async def fetch_items(self, context: RunContext) -> list[WorkspaceMemoryItem]:
        workspace_id = context.workspaceId
        if workspace_id is None:
            return []
        try:
            view = memory_view_for_context(context)
            items = await self.backend.retrieve_workspace_memory(
                workspace_id=workspace_id,
                query=context.message,
                limit=memory_retrieval_limit(context),
                view=view,
            )
            if items:
                await self.backend.append_event(
                    context.runId,
                    RunEventCreate(
                        eventType=MEMORY_RETRIEVED,
                        eventJson={
                            "count": len(items),
                            "view": view,
                            "memoryIds": [item.id for item in items],
                            "types": [item.memoryType for item in items],
                            "items": memory_trace_items(items),
                        },
                    ),
                )
            return items
        except Exception:
            return []

    async def fetch_context(self, context: RunContext) -> str:
        items = await self.fetch_items(context)
        memory_context = format_workspace_memory_context(items)
        session_context = await self.fetch_session_search_context(context)
        if session_context:
            memory_context = f"{memory_context}\n\n{session_context}".strip()
        retrieval_prompt = context.memorySettings.retrievalPrompt if context.memorySettings is not None else None
        if memory_context and retrieval_prompt and retrieval_prompt.strip():
            return f"{retrieval_prompt.strip()}\n\n{memory_context}"
        return memory_context

    async def fetch_session_search_context(self, context: RunContext) -> str:
        if not looks_like_session_search_request(context.message):
            return ""
        if not hasattr(self.backend, "search_session"):
            return ""
        try:
            items = await self.backend.search_session(
                user_id=context.userId,
                session_id=context.sessionId,
                query=context.message,
                limit=6,
            )
        except Exception:
            return ""
        return format_session_search_context(items)

    async def curate_after_run(
        self,
        context: RunContext,
        answer: str,
        *,
        tool_result: dict[str, Any] | None = None,
        memory_tool_executed: bool = False,
    ) -> None:
        if not context.workspaceId or not memory_auto_save_enabled(context):
            return
        try:
            existing = await self.fetch_items(context)
            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=MEMORY_CURATOR_STARTED,
                    eventJson={"existingCount": len(existing), "mode": "light"},
                ),
            )
            if memory_tool_executed:
                await self.maybe_consolidate(context, answer, existing)
                return

            decision = self.curator.decide(context, answer, existing_items=existing, tool_result=tool_result)
            payload = {
                "action": decision.action,
                "memoryType": decision.memory_type,
                "title": decision.title,
                "importance": decision.importance,
                "confidence": decision.confidence,
                "reason": decision.reason,
            }
            if decision.action == "none":
                await self.backend.append_event(
                    context.runId,
                    RunEventCreate(eventType=MEMORY_REJECTED, eventText=decision.reason, eventJson=payload),
                )
                return
            if decision.action == "add" and decision.confidence >= memory_consolidation_min_confidence(context):
                tool = MemoryTool(self.backend, context.workspaceId, context.userId, run_id=context.runId)
                await tool.add_memory(
                    memory_type=decision.memory_type,
                    title=decision.title,
                    content=decision.content,
                    source_run_id=context.runId,
                )
                return
            if decision.confidence < memory_candidate_confidence_threshold(context):
                await self.backend.append_event(
                    context.runId,
                    RunEventCreate(
                        eventType=MEMORY_REJECTED,
                        eventText=decision.reason,
                        eventJson={**payload, "belowCandidateThreshold": True},
                    ),
                )
                return
            await self.backend.create_workspace_memory_candidate(
                workspace_id=context.workspaceId,
                user_id=context.userId,
                action=decision.action,
                memory_type=decision.memory_type,
                title=decision.title,
                content=decision.content,
                source_run_id=context.runId,
                importance=decision.importance,
                confidence=decision.confidence,
                reason=decision.reason,
                metadata_json=build_memory_metadata(decision),
            )
            await self.emit_memory_candidate(
                context,
                title=decision.title,
                content=decision.content,
                decision_json=payload,
            )
        except Exception as exc:
            LOGGER.debug("memory curator failed runId=%s error=%s", context.runId, exc)

    async def emit_memory_candidate(self, context: RunContext, *, title: str, content: str, decision_json: dict[str, Any]) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=MEMORY_CANDIDATE_CREATED,
                eventText=title,
                eventJson={"title": title, "content": content, "sourceRunId": context.runId, **decision_json},
            ),
        )

    async def maybe_consolidate(self, context: RunContext, answer: str, existing: list[WorkspaceMemoryItem]) -> None:
        if not memory_consolidation_enabled(context):
            return
        user_turns = [message.content for message in context.history if message.role.lower() == "user"]
        user_turns.append(context.message)
        combined = "\n".join(user_turns)
        existing_profile = _find_auto_profile_summary(existing)
        trigger = memory_consolidation_trigger(context, user_turns=user_turns, char_count=len(combined), existing_profile=existing_profile)
        if not trigger["shouldRun"]:
            return
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=MEMORY_CURATOR_STARTED,
                eventJson={
                    "mode": "llm" if memory_consolidation_llm_enabled(context) and self.model_client is not None else "heuristic",
                    "stage": "consolidation",
                    **trigger,
                },
            ),
        )
        summary = ""
        confidence = memory_consolidation_min_confidence(context)
        reason = "heuristic_fallback"
        candidates: list[dict[str, Any]] = []
        if memory_consolidation_llm_enabled(context) and self.model_client is not None:
            decision = await self._consolidate_with_llm(context, answer, existing, trigger)
            if decision is not None:
                summary = decision.get("profileContent", "")
                confidence = _as_float(decision.get("confidence"), 0.0)
                reason = str(decision.get("reason") or "llm_profile_consolidation")
                candidates = _normalize_candidate_preferences(decision.get("candidatePreferences"))
                if not bool(decision.get("shouldUpdateProfile")):
                    await self._emit_memory_rejected(context, reason, {"mode": "llm", "decision": decision, **trigger})
                    await self._persist_candidate_preferences(context, candidates, existing=existing, reason=reason)
                    return
            else:
                reason = "llm_invalid_or_failed"
        if not summary:
            summary = build_consolidated_memory_summary(context, answer)
            if summary:
                reason = "heuristic_fallback"
        summary = safe_memory_text(summary, 1600)
        if not summary:
            return
        rejection_reason = _safety_rejection_reason(summary)
        if rejection_reason:
            await self._emit_memory_rejected(context, rejection_reason, {"mode": "consolidation", **trigger})
            return
        if confidence < memory_consolidation_min_confidence(context):
            await self._emit_memory_rejected(
                context,
                "below_consolidation_confidence_threshold",
                {"confidence": confidence, "reason": reason, **trigger},
            )
            await self._persist_candidate_preferences(context, candidates, existing=existing, reason=reason)
            return
        metadata_json = build_consolidation_metadata(context, trigger, confidence=confidence, reason=reason)
        if existing_profile is None:
            await self.backend.create_workspace_memory(
                workspace_id=context.workspaceId,
                user_id=context.userId,
                memory_type="user_profile",
                title=AUTO_PROFILE_TITLE,
                content=summary,
                source_run_id=context.runId,
                importance=7,
                confidence=confidence,
                tags_json=json.dumps(["auto_profile", "consolidation"], ensure_ascii=False),
                metadata_json=metadata_json,
            )
            action = "add"
        else:
            update = getattr(self.backend, "update_workspace_memory", None)
            if callable(update):
                await update(
                    workspace_id=context.workspaceId,
                    memory_id=existing_profile.id,
                    memory_type=existing_profile.memoryType,
                    title=existing_profile.title or AUTO_PROFILE_TITLE,
                    content=summary,
                    metadata_json=metadata_json,
                )
            else:
                tool = MemoryTool(self.backend, context.workspaceId, context.userId, run_id=context.runId)
                await tool.replace_memory(
                    memory_type=existing_profile.memoryType,
                    new_title=existing_profile.title or AUTO_PROFILE_TITLE,
                    new_content=summary,
                )
            action = "replace"
        await self._persist_candidate_preferences(context, candidates, existing=existing, reason=reason)
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=MEMORY_CONSOLIDATED,
                eventText=AUTO_PROFILE_TITLE,
                eventJson={
                    "action": action,
                    "mode": "llm" if reason != "heuristic_fallback" else "heuristic",
                    "confidence": confidence,
                    "reason": reason,
                    "turnCount": len(user_turns),
                    "charCount": len(combined),
                    "estimatedInputTokens": memory_context_token_count(context),
                    "recentToolCount": successful_recent_tool_count(context),
                    "triggerReasons": trigger["triggerReasons"],
                },
            ),
        )

    async def _consolidate_with_llm(
        self,
        context: RunContext,
        answer: str,
        existing: list[WorkspaceMemoryItem],
        trigger: dict[str, Any],
    ) -> dict[str, Any] | None:
        messages = build_consolidation_messages(context, answer, existing, trigger)
        try:
            raw = await self.model_client.chat(messages)
        except Exception as exc:
            LOGGER.debug("llm memory consolidation failed runId=%s error=%s", context.runId, exc)
            await self._emit_memory_rejected(context, "llm_request_failed", {"error": str(exc), **trigger})
            return None
        decision = parse_consolidation_json(raw)
        if decision is None:
            await self._emit_memory_rejected(context, "llm_invalid_json", {"raw": safe_memory_text(raw, 800), **trigger})
        return decision

    async def _persist_candidate_preferences(
        self,
        context: RunContext,
        candidates: list[dict[str, Any]],
        *,
        existing: list[WorkspaceMemoryItem],
        reason: str,
    ) -> None:
        if not context.workspaceId or not candidates:
            return
        existing_keys = {_memory_duplicate_key(item.title, item.content) for item in existing}
        for candidate in candidates[:5]:
            confidence = _as_float(candidate.get("confidence"), 0.0)
            if confidence < memory_candidate_confidence_threshold(context):
                continue
            title = safe_memory_text(candidate.get("title") or "候选用户偏好", 160)
            content = safe_memory_text(candidate.get("content") or "", 1200)
            if _memory_duplicate_key(title, content) in existing_keys:
                continue
            rejection_reason = _safety_rejection_reason(title) or _safety_rejection_reason(content)
            if not content or rejection_reason:
                await self._emit_memory_rejected(context, rejection_reason or "empty_candidate_preference", {"candidate": candidate})
                continue
            await self.backend.create_workspace_memory_candidate(
                workspace_id=context.workspaceId,
                user_id=context.userId,
                action="candidate",
                memory_type="preference",
                title=title,
                content=content,
                source_run_id=context.runId,
                importance=max(1, min(int(candidate.get("importance") or 6), 10)),
                confidence=confidence,
                reason=reason,
                metadata_json=json.dumps({"curator": "llm_profile_consolidation", "reason": reason}, ensure_ascii=False),
            )
            await self.emit_memory_candidate(
                context,
                title=title,
                content=content,
                decision_json={"action": "candidate", "memoryType": "preference", "confidence": confidence, "reason": reason},
            )

    async def _emit_memory_rejected(self, context: RunContext, reason: str, payload: dict[str, Any]) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(eventType=MEMORY_REJECTED, eventText=reason, eventJson={"reason": reason, **payload}),
        )


def format_workspace_memory_context(items: list[WorkspaceMemoryItem]) -> str:
    active_items = [item for item in items if item.content.strip() or item.title.strip()]
    if not active_items:
        return ""

    sections = ["Frozen workspace memory snapshot", "Priority: current user instruction > live tool result > recent tool calls > long-term memory."]
    profiles = [it for it in active_items if it.memoryType in ("user_profile", "preference")]
    knowledge = [it for it in active_items if it.memoryType in ("project_knowledge", "workspace_fact")]
    procedural = [it for it in active_items if it.memoryType in ("tool_lesson", "workflow_recipe")]
    others = [it for it in active_items if it.memoryType not in ("user_profile", "preference", "project_knowledge", "workspace_fact", "tool_lesson", "workflow_recipe")]

    if profiles:
        sections.append("[User Profile]")
        for item in profiles:
            sections.append(f"  - {memory_label(item)} {safe_memory_text(item.content, 800)}")
        sections.append("")

    if knowledge:
        sections.append("[Workspace Facts]")
        for item in knowledge:
            sections.append(f"  {memory_label(item)} {safe_memory_text(item.title, 120)}\n  {safe_memory_text(item.content, 1200)}")
        sections.append("")

    if procedural:
        sections.append("[Procedural Lessons]")
        for item in procedural:
            sections.append(f"  {memory_label(item)} {safe_memory_text(item.title, 120)}\n  {safe_memory_text(item.content, 1200)}")
        sections.append("")

    if others:
        sections.append("[Other Notes]")
        for item in others:
            sections.append(f"  {memory_label(item)} {safe_memory_text(item.title, 120)}\n  {safe_memory_text(item.content, 1200)}")

    return "\n".join(sections).strip()


def memory_trace_items(items: list[WorkspaceMemoryItem], *, limit: int = 8) -> list[dict[str, Any]]:
    trace_items: list[dict[str, Any]] = []
    for item in items[:limit]:
        preview_source = item.content or item.title
        trace_items.append(
            {
                "id": item.id,
                "type": item.memoryType,
                "title": safe_memory_text(item.title, 120),
                "preview": safe_memory_text(preview_source, 240),
                "score": item.score,
                "importance": item.importance,
                "confidence": item.confidence,
                "pinned": item.pinned,
                "reason": safe_memory_text(item.reason or "", 160),
            }
        )
    return trace_items


def memory_context_trace_payload(
    workspace_memory_context: str,
    *,
    source: str,
    items: list[WorkspaceMemoryItem] | None = None,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "frozen": bool(workspace_memory_context),
        "count": len(items) if items is not None else (len(workspace_memory_context) if workspace_memory_context else 0),
        "source": source,
        "snapshotPreview": safe_memory_text(workspace_memory_context, 800),
    }
    if items is not None:
        payload["items"] = memory_trace_items(items)
        payload["memoryIds"] = [item.id for item in items]
        payload["types"] = [item.memoryType for item in items]
    return payload


def format_workspace_memory_items(items: list[WorkspaceMemoryItem]) -> list[str]:
    memory_items = []
    for item in items:
        if not item.content.strip() and not item.title.strip():
            continue
        title = item.title.strip() or "Untitled memory"
        content = item.content.strip()
        memory_type = item.memoryType.strip() or "memory"
        memory_items.append(f"{memory_label(item)} {safe_memory_text(title, 120)} ({memory_type})\n{safe_memory_text(content, 1200)}")
    return memory_items


def format_session_search_context(items: list[SessionSearchItem]) -> str:
    active_items = [item for item in items if (item.content or item.argumentsJson or item.resultJson)]
    if not active_items:
        return ""
    sections = ["Session search results (episodic memory, not long-term facts)"]
    for item in active_items[:6]:
        if item.itemType == "tool_call":
            sections.append(
                f"  [tool_call:{item.id}, tool={item.toolCode}, run={item.runId}, task={item.taskId}, score={item.score}]\n"
                f"  args={safe_memory_text(item.argumentsJson or '', 500)}\n"
                f"  result={safe_memory_text(item.resultJson or item.content or '', 900)}"
            )
        else:
            sections.append(
                f"  [message:{item.id}, role={item.role}, run={item.runId}, score={item.score}]\n"
                f"  {safe_memory_text(item.content or '', 900)}"
            )
    return "\n".join(sections).strip()


def looks_like_session_search_request(message: str) -> bool:
    compact = re.sub(r"\s+", "", (message or "").lower())
    return any(token in compact for token in ("上次", "之前", "刚才", "那张", "那个", "历史", "previous", "lasttime"))


def memory_label(item: WorkspaceMemoryItem) -> str:
    pinned = ", pinned" if item.pinned else ""
    importance = item.importance if item.importance is not None else "-"
    confidence = item.confidence if item.confidence is not None else "-"
    updated = item.updatedAt or "unknown"
    return f"[memory:{item.id}, type={item.memoryType}, score={item.score}, importance={importance}, confidence={confidence}, updated={updated}{pinned}]"


def memory_auto_save_enabled(context: RunContext) -> bool:
    if context.memorySettings is not None and context.memorySettings.autoSaveEnabled is not None:
        return bool(context.memorySettings.autoSaveEnabled)
    return settings.agent_memory_auto_save_enabled


def memory_tool_loop_enabled(context: RunContext) -> bool:
    if context.memorySettings is not None and context.memorySettings.toolLoopEnabled is not None:
        return bool(context.memorySettings.toolLoopEnabled)
    return settings.agent_memory_tool_loop_enabled


def memory_consolidation_enabled(context: RunContext) -> bool:
    if context.memorySettings is not None and context.memorySettings.consolidationEnabled is not None:
        return bool(context.memorySettings.consolidationEnabled)
    return settings.agent_memory_consolidation_enabled


def memory_consolidation_llm_enabled(context: RunContext) -> bool:
    if context.memorySettings is not None and context.memorySettings.consolidationLlmEnabled is not None:
        return bool(context.memorySettings.consolidationLlmEnabled)
    return settings.agent_memory_consolidation_llm_enabled


def memory_consolidation_turn_interval(context: RunContext) -> int:
    if context.memorySettings is not None and context.memorySettings.consolidationTurnInterval is not None:
        return max(2, min(int(context.memorySettings.consolidationTurnInterval), 50))
    return settings.agent_memory_consolidation_turn_interval


def memory_consolidation_char_threshold(context: RunContext) -> int:
    if context.memorySettings is not None and context.memorySettings.consolidationCharThreshold is not None:
        return max(500, min(int(context.memorySettings.consolidationCharThreshold), 50000))
    return settings.agent_memory_consolidation_char_threshold


def memory_consolidation_token_threshold(context: RunContext) -> int:
    if context.memorySettings is not None and context.memorySettings.consolidationTokenThreshold is not None:
        return max(0, min(int(context.memorySettings.consolidationTokenThreshold), 200000))
    return settings.agent_memory_consolidation_token_threshold


def memory_consolidation_recent_tool_threshold(context: RunContext) -> int:
    if context.memorySettings is not None and context.memorySettings.consolidationRecentToolThreshold is not None:
        return max(0, min(int(context.memorySettings.consolidationRecentToolThreshold), 50))
    return settings.agent_memory_consolidation_recent_tool_threshold


def memory_consolidation_max_context_messages(context: RunContext) -> int:
    if context.memorySettings is not None and context.memorySettings.consolidationMaxContextMessages is not None:
        return max(4, min(int(context.memorySettings.consolidationMaxContextMessages), 100))
    return settings.agent_memory_consolidation_max_context_messages


def memory_consolidation_prompt(context: RunContext) -> str:
    configured = context.memorySettings.consolidationPrompt if context.memorySettings is not None else None
    if configured and configured.strip():
        return configured.strip()
    if settings.agent_memory_consolidation_prompt.strip():
        return settings.agent_memory_consolidation_prompt.strip()
    return (
        "你是长期记忆画像梳理器。请根据最近的用户与 AI 对话，提炼长期稳定的用户画像、偏好、习惯和项目知识。\n"
        "只保留长期有价值的信息；不要保存临时改图要求、一次性参数、工具 JSON、图片/视频 URL、生成结果或短期上下文。\n"
        "已存在的显式记忆优先级高于你的推断，不能覆盖用户明确要求记住的独立偏好。\n"
        "如果发现新的稳定偏好但用户没有明确要求记住，请作为候选偏好输出。\n"
        "只能输出 JSON，格式为 {\"shouldUpdateProfile\": true, \"profileContent\": \"...\", \"confidence\": 0.0, \"reason\": \"...\", \"candidatePreferences\": []}。"
    )


def memory_consolidation_min_confidence(context: RunContext) -> float:
    if context.memorySettings is not None and context.memorySettings.consolidationMinConfidence is not None:
        return max(0.0, min(float(context.memorySettings.consolidationMinConfidence), 1.0))
    return settings.agent_memory_consolidation_min_confidence


def memory_candidate_confidence_threshold(context: RunContext) -> float:
    if context.memorySettings is not None and context.memorySettings.candidateConfidenceThreshold is not None:
        return max(0.0, min(float(context.memorySettings.candidateConfidenceThreshold), 1.0))
    return settings.agent_memory_candidate_confidence_threshold


def memory_retrieval_limit(context: RunContext) -> int:
    if context.memorySettings is not None and context.memorySettings.retrievalLimit is not None:
        return max(1, min(int(context.memorySettings.retrievalLimit), 20))
    return settings.agent_memory_retrieval_limit


def memory_view_for_context(context: RunContext) -> str:
    message = (context.message or "").lower()
    if any(token in message for token in (
        "记得",
        "记住",
        "喜欢",
        "偏好",
        "我是谁",
        "我是何人",
        "你了解我",
        "用户画像",
        "个人画像",
        "remember",
        "preference",
        "profile",
    )):
        return "chat"
    if context.recentToolCalls or any(token in message for token in ("生成", "工具", "同款", "again", "image", "video")):
        return "router"
    return "chat"


def successful_recent_tool_count(context: RunContext) -> int:
    count = 0
    for call in context.recentToolCalls or []:
        result = call.resultJson if isinstance(call.resultJson, dict) else {}
        if result and not result.get("error") and not result.get("errorCode"):
            count += 1
    return count


def memory_context_token_count(context: RunContext) -> int:
    window = context.contextWindow
    if window is not None and window.estimatedInputTokens:
        return max(0, int(window.estimatedInputTokens))
    message_text = "\n".join([*(message.content for message in context.history), context.message or ""])
    return max(0, len(message_text) // 4)


def memory_history_marker(context: RunContext) -> int:
    return max(0, len(context.history) + 1)


def memory_consolidation_trigger(
    context: RunContext,
    *,
    user_turns: list[str],
    char_count: int,
    existing_profile: WorkspaceMemoryItem | None,
) -> dict[str, Any]:
    token_count = memory_context_token_count(context)
    recent_tool_count = successful_recent_tool_count(context)
    previous = _consolidation_metadata(existing_profile)
    trigger_reasons: list[str] = []
    turn_interval = memory_consolidation_turn_interval(context)
    if turn_interval > 0 and len(user_turns) >= turn_interval and len(user_turns) % turn_interval == 0:
        trigger_reasons.append("turn_interval")
    char_threshold = memory_consolidation_char_threshold(context)
    previous_char_count = int(previous.get("charCount") or 0)
    if char_threshold > 0 and char_count >= char_threshold and previous_char_count < char_threshold:
        trigger_reasons.append("char_threshold")
    token_threshold = memory_consolidation_token_threshold(context)
    previous_token_count = int(previous.get("estimatedInputTokens") or 0)
    if token_threshold > 0 and token_count >= token_threshold and previous_token_count < token_threshold:
        trigger_reasons.append("token_threshold")
    tool_threshold = memory_consolidation_recent_tool_threshold(context)
    previous_tool_count = int(previous.get("recentToolCount") or 0)
    if tool_threshold > 0 and recent_tool_count >= tool_threshold and previous_tool_count < tool_threshold:
        trigger_reasons.append("recent_tool_threshold")
    marker = memory_history_marker(context)
    previous_marker = int(previous.get("historyMessageCount") or 0)
    if trigger_reasons and previous_marker >= marker:
        trigger_reasons = []
    return {
        "shouldRun": bool(trigger_reasons),
        "triggerReasons": trigger_reasons,
        "turnCount": len(user_turns),
        "charCount": char_count,
        "estimatedInputTokens": token_count,
        "recentToolCount": recent_tool_count,
        "historyMessageCount": marker,
    }


def build_consolidation_metadata(context: RunContext, trigger: dict[str, Any], *, confidence: float, reason: str) -> str:
    return json.dumps(
        {
            "curator": "llm_profile_consolidation_v1",
            "sourceRunId": context.runId,
            "historyMessageCount": trigger.get("historyMessageCount"),
            "turnCount": trigger.get("turnCount"),
            "charCount": trigger.get("charCount"),
            "estimatedInputTokens": trigger.get("estimatedInputTokens"),
            "recentToolCount": trigger.get("recentToolCount"),
            "triggerReasons": trigger.get("triggerReasons") or [],
            "confidence": confidence,
            "reason": reason,
        },
        ensure_ascii=False,
    )


def build_consolidation_messages(
    context: RunContext,
    answer: str,
    existing: list[WorkspaceMemoryItem],
    trigger: dict[str, Any],
) -> list[ChatMessage]:
    recent_messages = context.history[-memory_consolidation_max_context_messages(context) :]
    transcript = []
    for message in recent_messages:
        if looks_like_large_media_payload(message.content):
            continue
        transcript.append({"role": message.role, "content": safe_memory_text(message.content, 1000)})
    transcript.append({"role": "user", "content": safe_memory_text(context.message, 1000)})
    if answer:
        transcript.append({"role": "assistant", "content": safe_memory_text(answer, 1000)})
    tool_calls = []
    for call in (context.recentToolCalls or [])[-8:]:
        tool_calls.append(
            {
                "toolCode": call.toolCode,
                "resourceType": call.resourceType,
                "hasResult": bool(call.resultJson),
                "error": bool((call.resultJson or {}).get("error") or (call.resultJson or {}).get("errorCode")),
            }
        )
    memories = []
    for item in existing[:20]:
        memories.append(
            {
                "id": item.id,
                "type": item.memoryType,
                "title": safe_memory_text(item.title, 160),
                "content": safe_memory_text(item.content, 800),
                "confidence": item.confidence,
                "metadataJson": safe_memory_text(item.metadataJson or "", 300),
            }
        )
    payload = {
        "trigger": trigger,
        "existingMemories": memories,
        "recentToolCalls": tool_calls,
        "transcript": transcript,
    }
    return [
        ChatMessage(role="system", content=memory_consolidation_prompt(context)),
        ChatMessage(role="user", content=json.dumps(payload, ensure_ascii=False)),
    ]


def parse_consolidation_json(raw: str) -> dict[str, Any] | None:
    text = str(raw or "").strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    match = re.search(r"\{.*\}", text, flags=re.S)
    if match:
        text = match.group(0)
    try:
        parsed = json.loads(text)
    except Exception:
        return None
    if not isinstance(parsed, dict):
        return None
    return parsed


def build_consolidated_memory_summary(context: RunContext, answer: str) -> str:
    user_turns = [message.content.strip() for message in context.history if message.role.lower() == "user" and message.content.strip()]
    assistant_turns = [message.content.strip() for message in context.history if message.role.lower() in {"assistant", "ai"} and message.content.strip()]
    user_turns.append((context.message or "").strip())
    clues: list[str] = []
    for text in user_turns[-12:]:
        if looks_like_large_media_payload(text):
            continue
        clue = _extract_stable_user_clue(text)
        if clue:
            clues.append(clue)
    if answer and any(token in context.message for token in ("我是什么样的人", "用户画像", "个人画像")):
        clues.append(_normalize_summary_line(answer))
    tool_codes = []
    for call in (context.recentToolCalls or []):
        if call.toolCode and call.toolCode not in tool_codes:
            tool_codes.append(call.toolCode)
    if tool_codes:
        clues.append(f"常用工具倾向：{', '.join(tool_codes[:3])}")
    clues = _dedupe_lines(clues)
    if not clues and len(user_turns) < settings.agent_memory_consolidation_turn_interval:
        return ""
    summary_lines = [
        "根据近期对话整理出的用户画像与偏好。若与用户当前明确指令冲突，以当前指令为准。",
    ]
    for clue in clues[:8]:
        summary_lines.append(f"- {safe_memory_text(clue, 240)}")
    if not clues and assistant_turns:
        summary_lines.append(f"- 近期对话主题：{safe_memory_text(user_turns[-1], 240)}")
    return "\n".join(summary_lines)[:1600]


def _find_auto_profile_summary(items: list[WorkspaceMemoryItem]) -> WorkspaceMemoryItem | None:
    for item in items:
        if item.memoryType == "user_profile" and (item.title or "").strip() == AUTO_PROFILE_TITLE:
            return item
    return None


def _consolidation_metadata(item: WorkspaceMemoryItem | None) -> dict[str, Any]:
    if item is None or not item.metadataJson:
        return {}
    try:
        parsed = json.loads(item.metadataJson)
    except Exception:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _normalize_candidate_preferences(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    result: list[dict[str, Any]] = []
    for item in value:
        if not isinstance(item, dict):
            continue
        result.append(item)
    return result


def _memory_duplicate_key(title: str, content: str) -> str:
    return re.sub(r"\s+", "", f"{title}|{content}").lower()


def _as_float(value: Any, default: float) -> float:
    try:
        return max(0.0, min(float(value), 1.0))
    except Exception:
        return default


def _extract_stable_user_clue(text: str) -> str:
    normalized = _normalize_summary_line(text)
    if not normalized:
        return ""
    compact = re.sub(r"\s+", "", normalized)
    ephemeral_patterns = ("换成", "改成", "替换成", "这张", "上一张", "刚才", "再来", "来一张", "生成一张", "做成视频")
    explicit_memory_patterns = ("记住", "记得", "以后", "默认", "偏好", "喜欢", "习惯", "不要", "优先", "总是")
    profile_patterns = ("我是", "我的", "我喜欢", "我偏好", "我习惯")
    if any(token in compact for token in explicit_memory_patterns):
        return normalized
    if any(token in compact for token in profile_patterns) and not any(token in compact for token in ephemeral_patterns):
        return normalized
    return ""


def _normalize_summary_line(text: str) -> str:
    line = re.sub(r"\s+", " ", str(text or "")).strip()
    line = re.sub(r"^(用户|我)?(?:说|表示|要求)[：:]\s*", "", line)
    return safe_memory_text(line, 240)


def _dedupe_lines(lines: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for line in lines:
        normalized = re.sub(r"\s+", "", line).lower()
        if not normalized or normalized in seen:
            continue
        seen.add(normalized)
        result.append(line)
    return result


def safe_memory_text(value: Any, limit: int) -> str:
    text = str(value or "").strip()
    if not text:
        return ""
    if looks_like_large_media_payload(text):
        return "[omitted large media payload]"
    return text[:limit]


def looks_like_large_media_payload(text: str) -> bool:
    if len(text) > 2000 and ("base64" in text[:300].lower() or "data:image/" in text[:300].lower()):
        return True
    if len(text) > 5000 and text.lstrip().startswith(("{", "[")):
        lowered = text[:1000].lower()
        return "resourceurl" in lowered or "imageurl" in lowered or "videourl" in lowered or "contenttext" in lowered
    return False
