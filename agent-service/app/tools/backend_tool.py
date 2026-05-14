import asyncio
import re
import time
from typing import Any

from app.config import settings
from app.core.event_types import TOOL_TASK_DISPATCHED, TOOL_TASK_PROGRESS
from app.core.schemas import RunContext, RunEventCreate, TaskCreate, ToolCallComplete, ToolCallCreate, ToolCallFail, ToolDescriptor


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
    ) -> None:
        self.backend = backend_client
        self.timeout_seconds = timeout_seconds or settings.agent_tool_execution_timeout_seconds
        self.poll_interval_seconds = poll_interval_seconds or settings.agent_tool_poll_interval_seconds

    def build_arguments(self, context: RunContext, tool: ToolDescriptor) -> dict[str, Any]:
        arguments: dict[str, Any] = {"userRequest": context.message}
        properties = tool.inputSchema.get("properties", {})
        if not isinstance(properties, dict):
            return arguments
        for name in properties:
            if isinstance(name, str) and name != "userRequest":
                value = _extract_labeled_argument(context.message, name)
                if value:
                    arguments[name] = value
        if tool.toolCode == "xiaohongshu_copywriting":
            arguments = _with_xiaohongshu_defaults(context.message, arguments)
        return arguments

    def missing_required_arguments(self, context: RunContext, tool: ToolDescriptor) -> list[str]:
        arguments = self.build_arguments(context, tool)
        required = tool.inputSchema.get("required", [])
        if not isinstance(required, list):
            return []
        return [
            name
            for name in required
            if isinstance(name, str) and (name not in arguments or arguments[name] in (None, ""))
        ]

    async def execute(self, context: RunContext, tool: ToolDescriptor) -> dict[str, Any]:
        arguments = self.build_arguments(context, tool)
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
            arguments,
            task_detail.taskId,
            task_detail.status,
            content_text,
            task_detail.result.resourceType if task_detail.result else None,
        )
        await self.backend.complete_tool_call(call.id, ToolCallComplete(resultJson=result))
        return result

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


def _extract_labeled_argument(message: str, name: str) -> str:
    match = re.search(
        rf"(?:^|[\s,;，；]){re.escape(name)}\s*[:=：]\s*(.+?)(?=$|[\r\n,;，；])",
        message,
        flags=re.IGNORECASE,
    )
    if match is None:
        return ""
    return match.group(1).strip().strip("\"'")


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
    arguments: dict[str, Any],
    task_id: int,
    task_status: str,
    content_text: str | None,
    resource_type: str | None,
) -> dict[str, Any]:
    return {
        "success": True,
        "toolCode": tool_code,
        "taskId": task_id,
        "status": task_status,
        "arguments": arguments,
        "data": {
            "resourceType": resource_type,
            "contentText": content_text or "",
        },
        "summary": content_text or "",
    }


def _compact(value: str, max_length: int) -> str:
    normalized = re.sub(r"\s+", " ", value).strip()
    return normalized[:max_length]
