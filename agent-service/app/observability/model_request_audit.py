from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from contextlib import contextmanager
from contextvars import ContextVar
import hashlib
import json
import logging
import re
from typing import Any

from app.clients.model_client import ChatTurnResult, ModelClient, StreamPart
from app.core.schemas import ChatMessage


logger = logging.getLogger(__name__)
_stage: ContextVar[str] = ContextVar("agent_model_request_stage", default="model.request")
_iteration: ContextVar[int | None] = ContextVar("agent_model_request_iteration", default=None)
_SKILL_CODE_PATTERN = re.compile(r'<SkillHydration\s+[^>]*skill_code="([^"]+)"', re.IGNORECASE)


@contextmanager
def model_audit_scope(stage: str, iteration: int | None = None):
    stage_token = _stage.set(stage)
    iteration_token = _iteration.set(iteration)
    try:
        yield
    finally:
        _iteration.reset(iteration_token)
        _stage.reset(stage_token)


class ModelRequestAuditRecorder:
    """Buffers audit writes so observability never delays a model request."""

    def __init__(self, backend: Any, run_id: int, *, flush_delay_seconds: float = 0.2) -> None:
        self.backend = backend
        self.run_id = run_id
        self.flush_delay_seconds = flush_delay_seconds
        self._sequence = 0
        self._pending: list[dict[str, Any]] = []
        self._flush_task: asyncio.Task | None = None

    def record(
        self,
        *,
        invocation: str,
        model: ModelClient,
        messages: list[ChatMessage],
        tools: list[dict[str, Any]] | None,
        tool_choice: str | dict[str, Any] | None = None,
    ) -> None:
        self._sequence += 1
        serialized_messages = [_serialize_message(message) for message in messages]
        serialized_tools = tools or []
        payload = {
            "invocation": invocation,
            "messages": serialized_messages,
            "tools": serialized_tools,
            "toolChoice": tool_choice,
            "contentSha256": _sha256({"messages": serialized_messages, "tools": serialized_tools, "toolChoice": tool_choice}),
        }
        skill_codes = sorted(set(_skill_codes(serialized_messages)))
        self._pending.append({
            "requestSequence": self._sequence,
            "requestStage": _resolved_stage(invocation, serialized_messages, serialized_tools, skill_codes),
            "iterationNo": _iteration.get(),
            "modelProviderCode": str(getattr(model, "model_provider", "") or ""),
            "modelName": str(getattr(model, "model_name", "") or ""),
            "messageCount": len(serialized_messages),
            "toolCount": len(serialized_tools),
            "estimatedInputTokens": max(1, len(json.dumps(payload, ensure_ascii=False, default=str)) // 4),
            "skillCodes": skill_codes,
            "payload": payload,
        })
        if len(self._pending) >= 10:
            self._schedule_flush(delay=0)
        elif self._flush_task is None or self._flush_task.done():
            self._schedule_flush(delay=self.flush_delay_seconds)

    async def flush(self) -> None:
        current = asyncio.current_task()
        scheduled = self._flush_task
        if scheduled is not None and scheduled is not current and not scheduled.done():
            await scheduled
            if not self._pending:
                return
        self._flush_task = None
        if not self._pending:
            return
        batch, self._pending = self._pending[:50], self._pending[50:]
        writer = getattr(self.backend, "record_model_request_snapshots", None)
        if not callable(writer):
            return
        try:
            await writer(self.run_id, batch)
        except Exception:
            logger.warning("Model request audit write failed, runId=%s, count=%s", self.run_id, len(batch), exc_info=True)
        if self._pending:
            self._schedule_flush(delay=0)

    def _schedule_flush(self, *, delay: float) -> None:
        async def _delayed_flush() -> None:
            if delay:
                await asyncio.sleep(delay)
            await self.flush()

        if self._flush_task is None or self._flush_task.done():
            self._flush_task = asyncio.create_task(_delayed_flush())

    def schedule_immediate_flush(self) -> None:
        if not self._pending:
            return
        scheduled = self._flush_task
        if scheduled is not None and not scheduled.done():
            scheduled.cancel()
        self._flush_task = None
        self._schedule_flush(delay=0)


class AuditedModelClient:
    def __init__(self, delegate: ModelClient, recorder: ModelRequestAuditRecorder) -> None:
        self._delegate = delegate
        self.audit_recorder = recorder

    def __getattr__(self, name: str):
        return getattr(self._delegate, name)

    async def chat(self, messages: list[ChatMessage], tools: list[dict[str, Any]] | None = None) -> str:
        self.audit_recorder.record(invocation="chat", model=self._delegate, messages=messages, tools=tools)
        return await self._delegate.chat(messages, tools=tools)

    async def chat_turn(
        self,
        messages: list[ChatMessage],
        tools: list[dict[str, Any]] | None = None,
        tool_choice: str | dict[str, Any] | None = None,
    ) -> ChatTurnResult:
        self.audit_recorder.record(
            invocation="chat_turn",
            model=self._delegate,
            messages=messages,
            tools=tools,
            tool_choice=tool_choice,
        )
        return await self._delegate.chat_turn(messages, tools=tools, tool_choice=tool_choice)

    async def chat_stream_parts(
        self,
        messages: list[ChatMessage],
        tools: list[dict[str, Any]] | None = None,
    ) -> AsyncIterator[StreamPart]:
        self.audit_recorder.record(invocation="chat_stream_parts", model=self._delegate, messages=messages, tools=tools)
        async for part in self._delegate.chat_stream_parts(messages, tools=tools):
            yield part

    async def chat_stream(
        self,
        messages: list[ChatMessage],
        tools: list[dict[str, Any]] | None = None,
    ) -> AsyncIterator[str]:
        self.audit_recorder.record(invocation="chat_stream", model=self._delegate, messages=messages, tools=tools)
        async for part in self._delegate.chat_stream(messages, tools=tools):
            yield part

    async def flush_audit(self) -> None:
        await self.audit_recorder.flush()

    def schedule_audit_flush(self) -> None:
        self.audit_recorder.schedule_immediate_flush()


def _serialize_message(message: ChatMessage | Any) -> dict[str, Any]:
    if hasattr(message, "model_dump"):
        return message.model_dump(mode="json", by_alias=True, exclude_none=True)
    if isinstance(message, dict):
        return message
    return {"role": str(getattr(message, "role", "unknown")), "content": str(getattr(message, "content", message))}


def _skill_codes(messages: list[dict[str, Any]]) -> list[str]:
    found: list[str] = []
    for message in messages:
        content = message.get("content")
        if isinstance(content, str):
            found.extend(_SKILL_CODE_PATTERN.findall(content))
    return found


def _resolved_stage(invocation: str, messages: list[dict[str, Any]], tools: list[dict[str, Any]], skill_codes: list[str]) -> str:
    explicit = _stage.get()
    if explicit != "model.request":
        return explicit
    if skill_codes:
        return "skill.hydrated.retry"
    if invocation.startswith("chat_stream"):
        return "response.stream"
    if tools:
        return "tool.selection"
    combined = " ".join(str(message.get("content") or "")[:500] for message in messages[-3:]).lower()
    if "summary" in combined or "总结" in combined:
        return "memory.summary"
    return "conversation"


def _sha256(value: Any) -> str:
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()
