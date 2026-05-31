from __future__ import annotations

from typing import Any

from app.core.event_types import MEMORY_SAVED
from app.core.schemas import RunEventCreate

_ALLOWED_MEMORY_TYPES = {
    "user_profile",
    "workspace_fact",
    "preference",
    "tool_lesson",
    "workflow_recipe",
    "custom",
}

_INJECTION_PATTERNS = (
    "ignore previous instructions",
    "ignore all previous",
    "forget all",
    "you are now",
    "system prompt",
    "忽略之前",
    "忘记所有",
    "系统提示词",
)


def _is_safe(content: str) -> bool:
    lower = content.lower()
    return not any(pattern in lower for pattern in _INJECTION_PATTERNS)


class MemoryTool:
    """Safe memory tool exposed to the agent through function calling."""

    def __init__(self, backend, workspace_id: int, user_id: int, run_id: int | None = None) -> None:
        self.backend = backend
        self.workspace_id = workspace_id
        self.user_id = user_id
        self.run_id = run_id

    async def add_memory(
        self,
        memory_type: str,
        title: str,
        content: str,
        source_run_id: int | None = None,
    ) -> dict[str, Any]:
        memory_type = _normalize_memory_type(memory_type)
        title = _trim(title, 160)
        content = _trim(content, 2000)
        if not title or not content:
            return {"success": False, "error": "title and content are required"}
        if not _is_safe(content) or not _is_safe(title):
            return {"success": False, "error": "content rejected by security scan"}
        result = await self.backend.create_workspace_memory(
            workspace_id=self.workspace_id,
            user_id=self.user_id,
            memory_type=memory_type,
            title=title,
            content=content,
            source_run_id=source_run_id,
            importance=8 if memory_type in {"user_profile", "preference"} else None,
            confidence=0.9,
            tags_json=None,
        )
        await self._emit_saved_event("add", result.get("id"), memory_type, title)
        return {"success": True, "memory_id": result.get("id")}

    async def replace_memory(self, memory_type: str, new_title: str, new_content: str) -> dict[str, Any]:
        memory_type = _normalize_memory_type(memory_type)
        new_title = _trim(new_title, 160)
        new_content = _trim(new_content, 2000)
        if not new_title or not new_content:
            return {"success": False, "error": "new_title and new_content are required"}
        if not _is_safe(new_content) or not _is_safe(new_title):
            return {"success": False, "error": "content rejected by security scan"}
        try:
            items = await self.backend.retrieve_workspace_memory(
                workspace_id=self.workspace_id,
                query=new_title,
                limit=20,
            )
        except Exception:
            return {"success": False, "error": "failed to retrieve existing memories"}

        target = next((item for item in items if item.memoryType == memory_type), None)
        if target is None:
            return await self.add_memory(memory_type, new_title, new_content, source_run_id=self.run_id)
        if not hasattr(self.backend, "update_workspace_memory"):
            return {"success": False, "error": "update not supported"}

        await self.backend.update_workspace_memory(
            workspace_id=self.workspace_id,
            memory_id=target.id,
            memory_type=memory_type,
            title=new_title,
            content=new_content,
        )
        await self._emit_saved_event("replace", target.id, memory_type, new_title)
        return {"success": True, "memory_id": target.id}

    async def remove_memory(self, memory_id: int) -> dict[str, Any]:
        try:
            await self.backend.delete_workspace_memory(
                workspace_id=self.workspace_id,
                memory_id=memory_id,
            )
            return {"success": True}
        except Exception as exc:
            return {"success": False, "error": str(exc)}

    async def _emit_saved_event(self, action: str, memory_id: int | None, memory_type: str, title: str) -> None:
        if self.run_id is None:
            return
        try:
            await self.backend.append_event(
                self.run_id,
                RunEventCreate(
                    eventType=MEMORY_SAVED,
                    eventJson={
                        "action": action,
                        "memory_id": memory_id,
                        "memory_type": memory_type,
                        "title": title,
                    },
                ),
            )
        except Exception:
            pass


def _format_memory_tool_definitions() -> list[dict[str, Any]]:
    return [
        {
            "type": "function",
            "function": {
                "name": "memory_add",
                "description": (
                    "Save a durable memory for future conversations. Use only for stable user profile facts, "
                    "preferences, workspace facts, tool lessons, or workflow recipes. Do not save one-off prompts, "
                    "generated media URLs, large JSON, or temporary chat."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "memory_type": {
                            "type": "string",
                            "enum": sorted(_ALLOWED_MEMORY_TYPES),
                            "description": (
                                "user_profile=stable facts about the user; preference=stable user preference; "
                                "workspace_fact=project/business/system fact; tool_lesson=tool lesson; "
                                "workflow_recipe=reusable workflow; custom=other durable memory."
                            ),
                        },
                        "title": {"type": "string", "description": "Short title summarizing the memory."},
                        "content": {
                            "type": "string",
                            "description": (
                                "Memory content. For reflective requests like 'what kind of person am I, write it to memory', "
                                "save your final profile summary, not the raw question."
                            ),
                        },
                    },
                    "required": ["memory_type", "title", "content"],
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "memory_replace",
                "description": (
                    "Replace the latest memory of the same type when a durable profile, preference, or fact changed. "
                    "If no matching memory exists, create one."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "memory_type": {"type": "string", "enum": sorted(_ALLOWED_MEMORY_TYPES)},
                        "new_title": {"type": "string", "description": "New title."},
                        "new_content": {"type": "string", "description": "New content."},
                    },
                    "required": ["memory_type", "new_title", "new_content"],
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "memory_remove",
                "description": "Delete a memory when the user explicitly asks to forget it or it is clearly inaccurate.",
                "parameters": {
                    "type": "object",
                    "properties": {"memory_id": {"type": "integer", "description": "Memory id to delete."}},
                    "required": ["memory_id"],
                },
            },
        },
    ]


def _contains_memory_promise(text: str) -> bool:
    lower = text.lower()
    return any(token in lower for token in ("记住了", "记下来了", "已记录", "已记下", "已保存", "i saved", "recorded"))


MEMORY_TOOL_SYSTEM_PROMPT = (
    "You have a long-term memory system. You may call memory_add, memory_replace, and memory_remove.\n"
    "There is no memory_search tool; relevant memories have already been injected above as a frozen snapshot.\n"
    "Use memory tools only for durable information: stable user profile facts, stable preferences, workspace facts, tool lessons, and reusable workflow recipes.\n"
    "Do not save one-off image prompts, generated media URLs, large JSON, temporary jokes, or data that looks sensitive or uncertain.\n"
    "If the user explicitly says to remember/write/save something, call a memory tool before saying it has been recorded.\n"
    "If the user asks you to infer their profile and write it to memory, first answer naturally, then save your concise final profile summary as user_profile or preference.\n"
    "Current user instruction has highest priority; memory is helpful context, not absolute truth."
)


def _normalize_memory_type(memory_type: str) -> str:
    normalized = (memory_type or "").strip()
    if normalized == "project_knowledge":
        normalized = "workspace_fact"
    return normalized if normalized in _ALLOWED_MEMORY_TYPES else "custom"


def _trim(value: str, limit: int) -> str:
    clean = str(value or "").strip()
    return clean[:limit]
