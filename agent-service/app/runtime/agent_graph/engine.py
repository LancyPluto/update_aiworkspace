from __future__ import annotations

import json
import hashlib
import logging
from typing import Any

from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt

from app.config import settings
from app.core.budget_guard import BudgetExceeded, BudgetGuard, BudgetState
from app.core.event_types import (
    AGENT_STEP,
    CONTEXT_COMPACTED,
    TOOL_DISCLOSURE,
    MEMORY_CONTEXT_FROZEN,
    MESSAGE_COMPLETED,
    MESSAGE_DELTA,
    REASONING_DELTA,
    PLAN_UPDATED,
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
from app.routing.types import Intent
from app.routing.state_guard import StateGuard
from app.routing.helpers import looks_like_tool_request
from app.routing import semantic_tool_recall
from app.routing.v2.unified_router import UnifiedSemanticRouter
from app.core.preferred_tool_bias import resolve_preferred_tool
from app.core.attachment_catalog import build_user_message_content
from app.core.schemas import (
    AgentRouteDebugResponse,
    AgentRouteDebugTool,
    ChatMessage,
    RunComplete,
    RunContext,
    RunEventCreate,
    RunFail,
    ToolDescriptor,
)
from app.core.user_attachment_priority import apply_user_selected_attachment_priority
from app.observability.model_request_audit import model_audit_scope
from app.runtime.agent_graph.prompts import (
    GRAPH_SYSTEM_PROMPT,
    chunk_text as _chunks,
    estimate_tokens as _estimate_tokens,
    field_media_modality as _field_media_modality,
    first_url as _first_url,
    format_available_tools_prompt as _format_available_tools_prompt,
    format_file_context as _format_file_context,
)
from app.runtime.agent_graph.state import (
    AgentState,
    deserialize_checkpoint,
    serialize_checkpoint,
)
from app.runtime.agent_graph.backend_checkpointer import BackendCheckpointSaver
from app.runtime.context_manager import ContextManager, trim_tool_output_by_tokens
from app.runtime.product_tool_call_loop import _alias_for_tool_code
from app.runtime.product_tool_call_loop import _format_skill_catalog
from app.runtime.skill_hydration import SkillHydrationService, hydration_message
from app.runtime.tool_disclosure import (
    EXPAND_TOOL,
    build_disclosed_definitions,
    expand_tool_definition,
    format_tool_catalog,
)
from app.runtime.agent_graph.tool_specs import (
    FINISH_TOOL,
    MEMORY_ADD_TOOL,
    MEMORY_REMOVE_TOOL,
    MEMORY_REPLACE_TOOL,
    PLAN_TOOL,
    finish_tool_definition,
    memory_tool_definitions,
    planning_tool_definition,
    product_tool_definitions,
    redact_large,
    tool_call_message_payload,
)
from app.runtime.file_context_runtime import WorkspaceFileRuntime
from app.runtime.memory_curator import MemoryCuratorService
from app.runtime.memory_runtime import (
    WorkspaceMemoryRuntime,
    format_workspace_memory_context,
    memory_auto_save_enabled,
    memory_context_trace_payload,
)
from app.runtime.route_readiness import build_route_readiness
from app.runtime.runtime_settings import (
    runtime_budget_guard_for_context,
    runtime_int,
    runtime_settings_event_payload,
)
from app.runtime.session_state import IMAGE_TOOL_PROMPT_COMPLETENESS_RULES, SESSION_STATE_INSTRUCTIONS, format_session_state_context
from app.runtime.tool_orchestrator import ToolOrchestrator
from app.security.prompt_guard import PromptGuard
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


class AgentGraphEngine:
    """Multi-step agentic loop built on a LangGraph ``StateGraph``.

    The model selects tools via native function calling; every product tool is
    executed through :class:`ToolOrchestrator` so credit, permission, and
    confirmation guards are always enforced. The model never executes a tool
    directly or bypasses the backend task pipeline.
    """

    def __init__(
        self,
        backend_client,
        model_client,
        *,
        intent_router: StateGuard | None = None,
        budget_guard: BudgetGuard | None = None,
        prompt_guard: PromptGuard | None = None,
    ) -> None:
        self.backend = backend_client
        self.model = model_client
        self.intent_router = intent_router or StateGuard()
        self.prompt_guard = prompt_guard or PromptGuard()
        self.budget_guard = budget_guard or BudgetGuard(
            max_model_calls=settings.agent_max_model_calls,
            max_tool_calls=settings.agent_max_tool_calls,
            model_call_cost=settings.agent_model_call_cost,
            default_consumed_credits=settings.agent_default_consumed_credits,
        )
        self.tool_bridge = BackendToolBridge(backend_client, model_client=model_client)
        self.tool_orchestrator = ToolOrchestrator(self.tool_bridge, lambda: self._guard)
        self.memory_curator = MemoryCuratorService()
        self.memory_runtime = WorkspaceMemoryRuntime(backend_client, self.memory_curator, model_client=model_client)
        self.file_runtime = WorkspaceFileRuntime(backend_client)
        self.context_manager = ContextManager.from_settings(settings)
        self.unified_router = UnifiedSemanticRouter(backend_client, model_client)
        semantic_tool_recall.set_recall_model_client(model_client)
        self._memory_tool_executed_runs: set[int] = set()

        # Per-run scratch state (engines are constructed per request).
        self._guard: BudgetGuard = self.budget_guard
        self._budget: BudgetState = BudgetState()
        self._context: RunContext | None = None
        self._tool_defs: list[dict[str, Any]] = []
        self._aliases: dict[str, Any] = {}
        self._memory_tool: MemoryTool | None = None
        self._max_iterations: int = settings.agent_graph_max_iterations
        self._max_tool_executions: int = settings.agent_max_tool_calls
        self._max_tool_retries: int = 2
        self._run_artifacts: list[dict[str, Any]] = []
        self._tool_failures: dict[str, int] = {}
        self._expanded_tool_codes: set[str] = set()
        self._pending_schema_validation_tool_code: str | None = None
        self._hydrated_skill_codes: set[str] = set()
        self._skill_hydration = SkillHydrationService(backend_client)
        self._memory_tools_enabled: bool = False
        self._compiled = None

    # ------------------------------------------------------------------ #
    # Public engine API
    # ------------------------------------------------------------------ #
    async def run(self, context: RunContext) -> None:
        try:
            await self._prepare_run(context)
            guard_result = self.prompt_guard.inspect(context.message)
            if guard_result.rejected:
                await self._emit_answer(context.runId, guard_result.message or "")
                await self._complete_run(context, guard_result.message or "", intent=Intent.SECURITY_REJECTED.value)
                return
            messages = await self._base_messages(context)
            await self._run_graph(context, self._fresh_state(messages))
        except BudgetExceeded as exc:
            await self._fail_run(context.runId, exc.error_code, exc.message)
        except ToolExecutionError as exc:
            if exc.error_code == "AGENT_CANCELLED":
                await self._clear_checkpoint(context)
                return
            await self._fail_run(context.runId, exc.error_code or "TOOL_CALL_FAILED", str(exc))
        except Exception as exc:  # pragma: no cover - defensive runtime boundary.
            LOGGER.exception("agent graph run failed runId=%s", context.runId)
            await self._fail_run(context.runId, "AGENT_INTERNAL_ERROR", str(exc))

    async def _check_cancelled(self) -> None:
        context = self._context
        if context is None:
            return
        status = str(getattr(context, "status", "") or "").upper()
        if status in {"CANCELLED", "TIMEOUT"}:
            raise ToolExecutionError("Agent run was cancelled", "AGENT_CANCELLED")
        loader = getattr(self.backend, "get_run_context", None)
        if loader is not None:
            refreshed = await loader(context.runId)
            if str(getattr(refreshed, "status", "") or "").upper() in {"CANCELLED", "TIMEOUT"}:
                raise ToolExecutionError("Agent run was cancelled", "AGENT_CANCELLED")

    async def run_confirmed_tool(self, context: RunContext, tool_code: str) -> None:
        try:
            await self._prepare_run(context, emit_run_started=False)
            if self._compiled is None:
                self._compiled = self._build_graph()
            final_state: AgentState = await self._compiled.ainvoke(
                Command(resume={"toolCode": tool_code, "approved": True}),
                config={"recursion_limit": max(8, self._max_iterations * 4), "configurable": {"thread_id": f"agent-run:{context.runId}"}},
            )
            if final_state.get("__interrupt__"):
                return
            answer = final_state.get("final_answer") or ""
            await self._clear_checkpoint(context)
            await self._complete_run(context, answer, intent="agent_graph")
        except BudgetExceeded as exc:
            await self._fail_run(context.runId, exc.error_code, exc.message)
        except ToolExecutionError as exc:
            if exc.error_code == "AGENT_CANCELLED":
                await self._clear_checkpoint(context)
                return
            await self._fail_run(context.runId, exc.error_code or "TOOL_CALL_FAILED", str(exc))
        except Exception as exc:  # pragma: no cover
            LOGGER.exception("agent graph confirmed-tool run failed runId=%s tool=%s", context.runId, tool_code)
            await self._fail_run(context.runId, "AGENT_INTERNAL_ERROR", str(exc))

    async def debug_route(self, context: RunContext) -> AgentRouteDebugResponse:
        requested_modality = requested_output_modality(context.message)
        guard_intent = self.intent_router.classify(context)
        readiness = await build_route_readiness(
            context,
            self.model,
            guard_intent=guard_intent,
        )
        return AgentRouteDebugResponse(
            intent="agent_graph",
            confidence=1.0,
            selectedToolCode=None,
            candidateToolCodes=[],
            clarifyingQuestion=None,
            decisionSource="agent_graph",
            reason="multi_step_graph_engine",
            requestedOutputModality=requested_modality,
            visibleToolCount=len(context.availableTools),
            visibleTools=[
                AgentRouteDebugTool(toolCode=t.toolCode, toolName=t.toolName, autoCallable=t.autoCallable)
                for t in context.availableTools
            ],
            **readiness,
        )

    # ------------------------------------------------------------------ #
    # Graph construction
    # ------------------------------------------------------------------ #
    def _build_graph(self):
        graph = StateGraph(AgentState)
        graph.add_node("agent", self._agent_node)
        graph.add_node("tools", self._tools_node)
        graph.add_node("finalize", self._finalize_node)
        graph.add_edge(START, "agent")
        graph.add_conditional_edges("agent", self._route_after_agent, {"agent": "agent", "tools": "tools", "finalize": "finalize"})
        graph.add_conditional_edges("tools", self._route_after_tools, {"agent": "agent", "finalize": "finalize", "end": END})
        graph.add_edge("finalize", END)
        if self._context is None:
            return graph.compile()
        return graph.compile(checkpointer=BackendCheckpointSaver(
            self.backend,
            run_id=self._context.runId,
            thread_id=f"agent-run:{self._context.runId}",
        ))

    async def _run_graph(self, context: RunContext, initial: AgentState) -> None:
        if self._compiled is None:
            self._compiled = self._build_graph()
        await self.backend.append_event(
            context.runId,
            RunEventCreate(eventType=TOOL_CALL_LOOP_STARTED, eventJson={
                "kind": "graph",
                "tools": [d["function"]["name"] for d in self._tool_defs],
                "maxModelTurns": self._max_iterations,
                "maxToolExecutions": self._max_tool_executions,
            }),
        )
        final_state: AgentState = await self._compiled.ainvoke(
            initial,
            config={
                "recursion_limit": max(8, self._max_iterations * 4),
                "configurable": {"thread_id": f"agent-run:{context.runId}"},
            },
        )
        await self.backend.append_event(
            context.runId,
            RunEventCreate(eventType=TOOL_CALL_LOOP_COMPLETED, eventJson={
                "kind": "graph",
                "iterations": int(final_state.get("iteration", 0)),
                "modelTurns": int(final_state.get("iteration", 0)),
                "maxModelTurns": self._max_iterations,
                "maxToolExecutions": self._max_tool_executions,
                "awaitingConfirmation": bool(final_state.get("pending_confirmation") or final_state.get("__interrupt__")),
            }),
        )
        if final_state.get("pending_confirmation") or final_state.get("__interrupt__"):
            # Run pauses for user confirmation; persist a checkpoint so the loop
            # can resume with its plan/artifacts intact, then let the backend
            # mark the run WAITING on the confirmation event.
            await self._save_checkpoint(context, final_state)
            return
        answer = final_state.get("final_answer") or ""
        await self._complete_run(context, answer, intent="agent_graph")
        await self.memory_runtime.curate_after_run(
            context,
            answer,
            tool_result=None,
            memory_tool_executed=context.runId in self._memory_tool_executed_runs,
        )

    # ------------------------------------------------------------------ #
    # Graph nodes
    # ------------------------------------------------------------------ #
    async def _agent_node(self, state: AgentState) -> dict[str, Any]:
        context = self._context
        assert context is not None
        await self._check_cancelled()
        if settings.agent_tool_disclosure_enabled:
            # Recompute the shortlist for the current step so chained outputs
            # (e.g. an image just produced) surface the right next-step tools.
            self._refresh_tool_defs(context, artifacts=state.get("artifacts") or self._run_artifacts)
        iteration = int(state.get("iteration", 0)) + 1
        self._guard.reserve_model_call(self._budget)
        with model_audit_scope("tool.loop", iteration):
            turn = await self._stream_chat_turn(state["messages"])
        tool_calls = list(getattr(turn, "tool_calls", []) or [])
        await self.backend.append_event(
            context.runId,
            RunEventCreate(eventType=AGENT_STEP, eventText=f"step {iteration}", eventJson={
                "iteration": iteration,
                "toolCallCount": len(tool_calls),
                "finishReason": getattr(turn, "finish_reason", None),
            }),
        )
        update: dict[str, Any] = {"iteration": iteration, "last_content": turn.content or ""}
        if tool_calls:
            hydration = await self._maybe_hydrate_for_tool_calls(context, tool_calls)
            if hydration:
                update["messages"] = [hydration_message(hydration)]
                update["pending_tool_calls"] = []
                update["schema_validation_retry_pending"] = False
                update["skill_hydration_retry_pending"] = True
                return update
            payloads = [tool_call_message_payload(c.id, c.name, c.arguments) for c in tool_calls]
            update["messages"] = [ChatMessage(role="assistant", content=turn.content or "", toolCalls=payloads)]
            update["pending_tool_calls"] = [
                {"id": c.id, "name": c.name, "arguments": c.arguments if isinstance(c.arguments, dict) else {}}
                for c in tool_calls
            ]
            update["schema_validation_retry_pending"] = False
            update["skill_hydration_retry_pending"] = False
        else:
            if self._should_force_schema_validation_retry():
                update["messages"] = [
                    ChatMessage(role="assistant", content=turn.content or ""),
                    ChatMessage(role="system", content=_schema_validation_retry_nudge()),
                ]
                update["schema_validation_retry_pending"] = True
            else:
                update["schema_validation_retry_pending"] = False
            update["skill_hydration_retry_pending"] = False
            update["pending_tool_calls"] = []
        return update

    async def _stream_chat_turn(self, messages: list[ChatMessage]):
        streamer = getattr(self.model, "chat_stream_parts", None)
        if not callable(streamer):
            return await self.model.chat_turn(messages, tools=self._tool_defs, tool_choice="auto")
        text_parts: list[str] = []
        reasoning_parts: list[str] = []
        calls: dict[int, dict[str, Any]] = {}
        try:
            async for part in streamer(messages, tools=self._tool_defs):
                if part.kind == "text" and part.text:
                    text_parts.append(part.text)
                    self._streamed_model_text = True
                    await self.backend.append_event(self._context.runId, RunEventCreate(
                        eventType=MESSAGE_DELTA, eventText=part.text, eventJson={"delta": part.text}
                    ))
                elif part.kind == "reasoning" and part.text:
                    reasoning_parts.append(part.text)
                    await self.backend.append_event(self._context.runId, RunEventCreate(
                        eventType=REASONING_DELTA, eventText=part.text, eventJson={"delta": part.text}
                    ))
                elif part.kind == "tool_call":
                    index = int(part.tool_call_index or 0)
                    call = calls.setdefault(index, {"id": "", "name": "", "arguments": ""})
                    call["id"] = call["id"] or part.tool_call_id
                    call["name"] = call["name"] or part.tool_call_name
                    call["arguments"] += part.text or ""
            tool_calls = []
            for index, call in sorted(calls.items()):
                if not call["id"] or not call["name"]:
                    raise ToolExecutionError(f"incomplete streamed tool call at index {index}", "MODEL_TOOL_CALL_INCOMPLETE")
                try:
                    arguments = json.loads(call["arguments"])
                except json.JSONDecodeError:
                    raise ToolExecutionError(f"invalid streamed tool arguments at index {index}", "MODEL_TOOL_ARGUMENTS_INVALID")
                from app.clients.model_client import ChatToolCall
                tool_calls.append(ChatToolCall(
                    id=call["id"],
                    name=call["name"],
                    arguments=arguments if isinstance(arguments, dict) else {},
                ))
            from app.clients.model_client import ChatTurnResult
            return ChatTurnResult(
                content="".join(text_parts),
                reasoning="".join(reasoning_parts),
                tool_calls=tool_calls,
                finish_reason="stream",
            )
        except ToolExecutionError:
            raise

    async def _first_step_unified_route(self, context: RunContext, state: AgentState) -> dict[str, Any] | None:
        """Align graph first step with UnifiedSemanticRouter tool selection."""
        memory_items = await self.memory_runtime.fetch_items(context)
        memory_context = format_workspace_memory_context(memory_items)
        intent = await self.unified_router.route(context, workspace_memory_context=memory_context)
        if intent is None or intent.intent != Intent.TOOL_USE or not intent.selectedToolCode:
            return None
        alias = _alias_for_tool_code(intent.selectedToolCode)
        call_id = "unified-router-1"
        arguments = dict(intent.arguments or {})
        iteration = 1
        payloads = [tool_call_message_payload(call_id, alias, arguments)]
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=AGENT_STEP,
                eventText=f"step {iteration}",
                eventJson={
                    "iteration": iteration,
                    "toolCallCount": 1,
                    "finishReason": "unified_router",
                    "selectedToolCode": intent.selectedToolCode,
                },
            ),
        )
        return {
            "iteration": iteration,
            "last_content": "",
            "unified_router_applied": True,
            "messages": [ChatMessage(role="assistant", content="", toolCalls=payloads)],
            "pending_tool_calls": [{"id": call_id, "name": alias, "arguments": arguments}],
        }

    async def _tools_node(self, state: AgentState) -> dict[str, Any]:
        context = self._context
        assert context is not None
        await self._check_cancelled()
        registry = ToolRegistry(context)
        new_messages: list[ChatMessage] = []
        artifacts: list[dict[str, Any]] = []
        plan_update: list[dict[str, Any]] | None = None
        # Respect the remaining tool-call budget within a single turn.
        for call in state.get("pending_tool_calls", []):
            await self._check_cancelled()
            name = call["name"]
            call_id = call["id"]
            arguments = call.get("arguments") or {}
            await self.backend.append_event(
                context.runId,
                RunEventCreate(eventType=TOOL_CALL_REQUESTED, eventJson={
                    "kind": "graph", "id": call_id, "name": name, "arguments": redact_large(arguments),
                }),
            )

            if name == PLAN_TOOL:
                plan_update = self._normalize_plan(arguments.get("steps"))
                await self.backend.append_event(
                    context.runId,
                    RunEventCreate(eventType=PLAN_UPDATED, eventText="plan updated", eventJson={"steps": plan_update}),
                )
                new_messages.append(self._tool_message(call_id, name, {"success": True, "steps": plan_update}))
                continue

            if name == FINISH_TOOL:
                return {
                    "messages": new_messages,
                    "final_answer": str(arguments.get("answer") or "").strip(),
                    "finished": True,
                    "pending_tool_calls": [],
                    **({"plan": plan_update} if plan_update is not None else {}),
                    **({"artifacts": artifacts} if artifacts else {}),
                }

            if name in {MEMORY_ADD_TOOL, MEMORY_REPLACE_TOOL, MEMORY_REMOVE_TOOL}:
                result = await self._execute_memory_tool(context, name, arguments)
                new_messages.append(self._tool_message(call_id, name, result))
                continue

            if name == EXPAND_TOOL:
                code = str(arguments.get("toolCode") or "").strip()
                tool = registry.get(code)
                if tool is None:
                    new_messages.append(self._tool_message(call_id, name, {
                        "success": False, "error": f"unknown toolCode: {code}",
                    }))
                else:
                    self._expanded_tool_codes.add(code)
                    new_messages.append(self._tool_message(call_id, name, {
                        "success": True,
                        "toolCode": code,
                        "callableAs": _alias_for_tool_code(code),
                        "note": "已展开完整参数，可在下一步直接调用该工具。",
                    }))
                continue

            # Product tool path.
            alias = self._aliases.get(name)
            tool = alias.tool if alias is not None else registry.get(name)
            if tool is None:
                await self.backend.append_event(
                    context.runId,
                    RunEventCreate(eventType=TOOL_CALL_REJECTED, eventJson={"kind": "graph", "id": call_id, "name": name, "reason": "tool_not_available"}),
                )
                new_messages.append(self._tool_message(call_id, name, {"success": False, "error": f"tool not available: {name}"}))
                continue

            modality_error = self._modality_mismatch(context, tool)
            if modality_error is not None:
                await self.backend.append_event(
                    context.runId,
                    RunEventCreate(eventType=TOOL_CALL_REJECTED, eventJson={"kind": "graph", "id": call_id, "name": name, "reason": modality_error}),
                )
                new_messages.append(self._tool_message(call_id, name, {"success": False, "error": modality_error}))
                continue

            execution_args = await self._build_product_arguments(context, tool, arguments)

            if not self._should_auto_call(context, tool):
                # LangGraph persists this exact node boundary before returning the interrupt.
                await self.backend.append_event(
                    context.runId,
                    RunEventCreate(eventType=TOOL_CONFIRMATION_REQUIRED, eventText=tool.toolCode, eventJson={
                        "toolCode": tool.toolCode,
                        "toolName": tool.toolName,
                        "description": tool.description,
                        "creditCost": tool.estimatedCreditCost,
                        "inputSchema": tool.inputSchema,
                        "arguments": execution_args,
                    }),
                )
                approval = interrupt({"toolCode": tool.toolCode, "callId": call_id, "arguments": execution_args})
                if not isinstance(approval, dict) or approval.get("toolCode") != tool.toolCode or not approval.get("approved"):
                    raise ToolExecutionError("tool confirmation was rejected", "TOOL_CONFIRMATION_REJECTED")

            await self.backend.append_event(
                context.runId,
                RunEventCreate(eventType=TOOL_SELECTED, eventText=tool.toolCode, eventJson={"toolCode": tool.toolCode}),
            )
            try:
                result = await self.tool_orchestrator.execute_with_guard(
                    context, tool, self._budget, arguments=execution_args,
                    idempotency_key=f"agent-run:{context.runId}:call:{call_id}",
                )
                # A third-party call may naturally return after cancellation. Re-check
                # before publishing any success event or advancing the graph state.
                await self._check_cancelled()
            except ToolExecutionError as exc:
                # Reflect / retry: surface the failure to the model so it can fix
                # arguments and retry, but bound retries per tool to avoid loops.
                self._tool_failures[tool.toolCode] = self._tool_failures.get(tool.toolCode, 0) + 1
                exhausted = self._tool_failures[tool.toolCode] >= self._max_tool_retries
                details = getattr(exc, "details", {}) if hasattr(exc, "details") else {}
                if exc.error_code == "SCHEMA_VALIDATION" and not exhausted:
                    self._pending_schema_validation_tool_code = tool.toolCode
                elif exc.error_code == "SCHEMA_VALIDATION":
                    self._pending_schema_validation_tool_code = None
                await self.backend.append_event(
                    context.runId,
                    RunEventCreate(eventType=REFLECT_RETRY, eventText=tool.toolCode, eventJson={
                        "toolCode": tool.toolCode,
                        "errorCode": exc.error_code,
                        "error": str(exc),
                        "attempt": self._tool_failures[tool.toolCode],
                        "retryable": not exhausted,
                        **({"details": details} if isinstance(details, dict) and details else {}),
                    }),
                )
                if exc.error_code == "SCHEMA_VALIDATION" and exhausted:
                    return {
                        "messages": new_messages,
                        "final_answer": _schema_validation_clarification(tool.toolCode, details),
                        "finished": True,
                        "pending_tool_calls": [],
                        **({"plan": plan_update} if plan_update is not None else {}),
                        **({"artifacts": artifacts} if artifacts else {}),
                    }
                guidance = (
                    "已多次失败，请不要再重试该工具，改用其他方式或直接向用户说明原因。"
                    if exhausted
                    else "请检查并修正参数后再重试一次；若无法修正，请向用户说明。"
                )
                new_messages.append(self._tool_message(call_id, name, {
                    "success": False, "toolCode": tool.toolCode, "error": str(exc),
                    "errorCode": exc.error_code, "guidance": guidance,
                    **({"schemaValidationError": details} if exc.error_code == "SCHEMA_VALIDATION" and isinstance(details, dict) else {}),
                }))
                continue

            if result.get("missing_tool_arguments"):
                new_messages.append(self._tool_message(call_id, name, {
                    "success": False, "toolCode": tool.toolCode, "missingArguments": result["missing_tool_arguments"],
                    "guidance": "缺少必要参数，请补全后再调用。",
                }))
                continue

            if self._pending_schema_validation_tool_code == tool.toolCode:
                self._pending_schema_validation_tool_code = None
            artifact = self._artifact_from_result(tool, result)
            artifacts.append(artifact)
            self._run_artifacts.append(artifact)
            await self.backend.append_event(
                context.runId,
                RunEventCreate(eventType=TOOL_CALL_EXECUTED, eventJson={
                    "kind": "graph", "id": call_id, "name": name, "toolCode": tool.toolCode, "success": True,
                }),
            )
            new_messages.append(self._tool_message(call_id, name, self._tool_result_for_model(result)))

        update: dict[str, Any] = {"messages": new_messages, "pending_tool_calls": []}
        update["schema_validation_retry_pending"] = self._should_force_schema_validation_retry()
        if plan_update is not None:
            update["plan"] = plan_update
        if artifacts:
            update["artifacts"] = artifacts
        return update

    async def _finalize_node(self, state: AgentState) -> dict[str, Any]:
        context = self._context
        assert context is not None
        await self._check_cancelled()
        answer = (state.get("final_answer") or state.get("last_content") or "").strip()
        if not answer:
            answer = await self._summarize_when_silent(context, state)
        await self._emit_answer(
            context.runId,
            answer,
            content_json=_media_content_json(state.get("artifacts") or []),
        )
        return {"final_answer": answer, "finished": True}

    # ------------------------------------------------------------------ #
    # Routing
    # ------------------------------------------------------------------ #
    def _route_after_agent(self, state: AgentState) -> str:
        if state.get("pending_tool_calls"):
            return "tools"
        if state.get("skill_hydration_retry_pending"):
            return "agent"
        if state.get("schema_validation_retry_pending"):
            return "agent"
        return "finalize"

    def _route_after_tools(self, state: AgentState) -> str:
        if state.get("pending_confirmation"):
            return "end"
        if state.get("finished"):
            return "finalize"
        if int(state.get("iteration", 0)) >= self._max_iterations:
            return "finalize"
        return "agent"

    def _should_force_schema_validation_retry(self) -> bool:
        code = self._pending_schema_validation_tool_code
        if not code:
            return False
        return self._tool_failures.get(code, 0) < self._max_tool_retries

    async def _maybe_hydrate_for_tool_calls(
        self,
        context: RunContext,
        tool_calls: list[Any],
    ):
        if not tool_calls:
            return None
        tool_code = resolve_canonical_tool_code(tool_calls[0].name, context.availableTools) or tool_calls[0].name
        return await self._skill_hydration.hydrate_for_tool(context, tool_code, self._hydrated_skill_codes)

    # ------------------------------------------------------------------ #
    # Setup helpers
    # ------------------------------------------------------------------ #
    async def _prepare_run(self, context: RunContext, *, emit_run_started: bool = True) -> None:
        self._context = context
        self._guard = runtime_budget_guard_for_context(context, self.budget_guard)
        self._budget = BudgetState(credit_budget=context.creditBudget)
        self._run_artifacts = []
        self._tool_failures = {}
        self._pending_schema_validation_tool_code = None
        self._hydrated_skill_codes = set()
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
        await self._emit_runtime_settings(context)
        if emit_run_started:
            await self.backend.append_event(
                context.runId,
                RunEventCreate(eventType=RUN_STARTED, eventText="Agent 已开始处理", eventJson={"runId": context.runId, "engine": "agent_graph"}),
            )
        self._expanded_tool_codes = set()
        self._memory_tool = None
        self._memory_tools_enabled = bool(context.workspaceId and memory_auto_save_enabled(context))
        if self._memory_tools_enabled:
            self._memory_tool = MemoryTool(self.backend, context.workspaceId, context.userId, run_id=context.runId)
        self._refresh_tool_defs(context, artifacts=None)
        await self._emit_tool_disclosure(context)

    def _refresh_tool_defs(self, context: RunContext, *, artifacts: list[dict[str, Any]] | None) -> None:
        """(Re)build the function-calling toolset for the current step.

        With disclosure enabled only the relevant shortlist (plus tools the model
        explicitly expanded) carry full trimmed schemas; the model still sees every
        tool in the catalog and can pull any of them via ``expand_tool``.
        """
        if settings.agent_tool_disclosure_enabled:
            product_defs, aliases = build_disclosed_definitions(
                context.availableTools,
                context,
                k=settings.agent_tool_shortlist_k,
                expanded_codes=self._expanded_tool_codes,
                artifacts=artifacts,
            )
        else:
            product_defs, aliases = product_tool_definitions(context.availableTools, context)
        tool_defs = [*product_defs, planning_tool_definition(), finish_tool_definition()]
        if settings.agent_tool_disclosure_enabled and context.availableTools:
            tool_defs.append(expand_tool_definition())
        if self._memory_tools_enabled:
            tool_defs.extend(memory_tool_definitions())
        self._tool_defs = tool_defs
        self._aliases = aliases

    async def _emit_tool_disclosure(self, context: RunContext) -> None:
        if not settings.agent_tool_disclosure_enabled or not context.availableTools:
            return
        full_defs, _ = product_tool_definitions(context.availableTools, context)
        full_tokens = _estimate_tokens(json.dumps(full_defs, ensure_ascii=False))
        disclosed_tokens = _estimate_tokens(json.dumps(self._tool_defs, ensure_ascii=False))
        shortlisted_codes = sorted({
            str(getattr(getattr(alias, "tool", None), "toolCode", ""))
            for alias in self._aliases.values()
        })
        shortlisted_codes = [code for code in shortlisted_codes if code]
        candidate_codes = [tool.toolCode for tool in context.availableTools]
        filtered_tools = [
            {"toolCode": code, "reason": "relevance shortlist limit"}
            for code in candidate_codes
            if code not in shortlisted_codes and code not in self._expanded_tool_codes
        ]
        schema_hashes = {
            str(definition.get("function", {}).get("name") or "unknown"): hashlib.sha256(
                json.dumps(definition, ensure_ascii=False, sort_keys=True).encode("utf-8")
            ).hexdigest()
            for definition in self._tool_defs
            if isinstance(definition, dict)
        }
        await self.backend.append_event(
            context.runId,
            RunEventCreate(eventType=TOOL_DISCLOSURE, eventText="tool disclosure applied", eventJson={
                "availableToolCount": len(context.availableTools),
                "shortlistedToolCount": len(self._aliases),
                "expandedToolCount": len(self._expanded_tool_codes),
                "candidateToolCodes": candidate_codes,
                "shortlistedToolCodes": shortlisted_codes,
                "expandedToolCodes": sorted(self._expanded_tool_codes),
                "filteredTools": filtered_tools,
                "schemaHashes": schema_hashes,
                "toolDefsTokensBefore": full_tokens,
                "toolDefsTokensAfter": disclosed_tokens,
                "savedPercent": round((full_tokens - disclosed_tokens) / full_tokens * 100, 1) if full_tokens else 0.0,
            }),
        )

    def _fresh_state(self, messages: list[ChatMessage]) -> AgentState:
        self._streamed_model_text = False
        return {
            "messages": list(messages),
            "iteration": 0,
            "plan": [],
            "artifacts": [],
            "pending_tool_calls": [],
            "pending_confirmation": None,
            "final_answer": "",
            "finished": False,
        }

    async def _emit_runtime_settings(self, context: RunContext) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(eventType=RUNTIME_SETTINGS_APPLIED, eventText="Agent runtime settings applied", eventJson=runtime_settings_event_payload(
                context,
                guard=self._guard,
                tool_timeout_seconds=self.tool_bridge.timeout_seconds,
                tool_poll_interval_seconds=self.tool_bridge.poll_interval_seconds,
            )),
        )

    async def _base_messages(self, context: RunContext) -> list[ChatMessage]:
        configured = (
            ((context.agentSystemPrompt or "").strip() or GRAPH_SYSTEM_PROMPT)
            + "\n\n"
            + SESSION_STATE_INSTRUCTIONS
            + "\n\n"
            + IMAGE_TOOL_PROMPT_COMPLETENESS_RULES
        )
        messages: list[ChatMessage] = [ChatMessage(role="system", content=configured)]
        if settings.agent_tool_disclosure_enabled:
            tools_prompt = format_tool_catalog(context.availableTools)
        else:
            tools_prompt = _format_available_tools_prompt(context)
        if tools_prompt:
            messages.append(ChatMessage(role="system", content=tools_prompt))
        skill_catalog = _format_skill_catalog(context)
        if skill_catalog:
            messages.append(ChatMessage(role="system", content=skill_catalog))
        memory_items = await self.memory_runtime.fetch_items(context)
        memory_context = format_workspace_memory_context(memory_items)
        if memory_context:
            messages.append(ChatMessage(role="system", content=f"<!-- frozen memory snapshot -->\n{memory_context}"))
            await self.backend.append_event(
                context.runId,
                RunEventCreate(eventType=MEMORY_CONTEXT_FROZEN, eventJson=memory_context_trace_payload(memory_context, source="agent_graph", items=memory_items)),
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
        await self._emit_context_compaction(context)
        messages.extend(context_manager.build_working_memory(context.history))
        messages.append(ChatMessage(role="user", content=build_user_message_content(context)))
        return messages

    async def _emit_context_compaction(self, context: RunContext) -> None:
        """Trace compaction metrics and flush durable memory before aggressive trimming."""
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
                eventJson={
                    **metrics,
                    "savedPercent": saved_pct,
                    "memoryFlushTriggered": memory_flushed,
                },
            ),
        )

    # ------------------------------------------------------------------ #
    # Tool helpers
    # ------------------------------------------------------------------ #
    def _should_auto_call(self, context: RunContext, tool: ToolDescriptor) -> bool:
        if any(p.toolCode == tool.toolCode and p.autoCallEnabled for p in context.toolPreferences):
            return True
        if tool.autoCallable:
            return True
        return self._is_direct_generation_request(context, tool)

    def _is_direct_generation_request(self, context: RunContext, tool: ToolDescriptor) -> bool:
        modality = requested_output_modality(context.message)
        if not modality or not tool_supports_modality(tool, modality):
            return False
        return looks_like_tool_request(context.message)

    def _modality_mismatch(self, context: RunContext, tool: ToolDescriptor) -> str | None:
        requested = requested_output_modality(context.message)
        selected = infer_output_modality(tool)
        if requested and selected and requested != selected:
            return f"output_modality_mismatch:{requested}!={selected}"
        return None

    async def _build_product_arguments(self, context: RunContext, tool: ToolDescriptor, model_args: dict[str, Any]) -> dict[str, Any]:
        prepared = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=True)
        for key, value in (model_args or {}).items():
            if value not in (None, ""):
                prepared[key] = value
        # A generic single-field schema is used for tools without an explicit schema;
        # map the model's userRequest into the tool's prompt field when present.
        if "userRequest" in (model_args or {}) and "userRequest" not in (tool.inputSchema.get("properties") or {}):
            prompt_value = model_args.get("userRequest")
            if prompt_value:
                prepared.setdefault("prompt", prompt_value)
        prepared = self._backfill_artifacts(tool, prepared)
        prepared = enforce_locked_field_defaults(tool, prepared, user_message=context.message)
        prepared = apply_user_selected_attachment_priority(context, tool, prepared)
        return finalize_generation_arguments(context, tool, prepared)

    def _backfill_artifacts(self, tool: ToolDescriptor, prepared: dict[str, Any]) -> dict[str, Any]:
        """Chain prior tool outputs into the next tool's media input fields.

        Enables flows like image->video: when a tool needs an image/video/audio
        URL the model did not supply, fill it from the most recent matching
        artifact produced earlier in the same run.
        """
        if not self._run_artifacts:
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
        for artifact in reversed(self._run_artifacts):
            resource = str(artifact.get("resourceType") or "").lower()
            if modality in resource or (modality == "image" and resource in {"img", "picture"}):
                url = _first_url(artifact.get("contentText"))
                if url:
                    return url
        # Fall back to the most recent artifact with any URL when modality is loose.
        for artifact in reversed(self._run_artifacts):
            url = _first_url(artifact.get("contentText"))
            if url:
                return url
        return None

    async def _confirmed_tool_arguments(self, context: RunContext, tool: ToolDescriptor) -> dict[str, Any]:
        execution_args = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=True)
        pending = context.pendingToolContext
        if pending is not None and pending.status == "ACTIVE" and (not pending.selectedToolCode or pending.selectedToolCode == tool.toolCode):
            for key, value in (pending.collectedArgumentsJson or {}).items():
                if value not in (None, ""):
                    execution_args[key] = value
        execution_args = enforce_locked_field_defaults(tool, execution_args, user_message=context.message)
        execution_args = apply_user_selected_attachment_priority(context, tool, execution_args)
        return finalize_generation_arguments(context, tool, execution_args)

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
        if isinstance(result, dict) and result.get("success"):
            self._memory_tool_executed_runs.add(context.runId)
        return result

    def _tool_message(self, call_id: str, name: str, payload: dict[str, Any]) -> ChatMessage:
        return ChatMessage(role="tool", content=json.dumps(payload, ensure_ascii=False), toolCallId=call_id, name=name)

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

    def _normalize_plan(self, steps: Any) -> list[dict[str, Any]]:
        if not isinstance(steps, list):
            return []
        normalized: list[dict[str, Any]] = []
        for item in steps[:8]:
            if isinstance(item, dict) and item.get("title"):
                status = str(item.get("status") or "pending").strip().lower()
                if status not in {"pending", "in_progress", "done"}:
                    status = "pending"
                normalized.append({"title": str(item["title"])[:200], "status": status})
            elif isinstance(item, str) and item.strip():
                normalized.append({"title": item.strip()[:200], "status": "pending"})
        return normalized

    async def _summarize_when_silent(self, context: RunContext, state: AgentState) -> str:
        artifacts = state.get("artifacts") or []
        for artifact in reversed(artifacts):
            text = artifact.get("contentText")
            if text and str(text).strip():
                return str(text).strip()
        return "抱歉，本次未能生成有效回复，请换个说法或补充更多信息后再试。"

    async def _seed_state_after_tool(
        self,
        context: RunContext,
        tool: ToolDescriptor,
        arguments: dict[str, Any],
        result: dict[str, Any],
        *,
        checkpoint: dict[str, Any] | None = None,
    ) -> AgentState:
        call_id = f"confirmed-{tool.toolCode}"
        base = await self._base_messages(context)
        prior_plan = (checkpoint or {}).get("plan") or []
        prior_artifacts = (checkpoint or {}).get("artifacts") or []
        resume_notes = self._resume_context_note(prior_plan, prior_artifacts)
        messages: list[ChatMessage] = list(base)
        if resume_notes:
            messages.append(ChatMessage(role="system", content=resume_notes))
        assistant = ChatMessage(
            role="assistant",
            content="",
            toolCalls=[tool_call_message_payload(call_id, tool.toolCode, arguments)],
        )
        tool_message = self._tool_message(call_id, tool.toolCode, self._tool_result_for_model(result))
        messages.extend([assistant, tool_message])
        state = self._fresh_state(messages)
        new_artifact = self._artifact_from_result(tool, result)
        state["plan"] = prior_plan
        state["artifacts"] = [*prior_artifacts, new_artifact]
        # Make prior + just-produced artifacts available for downstream chaining.
        self._run_artifacts = [*prior_artifacts, new_artifact]
        return state

    def _resume_context_note(self, plan: list[dict[str, Any]], artifacts: list[dict[str, Any]]) -> str:
        parts: list[str] = []
        if plan:
            lines = [f"- [{step.get('status', 'pending')}] {step.get('title', '')}" for step in plan]
            parts.append("当前执行计划（确认后继续）：\n" + "\n".join(lines))
        usable = [a for a in artifacts if a.get("contentText")]
        if usable:
            lines = [f"- {a.get('toolCode')}（{a.get('resourceType') or 'text'}）：{str(a.get('contentText'))[:200]}" for a in usable]
            parts.append("此前步骤已产出可复用产物：\n" + "\n".join(lines))
        return "\n\n".join(parts)

    # ------------------------------------------------------------------ #
    # Checkpoint persistence
    # ------------------------------------------------------------------ #
    async def _save_checkpoint(self, context: RunContext, state: AgentState) -> None:
        saver = getattr(self.backend, "save_graph_checkpoint", None)
        if not callable(saver):
            raise RuntimeError("backend does not provide business checkpoint persistence")
        payload = serialize_checkpoint(state)
        await saver(context.runId, payload)

    async def _load_checkpoint(self, context: RunContext) -> dict[str, Any] | None:
        loader = getattr(self.backend, "load_graph_checkpoint", None)
        blob = None
        if callable(loader):
            try:
                blob = await loader(context.runId)
            except Exception:
                LOGGER.debug("failed to load graph checkpoint runId=%s", context.runId, exc_info=True)
        if not blob:
            return None
        try:
            return deserialize_checkpoint(blob)
        except Exception:
            LOGGER.warning("failed to parse graph checkpoint runId=%s", context.runId, exc_info=True)
            return None

    async def _clear_checkpoint(self, context: RunContext) -> None:
        clearer = getattr(self.backend, "clear_graph_checkpoint", None)
        if callable(clearer):
            try:
                await clearer(context.runId)
            except Exception:
                LOGGER.debug("failed to clear graph checkpoint runId=%s", context.runId, exc_info=True)

    def _checkpoint_tool_arguments(self, checkpoint: dict[str, Any] | None, tool: ToolDescriptor) -> dict[str, Any] | None:
        if not checkpoint:
            return None
        pending = checkpoint.get("pending_confirmation") or {}
        if pending.get("toolCode") == tool.toolCode and isinstance(pending.get("arguments"), dict):
            return dict(pending["arguments"])
        return None

    # ------------------------------------------------------------------ #
    # Run completion / streaming
    # ------------------------------------------------------------------ #
    async def _emit_answer(
        self,
        run_id: int,
        answer: str,
        *,
        content_json: dict[str, Any] | None = None,
    ) -> None:
        normalized = (answer or "").strip()
        if not getattr(self, "_streamed_model_text", False):
            for chunk in _chunks(normalized, 32):
                await self.backend.append_event(run_id, RunEventCreate(eventType=MESSAGE_DELTA, eventText=chunk, eventJson={"delta": chunk}))
        upsert = getattr(self.backend, "upsert_streaming_answer", None)
        if callable(upsert) and normalized:
            try:
                await upsert(run_id, normalized, content_json)
            except Exception:
                LOGGER.debug("streaming answer upsert failed runId=%s", run_id)
        await self.backend.append_event(run_id, RunEventCreate(eventType=MESSAGE_COMPLETED, eventText=normalized, eventJson={"content": normalized}))

    async def _complete_run(self, context: RunContext, final_answer: str, *, intent: str) -> None:
        normalized = (final_answer or "").strip() or "抱歉，本次未能生成有效回复，请换个说法或补充更多信息后再试。"
        usage = self._model_usage_or_estimate(context, normalized)
        await self.backend.complete_run(
            context.runId,
            RunComplete(
                finalAnswer=normalized,
                intent=intent,
                modelProviderCode="agent-service",
                modelName=getattr(self.model, "model_name", settings.model_name),
                consumedCredits=self._guard.consumed_for_completion(self._budget),
                promptTokens=usage["promptTokens"],
                completionTokens=usage["completionTokens"],
            ),
        )

    async def _fail_run(self, run_id: int, error_code: str, error_message: str) -> None:
        usage = self._model_usage()
        consumed = self._budget.consumed_credits if self._budget.consumed_credits > 0 else None
        if consumed is None and (usage["promptTokens"] or usage["completionTokens"]):
            consumed = self._guard.default_consumed_credits
        try:
            await self.backend.fail_run(run_id, RunFail(
                errorCode=error_code,
                errorMessage=error_message,
                consumedCredits=consumed,
                promptTokens=usage["promptTokens"],
                completionTokens=usage["completionTokens"],
            ))
            if error_code == "AGENT_CANCELLED":
                context = self._context
                if context is not None:
                    await self._clear_checkpoint(context)
        except Exception:
            LOGGER.exception("failed to report agent graph run failure runId=%s code=%s", run_id, error_code)

    def _model_usage(self) -> dict[str, int]:
        usage = getattr(self.model, "usage", None)
        if not isinstance(usage, dict):
            return {"promptTokens": 0, "completionTokens": 0}
        return {
            "promptTokens": max(0, int(usage.get("promptTokens") or 0)),
            "completionTokens": max(0, int(usage.get("completionTokens") or 0)),
        }

    def _model_usage_or_estimate(self, context: RunContext, final_answer: str) -> dict[str, int]:
        usage = self._model_usage()
        if usage["promptTokens"] or usage["completionTokens"]:
            return usage
        prompt_text = "\n".join([*(m.content for m in context.history if m.content), context.message or ""])
        return {"promptTokens": _estimate_tokens(prompt_text), "completionTokens": _estimate_tokens(final_answer)}


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


def _media_content_json(artifacts: list[dict[str, Any]]) -> dict[str, Any] | None:
    """Convert successful tool media into message attachments independent of LLM prose."""
    attachments: list[dict[str, str]] = []
    seen_urls: set[str] = set()
    for artifact in artifacts:
        resource_type = str(artifact.get("resourceType") or "").upper()
        if resource_type not in {"IMAGE", "VIDEO", "AUDIO"}:
            continue
        try:
            payload = json.loads(str(artifact.get("contentText") or ""))
        except (TypeError, json.JSONDecodeError):
            continue
        if not isinstance(payload, dict):
            continue
        plural_key = {"IMAGE": "images", "VIDEO": "videos", "AUDIO": "audios"}[resource_type]
        entries = payload.get(plural_key)
        if not isinstance(entries, list):
            continue
        for index, entry in enumerate(entries, start=1):
            if not isinstance(entry, dict):
                continue
            url = str(entry.get("url") or entry.get("downloadUrl") or "").strip()
            if not url or url in seen_urls:
                continue
            seen_urls.add(url)
            extension = {"IMAGE": "jpg", "VIDEO": "mp4", "AUDIO": "mp3"}[resource_type]
            attachments.append({
                "id": f"tool-{artifact.get('taskId') or 'result'}-{resource_type.lower()}-{index}",
                "name": f"生成{ {'IMAGE': '图片', 'VIDEO': '视频', 'AUDIO': '音频'}[resource_type] } {index}",
                "contentType": {"IMAGE": "image/jpeg", "VIDEO": "video/mp4", "AUDIO": "audio/mpeg"}[resource_type],
                "url": url,
                "downloadUrl": str(entry.get("downloadUrl") or url).strip(),
                "source": str(artifact.get("toolCode") or "agent_tool"),
            })
    return {"attachments": attachments} if attachments else None


def _schema_validation_retry_nudge() -> str:
    return (
        "You did not call the tool after a SchemaValidationError. "
        "Do not ask the user for clarification and do not explain the error. "
        "Immediately produce a valid function tool call. Read <SessionState>, fill the missing image prompt fields "
        "with complete visual text, and call the same image tool again now."
    )
