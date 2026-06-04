from __future__ import annotations

import logging
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
from app.core.schemas import RunContext, RunEventCreate, SessionSearchItem, WorkspaceMemoryItem
from app.runtime.memory_curator import MemoryCuratorService, build_memory_metadata
from app.tools.memory_tool import MemoryTool

LOGGER = logging.getLogger(__name__)


class WorkspaceMemoryRuntime:
    def __init__(self, backend, curator: MemoryCuratorService | None = None) -> None:
        self.backend = backend
        self.curator = curator or MemoryCuratorService()

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
        should_run = (
            len(user_turns) >= memory_consolidation_turn_interval(context)
            or len(combined) >= memory_consolidation_char_threshold(context)
            or successful_recent_tool_count(context) >= 3
        )
        if not should_run:
            return
        existing_profile = next((item for item in existing if item.memoryType in {"user_profile", "preference", "workflow_recipe"}), None)
        summary = build_consolidated_memory_summary(context, answer)
        if not summary:
            return
        tool = MemoryTool(self.backend, context.workspaceId, context.userId, run_id=context.runId)
        if existing_profile is None:
            await tool.add_memory(
                memory_type="user_profile",
                title="用户画像与偏好摘要",
                content=summary,
                source_run_id=context.runId,
            )
            action = "add"
        else:
            update = getattr(self.backend, "update_workspace_memory", None)
            if callable(update):
                await update(
                    workspace_id=context.workspaceId,
                    memory_id=existing_profile.id,
                    memory_type=existing_profile.memoryType,
                    title=existing_profile.title or "用户画像与偏好摘要",
                    content=summary,
                )
            else:
                await tool.replace_memory(
                    memory_type=existing_profile.memoryType,
                    new_title=existing_profile.title or "用户画像与偏好摘要",
                    new_content=summary,
                )
            action = "replace"
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=MEMORY_CONSOLIDATED,
                eventText="用户画像与偏好已整理",
                eventJson={
                    "action": action,
                    "turnCount": len(user_turns),
                    "charCount": len(combined),
                    "recentToolCount": successful_recent_tool_count(context),
                },
            ),
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


def memory_consolidation_turn_interval(context: RunContext) -> int:
    if context.memorySettings is not None and context.memorySettings.consolidationTurnInterval is not None:
        return max(2, min(int(context.memorySettings.consolidationTurnInterval), 50))
    return settings.agent_memory_consolidation_turn_interval


def memory_consolidation_char_threshold(context: RunContext) -> int:
    if context.memorySettings is not None and context.memorySettings.consolidationCharThreshold is not None:
        return max(500, min(int(context.memorySettings.consolidationCharThreshold), 50000))
    return settings.agent_memory_consolidation_char_threshold


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


def build_consolidated_memory_summary(context: RunContext, answer: str) -> str:
    user_turns = [message.content.strip() for message in context.history if message.role.lower() == "user" and message.content.strip()]
    assistant_turns = [message.content.strip() for message in context.history if message.role.lower() in {"assistant", "ai"} and message.content.strip()]
    user_turns.append((context.message or "").strip())
    clues: list[str] = []
    for text in user_turns[-12:]:
        if looks_like_large_media_payload(text):
            continue
        if any(token in text for token in ("喜欢", "偏好", "习惯", "以后", "记住", "二次元", "梗", "风格", "默认", "配置")):
            clues.append(text)
    if answer and any(token in context.message for token in ("我是什么样的人", "用户画像", "个人画像")):
        clues.append(answer)
    for call in (context.recentToolCalls or [])[:5]:
        if call.toolCode:
            clues.append(f"常用工具倾向：{call.toolCode}")
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
