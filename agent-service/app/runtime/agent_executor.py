from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field
from typing import Any

from app.clients.model_client import ChatToolCall, ChatTurnResult
from app.config import settings
from app.core.budget_guard import BudgetExceeded, BudgetGuard, BudgetState
from app.core.event_types import (
    AGENT_STEP,
    CONTEXT_COMPACTED,
    MEMORY_CONTEXT_FROZEN,
    MESSAGE_COMPLETED,
    MESSAGE_DELTA,
    REFLECT_RETRY,
    RUN_STARTED,
    RUNTIME_SETTINGS_APPLIED,
    TOOL_CALL_EXECUTED,
    TOOL_CALL_LOOP_COMPLETED,
    TOOL_CALL_LOOP_STARTED,
    TOOL_CALL_REJECTED,
    TOOL_CALL_REQUESTED,
    TOOL_CONFIRMATION_REQUIRED,
    TOOL_SELECTED,
)
from app.core.intent_router import Intent
from app.core.schemas import ChatMessage, RunContext, RunEventCreate, ToolDescriptor
from app.core.user_attachment_priority import apply_user_selected_attachment_priority
from app.runtime.agent_graph.prompts import (
    chunk_text as _chunks,
    field_media_modality as _field_media_modality,
    first_url as _first_url,
)
from app.runtime.agent_graph.tool_specs import (
    FINISH_TOOL,
    MEMORY_ADD_TOOL,
    MEMORY_REMOVE_TOOL,
    MEMORY_REPLACE_TOOL,
    memory_tool_definitions,
    redact_large,
    tool_call_message_payload,
)
from app.runtime.context_manager import ContextManager, trim_tool_output_by_tokens
from app.runtime.memory_curator import looks_like_memory_management_turn
from app.runtime.memory_runtime import (
    WorkspaceMemoryRuntime,
    format_workspace_memory_context,
    memory_auto_save_enabled,
    memory_context_trace_payload,
)
from app.runtime.product_tool_call_loop import _alias_for_tool_code, build_tool_definitions
from app.runtime.product_tool_call_loop import _format_skill_catalog
from app.runtime.runtime_settings import runtime_budget_guard_for_context, runtime_int, runtime_settings_event_payload
from app.runtime.session_state import IMAGE_TOOL_PROMPT_COMPLETENESS_RULES, SESSION_STATE_INSTRUCTIONS, format_session_state_context
from app.runtime.skill_hydration import SkillHydrationService, hydration_message
from app.runtime.tool_call_loop import contains_pseudo_tool_call, strip_pseudo_tool_calls
from app.runtime.tool_confirmation_policy import ToolConfirmationPolicy
from app.runtime.tool_disclosure import EXPAND_TOOL, build_disclosed_definitions, expand_tool_definition
from app.runtime.tool_orchestrator import ToolOrchestrator
from app.tools.backend_tool import BackendToolBridge, ToolExecutionError, enforce_locked_field_defaults, finalize_generation_arguments
from app.tools.memory_tool import MemoryTool
from app.tools.registry import (
    ToolRegistry,
    infer_output_modality,
    requested_output_modality,
    resolve_canonical_tool_code,
    tool_supports_modality,
)

LOGGER = logging.getLogger(__name__)

AGENT_EXECUTOR_SYSTEM_PROMPT = (
    "You are a helpful workspace agent for an AI tool marketplace. "
    "When the user asks to create, generate, transform, or operate with a platform AI tool, "
    "you MUST call the matching function tool. Do not describe tool invocations in plain text. "
    "After tool results are returned, summarize the outcome for the user in natural language. "
    "For greetings, explanations, or meta questions, answer directly without calling tools.\n\n"
    f"{SESSION_STATE_INSTRUCTIONS}"
)

PSEUDO_TOOL_CALL_GUIDANCE = (
    "Do not output tool-call markup or say you are calling a tool in plain text. "
    "Use the provided function tools when a platform tool is needed."
)

MAX_ITER_FALLBACK = "抱歉，本次处理步骤较多，未能完全完成。请补充更具体的需求后重试。"


@dataclass(slots=True)
class PendingConfirmation:
    tool_code: str
    alias: str
    call_id: str
    arguments: dict[str, Any]


@dataclass(slots=True)
class AgentExecutorResult:
    final_answer: str = ""
    intent: str = Intent.GENERAL_CHAT.value
    pending_confirmation: PendingConfirmation | None = None
    messages: list[ChatMessage] = field(default_factory=list)
    iterations: int = 0
    stop_reason: str = ""
    last_tool_result: dict[str, Any] | None = None


class AgentExecutor:
    """OpenAI-style ReAct loop: chat_turn → execute tools → feed results → repeat."""

    def __init__(
        self,
        backend,
        model,
        *,
        tool_orchestrator: ToolOrchestrator | None = None,
        budget_guard: BudgetGuard | None = None,
        confirmation_policy: ToolConfirmationPolicy | None = None,
        memory_runtime: WorkspaceMemoryRuntime | None = None,
    ) -> None:
        self.backend = backend
        self.model = model
        self.tool_bridge = BackendToolBridge(backend, model_client=model)
        self.budget_guard = budget_guard or BudgetGuard()
        self.confirmation_policy = confirmation_policy or ToolConfirmationPolicy()
        self.memory_runtime = memory_runtime or WorkspaceMemoryRuntime(backend, model_client=model)
        self.tool_orchestrator = tool_orchestrator or ToolOrchestrator(
            self.tool_bridge,
            lambda: self._guard,
        )
        self.context_manager = ContextManager.from_settings(settings)
        self._guard = self.budget_guard
        self._budget = BudgetState()
        self._expanded_tool_codes: set[str] = set()
        self._artifacts: list[dict[str, Any]] = []
        self._tool_failures: dict[str, int] = {}
        self._max_tool_retries = 2
        self._memory_tool: MemoryTool | None = None
        self._workspace_memory_items = []
        self._workspace_memory_context = ""
        self._tool_defs: list[dict[str, Any]] = []
        self._aliases: dict[str, Any] = {}
        self._pending_schema_validation_tool_code: str | None = None
        self._hydrated_skill_codes: set[str] = set()
        self._skill_hydration = SkillHydrationService(backend)
        self._max_tool_executions = settings.agent_max_tool_calls

    async def run(
        self,
        context: RunContext,
        *,
        seed_messages: list[ChatMessage] | None = None,
        start_iteration: int = 0,
    ) -> AgentExecutorResult:
        await self._prepare_run(context, emit_run_started=seed_messages is None)
        messages = list(seed_messages) if seed_messages is not None else await self._build_initial_messages(context)
        memory_only = looks_like_memory_management_turn(context.message)
        self._refresh_tool_defs(context, memory_only=memory_only)

        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=TOOL_CALL_LOOP_STARTED,
                eventJson={
                    "kind": "agent_executor",
                    "tools": [item["function"]["name"] for item in self._tool_defs],
                    "maxModelTurns": self._max_iterations,
                    "maxToolExecutions": self._max_tool_executions,
                },
            ),
        )

        last_content = ""
        executed_tools = 0
        for iteration in range(start_iteration + 1, self._max_iterations + 1):
            try:
                self._guard.reserve_model_call(self._budget)
            except BudgetExceeded:
                raise

            turn = await self._chat_turn(messages)
            tool_calls = list(getattr(turn, "tool_calls", []) or [])
            content = turn.content or ""
            last_content = content

            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=AGENT_STEP,
                    eventText=f"step {iteration}",
                    eventJson={
                        "iteration": iteration,
                        "toolCallCount": len(tool_calls),
                        "finishReason": getattr(turn, "finish_reason", None),
                    },
                ),
            )

            if tool_calls and await self._maybe_hydrate_for_tool_calls(context, messages, tool_calls):
                continue

            messages.append(self._assistant_message(turn))

            if not tool_calls:
                if contains_pseudo_tool_call(content):
                    messages.append(ChatMessage(role="system", content=PSEUDO_TOOL_CALL_GUIDANCE))
                    continue
                if self._should_force_schema_validation_retry():
                    messages.append(ChatMessage(role="system", content=_schema_validation_retry_nudge()))
                    continue
                answer = strip_pseudo_tool_calls(content)
                await self._emit_loop_completed(context.runId, iteration, executed_tools)
                return AgentExecutorResult(
                    final_answer=answer,
                    intent=Intent.TOOL_USE.value if executed_tools > 0 else Intent.GENERAL_CHAT.value,
                    messages=messages,
                    iterations=iteration,
                    stop_reason="final_answer",
                )

            for call in tool_calls:
                outcome = await self._execute_tool_call(context, call, messages)
                if outcome.pending_confirmation is not None:
                    await self._save_checkpoint(context, messages, iteration, outcome.pending_confirmation)
                    return AgentExecutorResult(
                        pending_confirmation=outcome.pending_confirmation,
                        messages=messages,
                        iterations=iteration,
                        stop_reason="pending_confirmation",
                    )
                if outcome.finish_answer is not None:
                    await self._emit_loop_completed(context.runId, iteration, executed_tools + 1)
                    return AgentExecutorResult(
                        final_answer=outcome.finish_answer,
                        messages=messages,
                        iterations=iteration,
                        stop_reason="finish_tool",
                        last_tool_result=outcome.tool_result,
                    )
                messages.append(outcome.tool_message)
                if outcome.tool_result is not None:
                    executed_tools += 1
                    media_answer = resolve_media_answer(outcome.tool_result)
                    if media_answer:
                        await self._emit_loop_completed(context.runId, iteration, executed_tools)
                        return AgentExecutorResult(
                            final_answer=media_answer,
                            messages=messages,
                            iterations=iteration,
                            stop_reason="tool_media_result",
                            last_tool_result=outcome.tool_result,
                        )

        await self._emit_loop_completed(context.runId, self._max_iterations, executed_tools)
        fallback = strip_pseudo_tool_calls(last_content) or MAX_ITER_FALLBACK
        return AgentExecutorResult(
            final_answer=fallback,
            intent=Intent.TOOL_USE.value if executed_tools > 0 else Intent.GENERAL_CHAT.value,
            messages=messages,
            iterations=self._max_iterations,
            stop_reason="max_iterations",
        )

    async def run_after_confirmed_tool(
        self,
        context: RunContext,
        tool: ToolDescriptor,
        arguments: dict[str, Any],
        result: dict[str, Any],
    ) -> AgentExecutorResult:
        await self._prepare_run(context, emit_run_started=False)
        memory_only = looks_like_memory_management_turn(context.message)
        self._refresh_tool_defs(context, memory_only=memory_only)
        checkpoint = await self._load_checkpoint(context)
        if checkpoint and checkpoint.get("messages"):
            messages = [ChatMessage.model_validate(item) for item in checkpoint["messages"]]
            start_iteration = int(checkpoint.get("iteration") or 0)
            self._artifacts = list(checkpoint.get("artifacts") or [])
            self._expanded_tool_codes = set(checkpoint.get("expanded_tool_codes") or [])
        else:
            messages = await self._build_initial_messages(context)
            start_iteration = 0

        call_id = f"confirmed-{tool.toolCode}"
        alias = _alias_for_tool_code(tool.toolCode)
        messages.append(
            ChatMessage(
                role="assistant",
                content="",
                toolCalls=[tool_call_message_payload(call_id, alias, arguments)],
            )
        )
        messages.append(self._tool_message(call_id, alias, self._tool_result_for_model(result)))
        artifact = self._artifact_from_result(tool, result)
        self._artifacts.append(artifact)
        await self._clear_checkpoint(context)
        return await self.run(context, seed_messages=messages, start_iteration=start_iteration)

    async def _prepare_run(self, context: RunContext, *, emit_run_started: bool) -> None:
        self._guard = runtime_budget_guard_for_context(context, self.budget_guard)
        self._budget = BudgetState(credit_budget=context.creditBudget)
        self._artifacts = []
        self._tool_failures = {}
        self._expanded_tool_codes = set()
        self._hydrated_skill_codes = set()
        self._workspace_memory_items = []
        self._workspace_memory_context = ""
        self._max_iterations = runtime_int(
            context,
            "maxModelCalls",
            settings.agent_max_model_calls,
            1,
            50,
        )
        self._max_tool_executions = runtime_int(
            context,
            "maxToolCalls",
            settings.agent_max_tool_calls,
            1,
            50,
        )
        self._memory_tool = None
        if context.workspaceId and memory_auto_save_enabled(context):
            self._memory_tool = MemoryTool(self.backend, context.workspaceId, context.userId, run_id=context.runId)
        if emit_run_started:
            await self._emit_runtime_settings(context)
            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=RUN_STARTED,
                    eventText="Agent 已开始处理",
                    eventJson={"runId": context.runId, "engine": "agent_executor"},
                ),
            )

    async def _chat_turn(self, messages: list[ChatMessage]) -> ChatTurnResult:
        chat_turn = getattr(self.model, "chat_turn", None)
        if not callable(chat_turn):
            raise TypeError("model does not support chat_turn")
        return await chat_turn(messages, tools=self._tool_defs, tool_choice="auto")

    def _refresh_tool_defs(self, context: RunContext, *, memory_only: bool) -> None:
        if memory_only:
            self._tool_defs = memory_tool_definitions()
            self._aliases = {}
            return
        if settings.agent_tool_disclosure_enabled:
            product_defs, aliases = build_disclosed_definitions(
                context.availableTools,
                context,
                k=settings.agent_tool_shortlist_k,
                expanded_codes=self._expanded_tool_codes,
                artifacts=self._artifacts or None,
            )
        else:
            product_defs, aliases = build_tool_definitions(context.availableTools, context)
        tool_defs = list(product_defs)
        if settings.agent_tool_disclosure_enabled and context.availableTools:
            tool_defs.append(expand_tool_definition())
        if self._memory_tool is not None:
            tool_defs.extend(memory_tool_definitions())
        self._tool_defs = tool_defs
        self._aliases = aliases

    async def _build_initial_messages(self, context: RunContext) -> list[ChatMessage]:
        from app.core.attachment_catalog import user_message_for_llm
        from app.runtime.deep_agents_engine import DEFAULT_AGENT_SYSTEM_PROMPT, _compose_system_prompt, _format_file_context

        configured = _compose_system_prompt(
            context.agentSystemPrompt,
            context,
            DEFAULT_AGENT_SYSTEM_PROMPT,
        )
        messages: list[ChatMessage] = [
            ChatMessage(role="system", content=AGENT_EXECUTOR_SYSTEM_PROMPT),
            ChatMessage(role="system", content=configured),
            ChatMessage(role="system", content=IMAGE_TOOL_PROMPT_COMPLETENESS_RULES),
        ]
        memory_items = await self.memory_runtime.fetch_items(context)
        self._workspace_memory_items = memory_items
        memory_context = format_workspace_memory_context(memory_items)
        self._workspace_memory_context = memory_context
        if memory_context:
            messages.append(ChatMessage(role="system", content=f"<!-- frozen memory snapshot -->\n{memory_context}"))
            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=MEMORY_CONTEXT_FROZEN,
                    eventJson=memory_context_trace_payload(memory_context, source="agent_executor", items=memory_items),
                ),
            )
        elif context.workspaceId is not None:
            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=MEMORY_CONTEXT_FROZEN,
                    eventJson=memory_context_trace_payload("", source="agent_executor", items=[]),
                ),
            )
        context_manager = ContextManager.from_settings(settings, context.runtimeSettings)
        summary_message = context_manager.format_conversation_summary(context.conversationSummary)
        if summary_message:
            messages.append(summary_message)
        file_context = _format_file_context(context)
        if file_context:
            messages.append(ChatMessage(role="system", content=file_context))
        session_state_context = format_session_state_context(context)
        if session_state_context:
            messages.append(ChatMessage(role="system", content=session_state_context))
        skill_catalog = _format_skill_catalog(context)
        if skill_catalog:
            messages.append(ChatMessage(role="system", content=skill_catalog))
        await self._emit_context_compaction(context)
        messages.extend(context_manager.build_working_memory(context.history))
        messages.append(ChatMessage(role="user", content=user_message_for_llm(context)))
        return messages

    @dataclass(slots=True)
    class _ToolOutcome:
        tool_message: ChatMessage | None = None
        pending_confirmation: PendingConfirmation | None = None
        finish_answer: str | None = None
        tool_result: dict[str, Any] | None = None

    async def _maybe_hydrate_for_tool_calls(
        self,
        context: RunContext,
        messages: list[ChatMessage],
        tool_calls: list[ChatToolCall],
    ) -> bool:
        if not tool_calls:
            return False
        tool_code = resolve_canonical_tool_code(tool_calls[0].name, context.availableTools) or tool_calls[0].name
        result = await self._skill_hydration.hydrate_for_tool(context, tool_code, self._hydrated_skill_codes)
        if result is None:
            return False
        messages.append(hydration_message(result))
        return True

    async def _execute_tool_call(
        self,
        context: RunContext,
        call: ChatToolCall,
        messages: list[ChatMessage],
    ) -> _ToolOutcome:
        name = call.name
        call_id = call.id
        arguments = call.arguments if isinstance(call.arguments, dict) else {}

        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=TOOL_CALL_REQUESTED,
                eventJson={"kind": "agent_executor", "id": call_id, "name": name, "arguments": redact_large(arguments)},
            ),
        )

        if name == FINISH_TOOL:
            return self._ToolOutcome(finish_answer=str(arguments.get("answer") or "").strip())

        if name in {MEMORY_ADD_TOOL, MEMORY_REPLACE_TOOL, MEMORY_REMOVE_TOOL}:
            result = await self._execute_memory_tool(context, name, arguments)
            return self._ToolOutcome(tool_message=self._tool_message(call_id, name, result), tool_result=result)

        if name == EXPAND_TOOL:
            code = str(arguments.get("toolCode") or "").strip()
            registry = ToolRegistry(context)
            tool = registry.get(code)
            if tool is None:
                payload = {"success": False, "error": f"unknown toolCode: {code}"}
            else:
                self._expanded_tool_codes.add(code)
                self._refresh_tool_defs(context, memory_only=False)
                payload = {
                    "success": True,
                    "toolCode": code,
                    "callableAs": _alias_for_tool_code(code),
                    "note": "已展开完整参数，可在下一步直接调用该工具。",
                }
            return self._ToolOutcome(tool_message=self._tool_message(call_id, name, payload))

        registry = ToolRegistry(context)
        alias = self._aliases.get(name)
        tool = alias.tool if alias is not None else registry.get(name)
        if tool is None:
            resolved = resolve_canonical_tool_code(name, context.availableTools)
            if resolved:
                tool = registry.get(resolved)
        if tool is None:
            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=TOOL_CALL_REJECTED,
                    eventJson={"kind": "agent_executor", "id": call_id, "name": name, "reason": "tool_not_available"},
                ),
            )
            return self._ToolOutcome(
                tool_message=self._tool_message(call_id, name, {"success": False, "error": f"tool not available: {name}"}),
            )

        modality_error = self._modality_mismatch(context, tool)
        if modality_error:
            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=TOOL_CALL_REJECTED,
                    eventJson={"kind": "agent_executor", "id": call_id, "name": name, "reason": modality_error},
                ),
            )
            return self._ToolOutcome(
                tool_message=self._tool_message(call_id, name, {"success": False, "error": modality_error}),
            )

        execution_args = await self._build_product_arguments(context, tool, arguments)
        if not self.confirmation_policy.should_auto_call(context, tool):
            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=TOOL_CONFIRMATION_REQUIRED,
                    eventText=tool.toolCode,
                    eventJson={
                        "toolCode": tool.toolCode,
                        "toolName": tool.toolName,
                        "description": tool.description,
                        "creditCost": tool.estimatedCreditCost,
                        "inputSchema": tool.inputSchema,
                        "arguments": execution_args,
                    },
                ),
            )
            return self._ToolOutcome(
                pending_confirmation=PendingConfirmation(
                    tool_code=tool.toolCode,
                    alias=name,
                    call_id=call_id,
                    arguments=execution_args,
                ),
            )

        return await self._invoke_product_tool(context, tool, name, call_id, execution_args)

    async def _invoke_product_tool(
        self,
        context: RunContext,
        tool: ToolDescriptor,
        alias: str,
        call_id: str,
        execution_args: dict[str, Any],
    ) -> _ToolOutcome:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(eventType=TOOL_SELECTED, eventText=tool.toolCode, eventJson={"toolCode": tool.toolCode}),
        )
        try:
            result = await self.tool_orchestrator.execute_with_guard(
                context,
                tool,
                self._budget,
                arguments=execution_args,
            )
        except ToolExecutionError as exc:
            self._tool_failures[tool.toolCode] = self._tool_failures.get(tool.toolCode, 0) + 1
            exhausted = self._tool_failures[tool.toolCode] >= self._max_tool_retries
            details = getattr(exc, "details", {}) if hasattr(exc, "details") else {}
            if exc.error_code == "SCHEMA_VALIDATION" and not exhausted:
                self._pending_schema_validation_tool_code = tool.toolCode
            elif exc.error_code == "SCHEMA_VALIDATION":
                self._pending_schema_validation_tool_code = None
            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=REFLECT_RETRY,
                    eventText=tool.toolCode,
                    eventJson={
                        "toolCode": tool.toolCode,
                        "errorCode": exc.error_code,
                        "error": str(exc),
                        "attempt": self._tool_failures[tool.toolCode],
                        "retryable": not exhausted,
                        **({"details": details} if isinstance(details, dict) and details else {}),
                    },
                ),
            )
            if exc.error_code == "SCHEMA_VALIDATION" and exhausted:
                return self._ToolOutcome(finish_answer=_schema_validation_clarification(tool.toolCode, details))
            guidance = (
                "已多次失败，请不要再重试该工具，改用其他方式或直接向用户说明原因。"
                if exhausted
                else "请检查并修正参数后再重试一次；若无法修正，请向用户说明。"
            )
            payload = {
                "success": False,
                "toolCode": tool.toolCode,
                "error": str(exc),
                "errorCode": exc.error_code,
                "guidance": guidance,
                **({"schemaValidationError": details} if exc.error_code == "SCHEMA_VALIDATION" and isinstance(details, dict) else {}),
            }
            return self._ToolOutcome(tool_message=self._tool_message(call_id, alias, payload))

        if result.get("missing_tool_arguments"):
            payload = {
                "success": False,
                "toolCode": tool.toolCode,
                "missingArguments": result["missing_tool_arguments"],
                "guidance": "缺少必要参数，请补全后再调用。",
            }
            return self._ToolOutcome(tool_message=self._tool_message(call_id, alias, payload))

        if self._pending_schema_validation_tool_code == tool.toolCode:
            self._pending_schema_validation_tool_code = None
        artifact = self._artifact_from_result(tool, result)
        self._artifacts.append(artifact)
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=TOOL_CALL_EXECUTED,
                eventJson={
                    "kind": "agent_executor",
                    "id": call_id,
                    "name": alias,
                    "toolCode": tool.toolCode,
                    "success": True,
                },
            ),
        )
        return self._ToolOutcome(
            tool_message=self._tool_message(call_id, alias, self._tool_result_for_model(result)),
            tool_result=result,
        )

    async def _build_product_arguments(
        self,
        context: RunContext,
        tool: ToolDescriptor,
        model_args: dict[str, Any],
    ) -> dict[str, Any]:
        prepared = self.tool_bridge.build_arguments(
            context,
            tool,
            apply_placeholder_defaults=True,
            workspace_memory_context=self._workspace_memory_context,
        )
        for key, value in (model_args or {}).items():
            if value not in (None, ""):
                prepared[key] = value
        if "userRequest" in (model_args or {}) and "userRequest" not in (tool.inputSchema.get("properties") or {}):
            prompt_value = model_args.get("userRequest")
            if prompt_value:
                prepared.setdefault("prompt", prompt_value)
        prepared = self._backfill_artifacts(tool, prepared)
        prepared = enforce_locked_field_defaults(tool, prepared, user_message=context.message)
        prepared = _apply_workspace_memory_argument_overrides(context, tool, prepared, self._workspace_memory_items)
        prepared = apply_user_selected_attachment_priority(context, tool, prepared)
        return finalize_generation_arguments(context, tool, prepared)

    def _should_force_schema_validation_retry(self) -> bool:
        code = self._pending_schema_validation_tool_code
        if not code:
            return False
        return self._tool_failures.get(code, 0) < self._max_tool_retries

    def _backfill_artifacts(self, tool: ToolDescriptor, prepared: dict[str, Any]) -> dict[str, Any]:
        if not self._artifacts:
            return prepared
        properties = (tool.inputSchema or {}).get("properties") or {}
        if not isinstance(properties, dict):
            return prepared
        for key, spec in properties.items():
            if prepared.get(key) not in (None, ""):
                continue
            modality = _field_media_modality(key, spec if isinstance(spec, dict) else {})
            if not modality:
                continue
            url = self._latest_artifact_url(modality)
            if url:
                prepared[key] = url
        return prepared

    def _latest_artifact_url(self, modality: str) -> str | None:
        for artifact in reversed(self._artifacts):
            resource = str(artifact.get("resourceType") or "").lower()
            if modality in resource or (modality == "image" and resource in {"img", "picture"}):
                url = _first_url(artifact.get("contentText"))
                if url:
                    return url
        for artifact in reversed(self._artifacts):
            url = _first_url(artifact.get("contentText"))
            if url:
                return url
        return None

    async def _execute_memory_tool(self, context: RunContext, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        if self._memory_tool is None:
            return {"success": False, "error": "memory tool not available"}
        try:
            if name == MEMORY_ADD_TOOL:
                result = await self._memory_tool.add_memory(
                    memory_type=str(arguments.get("memory_type") or ""),
                    title=str(arguments.get("title") or ""),
                    content=str(arguments.get("content") or ""),
                    source_run_id=context.runId,
                )
            elif name == MEMORY_REPLACE_TOOL:
                result = await self._memory_tool.replace_memory(
                    memory_type=str(arguments.get("memory_type") or ""),
                    new_title=str(arguments.get("new_title") or ""),
                    new_content=str(arguments.get("new_content") or ""),
                )
            else:
                result = await self._memory_tool.remove_memory(int(arguments.get("memory_id")))
        except Exception as exc:
            return {"success": False, "error": str(exc)}
        return result if isinstance(result, dict) else {"success": False, "error": "invalid memory tool response"}

    def _modality_mismatch(self, context: RunContext, tool: ToolDescriptor) -> str | None:
        requested = requested_output_modality(context.message)
        selected = infer_output_modality(tool)
        if requested and selected and requested != selected:
            return f"output_modality_mismatch:{requested}!={selected}"
        return None

    def _assistant_message(self, turn: ChatTurnResult) -> ChatMessage:
        tool_calls = list(getattr(turn, "tool_calls", []) or [])
        payloads = [tool_call_message_payload(c.id, c.name, c.arguments) for c in tool_calls]
        return ChatMessage(role="assistant", content=turn.content or "", toolCalls=payloads or None)

    def _tool_message(self, call_id: str, name: str, payload: dict[str, Any]) -> ChatMessage:
        return ChatMessage(
            role="tool",
            content=trim_tool_output_by_tokens(
                json.dumps(payload, ensure_ascii=False),
                self.context_manager.tool_output_token_soft_limit,
            ),
            toolCallId=call_id,
            name=name,
        )

    def _tool_result_for_model(self, result: dict[str, Any]) -> dict[str, Any]:
        data = result.get("data") if isinstance(result, dict) else {}
        content_text = data.get("contentText", "") if isinstance(data, dict) else ""
        return {
            "success": True,
            "toolCode": result.get("toolCode"),
            "taskId": result.get("taskId"),
            "resourceType": data.get("resourceType") if isinstance(data, dict) else None,
            "result": trim_tool_output_by_tokens(content_text, self.context_manager.tool_output_token_soft_limit),
        }

    def _artifact_from_result(self, tool: ToolDescriptor, result: dict[str, Any]) -> dict[str, Any]:
        data = result.get("data") if isinstance(result, dict) else {}
        return {
            "toolCode": tool.toolCode,
            "taskId": result.get("taskId"),
            "resourceType": data.get("resourceType") if isinstance(data, dict) else None,
            "contentText": data.get("contentText") if isinstance(data, dict) else None,
        }

    async def _emit_runtime_settings(self, context: RunContext) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=RUNTIME_SETTINGS_APPLIED,
                eventText="Agent runtime settings applied",
                eventJson=runtime_settings_event_payload(
                    context,
                    guard=self._guard,
                    tool_timeout_seconds=self.tool_bridge.timeout_seconds,
                    tool_poll_interval_seconds=self.tool_bridge.poll_interval_seconds,
                ),
            ),
        )

    async def _emit_context_compaction(self, context: RunContext) -> None:
        history = list(context.history or [])
        if not history:
            return
        metrics = self.context_manager.build_history_metrics(history)
        before = int(metrics["estimatedTokensBefore"])
        after = int(metrics["estimatedTokensAfter"])
        saved_pct = round((before - after) / before * 100, 1) if before else 0.0
        memory_flushed = await self.memory_runtime.maybe_flush_before_compaction(
            context,
            estimated_tokens_before=before,
            saved_percent=saved_pct,
        )
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=CONTEXT_COMPACTED,
                eventText="context window compacted",
                eventJson={**metrics, "savedPercent": saved_pct, "memoryFlushTriggered": memory_flushed},
            ),
        )

    async def _emit_loop_completed(self, run_id: int, iterations: int, executed_tools: int) -> None:
        await self.backend.append_event(
            run_id,
            RunEventCreate(
                eventType=TOOL_CALL_LOOP_COMPLETED,
                eventJson={
                    "kind": "agent_executor",
                    "iterations": iterations,
                    "modelTurns": iterations,
                    "executedToolCalls": executed_tools,
                    "maxModelTurns": self._max_iterations,
                    "maxToolExecutions": self._max_tool_executions,
                },
            ),
        )

    async def _save_checkpoint(
        self,
        context: RunContext,
        messages: list[ChatMessage],
        iteration: int,
        pending: PendingConfirmation,
    ) -> None:
        saver = getattr(self.backend, "save_graph_checkpoint", None)
        if not callable(saver):
            return
        payload = {
            "kind": "agent_executor",
            "messages": [message.model_dump(by_alias=True) for message in messages],
            "iteration": iteration,
            "artifacts": self._artifacts,
            "expanded_tool_codes": sorted(self._expanded_tool_codes),
            "pending_confirmation": {
                "toolCode": pending.tool_code,
                "alias": pending.alias,
                "callId": pending.call_id,
                "arguments": pending.arguments,
            },
        }
        try:
            await saver(context.runId, json.dumps(payload, ensure_ascii=False))
        except Exception:
            LOGGER.warning("failed to persist agent executor checkpoint runId=%s", context.runId, exc_info=True)

    async def _load_checkpoint(self, context: RunContext) -> dict[str, Any] | None:
        loader = getattr(self.backend, "load_graph_checkpoint", None)
        if not callable(loader):
            return None
        try:
            blob = await loader(context.runId)
        except Exception:
            return None
        if not blob:
            return None
        try:
            data = json.loads(blob)
            if data.get("kind") != "agent_executor":
                return None
            return data
        except Exception:
            return None

    async def _clear_checkpoint(self, context: RunContext) -> None:
        clearer = getattr(self.backend, "clear_graph_checkpoint", None)
        if not callable(clearer):
            return
        try:
            await clearer(context.runId)
        except Exception:
            LOGGER.debug("failed to clear agent executor checkpoint runId=%s", context.runId, exc_info=True)


def _apply_workspace_memory_argument_overrides(
    context: RunContext,
    tool: ToolDescriptor,
    arguments: dict[str, Any],
    memory_items,
) -> dict[str, Any]:
    if not memory_items:
        return arguments
    normalized = dict(arguments)
    quality_key = _quality_argument_key(tool, normalized)
    if quality_key and _memory_prefers_gpt_image_low_quality(context, tool, memory_items):
        normalized[quality_key] = "low"
    return normalized


def _quality_argument_key(tool: ToolDescriptor, arguments: dict[str, Any]) -> str | None:
    properties = tool.inputSchema.get("properties", {}) if isinstance(tool.inputSchema, dict) else {}
    candidate_keys: list[str] = []
    if isinstance(properties, dict):
        candidate_keys.extend(key for key in properties if isinstance(key, str) and "quality" in key.lower())
    candidate_keys.extend(field.fieldKey for field in tool.fields if "quality" in (field.fieldKey or "").lower())
    candidate_keys.extend(key for key in arguments if isinstance(key, str) and "quality" in key.lower())
    seen: set[str] = set()
    for key in candidate_keys:
        if key in seen:
            continue
        seen.add(key)
        if _quality_key_accepts_value(tool, key, "low"):
            return key
    return None


def _quality_key_accepts_value(tool: ToolDescriptor, key: str, value: str) -> bool:
    properties = tool.inputSchema.get("properties", {}) if isinstance(tool.inputSchema, dict) else {}
    prop = properties.get(key) if isinstance(properties, dict) else None
    enum_values = prop.get("enum") if isinstance(prop, dict) else None
    if isinstance(enum_values, list) and enum_values:
        return value in {str(item).strip().lower() for item in enum_values}
    field = next((item for item in tool.fields if item.fieldKey == key), None)
    options = getattr(field, "options", None) if field is not None else None
    if isinstance(options, list) and options:
        values = set()
        for option in options:
            raw = option.get("value") if isinstance(option, dict) else option
            if raw not in (None, ""):
                values.add(str(raw).strip().lower())
        return value in values
    return True


def _memory_prefers_gpt_image_low_quality(context: RunContext, tool: ToolDescriptor, memory_items) -> bool:
    if _current_request_explicitly_sets_quality(context.message):
        return False
    tool_text = f"{tool.toolCode} {tool.toolName or ''} {tool.description or ''}".lower()
    if "gpt" not in tool_text or not tool_supports_modality(tool, "image"):
        return False
    memory_text = "\n".join(f"{getattr(item, 'title', '')}\n{getattr(item, 'content', '')}" for item in memory_items).lower()
    compact = re.sub(r"\s+", "", memory_text)
    if "gpt" not in compact or not any(token in compact for token in ("生图", "image", "图片", "生成图")):
        return False
    return any(
        token in compact
        for token in (
            "qualitylow",
            "质量low",
            "低质量",
            "最低质量",
            "低档",
            "low档",
            "用low",
            "使用low",
            "一律使用low",
            "一定用质量low",
        )
    )


def _current_request_explicitly_sets_quality(message: str) -> bool:
    compact = re.sub(r"\s+", "", (message or "").lower())
    if not compact:
        return False
    return any(
        token in compact
        for token in (
            "qualityhigh",
            "quality=high",
            "高质量",
            "最高质量",
            "高档",
            "qualitymedium",
            "quality=medium",
            "中等质量",
            "qualitylow",
            "quality=low",
            "低质量",
            "最低质量",
        )
    )


def _schema_validation_clarification(tool_code: str, details: Any) -> str:
    missing: list[str] = []
    latest_preview = ""
    if isinstance(details, dict):
        missing = [str(item) for item in details.get("missingFields") or [] if str(item)]
        latest_preview = str(details.get("latestGeneratedImagePromptPreview") or "").strip()
    fields = "、".join(missing) if missing else "必要的图片提示词字段"
    answer = (
        f"我需要补全图片工具 `{tool_code}` 的 {fields} 后才能继续。"
        "请提供完整的画面描述，或明确说明要沿用上一张图的哪些视觉元素以及本次要改变什么。"
    )
    if latest_preview:
        answer += f"\n\n当前可继承的上一张图视觉描述片段：{latest_preview}"
    return answer


def _schema_validation_retry_nudge() -> str:
    return (
        "You did not call the tool after a SchemaValidationError. "
        "Do not ask the user for clarification and do not explain the error. "
        "Immediately produce a valid function tool call. Read <SessionState>, fill the missing image prompt fields "
        "with complete visual text, and call the same image tool again now."
    )


async def emit_executor_answer(backend, run_id: int, answer: str) -> None:
    normalized = (answer or "").strip()
    if not normalized:
        return
    for chunk in _chunks(normalized, 6):
        await backend.append_event(
            run_id,
            RunEventCreate(eventType=MESSAGE_DELTA, eventText=chunk, eventJson={"delta": chunk}),
        )
    await backend.append_event(
        run_id,
        RunEventCreate(eventType=MESSAGE_COMPLETED, eventText=normalized, eventJson={"content": normalized}),
    )


def resolve_media_answer(result: dict[str, Any]) -> str | None:
    from app.runtime.deep_agents_engine import _is_structured_media_result

    data = result.get("data") if isinstance(result, dict) else {}
    content_text = data.get("contentText", "") if isinstance(data, dict) else ""
    if isinstance(content_text, str) and content_text.strip():
        if _is_structured_media_result(content_text):
            return content_text
    return None
