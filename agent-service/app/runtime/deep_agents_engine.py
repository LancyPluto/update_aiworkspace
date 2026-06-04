import importlib.util
import inspect
import json
import logging
import re
from contextvars import ContextVar
from collections.abc import Callable
from dataclasses import dataclass
from types import ModuleType
from typing import Any

from app.config import settings
from app.core.agent_decision import AgentDecisionService
from app.core.budget_guard import BudgetExceeded, BudgetGuard, BudgetState
from app.core.event_types import (
    ARGUMENTS_MERGED,
    FOLLOWUP_DETECTED,
    FOLLOWUP_INHERITED,
    FOLLOWUP_REJECTED,
    INTENT_DETECTED,
    MEMORY_CONTEXT_FROZEN,
    MESSAGE_COMPLETED,
    MESSAGE_DELTA,
    RUN_STARTED,
    RUNTIME_SETTINGS_APPLIED,
    SUBAGENT_COMPLETED,
    SUBAGENT_FAILED,
    SUBAGENT_STARTED,
    TOOL_ARGUMENTS_PREVIEW,
    TOOL_CONFIRMATION_REQUIRED,
    TOOL_MISSING_ARGUMENTS,
    TOOL_RECOMMENDATIONS,
    TOOL_SELECTED,
)
from app.core.intent_router import Intent, IntentResult, IntentRouter
from app.core.schemas import (
    AgentRouteDebugResponse,
    AgentRouteDebugTool,
    ChatMessage,
    RunComplete,
    RunContext,
    RunEventCreate,
    RunFail,
    SessionSearchItem,
    ToolDescriptor,
    WorkspaceMemoryItem,
)
from app.runtime.file_context_runtime import WorkspaceFileRuntime
from app.runtime.followup_task_resolver import FollowupTaskResolver, FollowupResolution
from app.runtime.memory_curator import MemoryCuratorService
from app.runtime.memory_runtime import (
    WorkspaceMemoryRuntime,
    build_consolidated_memory_summary,
    format_workspace_memory_context,
    format_workspace_memory_items,
    memory_auto_save_enabled,
    memory_tool_loop_enabled,
)
from app.runtime.product_tool_call_loop import ProductToolCallLoopExecutor
from app.runtime.runtime_settings import (
    runtime_bool,
    runtime_budget_guard_for_context,
    runtime_float,
    runtime_int,
    runtime_settings_event_payload,
)
from app.runtime.subagent_profiles import default_subagent_profiles, profiles_to_deepagents_subagents
from app.runtime.agent_router_service import AgentRouterService
from app.runtime.tool_call_loop import AgentToolCallLoopExecutor
from app.runtime.tool_decision_validator import ToolDecisionValidator
from app.runtime.tool_orchestrator import ToolOrchestrator, missing_execution_arguments
from app.runtime.workspace_files import WorkspaceFileContext
from app.security.prompt_guard import PromptGuard
from app.tools.backend_tool import BackendToolBridge, ToolExecutionError
from app.tools.memory_tool import (
    MEMORY_TOOL_SYSTEM_PROMPT,
    MemoryTool,
)
from app.tools.missing_argument_hints import format_missing_tool_arguments_message
from app.tools.registry import ToolRegistry, infer_output_modality, requested_output_modality, tool_supports_modality
from langchain_core.callbacks import AsyncCallbackHandler

LOGGER = logging.getLogger(__name__)
_CURRENT_BUDGET_GUARD: ContextVar[BudgetGuard | None] = ContextVar("agent_budget_guard", default=None)

DEEP_AGENTS_INTENT = "deep_agents"
DEFAULT_AGENT_SYSTEM_PROMPT = (
    "You are a helpful cloud agent for an AI tool marketplace. "
    "Your reasoning model is only used for conversation, planning, and orchestration. "
    "Each AI tool runs with its own backend tool configuration and model binding."
)
DEEP_AGENTS_SYSTEM_PROMPT = (
    "You are a workspace agent for an AI tool marketplace. Plan and execute tasks carefully, "
    "use available context from the conversation, workspace memory, and files, "
    "and return a concise final answer for the user."
)


class DeepAgentsRuntimeEngine:
    def __init__(
        self,
        backend_client,
        model_client,
        *,
        intent_router: IntentRouter | None = None,
        budget_guard: BudgetGuard | None = None,
        prompt_guard: PromptGuard | None = None,
        dependency_loader: Callable[[], ModuleType | None] | None = None,
        deep_agents_enabled: bool | None = None,
    ) -> None:
        self.backend_client = backend_client
        self.backend = backend_client
        self.model_client = model_client
        self.model = model_client
        self._explicit_deep_agents_flag = deep_agents_enabled is not None
        self.deep_agents_enabled = bool(deep_agents_enabled)
        self.intent_router = intent_router or IntentRouter()
        self.budget_guard = budget_guard or BudgetGuard(
            max_model_calls=settings.agent_max_model_calls,
            max_tool_calls=settings.agent_max_tool_calls,
            model_call_cost=settings.agent_model_call_cost,
            default_consumed_credits=settings.agent_default_consumed_credits,
        )
        self.prompt_guard = prompt_guard or PromptGuard()
        self.dependency_loader = dependency_loader or self._load_deepagents
        self.tool_bridge = BackendToolBridge(backend_client, model_client=model_client)
        self.decision_service = AgentDecisionService(self.intent_router)
        self.router_service = AgentRouterService(
            backend_client,
            model_client,
            intent_router=self.intent_router,
        )
        self.followup_resolver = FollowupTaskResolver()
        self.tool_decision_validator = ToolDecisionValidator()
        self.memory_curator = MemoryCuratorService()
        self.memory_runtime = WorkspaceMemoryRuntime(backend_client, self.memory_curator, model_client=model_client)
        self.file_runtime = WorkspaceFileRuntime(backend_client)
        self.product_tool_loop = ProductToolCallLoopExecutor(
            backend=backend_client,
            model=model_client,
            max_tool_calls=settings.agent_product_tool_loop_max_calls,
        )
        self.tool_orchestrator = ToolOrchestrator(self.tool_bridge, self._budget_guard)
        self._memory_tool_executed_runs: set[int] = set()

    def _runtime_budget_guard_for_context(self, context: RunContext) -> BudgetGuard:
        return runtime_budget_guard_for_context(context, self.budget_guard)

    def _budget_guard(self) -> BudgetGuard:
        return _CURRENT_BUDGET_GUARD.get() or self.budget_guard

    async def _emit_runtime_settings_event(self, context: RunContext) -> None:
        guard = self._budget_guard()
        runtime = context.runtimeSettings
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=RUNTIME_SETTINGS_APPLIED,
                eventText="Agent runtime settings applied",
                eventJson=runtime_settings_event_payload(
                    context,
                    guard=guard,
                    tool_timeout_seconds=self.tool_bridge.timeout_seconds,
                    tool_poll_interval_seconds=self.tool_bridge.poll_interval_seconds,
                ),
            ),
        )

    async def run(self, context: RunContext) -> None:
        token = _CURRENT_BUDGET_GUARD.set(self._runtime_budget_guard_for_context(context))
        try:
            await self._run(context)
        finally:
            _CURRENT_BUDGET_GUARD.reset(token)

    async def _run(self, context: RunContext) -> None:
        if self._explicit_deep_agents_flag and not self.deep_agents_enabled:
            await self._fail_run(context.runId, "DEEP_AGENTS_DISABLED", "Deep Agents runtime is disabled")
            return

        state = {
            "run_id": context.runId,
            "context": context,
            "budget": BudgetState(credit_budget=context.creditBudget),
        }
        if not self.deep_agents_enabled:
            await self._emit_runtime_settings_event(context)
            await self.backend.append_event(
                context.runId,
                RunEventCreate(eventType=RUN_STARTED, eventText="Agent 已开始处理", eventJson={"runId": context.runId}),
            )

        guard_result = self.prompt_guard.inspect(context.message)
        if guard_result.rejected:
            await self._emit_answer_events(context.runId, guard_result.message or "")
            await self._complete_run(context, guard_result.message or "", intent=Intent.SECURITY_REJECTED.value)
            return

        if self.deep_agents_enabled:
            await self._run_deep_agents_or_chat(context, None)
            return

        # 1. Classify intent via LLM router (infrastructure rules short-circuit first).
        intent = await self._classify_intent(context)
        LOGGER.info(
            "agent route selected runId=%s intent=%s confidence=%.2f selectedTool=%s candidates=%s reason=%s",
            context.runId,
            intent.intent.value,
            intent.confidence,
            intent.selectedToolCode or "-",
            intent.candidateToolCodes,
            intent.reason,
        )
        await self._emit_intent_event(context, intent)

        # 2. Route by intent
        intent_enum = intent.intent

        if intent_enum == Intent.FILE_ANALYSIS:
            if self.deep_agents_enabled:
                await self._run_deep_agents_or_chat(context, intent)
                return
            try:
                answer = await self._run_chat(context, intent)
            except BudgetExceeded as exception:
                await self._fail_run(context.runId, exception.error_code, exception.message)
                return
            await self._complete_run(context, answer, intent=intent_enum.value)
            await self._curate_memory_after_run(context, answer)
            return

        if intent_enum == Intent.TOOL_USE:
            await self._handle_tool_use(context, intent)
            return

        if intent_enum == Intent.NEEDS_CLARIFICATION:
            answer = self._format_clarifying_answer(context, intent)
            await self._emit_answer_events(context.runId, answer)
            await self._complete_run(context, answer, intent=intent_enum.value)
            return

        if intent_enum == Intent.UNSUPPORTED:
            if self._should_fallback_from_unsupported(context):
                if self.deep_agents_enabled:
                    await self._run_deep_agents_or_chat(context, None)
                    return
                try:
                    answer = await self._run_chat(context, None)
                except BudgetExceeded as exception:
                    await self._fail_run(context.runId, exception.error_code, exception.message)
                    return
                await self._complete_run(context, answer, intent=Intent.GENERAL_CHAT.value)
                await self._curate_memory_after_run(context, answer)
                return
            answer = "当前阶段暂不支持文件分析、知识库检索或复杂工作流。我可以先帮你完成通用问答或调用已开放的工具。"
            await self._emit_answer_events(context.runId, answer)
            await self._complete_run(context, answer, intent=intent_enum.value)
            return

        if intent_enum == Intent.SECURITY_REJECTED:
            answer = intent.reason or "该请求被安全策略拒绝。"
            await self._emit_answer_events(context.runId, answer)
            await self._complete_run(context, answer, intent=intent_enum.value)
            return

        if intent_enum == Intent.GENERAL_CHAT:
            if self.deep_agents_enabled:
                await self._run_deep_agents_or_chat(context, intent)
                return
            try:
                answer = await self._run_chat(context, intent)
            except BudgetExceeded as exception:
                await self._fail_run(context.runId, exception.error_code, exception.message)
                return
            await self._complete_run(context, answer, intent=intent_enum.value)
            await self._curate_memory_after_run(context, answer)
            return

        # Fallback for any other intent — try deep agents if available, else fall back to chat
        await self._run_deep_agents_or_chat(context, intent)

    async def run_confirmed_tool(self, context: RunContext, tool_code: str) -> None:
        token = _CURRENT_BUDGET_GUARD.set(self._runtime_budget_guard_for_context(context))
        try:
            await self._emit_runtime_settings_event(context)
            await self._run_confirmed_tool(context, tool_code)
        finally:
            _CURRENT_BUDGET_GUARD.reset(token)

    async def _run_confirmed_tool(self, context: RunContext, tool_code: str) -> None:
        tool = ToolRegistry(context).get(tool_code)
        if tool is None:
            intent = self.intent_router.classify(context)
            from app.core.intent_router import IntentResult
            intent_result = IntentResult(
                intent=Intent.NEEDS_CLARIFICATION, confidence=0.5, reason="confirmed_tool_unavailable"
            )
            answer = self._format_clarifying_answer(context, intent_result)
            await self._emit_answer_events(context.runId, answer)
            await self._complete_run(context, answer, intent=Intent.NEEDS_CLARIFICATION.value)
            return

        await self.backend.append_event(
            context.runId,
            RunEventCreate(eventType=TOOL_SELECTED, eventText=tool.toolCode, eventJson={"toolCode": tool.toolCode, "confirmed": True}),
        )

        budget = BudgetState(credit_budget=context.creditBudget)
        try:
            result = await self._execute_tool_with_guard(context, tool, budget)
        except BudgetExceeded as exception:
            await self._fail_run(context.runId, exception.error_code, exception.message, budget=budget)
            return
        except ToolExecutionError as exception:
            await self._fail_run(
                context.runId,
                exception.error_code or "TOOL_CALL_FAILED",
                str(exception),
                budget=budget,
            )
            return

        if result.get("missing_tool_arguments"):
            await self._emit_missing_arguments(context, tool, result["missing_tool_arguments"])
            answer = self._format_missing_arguments_message(tool, result["missing_tool_arguments"])
            await self._emit_answer_events(context.runId, answer)
            await self._complete_run(context, answer, intent=Intent.NEEDS_CLARIFICATION.value)
            return

        answer = await self._synthesize_answer(context, tool, result, budget)
        await self._complete_run(context, answer, intent=Intent.TOOL_USE.value)
        await self._curate_memory_after_run(context, answer, tool_result=result)

    async def debug_route(self, context: RunContext) -> AgentRouteDebugResponse:
        intent = await self._classify_intent(context)
        requested_modality = requested_output_modality(context.message)
        return AgentRouteDebugResponse(
            intent=intent.intent.value,
            confidence=intent.confidence,
            selectedToolCode=intent.selectedToolCode,
            candidateToolCodes=intent.candidateToolCodes,
            clarifyingQuestion=intent.clarifyingQuestion,
            decisionSource=intent.decisionSource,
            reason=intent.reason,
            requestedOutputModality=requested_modality,
            visibleToolCount=len(context.availableTools),
            visibleTools=[
                AgentRouteDebugTool(
                    toolCode=tool.toolCode,
                    toolName=tool.toolName,
                    autoCallable=tool.autoCallable,
                )
                for tool in context.availableTools
            ],
        )

    def _get_available_module(self) -> ModuleType:
        module = self.dependency_loader()
        if module is None:
            raise RuntimeError("deepagents package is not installed; enable the preview runtime only after installing it")
        return module

    def ensure_available(self) -> None:
        self._get_available_module()

    @staticmethod
    def _load_deepagents() -> ModuleType | None:
        if importlib.util.find_spec("deepagents") is None:
            return None
        return importlib.import_module("deepagents")

    # --- Intent helpers ---

    async def _classify_intent(self, context: RunContext):
        intent = await self.decision_service.decide(
            context,
            llm_router=self.router_service.classify,
        )
        if intent.reason != "router_fallback_general_chat":
            return intent
        if not self._product_tool_loop_enabled(context):
            return intent
        product_intent = await self._try_product_tool_loop_fallback(context)
        return product_intent or intent

    @staticmethod
    def _product_tool_loop_enabled(context: RunContext) -> bool:
        if not _runtime_bool(context, "productToolLoopEnabled", settings.agent_product_tool_loop_enabled):
            return False
        if not context.availableTools:
            return False
        if context.routerSettings is not None and context.routerSettings.enabled is False:
            return False
        return True

    async def _try_product_tool_loop_fallback(self, context: RunContext):
        try:
            workspace_memory_context = await self._fetch_workspace_memory_context(context)
            if workspace_memory_context:
                await self._emit_memory_context_frozen(context, workspace_memory_context, source="product_tool_loop")
            product_result = await self.product_tool_loop.run(
                context,
                max_tool_calls=_runtime_int(
                    context,
                    "productToolLoopMaxCalls",
                    self.product_tool_loop.max_tool_calls,
                    1,
                    20,
                ),
                workspace_memory_context=workspace_memory_context,
            )
        except Exception as exc:
            LOGGER.info("product tool loop fallback skipped runId=%s error=%s", context.runId, exc)
            return None
        if product_result.intent is None:
            return None
        product_result.intent.signals = [
            {
                "source": "llm_router",
                "verdict": "general_chat",
                "confidence": 0.55,
                "reason": "router_fallback_general_chat",
            },
            {
                "source": "tool_call_loop",
                "verdict": product_result.intent.intent.value,
                "confidence": product_result.intent.confidence,
                "reason": product_result.intent.reason,
            },
        ]
        LOGGER.info(
            "product tool loop fallback selected runId=%s intent=%s tool=%s",
            context.runId,
            product_result.intent.intent.value,
            product_result.intent.selectedToolCode or "-",
        )
        return product_result.intent

    def _should_fallback_from_unsupported(self, context: RunContext) -> bool:
        message = (context.message or "").strip()
        if not message:
            return False
        return self.intent_router._looks_like_tool_request(message)

    async def _emit_memory_context_frozen(self, context: RunContext, workspace_memory_context: str, *, source: str) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=MEMORY_CONTEXT_FROZEN,
                eventJson={
                    "frozen": bool(workspace_memory_context),
                    "count": len(workspace_memory_context) if workspace_memory_context else 0,
                    "source": source,
                },
            ),
        )

    async def _emit_intent_event(self, context: RunContext, intent) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=INTENT_DETECTED,
                eventText=intent.intent.value,
                eventJson={
                    "confidence": intent.confidence,
                    "reason": intent.reason,
                    "selectedToolCode": intent.selectedToolCode,
                    "candidateToolCodes": intent.candidateToolCodes,
                    "clarifyingQuestion": intent.clarifyingQuestion,
                    "decisionSource": intent.decisionSource,
                    "decisionSignals": intent.signals,
                },
            ),
        )
        if intent.candidateToolCodes and len(intent.candidateToolCodes) > 1:
            registry = ToolRegistry(context)
            candidates_info = []
            for code in intent.candidateToolCodes:
                t = registry.get(code)
                candidates_info.append({
                    "toolCode": code,
                    "toolName": t.toolName if t else code,
                    "description": t.description if t else "",
                })
            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=TOOL_RECOMMENDATIONS,
                    eventText=f"Recommended {len(intent.candidateToolCodes)} tools",
                    eventJson={
                        "candidates": candidates_info,
                        "recommendedToolCode": intent.selectedToolCode,
                        "reason": intent.reason,
                        "disambiguationQuestion": intent.clarifyingQuestion or "请选择你想使用的工具",
                    },
                ),
            )

    # --- Tool use flow ---

    async def _handle_tool_use(self, context: RunContext, intent) -> None:
        registry = ToolRegistry(context)
        tool = registry.get(intent.selectedToolCode or "") if intent.selectedToolCode else None
        followup = self._resolve_router_followup(context, intent, tool)
        if not followup.accepted:
            followup = self.followup_resolver.resolve(context, tool)
        await self._emit_followup_event(context, followup)
        if followup.accepted and followup.tool_code:
            inherited_tool = registry.get(followup.tool_code)
            if inherited_tool is not None:
                tool = inherited_tool
                intent.selectedToolCode = inherited_tool.toolCode
                intent.candidateToolCodes = [inherited_tool.toolCode, *[
                    code for code in intent.candidateToolCodes if code != inherited_tool.toolCode
                ]][:3]
                intent.arguments = self._merge_tool_arguments(
                    followup.inherited_arguments,
                    followup.patched_arguments,
                    intent.arguments,
                    user_request=context.message,
                )
                intent.isFollowUp = True
                intent.inheritedFromToolCallId = followup.inherited_from_tool_call_id
        if tool is None:
            from app.core.intent_router import IntentResult
            intent_result = IntentResult(
                intent=Intent.NEEDS_CLARIFICATION, confidence=0.5, reason="tool_unavailable"
            )
            answer = self._format_clarifying_answer(context, intent_result)
            await self._emit_answer_events(context.runId, answer)
            await self._complete_run(context, answer, intent=Intent.NEEDS_CLARIFICATION.value)
            return

        validation = self.tool_decision_validator.validate(context, intent, tool)
        if not validation.accepted:
            replacement_tool = self._select_valid_candidate_tool(context, registry, intent, excluded_tool_code=tool.toolCode)
            if replacement_tool is not None:
                tool = replacement_tool
                intent.selectedToolCode = replacement_tool.toolCode
                intent.candidateToolCodes = [replacement_tool.toolCode, *[
                    code for code in intent.candidateToolCodes if code != replacement_tool.toolCode
                ]][:3]
                validation = self.tool_decision_validator.validate(context, intent, tool)
        if not validation.accepted:
            LOGGER.info(
                "agent tool decision rejected runId=%s selectedTool=%s reason=%s warnings=%s",
                context.runId,
                tool.toolCode,
                validation.reason,
                validation.warnings,
            )
            intent_result = IntentResult(
                intent=Intent.NEEDS_CLARIFICATION,
                confidence=0.55,
                reason=validation.reason,
                candidateToolCodes=intent.candidateToolCodes,
            )
            answer = self._format_clarifying_answer(context, intent_result)
            await self._emit_answer_events(context.runId, answer)
            await self._complete_run(context, answer, intent=Intent.NEEDS_CLARIFICATION.value)
            return
        if validation.requires_confirmation:
            intent.requiresConfirmation = True

        LOGGER.info(
            "agent tool path runId=%s selectedTool=%s toolName=%s autoCallable=%s candidateTools=%s",
            context.runId,
            tool.toolCode,
            tool.toolName,
            tool.autoCallable,
            intent.candidateToolCodes,
        )
        await self.backend.append_event(
            context.runId,
            RunEventCreate(eventType=TOOL_SELECTED, eventText=tool.toolCode, eventJson={"toolCode": tool.toolCode}),
        )

        budget = BudgetState(credit_budget=context.creditBudget)
        seed_args = dict(intent.arguments or {})
        base_args = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=True)
        base_args.update({key: value for key, value in seed_args.items() if value not in (None, "")})
        missing_args = self._missing_user_arguments(base_args, tool)
        extracted_args = base_args
        LOGGER.info(
            "agent tool arguments check runId=%s tool=%s missing=%s",
            context.runId,
            tool.toolCode,
            missing_args,
        )

        if missing_args:
            enriched = await self.tool_bridge.enrich_arguments(
                self.tool_bridge.conversation_argument_text(context), tool, existing_args=base_args,
            )
            prepared_enriched = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=True)
            prepared_enriched.update({key: value for key, value in enriched.items() if value not in (None, "")})
            enriched = prepared_enriched
            still_missing = self._missing_user_arguments(enriched, tool)
            extracted_args = enriched
            auto_call = self._should_auto_call(context, tool, followup, intent)
            if not still_missing:
                await self._emit_arguments_preview(context, tool, extracted_args, [], not auto_call)
                await self._emit_arguments_merged(context, tool, intent, extracted_args)
                if not auto_call:
                    await self._request_confirmation(context, tool)
                    return
                try:
                    result = await self._execute_tool_with_guard(context, tool, budget, arguments=enriched)
                except BudgetExceeded as exception:
                    await self._fail_run(context.runId, exception.error_code, exception.message, budget=budget)
                    return
                except ToolExecutionError as exception:
                    await self._fail_run(
                        context.runId,
                        exception.error_code or "TOOL_CALL_FAILED",
                        str(exception),
                        budget=budget,
                    )
                    return
                if result.get("missing_tool_arguments"):
                    await self._emit_missing_arguments(context, tool, result["missing_tool_arguments"])
                    answer = self._format_missing_arguments_message(tool, result["missing_tool_arguments"])
                    await self._emit_answer_events(context.runId, answer)
                    await self._complete_run(context, answer, intent=Intent.NEEDS_CLARIFICATION.value)
                    return
                answer = await self._synthesize_answer(context, tool, result, budget)
                await self._complete_run(context, answer, intent=Intent.TOOL_USE.value)
                await self._curate_memory_after_run(context, answer, tool_result=result)
                return
            else:
                await self._emit_arguments_preview(context, tool, enriched, still_missing, False)
                await self._emit_missing_arguments(context, tool, still_missing)
                answer = self._format_missing_arguments_message(tool, still_missing)
                await self._emit_answer_events(context.runId, answer)
                await self._complete_run(context, answer, intent=Intent.NEEDS_CLARIFICATION.value)
                return

        auto_call = self._should_auto_call(context, tool, followup, intent)
        current_args = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=True)
        extracted_args = self._merge_tool_arguments(current_args, extracted_args, {}, user_request=context.message)
        await self._emit_arguments_preview(context, tool, extracted_args, [], not auto_call)
        await self._emit_arguments_merged(context, tool, intent, extracted_args)

        if not auto_call:
            await self._request_confirmation(context, tool)
            return

        try:
            result = await self._execute_tool_with_guard(context, tool, budget, arguments=extracted_args)
        except BudgetExceeded as exception:
            await self._fail_run(context.runId, exception.error_code, exception.message, budget=budget)
            return
        except ToolExecutionError as exception:
            await self._fail_run(
                context.runId,
                exception.error_code or "TOOL_CALL_FAILED",
                str(exception),
                budget=budget,
            )
            return

        if result.get("missing_tool_arguments"):
            await self._emit_missing_arguments(context, tool, result["missing_tool_arguments"])
            answer = self._format_missing_arguments_message(tool, result["missing_tool_arguments"])
            await self._emit_answer_events(context.runId, answer)
            await self._complete_run(context, answer, intent=Intent.NEEDS_CLARIFICATION.value)
            return

        answer = await self._synthesize_answer(context, tool, result, budget)
        await self._complete_run(context, answer, intent=Intent.TOOL_USE.value)
        await self._curate_memory_after_run(context, answer, tool_result=result)

    async def _execute_tool_with_guard(
        self, context: RunContext, tool: ToolDescriptor, budget: BudgetState, arguments: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        return await self.tool_orchestrator.execute_with_guard(context, tool, budget, arguments=arguments)

    async def _emit_arguments_preview(
        self, context: RunContext, tool: ToolDescriptor, extracted_args: dict[str, Any],
        missing: list[str], needs_confirmation: bool,
    ) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=TOOL_ARGUMENTS_PREVIEW,
                eventText=f"Tool {tool.toolCode} arguments preview",
                eventJson={
                    "toolCode": tool.toolCode,
                    "toolName": tool.toolName,
                    "arguments": extracted_args,
                    "missingArguments": missing,
                    "needsConfirmation": needs_confirmation,
                },
            ),
        )

    async def _emit_followup_event(self, context: RunContext, followup: FollowupResolution) -> None:
        if not followup.accepted and followup.reason == "not_followup":
            return
        event_type = FOLLOWUP_INHERITED if followup.accepted else FOLLOWUP_REJECTED
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=FOLLOWUP_DETECTED,
                eventText=followup.reason,
                eventJson={
                    "accepted": followup.accepted,
                    "reason": followup.reason,
                    "toolCode": followup.tool_code,
                    "inheritedFromToolCallId": followup.inherited_from_tool_call_id,
                    "mediaUrls": followup.media_urls,
                },
            ),
        )
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=event_type,
                eventText=followup.tool_code or followup.reason,
                eventJson={
                    "reason": followup.reason,
                    "toolCode": followup.tool_code,
                    "inheritedFromToolCallId": followup.inherited_from_tool_call_id,
                    "inheritedArguments": followup.inherited_arguments if followup.accepted else {},
                    "patchedArguments": followup.patched_arguments if followup.accepted else {},
                },
            ),
        )

    async def _emit_arguments_merged(self, context: RunContext, tool: ToolDescriptor, intent, arguments: dict[str, Any]) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=ARGUMENTS_MERGED,
                eventText=tool.toolCode,
                eventJson={
                    "toolCode": tool.toolCode,
                    "arguments": arguments,
                    "routerArguments": intent.arguments or {},
                    "isFollowUp": bool(intent.isFollowUp),
                    "inheritedFromToolCallId": intent.inheritedFromToolCallId,
                    "missingFields": intent.missingFields or [],
                },
            ),
        )

    @staticmethod
    def _merge_tool_arguments(*sources: dict[str, Any], user_request: str | None = None) -> dict[str, Any]:
        merged: dict[str, Any] = {}
        for source in sources:
            for key, value in (source or {}).items():
                if value not in (None, ""):
                    merged[key] = value
        if user_request:
            merged["userRequest"] = user_request
        return merged

    def _resolve_router_followup(self, context: RunContext, intent, tool: ToolDescriptor | None) -> FollowupResolution:
        if not context.recentToolCalls:
            return FollowupResolution(False, "not_followup")
        if not (intent.isFollowUp or intent.inheritedFromToolCallId or intent.followupPatch):
            return FollowupResolution(False, "not_followup")

        selected_source = None
        if intent.inheritedFromToolCallId:
            selected_source = next((call for call in context.recentToolCalls if call.id == intent.inheritedFromToolCallId), None)
        if selected_source is None and tool is not None:
            selected_source = next((call for call in context.recentToolCalls if call.toolCode == tool.toolCode), None)
        if selected_source is None:
            selected_source = context.recentToolCalls[0]

        inherited = dict(selected_source.argumentsJson or {})
        patch = dict(intent.followupPatch or {})
        if not patch and intent.arguments:
            patch = dict(intent.arguments)
        if not patch and tool is not None:
            fallback = self.followup_resolver.resolve(context, tool)
            if fallback.accepted:
                return fallback
        if selected_source.mediaUrls and tool is not None and tool_supports_modality(tool, "VIDEO"):
            image_key = self._first_schema_key(tool, ("imageUrl", "image_url", "referenceImageUrl", "reference_image_url", "initImage", "inputImage"))
            if image_key and image_key not in patch:
                patch[image_key] = selected_source.mediaUrls[0]

        tool_code = tool.toolCode if tool is not None else selected_source.toolCode
        return FollowupResolution(
            True,
            "router_followup_inherited",
            tool_code=tool_code,
            inherited_from_tool_call_id=selected_source.id,
            inherited_arguments=inherited,
            patched_arguments=self._merge_tool_arguments(inherited, patch, intent.arguments, user_request=context.message),
            media_urls=selected_source.mediaUrls or [],
        )

    @staticmethod
    def _first_schema_key(tool: ToolDescriptor, keys: tuple[str, ...]) -> str | None:
        properties = tool.inputSchema.get("properties", {}) if isinstance(tool.inputSchema, dict) else {}
        if not isinstance(properties, dict):
            return None
        return next((key for key in keys if key in properties), None)

    @staticmethod
    def _select_valid_candidate_tool(
        context: RunContext,
        registry: ToolRegistry,
        intent,
        *,
        excluded_tool_code: str | None = None,
    ) -> ToolDescriptor | None:
        requested_modality = requested_output_modality(context.message)
        if not requested_modality:
            return None
        for code in intent.candidateToolCodes:
            if code == excluded_tool_code:
                continue
            candidate = registry.get(code)
            if candidate is not None and infer_output_modality(candidate) == requested_modality:
                return candidate
        return None

    async def _emit_missing_arguments(self, context: RunContext, tool: ToolDescriptor, missing: list[str]) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=TOOL_MISSING_ARGUMENTS,
                eventText=f"Tool {tool.toolCode} missing arguments",
                eventJson={
                    "toolCode": tool.toolCode,
                    "toolName": tool.toolName,
                    "missingArguments": missing,
                },
            ),
        )

    async def _request_confirmation(self, context: RunContext, tool: ToolDescriptor) -> None:
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
                },
            ),
        )

    def _should_auto_call(self, context: RunContext, tool: ToolDescriptor, followup: FollowupResolution | None = None, intent=None) -> bool:
        if intent is not None and intent.requiresConfirmation is True:
            return False
        if tool.autoCallable:
            return True
        if any(p.toolCode == tool.toolCode and p.autoCallEnabled for p in context.toolPreferences):
            return True
        if followup is not None and followup.accepted and self._is_safe_followup_auto_call(tool):
            return True
        return self._is_direct_generation_request(context, tool)

    @staticmethod
    def _is_safe_followup_auto_call(tool: ToolDescriptor) -> bool:
        if tool.estimatedCreditCost and tool.estimatedCreditCost > 20:
            return False
        high_risk = {"high", "critical", "danger"}
        for field in tool.fields:
            if (field.riskLevel or "").strip().lower() in high_risk:
                return False
        return True

    def _is_direct_generation_request(self, context: RunContext, tool: ToolDescriptor) -> bool:
        modality = requested_output_modality(context.message)
        if not modality:
            return False
        if not tool_supports_modality(tool, modality):
            return False
        return self.intent_router._looks_like_tool_request(context.message)

    @staticmethod
    def _missing_user_arguments(arguments: dict[str, Any], tool: ToolDescriptor) -> list[str]:
        if tool.fields:
            return [
                field.fieldKey
                for field in tool.fields
                if _field_requires_user_input(field, tool)
                and (field.fieldKey not in arguments or arguments[field.fieldKey] in (None, ""))
            ]
        required = tool.inputSchema.get("required", [])
        if not isinstance(required, list):
            return []
        properties = tool.inputSchema.get("properties", {})
        return [
            name
            for name in required
            if isinstance(name, str)
            and _schema_property_user_required(properties.get(name) if isinstance(properties, dict) else None)
            and (name not in arguments or arguments[name] in (None, ""))
        ]

    @staticmethod
    def _missing_execution_arguments(arguments: dict[str, Any], tool: ToolDescriptor) -> list[str]:
        return missing_execution_arguments(arguments, tool)

    @staticmethod
    def _format_missing_arguments_message(tool: ToolDescriptor, missing: list[str]) -> str:
        return format_missing_tool_arguments_message(tool, missing)

    # --- Chat / Deep Agents flow ---

    async def _run_chat(self, context: RunContext, intent=None) -> str:
        system_prompt = _compose_system_prompt(
            context.agentSystemPrompt,
            context,
            DEFAULT_AGENT_SYSTEM_PROMPT,
        )

        messages = [ChatMessage(role="system", content=system_prompt)]
        workspace_memory_context = await self._fetch_workspace_memory_context(context)
        if workspace_memory_context:
            messages.append(ChatMessage(
                role="system",
                content=f"<!-- frozen memory snapshot -->\n{workspace_memory_context}",
            ))

        # Emit frozen event so frontend/tracing can see the snapshot state
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=MEMORY_CONTEXT_FROZEN,
                eventJson={
                    "frozen": bool(workspace_memory_context),
                    "count": len(workspace_memory_context) if workspace_memory_context else 0,
                },
            ),
        )

        file_context = _format_file_context(context)
        if file_context:
            messages.append(ChatMessage(role="system", content=file_context))

        memory_tool = None
        recap_question = _looks_like_session_recap_question(context.message)
        if recap_question:
            deterministic_answer = _direct_session_recap_answer(context)
            if deterministic_answer:
                return deterministic_answer
            summary = _format_recent_session_summary(context)
            if summary:
                messages.append(ChatMessage(role="system", content=summary))
            messages.append(
                ChatMessage(
                    role="system",
                    content=(
                        "用户正在询问本会话里你已经帮他做过什么。"
                        "请直接根据上方「本轮会话近期记录」、记忆快照与对话历史作答，列出已完成的事项。"
                        "禁止只说「让我查一下记忆/记录」却不给出具体结果。"
                        "本回合不要调用 memory_add / memory_replace / memory_remove。"
                    ),
                )
            )
        elif context.workspaceId and _memory_auto_save_enabled(context):
            memory_tool = MemoryTool(self.backend, context.workspaceId, context.userId, run_id=context.runId)
            messages.append(ChatMessage(role="system", content=_memory_tool_prompt(context)))

        messages.extend(context.history)
        messages.append(ChatMessage(role="user", content=context.message))
        budget = BudgetState(credit_budget=context.creditBudget)
        return await self._stream_model_answer(
            context.runId,
            messages,
            budget,
            memory_tool=memory_tool,
            memory_tool_loop_enabled=_memory_tool_loop_enabled(context),
        )

    def _format_clarifying_answer(self, context: RunContext, intent) -> str:
        if intent.clarifyingQuestion:
            return intent.clarifyingQuestion
        if intent.candidateToolCodes:
            registry = ToolRegistry(context)
            names = []
            for code in intent.candidateToolCodes:
                tool = registry.get(code)
                names.append(tool.toolName if tool else code)
            if len(names) >= 2:
                return f"你说的范围有点宽，我更想先确认你想用哪一种：{'、'.join(names)}。请补充更具体的需求或参数。"
            return "请补充你想完成的目标、对象和期望输出，我再帮你选择合适的工具。"
        return "请补充你想完成的目标、对象和期望输出，我再帮你选择合适的工具。"

    async def _run_deep_agents_or_chat(self, context: RunContext, intent=None) -> None:
        module = self.dependency_loader()
        if module is None:
            answer = await self._run_chat(context, intent)
            await self._complete_run(context, answer, intent=intent.intent.value if intent else Intent.GENERAL_CHAT.value)
            return

        create_deep_agent = getattr(module, "create_deep_agent", None)
        if not callable(create_deep_agent):
            answer = await self._run_chat(context, intent)
            await self._complete_run(context, answer, intent=intent.intent.value if intent else Intent.GENERAL_CHAT.value)
            return

        try:
            chat_model = self._chat_model()
            workspace_memory_items = await self._fetch_workspace_memory_items(context)
            workspace_file_context = await self._build_workspace_file_context(context)
            system_prompt = _compose_system_prompt(
                context.deepAgentsSystemPrompt or context.agentSystemPrompt,
                context,
                DEEP_AGENTS_SYSTEM_PROMPT,
            )
            agent = create_deep_agent(
                tools=[],
                system_prompt=system_prompt,
                model=chat_model,
                subagents=profiles_to_deepagents_subagents(
                    default_subagent_profiles(),
                    model=chat_model,
                    tools=[],
                ),
                memory=format_workspace_memory_items(workspace_memory_items) + workspace_file_context.memory_items,
            )
        except AttributeError as exception:
            await self._fail_run(context.runId, "DEEP_AGENTS_MODEL_UNSUPPORTED", f"Deep Agents requires a LangChain-compatible chat model: {exception}")
            return

        workspace_memory_context = format_workspace_memory_context(workspace_memory_items)

        # Emit frozen event only when there is memory to expose in the trace.
        if workspace_memory_context:
            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=MEMORY_CONTEXT_FROZEN,
                    eventJson={
                        "frozen": True,
                        "count": len(workspace_memory_items),
                    },
                ),
            )

        answer, streamed = await _invoke_agent(
            agent,
            {"messages": _messages(context, workspace_memory_context, workspace_file_context.prompt_context)},
            config={"callbacks": [SubagentTraceCallbackHandler(context.runId, self.backend)]},
            run_id=context.runId,
            backend_client=self.backend,
        )
        artifact = _parse_artifact_directive(answer)
        if artifact is not None:
            await self._create_artifact(context.runId, artifact)
        if streamed:
            await self.backend.append_event(
                context.runId,
                RunEventCreate(eventType=MESSAGE_COMPLETED, eventText=answer, eventJson={"content": answer}),
            )
        else:
            await self._emit_answer_events(context.runId, answer)
        await self._complete_run(context, answer, intent=DEEP_AGENTS_INTENT)
        await self._curate_memory_after_run(context, answer)

    # --- Synthesize tool answer ---

    async def _synthesize_answer(self, context: RunContext, tool: ToolDescriptor, result: dict[str, Any], budget: BudgetState | None = None) -> str:
        tool_arguments = result.get("arguments") if isinstance(result, dict) else {}
        tool_data = result.get("data") if isinstance(result, dict) else {}
        content_text = tool_data.get("contentText", "") if isinstance(tool_data, dict) else ""
        # 工具已成功产出正文时直接回显，避免二次 LLM 总结/记忆提示把正文换成「已生成」等空话。
        if isinstance(content_text, str) and content_text.strip():
            if _is_structured_media_result(content_text):
                await self._emit_completed_answer_event(context.runId, content_text)
            else:
                await self._emit_answer_events(context.runId, content_text)
            return content_text
        messages_list = [
            ChatMessage(role="system", content="请直接展示工具返回的结果，不要添加额外的总结说明。"),
        ]
        workspace_memory_context = await self._fetch_workspace_memory_context(context)
        if workspace_memory_context:
            messages_list.append(ChatMessage(role="system", content=workspace_memory_context))
        memory_tool = None
        if context.workspaceId and _memory_auto_save_enabled(context):
            memory_tool = MemoryTool(self.backend, context.workspaceId, context.userId, run_id=context.runId)
            messages_list.append(ChatMessage(role="system", content=_memory_tool_prompt(context)))
        if content_text:
            messages_list.append(
                ChatMessage(
                    role="user",
                    content=(
                        f"User request: {context.message}\n"
                        f"Tool arguments: {tool_arguments}\n"
                        f"Tool output:\n{content_text}\n\n"
                        "Use the tool output as the source of truth. Do not repeat identical paragraphs."
                    ),
                )
            )
        else:
            messages_list.append(ChatMessage(role="user", content=f"User request: {context.message}\nTool result: {result}"))
        return await self._stream_model_answer(
            context.runId,
            messages_list,
            budget,
            memory_tool=memory_tool,
            fallback_answer=content_text,
            memory_tool_loop_enabled=_memory_tool_loop_enabled(context),
        )

    # --- Common helpers ---

    async def _stream_model_answer(
        self, run_id: int, messages_list: list[ChatMessage],
        budget: BudgetState | None = None,
        memory_tool: MemoryTool | None = None,
        fallback_answer: str | None = None,
        memory_tool_loop_enabled: bool | None = None,
    ) -> str:
        parts: list[str] = []
        stream = getattr(self.model, "chat_stream", None)
        if memory_tool is not None and (settings.agent_memory_tool_loop_enabled if memory_tool_loop_enabled is None else memory_tool_loop_enabled) and callable(getattr(self.model, "chat_turn", None)):
            try:
                loop = AgentToolCallLoopExecutor(
                    backend=self.backend,
                    model=self.model,
                    run_id=run_id,
                    memory_tool=memory_tool,
                    reserve_model_call=lambda: self._budget_guard().reserve_model_call(budget) if budget is not None else None,
                    max_iterations=self._budget_guard().max_tool_calls,
                    max_tool_calls_per_turn=4,
                )
                result = await loop.run(messages_list)
                if result.executed_tool_calls > 0:
                    self._memory_tool_executed_runs.add(run_id)
                answer = result.answer.strip()
                if not answer and fallback_answer:
                    answer = fallback_answer
                if answer:
                    await self._emit_answer_events(run_id, answer)
                    return answer
            except Exception as exc:
                LOGGER.debug("memory tool-call loop skipped runId=%s error=%s", run_id, exc)
        if budget is not None:
            self._budget_guard().reserve_model_call(budget)
        if stream is None:
            try:
                answer = await self.model.chat(messages_list)
            except TypeError:
                answer = await self.model.chat(messages_list)
            if not answer.strip() and fallback_answer:
                answer = fallback_answer
            await self._emit_answer_events(run_id, answer)
            return answer
        try:
            stream_iter = self.model.chat_stream(messages_list)
        except TypeError:
            stream_iter = self.model.chat_stream(messages_list)
        persisted_length = 0
        try:
            async for chunk in stream_iter:
                parts.append(chunk)
                await self.backend.append_event(
                    run_id,
                    RunEventCreate(eventType=MESSAGE_DELTA, eventText=chunk, eventJson={"delta": chunk}),
                )
                current_answer = "".join(parts)
                if len(current_answer) - persisted_length >= 160:
                    await self._upsert_streaming_answer(run_id, current_answer)
                    persisted_length = len(current_answer)
        except Exception:
            partial_answer = "".join(parts)
            if partial_answer.strip():
                await self._upsert_streaming_answer(run_id, partial_answer)
            raise
        answer = "".join(parts)
        if not answer.strip() and fallback_answer:
            answer = fallback_answer
            for chunk in _chunks(answer, 32):
                await self.backend.append_event(
                    run_id,
                    RunEventCreate(eventType=MESSAGE_DELTA, eventText=chunk, eventJson={"delta": chunk}),
                )
        await self._upsert_streaming_answer(run_id, answer)
        await self.backend.append_event(
            run_id,
            RunEventCreate(eventType=MESSAGE_COMPLETED, eventText=answer, eventJson={"content": answer}),
        )
        return answer

    async def _upsert_streaming_answer(self, run_id: int, answer: str) -> None:
        if not answer.strip():
            return
        upsert = getattr(self.backend, "upsert_streaming_answer", None)
        if not callable(upsert):
            return
        try:
            await upsert(run_id, answer)
        except Exception:
            LOGGER.exception("failed to persist streaming answer preview, runId=%s", run_id)

    async def _emit_answer_events(self, run_id: int, answer: str) -> None:
        for chunk in _chunks(answer, 32):
            await self.backend.append_event(
                run_id,
                RunEventCreate(eventType=MESSAGE_DELTA, eventText=chunk, eventJson={"delta": chunk}),
            )
        await self.backend.append_event(
            run_id,
            RunEventCreate(eventType=MESSAGE_COMPLETED, eventText=answer, eventJson={"content": answer}),
        )

    async def _emit_completed_answer_event(self, run_id: int, answer: str) -> None:
        await self.backend.append_event(
            run_id,
            RunEventCreate(eventType=MESSAGE_COMPLETED, eventText=answer, eventJson={"content": answer}),
        )

    async def _complete_run(self, context: RunContext, final_answer: str, intent: str = Intent.GENERAL_CHAT.value) -> None:
        normalized_answer = (final_answer or "").strip()
        if not normalized_answer:
            normalized_answer = "抱歉，本次未能生成有效回复，请换个说法或补充更多信息后再试。"
        consumed_credits = self._budget_guard().default_consumed_credits
        model_name = getattr(self.model, "model_name", settings.model_name)
        usage = self._model_usage_or_estimate(context, normalized_answer)
        await self.backend.complete_run(
            context.runId,
            RunComplete(
                finalAnswer=normalized_answer,
                intent=intent,
                modelProviderCode="agent-service",
                modelName=model_name,
                consumedCredits=consumed_credits,
                promptTokens=usage["promptTokens"],
                completionTokens=usage["completionTokens"],
            ),
        )

    async def _fail_run(
        self,
        run_id: int,
        error_code: str,
        error_message: str,
        budget: BudgetState | None = None,
    ) -> None:
        usage = self._model_usage()
        consumed_credits = budget.consumed_credits if budget and budget.consumed_credits > 0 else None
        if consumed_credits is None and (usage["promptTokens"] > 0 or usage["completionTokens"] > 0):
            consumed_credits = self._budget_guard().default_consumed_credits
        await self.backend.fail_run(
            run_id,
            RunFail(
                errorCode=error_code,
                errorMessage=error_message,
                consumedCredits=consumed_credits,
                promptTokens=usage["promptTokens"],
                completionTokens=usage["completionTokens"],
            ),
        )

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
        if usage["promptTokens"] > 0 or usage["completionTokens"] > 0:
            return usage
        prompt_text = "\n".join(
            [
                *[message.content for message in context.history if message.content],
                context.message or "",
            ]
        )
        return {
            "promptTokens": _estimate_tokens(prompt_text),
            "completionTokens": _estimate_tokens(final_answer),
        }

    def _chat_model(self):
        return getattr(self.model, "chat_model", self.model)

    # --- Workspace memory & files ---

    async def _fetch_workspace_memory_items(self, context: RunContext) -> list[WorkspaceMemoryItem]:
        return await self.memory_runtime.fetch_items(context)

    async def _fetch_workspace_memory_context(self, context: RunContext) -> str:
        return await self.memory_runtime.fetch_context(context)

    async def _fetch_session_search_context(self, context: RunContext) -> str:
        return await self.memory_runtime.fetch_session_search_context(context)

    async def _build_workspace_file_context(self, context: RunContext) -> WorkspaceFileContext:
        return await self.file_runtime.build_context(context)

    async def _create_artifact(self, run_id: int, artifact: "ArtifactDirective") -> None:
        await self.file_runtime.create_artifact(run_id, artifact)

    async def _emit_memory_candidate(self, context: RunContext, *, title: str, content: str, decision_json: dict[str, Any]) -> None:
        await self.memory_runtime.emit_memory_candidate(context, title=title, content=content, decision_json=decision_json)

    async def _curate_memory_after_run(self, context: RunContext, answer: str, tool_result: dict[str, Any] | None = None) -> None:
        await self.memory_runtime.curate_after_run(
            context,
            answer,
            tool_result=tool_result,
            memory_tool_executed=context.runId in self._memory_tool_executed_runs,
        )

    async def _maybe_consolidate_memory(self, context: RunContext, answer: str, existing: list[WorkspaceMemoryItem]) -> None:
        await self.memory_runtime.maybe_consolidate(context, answer, existing)


async def _invoke_agent(
    agent,
    payload: dict,
    config: dict | None = None,
    *,
    run_id: int | None = None,
    backend_client=None,
) -> tuple[str, bool]:
    if hasattr(agent, "astream_events"):
        final_result = None
        async for event in agent.astream_events(payload, config=config, version="v2"):
            if not isinstance(event, dict):
                continue
            if event.get("event") == "on_chat_model_stream":
                chunk = event.get("data", {}).get("chunk")
                content = _raw_message_content(chunk) or _message_content(chunk)
                if content and run_id is not None and backend_client is not None:
                    await backend_client.append_event(
                        run_id,
                        RunEventCreate(eventType=MESSAGE_DELTA, eventText=content, eventJson={"delta": content}),
                    )
            elif event.get("event") == "on_chain_end":
                final_result = event.get("data", {}).get("output")
        return _extract_final_answer(final_result), True

    if hasattr(agent, "ainvoke"):
        result = agent.ainvoke(payload, config=config)
    elif hasattr(agent, "invoke"):
        result = agent.invoke(payload, config=config)
    else:
        raise RuntimeError("deepagents agent does not expose invoke or ainvoke")
    if inspect.isawaitable(result):
        result = await result
    return _extract_final_answer(result), False


class SubagentTraceCallbackHandler(AsyncCallbackHandler):
    """Tracks subagent lifecycle events via LangChain / deepagents tool callbacks."""

    def __init__(self, run_id: int, backend_client) -> None:
        self.run_id = run_id
        self.backend_client = backend_client
        self._subagent_names_by_tool_run: dict[str, str] = {}

    async def on_tool_start(
        self,
        serialized: dict[str, Any],
        input_str: str,
        *,
        run_id,
        inputs: dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        if not _is_task_tool(serialized):
            return
        subagent_name = _subagent_name_from_inputs(inputs)
        if not subagent_name:
            return
        self._subagent_names_by_tool_run[str(run_id)] = subagent_name
        task_description = _task_description_from_inputs(inputs, input_str)
        await self._append_event(
            SUBAGENT_STARTED,
            subagent_name,
            {
                "subagentName": subagent_name,
                "taskDescription": task_description,
            },
        )

    async def on_tool_end(self, output: Any, *, run_id, **kwargs: Any) -> None:
        subagent_name = self._subagent_names_by_tool_run.pop(str(run_id), None)
        if not subagent_name:
            return
        await self._append_event(
            SUBAGENT_COMPLETED,
            subagent_name,
            {
                "subagentName": subagent_name,
            },
        )

    async def on_tool_error(self, error: BaseException, *, run_id, **kwargs: Any) -> None:
        subagent_name = self._subagent_names_by_tool_run.pop(str(run_id), None)
        if not subagent_name:
            return
        await self._append_event(
            SUBAGENT_FAILED,
            subagent_name,
            {
                "subagentName": subagent_name,
                "error": str(error),
            },
        )

    async def _append_event(self, event_type: str, event_text: str, event_json: dict[str, Any]) -> None:
        try:
            await self.backend_client.append_event(
                self.run_id,
                RunEventCreate(eventType=event_type, eventText=event_text, eventJson=event_json),
            )
        except Exception:
            return


def _is_task_tool(serialized: dict[str, Any]) -> bool:
    return serialized.get("name") == "task"


def _subagent_name_from_inputs(inputs: dict[str, Any] | None) -> str:
    if not isinstance(inputs, dict):
        return ""
    value = inputs.get("subagent_type") or inputs.get("subagentType")
    return value if isinstance(value, str) else ""


def _task_description_from_inputs(inputs: dict[str, Any] | None, input_str: str) -> str:
    if isinstance(inputs, dict) and isinstance(inputs.get("description"), str):
        return inputs["description"]
    return input_str


def _messages(context: RunContext, workspace_memory_context: str = "", workspace_file_context: str = "") -> list[dict[str, str]]:
    messages_list = [_message(message) for message in context.history]
    if workspace_memory_context:
        messages_list.append({"role": "system", "content": workspace_memory_context})
    if workspace_file_context:
        messages_list.append({"role": "system", "content": workspace_file_context})
    messages_list.append({"role": "user", "content": context.message})
    return messages_list


def _message(message: ChatMessage) -> dict[str, str]:
    role = message.role.lower()
    if role in {"assistant", "ai"}:
        role = "assistant"
    elif role != "system":
        role = "user"
    return {"role": role, "content": message.content}


def _extract_final_answer(result) -> str:
    if isinstance(result, dict):
        for key in ("final_answer", "finalAnswer", "output"):
            value = result.get(key)
            if isinstance(value, str) and value.strip():
                return value
        messages = result.get("messages")
        if isinstance(messages, list):
            for message in reversed(messages):
                content = _message_content(message)
                if content:
                    return content
    content = _message_content(result)
    if content:
        return content
    raise RuntimeError("deepagents returned no final answer")


@dataclass(frozen=True)
class ArtifactDirective:
    filename: str
    content: str
    content_type: str


def _parse_artifact_directive(answer: str) -> ArtifactDirective | None:
    match = re.match(r"^\[artifact:([^\]\r\n]+)\]\r?\n([\s\S]*)\Z", answer)
    if not match:
        return None
    filename = match.group(1).strip()
    content = match.group(2)
    if not filename:
        return None
    return ArtifactDirective(filename=filename, content=content, content_type=_content_type_for_filename(filename))


def _content_type_for_filename(filename: str) -> str:
    lower_filename = filename.lower()
    if lower_filename.endswith(".md") or lower_filename.endswith(".markdown"):
        return "text/markdown"
    if lower_filename.endswith(".json"):
        return "application/json"
    if lower_filename.endswith(".html") or lower_filename.endswith(".htm"):
        return "text/html"
    if lower_filename.endswith(".csv"):
        return "text/csv"
    return "text/plain"


def _message_content(message) -> str:
    if isinstance(message, dict):
        content = message.get("content")
    else:
        content = getattr(message, "content", None)
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict) and isinstance(item.get("text"), str):
                parts.append(item["text"])
        return "".join(parts).strip()
    return ""


def _raw_message_content(message) -> str:
    if isinstance(message, dict):
        content = message.get("content")
    else:
        content = getattr(message, "content", None)
    return content if isinstance(content, str) else ""


def _chunks(value: str, size: int) -> list[str]:
    return [value[index : index + size] for index in range(0, len(value), size)] or [""]


def _estimate_tokens(value: str) -> int:
    text = value or ""
    if not text.strip():
        return 0
    return max(1, (len(text) + 3) // 4)


def _parse_json_object(raw: str) -> Any:
    text = raw.strip()
    if text.startswith("```"):
        lines = text.splitlines()
        if lines:
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, re.DOTALL)
        if not match:
            return None
        try:
            return json.loads(match.group(0))
        except json.JSONDecodeError:
            return None


def _safe_float(value: Any, fallback: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return fallback


def _clip(value: str, limit: int = 500) -> str:
    if len(value) <= limit:
        return value
    return value[:limit] + "...<truncated>"


def _is_structured_media_result(value: str) -> bool:
    text = value.strip()
    if not text or not text.startswith(("{", "[")):
        return False
    try:
        import json

        parsed = json.loads(text)
    except Exception:
        return False
    return _contains_media_result(parsed)


def _contains_media_result(value: Any) -> bool:
    if isinstance(value, dict):
        for key, nested in value.items():
            lowered = str(key).lower()
            if lowered in {
                "images",
                "videos",
                "audios",
                "imageurl",
                "image_url",
                "videourl",
                "video_url",
                "audiourl",
                "audio_url",
            }:
                return True
            if _contains_media_result(nested):
                return True
    if isinstance(value, list):
        return any(_contains_media_result(item) for item in value)
    return False


def _looks_like_session_recap_question(message: str) -> bool:
    return IntentRouter._looks_like_session_recap_question(message)


def _field_requires_user_input(field, tool: ToolDescriptor) -> bool:
    strategy = (field.agentFillStrategy or "").strip().lower()
    if strategy in {"default", "derive", "none"}:
        return False
    if field.defaultValue not in (None, "") and strategy != "ask_user":
        return False
    if field.userRequired is not None:
        return bool(field.userRequired)
    properties = tool.inputSchema.get("properties", {})
    return _schema_property_user_required(properties.get(field.fieldKey) if isinstance(properties, dict) else None)


def _schema_property_user_required(prop: Any) -> bool:
    if not isinstance(prop, dict):
        return True
    if prop.get("x-user-required") is False:
        return False
    strategy = str(prop.get("x-agent-fill-strategy") or "").strip().lower()
    if strategy in {"default", "derive", "none"}:
        return False
    if prop.get("default") not in (None, "") and strategy != "ask_user":
        return False
    return True


def _compose_system_prompt(configured_prompt: str | None, context: RunContext, fallback: str) -> str:
    base_prompt = (configured_prompt or "").strip() or fallback
    tools_prompt = _format_available_tools_prompt(context)
    if not tools_prompt:
        return base_prompt
    return f"{base_prompt}\n\n{tools_prompt}"


def _format_available_tools_prompt(context: RunContext) -> str:
    available_tools = context.availableTools or []
    if not available_tools:
        return ""
    tool_descriptions = []
    for tool in available_tools:
        name = tool.toolName or tool.toolCode
        desc = tool.description or ""
        code = tool.toolCode or ""
        if desc:
            tool_descriptions.append(f"- {name} ({code}): {desc}")
        else:
            tool_descriptions.append(f"- {name} ({code})")
    return (
        "你可以读取并编排的平台 AI 工具如下。注意：这些工具会使用各自后台绑定的模型配置，"
        "不要把当前 Agent 模型当作工具执行模型。\n"
        + "\n".join(tool_descriptions)
    )


def _format_recent_session_summary(context: RunContext) -> str:
    lines: list[str] = []
    for item in context.history[-24:]:
        role = (item.role or "").strip().lower()
        content = (item.content or "").strip()
        if not content:
            continue
        if role in {"user", "human"}:
            preview = re.sub(r"\s+", " ", content)[:140]
            lines.append(f"- 用户：{preview}")
            continue
        if role not in {"assistant", "ai"}:
            continue
        if "如果想使用「" in content and len(content) < 500:
            tool_match = re.search(r"如果想使用「([^」]+)」", content)
            tool_name = tool_match.group(1) if tool_match else "某工具"
            lines.append(f"- 助手：引导你补充「{tool_name}」所需参数")
        elif len(content) >= 80:
            preview = re.sub(r"\s+", " ", content)[:180]
            lines.append(f"- 助手：已产出内容（节选）{preview}…")
    if not lines:
        return ""
    return "本轮会话近期记录（供直接回答「做过什么」）：\n" + "\n".join(lines[-14:])


def _direct_session_recap_answer(context: RunContext) -> str:
    message = re.sub(r"\s+", "", context.message or "")
    asks_generation_tool = (
        ("用什么" in message or "哪个工具" in message or "什么生成" in message or "怎么生成" in message)
        and ("生成" in message or "工具" in message or "这张图" in message or "图片" in message)
    )
    if not asks_generation_tool:
        return ""
    assistant_text = _latest_assistant_text(context)
    tool_names = _extract_tool_mentions(assistant_text, context)
    if not tool_names:
        tool_names = _infer_tool_names_from_recent_text(context)
    if not tool_names:
        return "我这边没有在当前上下文里读到上一张图对应的工具记录，可能是历史记录还没同步完整。你可以在后台 Agent Run 详情里按最近一次 taskId 查看。"
    tool_text = "、".join(tool_names[:3])
    if _contains_media_hint(assistant_text):
        return f"刚刚这次是用「{tool_text}」生成的，不是当前对话模型自己生成。当前 Agent 模型只负责理解需求和调度工具，真正出图走的是工具后台绑定的模型配置。"
    return f"刚刚这次用到的是「{tool_text}」。当前 Agent 模型只负责理解需求和调度，工具本身会使用后台绑定的模型配置。"


def _latest_assistant_text(context: RunContext) -> str:
    for item in reversed(context.history[-12:]):
        role = (item.role or "").strip().lower()
        content = (item.content or "").strip()
        if role in {"assistant", "ai"} and content:
            return content
    return ""


def _extract_tool_mentions(text: str, context: RunContext) -> list[str]:
    if not text:
        return []
    found: list[str] = []
    for tool in context.availableTools or []:
        candidates = [tool.toolName or "", tool.toolCode or ""]
        for candidate in candidates:
            name = candidate.strip()
            if name and name in text and (tool.toolName or tool.toolCode) not in found:
                found.append(tool.toolName or tool.toolCode)
                break
    quoted = re.findall(r"「([^」]{2,80})」", text)
    for item in quoted:
        if any(keyword in item.lower() for keyword in ("image", "图", "视频", "tts", "deepseek", "gpt")) and item not in found:
            found.append(item)
    return found


def _infer_tool_names_from_recent_text(context: RunContext) -> list[str]:
    combined = "\n".join((item.content or "") for item in context.history[-8:])
    return _extract_tool_mentions(combined, context)


def _contains_media_hint(text: str) -> bool:
    lowered = (text or "").lower()
    return any(keyword in lowered for keyword in ("图片", "照片", "生成图", "image", ".png", ".jpg", ".jpeg", "http"))


def _format_file_context(context: RunContext) -> str:
    ready_chunks = [chunk for chunk in context.agentFileChunks if chunk.contentText.strip()]
    if ready_chunks:
        sections = [
            "Relevant excerpts have been retrieved from the user's uploaded files. Use them when relevant, and cite filenames in your answer."
        ]
        for chunk in ready_chunks:
            sections.append(f"\n[File: {chunk.originalFilename}, chunk {chunk.chunkIndex}]\n{chunk.contentText[:4000]}")
        return "\n".join(sections)
    ready_files = [file for file in context.agentFiles if file.status == "READY" and file.extractedText.strip()]
    if not ready_files:
        return ""
    sections = [
        "The user has uploaded files. Use this file context when it is relevant, and cite filenames in your answer."
    ]
    for file in ready_files:
        sections.append(f"\n[File: {file.originalFilename}]\n{file.extractedText[:12000]}")
    return "\n".join(sections)


def _format_workspace_memory_context(items: list[WorkspaceMemoryItem]) -> str:
    active_items = [item for item in items if item.content.strip() or item.title.strip()]
    if not active_items:
        return ""

    sections = ["Frozen workspace memory snapshot", "Priority: current user instruction > live tool result > recent tool calls > long-term memory."]
    profiles = [it for it in active_items if it.memoryType in ("user_profile", "preference")]
    knowledge = [it for it in active_items if it.memoryType in ("project_knowledge", "workspace_fact")]
    procedural = [it for it in active_items if it.memoryType in ("tool_lesson", "workflow_recipe")]
    others = [it for it in active_items if it.memoryType not in ("user_profile", "preference", "project_knowledge", "workspace_fact", "tool_lesson", "workflow_recipe")]

    if profiles:
        sections.append("[User Profile]")
        for item in profiles:
            sections.append(f"  - {_memory_label(item)} {_safe_memory_text(item.content, 800)}")
        sections.append("")

    if knowledge:
        sections.append("[Workspace Facts]")
        for item in knowledge:
            sections.append(f"  {_memory_label(item)} {_safe_memory_text(item.title, 120)}\n  {_safe_memory_text(item.content, 1200)}")
        sections.append("")

    if procedural:
        sections.append("[Procedural Lessons]")
        for item in procedural:
            sections.append(f"  {_memory_label(item)} {_safe_memory_text(item.title, 120)}\n  {_safe_memory_text(item.content, 1200)}")
        sections.append("")

    if others:
        sections.append("[Other Notes]")
        for item in others:
            sections.append(f"  {_memory_label(item)} {_safe_memory_text(item.title, 120)}\n  {_safe_memory_text(item.content, 1200)}")

    return "\n".join(sections).strip()


def _format_workspace_memory_items(items: list[WorkspaceMemoryItem]) -> list[str]:
    memory_items = []
    for item in items:
        if not item.content.strip() and not item.title.strip():
            continue
        title = item.title.strip() or "Untitled memory"
        content = item.content.strip()
        memory_type = item.memoryType.strip() or "memory"
        memory_items.append(f"{_memory_label(item)} {_safe_memory_text(title, 120)} ({memory_type})\n{_safe_memory_text(content, 1200)}")
    return memory_items


def _format_session_search_context(items: list[SessionSearchItem]) -> str:
    active_items = [item for item in items if (item.content or item.argumentsJson or item.resultJson)]
    if not active_items:
        return ""
    sections = ["Session search results (episodic memory, not long-term facts)"]
    for item in active_items[:6]:
        if item.itemType == "tool_call":
            sections.append(
                f"  [tool_call:{item.id}, tool={item.toolCode}, run={item.runId}, task={item.taskId}, score={item.score}]\n"
                f"  args={_safe_memory_text(item.argumentsJson or '', 500)}\n"
                f"  result={_safe_memory_text(item.resultJson or item.content or '', 900)}"
            )
        else:
            sections.append(
                f"  [message:{item.id}, role={item.role}, run={item.runId}, score={item.score}]\n"
                f"  {_safe_memory_text(item.content or '', 900)}"
            )
    return "\n".join(sections).strip()


def _looks_like_session_search_request(message: str) -> bool:
    compact = re.sub(r"\s+", "", (message or "").lower())
    return any(token in compact for token in ("上次", "之前", "刚才", "那张", "那个", "历史", "previous", "lasttime"))


def _memory_label(item: WorkspaceMemoryItem) -> str:
    pinned = ", pinned" if item.pinned else ""
    importance = item.importance if item.importance is not None else "-"
    confidence = item.confidence if item.confidence is not None else "-"
    updated = item.updatedAt or "unknown"
    return f"[memory:{item.id}, type={item.memoryType}, score={item.score}, importance={importance}, confidence={confidence}, updated={updated}{pinned}]"


def _memory_auto_save_enabled(context: RunContext) -> bool:
    if context.memorySettings is not None and context.memorySettings.autoSaveEnabled is not None:
        return bool(context.memorySettings.autoSaveEnabled)
    return settings.agent_memory_auto_save_enabled


def _memory_tool_loop_enabled(context: RunContext) -> bool:
    if context.memorySettings is not None and context.memorySettings.toolLoopEnabled is not None:
        return bool(context.memorySettings.toolLoopEnabled)
    return settings.agent_memory_tool_loop_enabled


def _memory_consolidation_enabled(context: RunContext) -> bool:
    if context.memorySettings is not None and context.memorySettings.consolidationEnabled is not None:
        return bool(context.memorySettings.consolidationEnabled)
    return settings.agent_memory_consolidation_enabled


def _memory_consolidation_turn_interval(context: RunContext) -> int:
    if context.memorySettings is not None and context.memorySettings.consolidationTurnInterval is not None:
        return max(2, min(int(context.memorySettings.consolidationTurnInterval), 50))
    return settings.agent_memory_consolidation_turn_interval


def _memory_consolidation_char_threshold(context: RunContext) -> int:
    if context.memorySettings is not None and context.memorySettings.consolidationCharThreshold is not None:
        return max(500, min(int(context.memorySettings.consolidationCharThreshold), 50000))
    return settings.agent_memory_consolidation_char_threshold


def _memory_consolidation_min_confidence(context: RunContext) -> float:
    if context.memorySettings is not None and context.memorySettings.consolidationMinConfidence is not None:
        return max(0.0, min(float(context.memorySettings.consolidationMinConfidence), 1.0))
    return settings.agent_memory_consolidation_min_confidence


def _memory_candidate_confidence_threshold(context: RunContext) -> float:
    if context.memorySettings is not None and context.memorySettings.candidateConfidenceThreshold is not None:
        return max(0.0, min(float(context.memorySettings.candidateConfidenceThreshold), 1.0))
    return settings.agent_memory_candidate_confidence_threshold


def _memory_retrieval_limit(context: RunContext) -> int:
    if context.memorySettings is not None and context.memorySettings.retrievalLimit is not None:
        return max(1, min(int(context.memorySettings.retrievalLimit), 20))
    return settings.agent_memory_retrieval_limit


def _memory_view_for_context(context: RunContext) -> str:
    message = (context.message or "").lower()
    if any(token in message for token in (
        "记得",
        "记住",
        "喜欢",
        "偏好",
        "我是谁",
        "我是何人",
        "你了解我",
        "用户画像",
        "个人画像",
        "remember",
        "preference",
        "profile",
    )):
        return "chat"
    if context.recentToolCalls or any(token in message for token in ("生成", "工具", "同款", "again", "image", "video")):
        return "router"
    return "chat"


def _successful_recent_tool_count(context: RunContext) -> int:
    count = 0
    for call in context.recentToolCalls or []:
        result = call.resultJson if isinstance(call.resultJson, dict) else {}
        if result and not result.get("error") and not result.get("errorCode"):
            count += 1
    return count


def _build_consolidated_memory_summary(context: RunContext, answer: str) -> str:
    return build_consolidated_memory_summary(context, answer)


def _memory_tool_prompt(context: RunContext) -> str:
    configured = context.memorySettings.writePrompt if context.memorySettings is not None else None
    prompt = configured.strip() if configured else MEMORY_TOOL_SYSTEM_PROMPT
    enabled_types = context.memorySettings.enabledTypes if context.memorySettings is not None else []
    if enabled_types:
        prompt = f"{prompt}\nAllowed memory types: {', '.join(enabled_types)}"
    return prompt


def _runtime_int(context: RunContext, field: str, fallback: int, min_value: int, max_value: int) -> int:
    return runtime_int(context, field, fallback, min_value, max_value)


def _runtime_float(context: RunContext, field: str, fallback: float, min_value: float, max_value: float) -> float:
    return runtime_float(context, field, fallback, min_value, max_value)


def _runtime_bool(context: RunContext, field: str, fallback: bool) -> bool:
    return runtime_bool(context, field, fallback)


def _safe_memory_text(value: str, limit: int) -> str:
    text = (value or "").strip()
    if not text:
        return ""
    if _looks_like_large_media_payload(text):
        return "[omitted large media payload]"
    return text[:limit]


def _looks_like_large_media_payload(text: str) -> bool:
    if len(text) > 2000 and ("base64" in text[:300].lower() or "data:image/" in text[:300].lower()):
        return True
    if len(text) > 5000 and text.lstrip().startswith(("{", "[")):
        lowered = text[:1000].lower()
        return "resourceurl" in lowered or "imageurl" in lowered or "videourl" in lowered or "contenttext" in lowered
    return False
