from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any

from app.core.schemas import RunContext, WorkspaceMemoryItem


@dataclass(slots=True)
class MemoryCuratorDecision:
    action: str
    memory_type: str = "custom"
    title: str = ""
    content: str = ""
    importance: int = 5
    confidence: float = 0.0
    reason: str = ""
    tags: list[str] | None = None

    @property
    def should_persist(self) -> bool:
        return self.action in {"add", "update", "candidate"}


class MemoryCuratorService:
    """Conservative fallback curator for durable long-term memory.

    The primary path is OpenAI tool calling. This heuristic curator is the
    safety net for providers that do not emit tool calls or for simple durable
    signals that can be handled without another model call.
    """

    def decide(
        self,
        context: RunContext,
        answer: str,
        existing_items: list[WorkspaceMemoryItem] | None = None,
        tool_result: dict[str, Any] | None = None,
    ) -> MemoryCuratorDecision:
        message = (context.message or "").strip()
        if not message:
            return MemoryCuratorDecision(action="none", reason="empty_message")
        if _looks_like_ephemeral_payload(message) or _looks_like_ephemeral_payload(answer):
            return MemoryCuratorDecision(action="none", reason="ephemeral_or_large_payload")

        if _explicit_memory_request(message):
            memory_type = _infer_memory_type(message)
            content = _explicit_memory_content(message, answer, memory_type)
            return MemoryCuratorDecision(
                action="add",
                memory_type=memory_type,
                title=_title_from_text(content),
                content=content,
                importance=8,
                confidence=0.92,
                reason="explicit_memory_request",
                tags=[memory_type, "explicit"],
            )

        if _looks_like_preference(message):
            return MemoryCuratorDecision(
                action="add",
                memory_type="preference",
                title=_title_from_text(message),
                content=message,
                importance=7,
                confidence=0.82,
                reason="stable_user_preference",
                tags=["preference"],
            )

        if _looks_like_workspace_fact(message):
            return MemoryCuratorDecision(
                action="candidate",
                memory_type="workspace_fact",
                title=_title_from_text(message),
                content=message,
                importance=7,
                confidence=0.68,
                reason="possible_workspace_fact_needs_review",
                tags=["workspace_fact"],
            )

        if tool_result and _looks_like_tool_lesson(tool_result):
            return MemoryCuratorDecision(
                action="candidate",
                memory_type="tool_lesson",
                title=_title_from_text(f"Tool lesson: {context.message}"),
                content=_tool_lesson_content(context, tool_result),
                importance=6,
                confidence=0.66,
                reason="tool_execution_lesson_candidate",
                tags=["tool_lesson"],
            )

        return MemoryCuratorDecision(action="none", reason="no_durable_memory_signal")


def build_memory_metadata(decision: MemoryCuratorDecision) -> str:
    return json.dumps(
        {
            "reason": decision.reason,
            "tags": decision.tags or [],
            "curator": "fallback_heuristic_v2",
        },
        ensure_ascii=False,
    )


def _explicit_memory_request(text: str) -> bool:
    compact = re.sub(r"\s+", "", text.lower())
    return any(
        token in compact
        for token in (
            "记住",
            "记下来",
            "记录一下",
            "帮我记",
            "写入你的记忆",
            "写到你的记忆",
            "存到记忆",
            "保存到记忆",
            "remember",
            "save this",
            "save to memory",
        )
    )


def _looks_like_preference(text: str) -> bool:
    compact = re.sub(r"\s+", "", text.lower())
    return any(token in compact for token in ("我喜欢", "我偏好", "我的习惯", "以后都", "prefer", "i like", "my preference"))


def _looks_like_workspace_fact(text: str) -> bool:
    compact = re.sub(r"\s+", "", text.lower())
    return any(token in compact for token in ("默认", "规则", "配置", "业务", "系统", "模型", "apikey", "baseurl", "workflow", "default"))


def _infer_memory_type(text: str) -> str:
    compact = re.sub(r"\s+", "", text.lower())
    if any(token in compact for token in ("我是什么样的人", "用户画像", "个人画像", "profile")):
        return "user_profile"
    if _looks_like_preference(text):
        return "preference"
    if _looks_like_workspace_fact(text):
        return "workspace_fact"
    return "custom"


def _explicit_memory_content(message: str, answer: str, memory_type: str) -> str:
    compact = re.sub(r"\s+", "", message.lower())
    if memory_type == "user_profile" and answer and any(token in compact for token in ("我是什么样的人", "用户画像", "个人画像")):
        clean = re.sub(r"\s+", " ", answer).strip()
        clean = re.sub(r"^(已记录[。:：]?|记住了[。:：]?|好的[，,。]?)", "", clean).strip()
        return clean[:1200] or answer[:1200]
    return message


def _looks_like_ephemeral_payload(text: str) -> bool:
    if not text:
        return False
    lower = text.lower()
    if len(text) > 3000:
        return True
    if "base64," in lower or "data:image/" in lower:
        return True
    if len(re.findall(r"https?://|/generated/|\.png|\.jpg|\.mp4", lower)) >= 2:
        return True
    stripped = text.strip()
    return (stripped.startswith("{") and stripped.endswith("}")) or (stripped.startswith("[") and stripped.endswith("]"))


def _looks_like_tool_lesson(tool_result: dict[str, Any]) -> bool:
    return bool(tool_result.get("errorCode") or tool_result.get("error"))


def _tool_lesson_content(context: RunContext, tool_result: dict[str, Any]) -> str:
    return f"Request: {context.message}\nTool result: {json.dumps(tool_result, ensure_ascii=False)[:1200]}"


def _title_from_text(text: str) -> str:
    clean = re.sub(r"\s+", " ", text).strip()
    if len(clean) <= 60:
        return clean or "Memory"
    return clean[:57] + "..."
