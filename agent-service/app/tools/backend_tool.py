import re
from typing import Any

from app.core.event_types import TOOL_FINISHED, TOOL_STARTED
from app.core.schemas import RunContext, RunEventCreate, ToolCallComplete, ToolCallCreate, ToolDescriptor


class ToolExecutionError(RuntimeError):
    pass


class BackendToolBridge:
    def __init__(self, backend_client) -> None:
        self.backend = backend_client

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
        await self.backend.append_event(
            context.runId,
            RunEventCreate(eventType=TOOL_STARTED, eventText=f"Tool {tool.toolCode} started", eventJson={"toolCode": tool.toolCode}),
        )
        result = {
            "toolCode": tool.toolCode,
            "summary": f"Tool {tool.toolName or tool.toolCode} accepted the request.",
            "arguments": arguments,
        }
        await self.backend.complete_tool_call(call.id, ToolCallComplete(resultJson=result))
        await self.backend.append_event(
            context.runId,
            RunEventCreate(eventType=TOOL_FINISHED, eventText=f"Tool {tool.toolCode} finished", eventJson=result),
        )
        return result


def _extract_labeled_argument(message: str, name: str) -> str:
    match = re.search(
        rf"(?:^|[\s,;，；]){re.escape(name)}\s*[:=：]\s*(.+?)(?=$|[\r\n,;，；])",
        message,
        flags=re.IGNORECASE,
    )
    if match is None:
        return ""
    return match.group(1).strip().strip("\"'")
