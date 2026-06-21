from __future__ import annotations

import json
import re
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
from app.config import settings
from app.core.schemas import ChatMessage, RunEventCreate
from app.runtime.context_manager import trim_tool_output
from app.tools.memory_tool import MemoryTool, _format_memory_tool_definitions


MEMORY_TASK_COMPLETE_FALLBACK = "已根据你的要求更新长期记忆。"

TOOL_REJECTION_GUIDANCE = (
    "你刚才请求的工具不可用。本回合仅允许 memory 工具。"
    "请用自然语言回复用户，不要输出任何工具调用格式。"
)


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

    async def run(
        self,
        messages: list[ChatMessage],
        *,
        explicit_memory_request: bool = False,
    ) -> ToolCallLoopResult:
        chat_turn = getattr(self.model, "chat_turn", None)
        if not callable(chat_turn):
            raise TypeError("model does not support chat_turn")

        working = list(messages)
        tools = _format_memory_tool_definitions()
        await self._event(TOOL_CALL_LOOP_STARTED, {"tools": sorted(self.allowed_tools)})
        executed = 0
        memory_task_completed = False

        for iteration in range(self.max_iterations):
            if memory_task_completed:
                break
            self._reserve_model_call()
            turn = await chat_turn(working, tools=tools, tool_choice="auto")
            if not turn.tool_calls:
                answer = finalize_loop_answer(turn.content or "", explicit_memory_request=explicit_memory_request)
                await self._event(
                    TOOL_CALL_LOOP_COMPLETED,
                    {"iterations": iteration + 1, "executedToolCalls": executed, "finishReason": turn.finish_reason},
                )
                return ToolCallLoopResult(answer=answer, messages=working, executed_tool_calls=executed)

            selected_calls = turn.tool_calls[: self.max_tool_calls_per_turn]
            working.append(
                ChatMessage(
                    role="assistant",
                    content=turn.content or "",
                    toolCalls=[_tool_call_message_payload(call) for call in selected_calls],
                )
            )

            rejected_non_memory = False
            for call in selected_calls:
                await self._event(
                    TOOL_CALL_REQUESTED,
                    {"id": call.id, "name": call.name, "arguments": _redact_large(call.arguments)},
                )
                result = await self._execute_call(call)
                executed += 1
                if call.name not in self.allowed_tools:
                    rejected_non_memory = True
                elif result.get("success") and explicit_memory_request and call.name in {"memory_add", "memory_replace"}:
                    memory_task_completed = True
                working.append(
                    ChatMessage(
                        role="tool",
                        content=trim_tool_output(
                            json.dumps(result, ensure_ascii=False),
                            max(1, settings.agent_tool_output_char_limit),
                        ),
                        toolCallId=call.id,
                        name=call.name,
                    )
                )
            if rejected_non_memory:
                working.append(ChatMessage(role="system", content=TOOL_REJECTION_GUIDANCE))

        self._reserve_model_call()
        final_turn = await chat_turn(working, tool_choice="none")
        answer = finalize_loop_answer(final_turn.content or "", explicit_memory_request=explicit_memory_request)
        await self._event(
            TOOL_CALL_LOOP_COMPLETED,
            {"iterations": self.max_iterations, "executedToolCalls": executed, "finishReason": final_turn.finish_reason},
        )
        return ToolCallLoopResult(answer=answer, messages=working, executed_tool_calls=executed)

    async def _execute_call(self, call: ChatToolCall) -> dict[str, Any]:
        executor = self.allowed_tools.get(call.name)
        if executor is None:
            result = {"success": False, "error": f"tool not allowed: {call.name}"}
            await self._event(TOOL_CALL_REJECTED, {"kind": "internal", "id": call.id, "name": call.name, "reason": "tool_not_allowed"})
            return result
        try:
            result = await executor(call.arguments)
        except Exception as exc:
            result = {"success": False, "error": str(exc)}
            await self._event(TOOL_CALL_REJECTED, {"kind": "internal", "id": call.id, "name": call.name, "reason": "execution_error"})
            return result
        await self._event(
            TOOL_CALL_EXECUTED,
            {"kind": "internal", "id": call.id, "name": call.name, "success": bool(result.get("success")), "result": _redact_large(result)},
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


def contains_pseudo_tool_call(text: str) -> bool:
    lowered = str(text or "").lower()
    return (
        "dsml" in lowered
        or "tool_calls" in lowered
        or "<｜｜dsml｜｜" in lowered
        or "invoke name=" in lowered
    )


def strip_pseudo_tool_calls(text: str) -> str:
    cleaned = str(text or "").strip()
    if not cleaned or not contains_pseudo_tool_call(cleaned):
        return cleaned
    cleaned = re.sub(r"<｜｜DSML｜｜[\s\S]*", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"<\|DSML\|>[\s\S]*", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"invoke\s+name=\"[^\"]*\"[\s\S]*", "", cleaned, flags=re.IGNORECASE)
    return cleaned.strip()


def finalize_loop_answer(answer: str, *, explicit_memory_request: bool = False) -> str:
    had_pseudo = contains_pseudo_tool_call(answer)
    cleaned = strip_pseudo_tool_calls(answer)
    if had_pseudo and (explicit_memory_request or not cleaned):
        return MEMORY_TASK_COMPLETE_FALLBACK
    if cleaned:
        return cleaned
    if explicit_memory_request:
        return MEMORY_TASK_COMPLETE_FALLBACK
    return cleaned


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
