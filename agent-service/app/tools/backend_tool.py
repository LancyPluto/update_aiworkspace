import asyncio
import json
import re
import time
from typing import Any

from app.config import settings
from app.core.event_types import TOOL_TASK_DISPATCHED, TOOL_TASK_PROGRESS
from app.core.schemas import ChatMessage, RunContext, RunEventCreate, TaskCreate, ToolCallComplete, ToolCallCreate, ToolCallFail, ToolDescriptor


class ToolExecutionError(RuntimeError):
    pass


class BackendToolBridge:
    TERMINAL_TASK_STATUSES = {"SUCCESS", "FAILED", "CANCELLED"}
    TERMINAL_RUN_STATUSES = {"SUCCESS", "FAILED", "CANCELLED", "TIMEOUT"}

    def __init__(
        self,
        backend_client,
        timeout_seconds: int | None = None,
        poll_interval_seconds: float | None = None,
        model_client=None,
    ) -> None:
        self.backend = backend_client
        self.model = model_client
        self.timeout_seconds = timeout_seconds or settings.agent_tool_execution_timeout_seconds
        self.poll_interval_seconds = poll_interval_seconds or settings.agent_tool_poll_interval_seconds

    def build_arguments(self, context: RunContext, tool: ToolDescriptor, *, apply_placeholder_defaults: bool = True) -> dict[str, Any]:
        """从用户消息解析参数。占位默认值仅应在「即将执行工具」时启用；缺参检测必须关闭，否则会静默填满 schema 导致从不追问。"""
        arguments: dict[str, Any] = {"userRequest": context.message}
        properties = tool.inputSchema.get("properties", {})
        if not isinstance(properties, dict):
            return arguments
        for message in _recent_user_messages(context):
            for name, prop in properties.items():
                if not isinstance(name, str) or name == "userRequest":
                    continue
                for alias in _field_aliases(name, prop):
                    value = _extract_labeled_argument(message, alias)
                    if value:
                        arguments[name] = value
                        break
        if apply_placeholder_defaults and tool.toolCode == "xiaohongshu_copywriting":
            arguments = _with_xiaohongshu_defaults(context.message, arguments)
        return arguments

    def missing_required_arguments(self, context: RunContext, tool: ToolDescriptor) -> list[str]:
        if tool.toolCode == "xiaohongshu_copywriting" and _user_accepts_builtin_examples(context.message):
            return []
        arguments = self.build_arguments(context, tool, apply_placeholder_defaults=False)
        required = tool.inputSchema.get("required", [])
        if not isinstance(required, list):
            return []
        return [
            name
            for name in required
            if isinstance(name, str) and (name not in arguments or arguments[name] in (None, ""))
        ]

    async def enrich_arguments(self, message: str, tool: ToolDescriptor, existing_args: dict[str, Any] | None = None) -> dict[str, Any]:
        if self.model is None:
            return existing_args or {}
        properties = tool.inputSchema.get("properties", {})
        if not isinstance(properties, dict) or not properties:
            return existing_args or {}
        field_descriptions = []
        for key, prop in properties.items():
            if key == "userRequest":
                continue
            title = prop.get("title", key) if isinstance(prop, dict) else key
            desc = prop.get("description", "") if isinstance(prop, dict) else ""
            enum_vals = prop.get("enum") if isinstance(prop, dict) else None
            line = f"- {key} ({title})"
            if desc:
                line += f": {desc}"
            if enum_vals and isinstance(enum_vals, list):
                line += f" [可选值: {'/'.join(str(v) for v in enum_vals)}]"
            field_descriptions.append(line)
        if not field_descriptions:
            return existing_args or {}
        existing_info = ""
        if existing_args:
            existing_info = f"\n已从格式匹配中提取的参数 (不要覆盖): {json.dumps(existing_args, ensure_ascii=False)}"
        prompt = (
            f"从用户消息中提取工具参数。只提取消息中明确提到的字段值，不要编造。\n\n"
            f"工具: {tool.toolName or tool.toolCode}\n"
            f"描述: {tool.description or '无'}\n\n"
            f"参数字段:\n{chr(10).join(field_descriptions)}\n"
            f"{existing_info}\n"
            f"用户消息: {message}\n\n"
            f"返回纯 JSON 对象，key 用英文字段名，value 是提取的中文值。"
            f"只包含能从消息中识别出的字段。不要添加任何解释文字，只返回 JSON。"
        )
        try:
            raw = await self.model.chat([ChatMessage(role="user", content=prompt)])
        except Exception:
            return existing_args or {}
        parsed = self._parse_json_block(raw)
        if not isinstance(parsed, dict):
            return existing_args or {}
        merged = dict(existing_args or {})
        for key, value in parsed.items():
            if isinstance(key, str) and isinstance(value, str) and value.strip() and key not in merged:
                merged[key] = value.strip()
        return merged

    def conversation_argument_text(self, context: RunContext) -> str:
        return "\n".join(_recent_user_messages(context))

    @staticmethod
    def _parse_json_block(raw: str) -> Any:
        text = raw.strip()
        if text.startswith("```"):
            lines = text.split("\n")
            lines = lines[1:] if lines else []
            if lines and lines[-1].strip() == "```":
                lines = lines[:-1]
            text = "\n".join(lines).strip()
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            match = re.search(r"\{.*\}", text, re.DOTALL)
            if match:
                try:
                    return json.loads(match.group(0))
                except json.JSONDecodeError:
                    return None
            return None

    async def execute_with_args(self, context: RunContext, tool: ToolDescriptor, arguments: dict[str, Any]) -> dict[str, Any]:
        if tool.toolCode == "xiaohongshu_copywriting":
            arguments = _with_xiaohongshu_defaults(context.message, arguments)
        call = await self.backend.create_tool_call(context.runId, ToolCallCreate(toolCode=tool.toolCode, argumentsJson=arguments))
        try:
            task = await self.backend.create_task(
                TaskCreate(
                    userId=context.userId,
                    toolCode=tool.toolCode,
                    params=arguments,
                    clientRequestId=f"agent-run-{context.runId}-tool-call-{call.id}",
                )
            )
            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=TOOL_TASK_DISPATCHED,
                    eventText=f"Tool {tool.toolCode} task dispatched",
                    eventJson={"toolCode": tool.toolCode, "toolCallId": call.id, "taskId": task.taskId, "status": task.status},
                ),
            )
            task_detail = await self._wait_for_task(context, tool.toolCode, task.taskId)
        except ToolExecutionError as exc:
            if "task" in locals():
                await self._cancel_task(context.userId, task.taskId)
            await self.backend.fail_tool_call(call.id, ToolCallFail(errorCode="TOOL_TASK_TIMEOUT", errorMessage=str(exc)))
            raise
        except Exception as exc:
            await self.backend.fail_tool_call(call.id, ToolCallFail(errorCode="TOOL_TASK_FAILED", errorMessage=str(exc)))
            raise
        if task_detail.status != "SUCCESS":
            error_code = task_detail.errorCode or f"TASK_{task_detail.status}"
            error_message = task_detail.errorMessage or task_detail.progressMessage or f"Tool task ended with status {task_detail.status}"
            await self.backend.fail_tool_call(call.id, ToolCallFail(errorCode=error_code, errorMessage=error_message))
            raise ToolExecutionError(error_message)
        content_text = task_detail.result.contentText if task_detail.result is not None else ""
        result = _tool_result(
            tool.toolCode,
            call.id,
            arguments,
            task_detail.taskId,
            task_detail.status,
            content_text,
            task_detail.result.resourceType if task_detail.result else None,
        )
        await self.backend.complete_tool_call(call.id, ToolCallComplete(resultJson=result))
        return result

    async def execute(self, context: RunContext, tool: ToolDescriptor) -> dict[str, Any]:
        arguments = self.build_arguments(context, tool, apply_placeholder_defaults=True)
        return await self.execute_with_args(context, tool, arguments)

    async def _wait_for_task(self, context: RunContext, tool_code: str, task_id: int):
        deadline = time.monotonic() + self.timeout_seconds
        last_status = ""
        while time.monotonic() <= deadline:
            run_context = await self.backend.get_run_context(context.runId)
            if run_context.status in self.TERMINAL_RUN_STATUSES:
                raise ToolExecutionError(f"Agent run ended with status {run_context.status}")
            detail = await self.backend.get_task_detail(context.userId, task_id)
            if detail.status != last_status:
                last_status = detail.status
                if detail.status not in self.TERMINAL_TASK_STATUSES:
                    await self.backend.append_event(
                        context.runId,
                        RunEventCreate(
                            eventType=TOOL_TASK_PROGRESS,
                            eventText=f"Tool {tool_code} task status: {detail.status}",
                            eventJson={
                                "toolCode": tool_code,
                                "taskId": task_id,
                                "status": detail.status,
                                "progress": detail.progress,
                                "progressMessage": detail.progressMessage,
                            },
                        ),
                    )
            if detail.status in self.TERMINAL_TASK_STATUSES:
                return detail
            await asyncio.sleep(self.poll_interval_seconds)
        raise ToolExecutionError(f"Tool task {task_id} timed out after {self.timeout_seconds} seconds")

    async def _cancel_task(self, user_id: int, task_id: int) -> None:
        try:
            await self.backend.cancel_task(user_id, task_id)
        except Exception:
            pass


def _user_accepts_builtin_examples(message: str) -> bool:
    """用户明确让系统沿用补参说明里的示例 / 默认占位时，不再卡缺参（与 IntentRouter 的续接话术对齐）。"""
    text = message.strip()
    if not text:
        return False
    if "使用默认" in text or "就用默认" in text or "就用示例" in text:
        return True
    if "按照你给的例子" in text or "按照你的例子" in text or "按你的例子" in text:
        return True
    if "用你给的例子" in text or "用你的例子" in text:
        return True
    if "照你给的例子" in text or "照你的例子" in text:
        return True
    if "全部按照" in text and ("你给" in text or "你的" in text):
        return True
    if "全部按照" in text and "例子" in text:
        return True
    return False


def _extract_labeled_argument(message: str, name: str) -> str:
    match = re.search(
        rf"(?:^|[\s,;，；]){re.escape(name)}\s*[:=：]\s*(.+?)(?=$|[\r\n,;，；])",
        message,
        flags=re.IGNORECASE,
    )
    if match is None:
        return ""
    return match.group(1).strip().strip("\"'")


def _field_aliases(field_key: str, prop: Any) -> list[str]:
    aliases = [field_key]
    if isinstance(prop, dict):
        title = prop.get("title")
        if isinstance(title, str) and title.strip() and title.strip() not in aliases:
            aliases.append(title.strip())
    return aliases


def _recent_user_messages(context: RunContext) -> list[str]:
    history_pairs: list[tuple[str, str]] = []
    for item in context.history:
        role = (item.role or "").strip().lower()
        content = (item.content or "").strip()
        if content:
            history_pairs.append((role, content))
    if not history_pairs or history_pairs[-1][1] != context.message.strip():
        history_pairs.append(("user", context.message.strip()))

    recent: list[str] = []
    seen_user = False
    for role, content in reversed(history_pairs):
        if role in {"user", "human"}:
            recent.append(content)
            seen_user = True
            continue
        if role in {"assistant", "ai"} and _is_tool_guidance_message(content):
            continue
        if seen_user:
            break
    recent.reverse()
    return recent or [context.message.strip()]


def _is_tool_guidance_message(content: str) -> bool:
    return "如果想使用「" in content and "请在同一条或下一条消息里按下面补充" in content


def _with_xiaohongshu_defaults(message: str, arguments: dict[str, Any]) -> dict[str, Any]:
    user_request = message.strip()
    normalized = dict(arguments)
    normalized.setdefault("productName", _compact(user_request, 80) or "用户提供的产品或服务")
    normalized.setdefault("targetCustomer", "未指定目标用户")
    normalized.setdefault("style", "种草")
    normalized.setdefault("sellingPoints", user_request or "用户希望生成小红书文案")
    normalized.setdefault("extraInfo", "")
    return normalized


def _tool_result(
    tool_code: str,
    tool_call_id: int,
    arguments: dict[str, Any],
    task_id: int,
    task_status: str,
    content_text: str | None,
    resource_type: str | None,
) -> dict[str, Any]:
    return {
        "success": True,
        "toolCode": tool_code,
        "toolCallId": tool_call_id,
        "taskId": task_id,
        "status": task_status,
        "arguments": arguments,
        "resultSummary": content_text or "",
        "data": {
            "resourceType": resource_type,
            "contentText": content_text or "",
        },
        "summary": content_text or "",
    }


def _compact(value: str, max_length: int) -> str:
    normalized = re.sub(r"\s+", " ", value).strip()
    return normalized[:max_length]
