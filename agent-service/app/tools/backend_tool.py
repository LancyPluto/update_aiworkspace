from typing import Any

from app.core.event_types import TOOL_FINISHED, TOOL_STARTED
from app.core.schemas import RunContext, RunEventCreate, ToolCallComplete, ToolCallCreate, ToolDescriptor


class ToolExecutionError(RuntimeError):
    pass


class BackendToolBridge:
    def __init__(self, backend_client) -> None:
        self.backend = backend_client

    async def execute(self, context: RunContext, tool: ToolDescriptor) -> dict[str, Any]:
        arguments = {"userRequest": context.message}
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
