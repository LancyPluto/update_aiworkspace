import importlib.util
import inspect
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
from app.core.intent_router import Intent, IntentRouter
from app.core.schemas import ChatMessage, RunComplete, RunContext, RunEventCreate, RunFail, ToolDescriptor, WorkspaceMemoryItem
from app.runtime.subagent_profiles import default_subagent_profiles, profiles_to_deepagents_subagents
from app.runtime.workspace_files import WorkspaceFileContext, build_workspace_file_context
from app.security.prompt_guard import PromptGuard
from app.tools.backend_tool import BackendToolBridge
from app.tools.memory_tool import MEMORY_TOOL_SYSTEM_PROMPT, MemoryTool, _format_memory_tool_definitions
from app.tools.missing_argument_hints import format_missing_tool_arguments_message
from app.tools.registry import ToolRegistry
from langchain_core.callbacks import AsyncCallbackHandler

DEEP_AGENTS_INTENT = "deep_agents"
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
        intent = self.intent_router.classify(context)
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
            await self._fail_run(context.runId, exception.error_code, exception.message)
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

        await self.backend.append_event(
            context.runId,
            RunEventCreate(eventType=TOOL_SELECTED, eventText=tool.toolCode, eventJson={"toolCode": tool.toolCode}),
        )

        budget = BudgetState(credit_budget=context.creditBudget)
        missing_args = self.tool_bridge.missing_required_arguments(context, tool)
        extracted_args = None

        if missing_args:
            base_args = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=False)
            enriched = await self.tool_bridge.enrich_arguments(
                self.tool_bridge.conversation_argument_text(context), tool, existing_args=base_args,
            )
            still_missing = self._missing_from_enriched(enriched, tool)
            extracted_args = enriched
            auto_call = any(
                p.toolCode == tool.toolCode and p.autoCallEnabled for p in context.toolPreferences
            )
            if not still_missing:
                await self._emit_arguments_preview(context, tool, extracted_args, [], not auto_call)
                if not auto_call:
                    await self._request_confirmation(context, tool)
                    return
                try:
                    result = await self._execute_tool_with_guard(context, tool, budget, arguments=enriched)
                except BudgetExceeded as exception:
                    await self._fail_run(context.runId, exception.error_code, exception.message)
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

        auto_call = any(
            p.toolCode == tool.toolCode and p.autoCallEnabled for p in context.toolPreferences
        )
        if not extracted_args:
            extracted_args = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=False)
        await self._emit_arguments_preview(context, tool, extracted_args, [], not auto_call)

        if not auto_call:
            await self._request_confirmation(context, tool)
            return

        try:
            result = await self._execute_tool_with_guard(context, tool, budget)
        except BudgetExceeded as exception:
            await self._fail_run(context.runId, exception.error_code, exception.message)
            return

        answer = await self._synthesize_answer(context, tool, result, budget)
        await self._complete_run(context, answer, intent=Intent.TOOL_USE.value)
        await self._emit_memory_candidate(context.runId, answer, None)

    async def _execute_tool_with_guard(
        self, context: RunContext, tool: ToolDescriptor, budget: BudgetState, arguments: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        if arguments:
            missing = self._missing_from_enriched(arguments, tool)
            if missing:
                return {"missing_tool_arguments": missing}
        else:
            base_args = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=False)
            enriched = await self.tool_bridge.enrich_arguments(
                self.tool_bridge.conversation_argument_text(context), tool, existing_args=base_args,
            )
            missing = self._missing_from_enriched(enriched, tool)
            arguments = enriched
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

    @staticmethod
    def _missing_from_enriched(arguments: dict[str, Any], tool: ToolDescriptor) -> list[str]:
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
        available_tools = context.availableTools or []
        if available_tools:
            tool_descriptions = []
            for t in available_tools:
                name = t.toolName or t.toolCode
                desc = t.description or ""
                tool_descriptions.append(f"- {name}: {desc}")
            tool_list_text = "你可以使用的AI工具列表：\n" + "\n".join(tool_descriptions)
        else:
            tool_list_text = ""
        system_prompt = "You are a helpful cloud agent for an AI tool marketplace."
        if tool_list_text:
            system_prompt += "\n\n" + tool_list_text

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
        if context.workspaceId and settings.agent_memory_auto_save_enabled:
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
            agent = create_deep_agent(
                tools=[],
                system_prompt=DEEP_AGENTS_SYSTEM_PROMPT,
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
        messages_list = [
            ChatMessage(role="system", content="Summarize the tool result for the user."),
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
        return await self._stream_model_answer(context.runId, messages_list, budget, memory_tool=memory_tool)

    # --- Common helpers ---

    async def _stream_model_answer(
        self, run_id: int, messages_list: list[ChatMessage],
        budget: BudgetState | None = None,
        memory_tool: MemoryTool | None = None,
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

    async def _complete_run(self, context: RunContext, final_answer: str, intent: str = Intent.GENERAL_CHAT.value) -> None:
        consumed_credits = self.budget_guard.default_consumed_credits
        model_name = getattr(self.model, "model_name", settings.model_name)
        await self.backend.complete_run(
            context.runId,
            RunComplete(
                finalAnswer=final_answer,
                intent=intent,
                modelProviderCode="agent-service",
                modelName=model_name,
                consumedCredits=consumed_credits,
            ),
        )

    async def _fail_run(self, run_id: int, error_code: str, error_message: str) -> None:
        await self.backend.fail_run(run_id, RunFail(errorCode=error_code, errorMessage=error_message))

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
