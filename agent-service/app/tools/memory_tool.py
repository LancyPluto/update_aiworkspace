from typing import Any

from app.core.event_types import MEMORY_SAVED
from app.core.schemas import RunEventCreate, WorkspaceMemoryItem

_INJECTION_PATTERNS = [
    "ignore previous instructions",
    "ignore all previous",
    "forget all",
    "you are now",
    "system prompt",
    "你被",
    "忽略之前",
    "忘记所有",
]


def _is_safe(content: str) -> bool:
    lower = content.lower()
    for pattern in _INJECTION_PATTERNS:
        if pattern in lower:
            return False
    return True


class MemoryTool:
    """Agent 用来自主读写记忆的工具。

    Agent 在对话中发现重要信息时，可以调用此工具的 add/replace/remove 方法
    来持久化记忆。写入的记忆会被安全扫描，防止 prompt 注入。
    """

    def __init__(self, backend, workspace_id: int, user_id: int, run_id: int | None = None) -> None:
        self.backend = backend
        self.workspace_id = workspace_id
        self.user_id = user_id
        self.run_id = run_id

    async def add_memory(self, memory_type: str, title: str, content: str, source_run_id: int | None = None) -> dict[str, Any]:
        """添加一条新的记忆。

        Args:
            memory_type: 记忆类型，可选 project_knowledge / user_profile / custom
            title: 记忆标题，最长 160 字符
            content: 记忆内容
            source_run_id: （可选）来源运行 ID
        """
        if len(title) > 160:
            title = title[:160]
        if memory_type not in ("project_knowledge", "user_profile", "custom"):
            memory_type = "custom"
        if not _is_safe(content) or not _is_safe(title):
            return {"success": False, "error": "content rejected by security scan"}
        result = await self.backend.create_workspace_memory(
            workspace_id=self.workspace_id,
            user_id=self.user_id,
            memory_type=memory_type,
            title=title,
            content=content,
            source_run_id=source_run_id,
        )

        await self._emit_saved_event("add", result.get("id"), memory_type, title)

        return {"success": True, "memory_id": result.get("id")}

    async def replace_memory(self, memory_type: str, new_title: str, new_content: str) -> dict[str, Any]:
        """替换某类记忆的最新一条。

        例如更新 user_profile 类型的最新记忆内容。

        Args:
            memory_type: 要替换的记忆类型
            new_title: 新标题
            new_content: 新内容
        """
        if not _is_safe(new_content) or not _is_safe(new_title):
            return {"success": False, "error": "content rejected by security scan"}
        try:
            items = await self.backend.retrieve_workspace_memory(
                workspace_id=self.workspace_id,
                query="",
                limit=20,
            )
        except Exception:
            return {"success": False, "error": "failed to retrieve existing memories"}

        target = None
        for item in items:
            if item.memoryType == memory_type:
                target = item
                break

        if target is None:
            result = await self.add_memory(memory_type, new_title, new_content)
            return result

        from app.clients.backend_client import BackendClient
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
        """删除一条记忆。

        Args:
            memory_id: 要删除的记忆 ID
        """
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
    """返回 LLM function calling 格式的记忆工具定义。"""
    return [
        {
            "type": "function",
            "function": {
                "name": "memory_add",
                "description": "记住一条信息，供未来对话长期使用。适合记录项目事实、用户偏好、工作流程等。不要过度使用，每条记忆应有明确的长期价值。",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "memory_type": {
                            "type": "string",
                            "enum": ["project_knowledge", "user_profile", "custom"],
                            "description": "project_knowledge=项目事实/约定/workaround, user_profile=用户偏好/沟通风格, custom=其他",
                        },
                        "title": {
                            "type": "string",
                            "description": "简短标题，概括这条记忆的核心内容",
                        },
                        "content": {
                            "type": "string",
                            "description": "详细的记忆内容",
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
                "description": "替换某类记忆的最新一条。用于更新过时的信息，比如用户偏好发生了变化。会先查找同类型的最新记忆，如果找不到则创建新的。",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "memory_type": {
                            "type": "string",
                            "enum": ["project_knowledge", "user_profile", "custom"],
                            "description": "要替换的记忆类型",
                        },
                        "new_title": {
                            "type": "string",
                            "description": "新的标题",
                        },
                        "new_content": {
                            "type": "string",
                            "description": "新的内容",
                        },
                    },
                    "required": ["memory_type", "new_title", "new_content"],
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "memory_remove",
                "description": "删除一条记忆。当发现某条记忆已经不准确或不再需要时使用。",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "memory_id": {
                            "type": "integer",
                            "description": "要删除的记忆 ID，格式为 memory:<id> 中的 id 部分",
                        },
                    },
                    "required": ["memory_id"],
                },
            },
        },
    ]


_MEMORY_PROMISE_KEYWORDS = [
    "记住了",
    "记下来了",
    "我记下",
    "我记住了",
    "已经记下",
    "已记录",
    "已记住",
]


def _contains_memory_promise(text: str) -> bool:
    """检查 LLM 回复中是否包含'记住了'等承诺性表述。"""
    lower = text.lower()
    return any(kw in lower for kw in _MEMORY_PROMISE_KEYWORDS)


MEMORY_TOOL_SYSTEM_PROMPT = (
    "你有一个记忆系统，可以通过 memory_add / memory_replace / memory_remove 工具管理长期记忆。\n"
    "当你发现以下情况时，必须立即使用对应的记忆工具，**不要只是口头答应**：\n"
    "- 用户告诉你关于自己的偏好或习惯 → 立即调 memory_add，type=user_profile\n"
    "- 用户告诉你项目事实、业务规则、配置信息 → 立即调 memory_add，type=project_knowledge\n"
    "- 用户明确要求你记住某件事 → 立即调 memory_add\n"
    "- 用户告诉你某条旧信息已经过时 → 调 memory_replace 或 memory_remove\n"
    "规则：如果你准备回复'记住了'、'已记录'之类的话，必须先调 memory_add 把信息真实写入记忆系统，"
    "然后再回复用户。不要只口头答应而不实际写入。\n"
    "注意：不要过度写入，每条记忆应该有明确的长期价值。"
)
