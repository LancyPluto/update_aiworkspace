import importlib.util
import inspect
import json
import logging
import re
from collections.abc import Callable
from dataclasses import dataclass
from types import ModuleType
from typing import Any

from app.config import settings
from app.core.budget_guard import BudgetExceeded, BudgetGuard, BudgetState
from app.core.event_types import (
    INTENT_DETECTED,
    MEMORY_CANDIDATE_CREATED,
    MEMORY_CONTEXT_FROZEN,
    MESSAGE_COMPLETED,
    MESSAGE_DELTA,
    RUN_STARTED,
    SUBAGENT_COMPLETED,
    SUBAGENT_FAILED,
    SUBAGENT_STARTED,
    TOOL_ARGUMENTS_PREVIEW,
    TOOL_CONFIRMATION_REQUIRED,
    TOOL_RECOMMENDATIONS,
    TOOL_SELECTED,
    WORKSPACE_FILE_CREATED,
    WORKSPACE_FILE_READ,
)
from app.core.intent_router import Intent, IntentResult, IntentRouter
from app.core.schemas import ChatMessage, RunComplete, RunContext, RunEventCreate, RunFail, ToolDescriptor, WorkspaceMemoryItem
from app.runtime.subagent_profiles import default_subagent_profiles, profiles_to_deepagents_subagents
from app.runtime.workspace_files import WorkspaceFileContext, build_workspace_file_context
from app.security.prompt_guard import PromptGuard
from app.tools.backend_tool import BackendToolBridge
from app.tools.memory_tool import (
    MEMORY_TOOL_SYSTEM_PROMPT,
    MemoryTool,
    _contains_memory_promise,
    _format_memory_tool_definitions,
)
from app.tools.missing_argument_hints import format_missing_tool_arguments_message
from app.tools.registry import ToolRegistry, requested_output_modality, tool_supports_modality
from langchain_core.callbacks import AsyncCallbackHandler

LOGGER = logging.getLogger(__name__)

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

    async def run(self, context: RunContext) -> None:
        if self._explicit_deep_agents_flag and not self.deep_agents_enabled:
            await self._fail_run(context.runId, "DEEP_AGENTS_DISABLED", "Deep Agents runtime is disabled")
            return

        state = {
            "run_id": context.runId,
            "context": context,
            "budget": BudgetState(credit_budget=context.creditBudget),
        }
        if not self.deep_agents_enabled:
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

        # 1. Classify intent
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
            await self._maybe_save_memory(context, answer)
            await self._complete_run(context, answer, intent=intent_enum.value)
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
            await self._maybe_save_memory(context, answer)
            await self._complete_run(context, answer, intent=intent_enum.value)
            return

        # Fallback for any other intent — try deep agents if available, else fall back to chat
        await self._run_deep_agents_or_chat(context, intent)

    async def run_confirmed_tool(self, context: RunContext, tool_code: str) -> None:
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

        if result.get("missing_tool_arguments"):
            answer = self._format_missing_arguments_message(tool, result["missing_tool_arguments"])
            await self._emit_answer_events(context.runId, answer)
            await self._complete_run(context, answer, intent=Intent.NEEDS_CLARIFICATION.value)
            return

        answer = await self._synthesize_answer(context, tool, result, budget)
        await self._complete_run(context, answer, intent=Intent.TOOL_USE.value)
        await self._emit_memory_candidate(context.runId, answer, None)

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
        rule_intent = self.intent_router.classify(context)
        if not self._should_use_llm_router(context, rule_intent):
            return rule_intent
        llm_intent = await self._classify_intent_with_llm(context, rule_intent)
        return llm_intent or rule_intent

    def _should_use_llm_router(self, context: RunContext, rule_intent) -> bool:
        if not settings.agent_llm_router_enabled:
            return False
        if not context.availableTools:
            return False
        hard_rule_reasons = {
            "ready_file_context_available",
            "file_analysis_request",
            "phase_unsupported_capability",
            "empty_request",
            "session_recap_question",
            "short_general_chat",
            "restored_from_pending_tool_context",
            "continuing_pending_tool_prompt",
            "structured_tool_arguments",
        }
        return rule_intent.reason not in hard_rule_reasons

    async def _classify_intent_with_llm(self, context: RunContext, rule_intent):
        tools = ToolRegistry(context).list_tools()
        tool_lines = []
        for tool in tools[:30]:
            tool_lines.append(
                {
                    "toolCode": tool.toolCode,
                    "toolName": tool.toolName,
                    "description": tool.description or "",
                    "autoCallable": tool.autoCallable,
                    "fields": [
                        {
                            "fieldKey": field.fieldKey,
                            "fieldName": field.fieldName,
                            "description": field.description or "",
                            "required": field.required,
                            "userRequired": field.userRequired,
                            "agentFillStrategy": field.agentFillStrategy,
                        }
                        for field in tool.fields[:12]
                    ],
                }
            )
        prompt = (
            "你是 AI 工具市场的路由器。根据用户请求选择最合适的意图和工具。\n"
            "必须只返回 JSON，不要解释。\n"
            "JSON schema: {"
            "\"intent\":\"tool_use|general_chat|needs_clarification|unsupported\","
            "\"selectedToolCode\":string|null,"
            "\"candidateToolCodes\":string[],"
            "\"confidence\":0到1,"
            "\"reason\":string,"
            "\"clarifyingQuestion\":string|null"
            "}\n"
            "选择原则：用户要生成图片/照片/视觉/拍摄/cos/海报/画面时优先图片生成工具；"
            "用户要生成视频/短视频/成片时优先视频工具；用户要文案/标题/文章时选择文本工具。"
            "只有关键目标完全不清楚才 needs_clarification，不要因为比例、张数、画质等可默认字段追问。\n\n"
            f"用户请求：{context.message}\n"
            f"规则兜底判断：intent={rule_intent.intent.value}, selectedTool={rule_intent.selectedToolCode}, reason={rule_intent.reason}\n"
            f"可用工具：{json.dumps(tool_lines, ensure_ascii=False)}"
        )
        try:
            raw = await self.model.chat([ChatMessage(role="user", content=prompt)])
            parsed = _parse_json_object(raw)
            result = self._validate_llm_intent(context, parsed)
            LOGGER.info(
                "agent llm router runId=%s accepted=%s raw=%s parsed=%s fallbackReason=%s",
                context.runId,
                result is not None,
                _clip(raw, 500),
                parsed,
                "none" if result is not None else "invalid_or_low_confidence",
            )
            return result
        except Exception as exc:
            LOGGER.warning("agent llm router fallback runId=%s error=%s", context.runId, exc)
            return None

    def _validate_llm_intent(self, context: RunContext, parsed: Any):
        if not isinstance(parsed, dict):
            return None
        raw_intent = str(parsed.get("intent") or "").strip()
        try:
            intent = Intent(raw_intent)
        except ValueError:
            return None
        confidence = _safe_float(parsed.get("confidence"), 0)
        if confidence < 0.7:
            return None
        selected_tool = parsed.get("selectedToolCode")
        selected_tool = selected_tool.strip() if isinstance(selected_tool, str) else None
        available = {tool.toolCode for tool in context.availableTools}
        if intent == Intent.TOOL_USE:
            if not selected_tool or selected_tool not in available:
                return None
        elif selected_tool and selected_tool not in available:
            selected_tool = None
        candidates = parsed.get("candidateToolCodes")
        candidate_codes = [
            code for code in candidates
            if isinstance(code, str) and code in available
        ] if isinstance(candidates, list) else []
        if selected_tool and selected_tool not in candidate_codes:
            candidate_codes.insert(0, selected_tool)
        clarifying = parsed.get("clarifyingQuestion")
        return IntentResult(
            intent=intent,
            confidence=confidence,
            selectedToolCode=selected_tool,
            candidateToolCodes=candidate_codes[:3],
            clarifyingQuestion=clarifying if isinstance(clarifying, str) else None,
            decisionSource="llm_router",
            reason=str(parsed.get("reason") or "llm_router"),
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
        tool = ToolRegistry(context).get(intent.selectedToolCode or "") if intent.selectedToolCode else None
        if tool is None:
            from app.core.intent_router import IntentResult
            intent_result = IntentResult(
                intent=Intent.NEEDS_CLARIFICATION, confidence=0.5, reason="tool_unavailable"
            )
            answer = self._format_clarifying_answer(context, intent_result)
            await self._emit_answer_events(context.runId, answer)
            await self._complete_run(context, answer, intent=Intent.NEEDS_CLARIFICATION.value)
            return

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
        missing_args = self.tool_bridge.missing_required_arguments(context, tool)
        extracted_args = None
        LOGGER.info(
            "agent tool arguments check runId=%s tool=%s missing=%s",
            context.runId,
            tool.toolCode,
            missing_args,
        )

        if missing_args:
            base_args = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=False)
            enriched = await self.tool_bridge.enrich_arguments(
                self.tool_bridge.conversation_argument_text(context), tool, existing_args=base_args,
            )
            still_missing = self._missing_user_arguments(enriched, tool)
            extracted_args = enriched
            auto_call = self._should_auto_call(context, tool)
            if not still_missing:
                await self._emit_arguments_preview(context, tool, extracted_args, [], not auto_call)
                if not auto_call:
                    await self._request_confirmation(context, tool)
                    return
                try:
                    result = await self._execute_tool_with_guard(context, tool, budget, arguments=enriched)
                except BudgetExceeded as exception:
                    await self._fail_run(context.runId, exception.error_code, exception.message, budget=budget)
                    return
                if result.get("missing_tool_arguments"):
                    answer = self._format_missing_arguments_message(tool, result["missing_tool_arguments"])
                    await self._emit_answer_events(context.runId, answer)
                    await self._complete_run(context, answer, intent=Intent.NEEDS_CLARIFICATION.value)
                    return
                answer = await self._synthesize_answer(context, tool, result, budget)
                await self._complete_run(context, answer, intent=Intent.TOOL_USE.value)
                await self._emit_memory_candidate(context.runId, answer, None)
                return
            else:
                await self._emit_arguments_preview(context, tool, enriched, still_missing, False)
                answer = self._format_missing_arguments_message(tool, still_missing)
                await self._emit_answer_events(context.runId, answer)
                await self._complete_run(context, answer, intent=Intent.NEEDS_CLARIFICATION.value)
                return

        auto_call = self._should_auto_call(context, tool)
        if not extracted_args:
            extracted_args = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=False)
        await self._emit_arguments_preview(context, tool, extracted_args, [], not auto_call)

        if not auto_call:
            await self._request_confirmation(context, tool)
            return

        try:
            result = await self._execute_tool_with_guard(context, tool, budget)
        except BudgetExceeded as exception:
            await self._fail_run(context.runId, exception.error_code, exception.message, budget=budget)
            return

        if result.get("missing_tool_arguments"):
            answer = self._format_missing_arguments_message(tool, result["missing_tool_arguments"])
            await self._emit_answer_events(context.runId, answer)
            await self._complete_run(context, answer, intent=Intent.NEEDS_CLARIFICATION.value)
            return

        answer = await self._synthesize_answer(context, tool, result, budget)
        await self._complete_run(context, answer, intent=Intent.TOOL_USE.value)
        await self._emit_memory_candidate(context.runId, answer, None)

    async def _execute_tool_with_guard(
        self, context: RunContext, tool: ToolDescriptor, budget: BudgetState, arguments: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        if arguments:
            prepared = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=True)
            prepared.update({key: value for key, value in arguments.items() if value not in (None, "")})
            arguments = prepared
            missing = self._missing_execution_arguments(arguments, tool)
            if missing:
                return {"missing_tool_arguments": missing}
        else:
            base_args = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=False)
            enriched = await self.tool_bridge.enrich_arguments(
                self.tool_bridge.conversation_argument_text(context), tool, existing_args=base_args,
            )
            prepared = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=True)
            prepared.update({key: value for key, value in enriched.items() if value not in (None, "")})
            missing = self._missing_execution_arguments(prepared, tool)
            arguments = prepared
            if missing:
                return {"missing_tool_arguments": missing}

        self.budget_guard.reserve_tool_call(budget, tool.estimatedCreditCost)
        result = await self.tool_bridge.execute_with_args(context, tool, arguments)
        return result

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

    def _should_auto_call(self, context: RunContext, tool: ToolDescriptor) -> bool:
        if tool.autoCallable:
            return True
        if any(p.toolCode == tool.toolCode and p.autoCallEnabled for p in context.toolPreferences):
            return True
        return self._is_direct_generation_request(context, tool)

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
        if tool.fields:
            return [
                field.fieldKey
                for field in tool.fields
                if bool(field.executionRequired if field.executionRequired is not None else field.required)
                and (field.fieldKey not in arguments or arguments[field.fieldKey] in (None, ""))
            ]
        required = tool.inputSchema.get("required", [])
        if not isinstance(required, list):
            return []
        return [
            name
            for name in required
            if isinstance(name, str) and (name not in arguments or arguments[name] in (None, ""))
        ]

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
        elif context.workspaceId and settings.agent_memory_auto_save_enabled:
            memory_tool = MemoryTool(self.backend, context.workspaceId, context.userId, run_id=context.runId)
            messages.append(ChatMessage(role="system", content=MEMORY_TOOL_SYSTEM_PROMPT))

        messages.extend(context.history)
        messages.append(ChatMessage(role="user", content=context.message))
        budget = BudgetState(credit_budget=context.creditBudget)
        return await self._stream_model_answer(context.runId, messages, budget, memory_tool=memory_tool)

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
                memory=_format_workspace_memory_items(workspace_memory_items) + workspace_file_context.memory_items,
            )
        except AttributeError as exception:
            await self._fail_run(context.runId, "DEEP_AGENTS_MODEL_UNSUPPORTED", f"Deep Agents requires a LangChain-compatible chat model: {exception}")
            return

        workspace_memory_context = _format_workspace_memory_context(workspace_memory_items)

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
        await self._emit_memory_candidate(context.runId, answer, artifact)

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
        if context.workspaceId and settings.agent_memory_auto_save_enabled:
            memory_tool = MemoryTool(self.backend, context.workspaceId, context.userId, run_id=context.runId)
            messages_list.append(ChatMessage(role="system", content=MEMORY_TOOL_SYSTEM_PROMPT))
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
        )

    # --- Common helpers ---

    async def _stream_model_answer(
        self, run_id: int, messages_list: list[ChatMessage],
        budget: BudgetState | None = None,
        memory_tool: MemoryTool | None = None,
        fallback_answer: str | None = None,
    ) -> str:
        if budget is not None:
            self.budget_guard.reserve_model_call(budget)
        parts: list[str] = []
        stream = getattr(self.model, "chat_stream", None)
        extra_kwargs: dict[str, Any] = {}
        if memory_tool is not None and settings.agent_memory_auto_save_enabled:
            extra_kwargs["tools"] = _format_memory_tool_definitions()
        if stream is None:
            try:
                answer = await self.model.chat(messages_list, tools=extra_kwargs.get("tools"))
            except TypeError:
                answer = await self.model.chat(messages_list)
            if not answer.strip() and fallback_answer:
                answer = fallback_answer
            await self._emit_answer_events(run_id, answer)
            return answer
        try:
            stream_iter = self.model.chat_stream(messages_list, tools=extra_kwargs.get("tools"))
        except TypeError:
            stream_iter = self.model.chat_stream(messages_list)
        async for chunk in stream_iter:
            parts.append(chunk)
            await self.backend.append_event(
                run_id,
                RunEventCreate(eventType=MESSAGE_DELTA, eventText=chunk, eventJson={"delta": chunk}),
            )
        answer = "".join(parts)
        if not answer.strip() and fallback_answer:
            answer = fallback_answer
            for chunk in _chunks(answer, 32):
                await self.backend.append_event(
                    run_id,
                    RunEventCreate(eventType=MESSAGE_DELTA, eventText=chunk, eventJson={"delta": chunk}),
                )
        await self.backend.append_event(
            run_id,
            RunEventCreate(eventType=MESSAGE_COMPLETED, eventText=answer, eventJson={"content": answer}),
        )
        return answer

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
        consumed_credits = self.budget_guard.default_consumed_credits
        model_name = getattr(self.model, "model_name", settings.model_name)
        usage = self._model_usage()
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
            consumed_credits = self.budget_guard.default_consumed_credits
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

    def _chat_model(self):
        return getattr(self.model, "chat_model", self.model)

    # --- Workspace memory & files ---

    async def _fetch_workspace_memory_items(self, context: RunContext) -> list[WorkspaceMemoryItem]:
        workspace_id = context.workspaceId
        if workspace_id is None:
            return []
        try:
            return await self.backend.retrieve_workspace_memory(
                workspace_id=workspace_id,
                query=context.message,
                limit=settings.agent_memory_retrieval_limit,
            )
        except Exception:
            return []

    async def _fetch_workspace_memory_context(self, context: RunContext) -> str:
        items = await self._fetch_workspace_memory_items(context)
        return _format_workspace_memory_context(items)

    async def _build_workspace_file_context(self, context: RunContext) -> WorkspaceFileContext:
        workspace_file_context = build_workspace_file_context(context)
        if workspace_file_context.is_empty:
            return workspace_file_context
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=WORKSPACE_FILE_READ,
                eventText=", ".join(workspace_file_context.filenames),
                eventJson={
                    "fileIds": workspace_file_context.file_ids,
                    "filenames": workspace_file_context.filenames,
                },
            ),
        )
        return workspace_file_context

    async def _create_artifact(self, run_id: int, artifact: "ArtifactDirective") -> None:
        response = await self.backend.create_run_artifact(
            run_id=run_id,
            filename=artifact.filename,
            content=artifact.content,
            content_type=artifact.content_type,
        )
        if _backend_emitted_workspace_file_created(response):
            return
        await self.backend.append_event(
            run_id,
            RunEventCreate(
                eventType=WORKSPACE_FILE_CREATED,
                eventText=artifact.filename,
                eventJson={
                    "artifactId": response.get("id"),
                    "filename": response.get("originalFilename") or response.get("filename") or artifact.filename,
                    "contentType": response.get("contentType") or artifact.content_type,
                    "sourceRunId": run_id,
                },
            ),
        )

    async def _emit_memory_candidate(self, run_id: int, answer: str, artifact: "ArtifactDirective | None") -> None:
        content = artifact.content if artifact is not None else answer
        title = artifact.filename if artifact is not None else _memory_candidate_title(content)
        await self.backend.append_event(
            run_id,
            RunEventCreate(
                eventType=MEMORY_CANDIDATE_CREATED,
                eventText=title,
                eventJson={"title": title, "content": content, "sourceRunId": run_id},
            ),
        )

    async def _maybe_save_memory(self, context: RunContext, answer: str) -> None:
        """兜底：当 LLM 口头承诺'记住了'但未调用 memory_add 时，自动提取并写入。"""
        if not context.workspaceId or not settings.agent_memory_auto_save_enabled:
            return
        if not _contains_memory_promise(answer):
            return
        text = context.message.strip()
        if not text or len(text) < 4:
            return
        # 提取用户消息中的有效信息作为记忆内容
        from app.tools.memory_tool import MemoryTool as MT
        tool = MT(self.backend, context.workspaceId, context.userId, run_id=context.runId)
        await tool.add_memory(
            memory_type="project_knowledge",
            title=text[:60],
            content=text,
            source_run_id=context.runId,
        )


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


def _backend_emitted_workspace_file_created(response: dict[str, Any]) -> bool:
    if response.get("eventEmitted") is True:
        return True
    if response.get("emittedEventType") == WORKSPACE_FILE_CREATED:
        return True
    event = response.get("event")
    return isinstance(event, dict) and event.get("eventType") == WORKSPACE_FILE_CREATED


def _memory_candidate_title(content: str) -> str:
    for line in content.splitlines():
        title = line.strip().lstrip("#").strip()
        if title:
            return title[:120]
    return "Deep Agents result"


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

    sections = ["Workspace memory"]
    profiles = [it for it in active_items if it.memoryType == "user_profile"]
    knowledge = [it for it in active_items if it.memoryType == "project_knowledge"]
    others = [it for it in active_items if it.memoryType not in ("user_profile", "project_knowledge")]

    if profiles:
        sections.append("[User Profile]")
        for item in profiles:
            sections.append(f"  - {item.content[:4000]}")
        sections.append("")

    if knowledge:
        sections.append("[Project Knowledge]")
        for item in knowledge:
            sections.append(f"  [memory:{item.id}] {item.title} (score={item.score})\n  {item.content[:4000]}")
        sections.append("")

    if others:
        sections.append("[Other Notes]")
        for item in others:
            sections.append(f"  [memory:{item.id}] {item.title} ({item.memoryType}, score={item.score})\n  {item.content[:4000]}")

    return "\n".join(sections).strip()


def _format_workspace_memory_items(items: list[WorkspaceMemoryItem]) -> list[str]:
    memory_items = []
    for item in items:
        if not item.content.strip() and not item.title.strip():
            continue
        title = item.title.strip() or "Untitled memory"
        content = item.content.strip()
        memory_type = item.memoryType.strip() or "memory"
        memory_items.append(f"[memory:{item.id}] {title} ({memory_type}, score={item.score})\n{content[:4000]}")
    return memory_items
