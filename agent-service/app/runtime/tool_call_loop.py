from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from app.clients.model_client import ChatToolCall
from app.core.event_types import (
    TOOL_CALL_EXECUTED,
    TOOL_CALL_LOOP_COMPLETED,
    TOOL_CALL_LOOP_STARTED,
    TOOL_CALL_REJECTED,
    TOOL_CALL_REQUESTED,
)
from app.core.schemas import ChatMessage, RunEventCreate
from app.tools.memory_tool import MemoryTool, _format_memory_tool_definitions


@dataclass(slots=True)
class ToolCallLoopResult:
    answer: str
    messages: list[ChatMessage]
    executed_tool_calls: int = 0


class AgentToolCallLoopExecutor:
    """Small OpenAI-style function-call loop for safe internal tools.

    v1 intentionally exposes only memory tools. Product generation tools stay on
    the existing backend Router/Validator path so LLM output cannot bypass cost,
    permission, confirmation, or tool binding checks.
    """

    def __init__(
        self,
        *,
        backend,
        model,
        run_id: int,
        memory_tool: MemoryTool,
        reserve_model_call: Callable[[], None] | None = None,
        max_iterations: int = 3,
        max_tool_calls_per_turn: int = 4,
    ) -> None:
        self.backend = backend
        self.model = model
        self.run_id = run_id
        self.memory_tool = memory_tool
        self.reserve_model_call = reserve_model_call
        self.max_iterations = max(1, max_iterations)
        self.max_tool_calls_per_turn = max(1, max_tool_calls_per_turn)
        self.allowed_tools = {
            "memory_add": self._execute_memory_add,
            "memory_replace": self._execute_memory_replace,
            "memory_remove": self._execute_memory_remove,
        }

    async def run(self, messages: list[ChatMessage]) -> ToolCallLoopResult:
        chat_turn = getattr(self.model, "chat_turn", None)
        if not callable(chat_turn):
            raise TypeError("model does not support chat_turn")

        working = list(messages)
        tools = _format_memory_tool_definitions()
        await self._event(TOOL_CALL_LOOP_STARTED, {"tools": sorted(self.allowed_tools)})
        executed = 0

        for iteration in range(self.max_iterations):
            self._reserve_model_call()
            turn = await chat_turn(working, tools=tools, tool_choice="auto")
            if not turn.tool_calls:
                await self._event(
                    TOOL_CALL_LOOP_COMPLETED,
                    {"iterations": iteration + 1, "executedToolCalls": executed, "finishReason": turn.finish_reason},
                )
                return ToolCallLoopResult(answer=turn.content or "", messages=working, executed_tool_calls=executed)

            selected_calls = turn.tool_calls[: self.max_tool_calls_per_turn]
            working.append(
                ChatMessage(
                    role="assistant",
                    content=turn.content or "",
                    toolCalls=[_tool_call_message_payload(call) for call in selected_calls],
                )
            )

            for call in selected_calls:
                await self._event(
                    TOOL_CALL_REQUESTED,
                    {"id": call.id, "name": call.name, "arguments": _redact_large(call.arguments)},
                )
                result = await self._execute_call(call)
                executed += 1
                working.append(
                    ChatMessage(
                        role="tool",
                        content=json.dumps(result, ensure_ascii=False),
                        toolCallId=call.id,
                        name=call.name,
                    )
                )

        self._reserve_model_call()
        final_turn = await chat_turn(working, tool_choice="none")
        await self._event(
            TOOL_CALL_LOOP_COMPLETED,
            {"iterations": self.max_iterations, "executedToolCalls": executed, "finishReason": final_turn.finish_reason},
        )
        return ToolCallLoopResult(answer=final_turn.content or "", messages=working, executed_tool_calls=executed)

    async def _execute_call(self, call: ChatToolCall) -> dict[str, Any]:
        executor = self.allowed_tools.get(call.name)
        if executor is None:
            result = {"success": False, "error": f"tool not allowed: {call.name}"}
            await self._event(TOOL_CALL_REJECTED, {"id": call.id, "name": call.name, "reason": "tool_not_allowed"})
            return result
        try:
            result = await executor(call.arguments)
        except Exception as exc:
            result = {"success": False, "error": str(exc)}
            await self._event(TOOL_CALL_REJECTED, {"id": call.id, "name": call.name, "reason": "execution_error"})
            return result
        await self._event(
            TOOL_CALL_EXECUTED,
            {"id": call.id, "name": call.name, "success": bool(result.get("success")), "result": _redact_large(result)},
        )
        return result

    async def _execute_memory_add(self, args: dict[str, Any]) -> dict[str, Any]:
        missing = [name for name in ("memory_type", "title", "content") if not _present(args.get(name))]
        if missing:
            return {"success": False, "error": "missing required arguments", "missing": missing}
        return await self.memory_tool.add_memory(
            memory_type=str(args.get("memory_type")),
            title=str(args.get("title")),
            content=str(args.get("content")),
            source_run_id=self.run_id,
        )

    async def _execute_memory_replace(self, args: dict[str, Any]) -> dict[str, Any]:
        missing = [name for name in ("memory_type", "new_title", "new_content") if not _present(args.get(name))]
        if missing:
            return {"success": False, "error": "missing required arguments", "missing": missing}
        return await self.memory_tool.replace_memory(
            memory_type=str(args.get("memory_type")),
            new_title=str(args.get("new_title")),
            new_content=str(args.get("new_content")),
        )

    async def _execute_memory_remove(self, args: dict[str, Any]) -> dict[str, Any]:
        memory_id = args.get("memory_id")
        if memory_id in (None, ""):
            return {"success": False, "error": "missing required arguments", "missing": ["memory_id"]}
        try:
            parsed_id = int(memory_id)
        except (TypeError, ValueError):
            return {"success": False, "error": "memory_id must be an integer"}
        return await self.memory_tool.remove_memory(parsed_id)

    def _reserve_model_call(self) -> None:
        if self.reserve_model_call is not None:
            self.reserve_model_call()

    async def _event(self, event_type: str, payload: dict[str, Any]) -> None:
        try:
            await self.backend.append_event(self.run_id, RunEventCreate(eventType=event_type, eventJson=payload))
        except Exception:
            pass


def _tool_call_message_payload(call: ChatToolCall) -> dict[str, Any]:
    return {
        "id": call.id,
        "type": "function",
        "function": {
            "name": call.name,
            "arguments": json.dumps(call.arguments, ensure_ascii=False, separators=(",", ":")),
        },
    }


def _present(value: Any) -> bool:
    return value is not None and str(value).strip() != ""


def _redact_large(value: Any) -> Any:
    text = json.dumps(value, ensure_ascii=False, default=str)
    if len(text) <= 1200:
        return value
    return {"preview": text[:1200], "truncated": True}
