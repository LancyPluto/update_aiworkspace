from typing import Any, TypedDict

from app.config import settings
from app.core.event_types import INTENT_DETECTED, MESSAGE_COMPLETED, MESSAGE_DELTA, TOOL_CONFIRMATION_REQUIRED, TOOL_SELECTED
from app.core.budget_guard import BudgetExceeded, BudgetGuard, BudgetState
from app.core.intent_router import Intent, IntentResult, IntentRouter
from app.core.schemas import ChatMessage, RunComplete, RunContext, RunEventCreate, RunFail, ToolDescriptor, WorkspaceMemoryItem
from app.graphs.subagent_router import SubagentRouter, format_subagent_delegation_hint
from app.security.prompt_guard import PromptGuard
from app.tools.backend_tool import BackendToolBridge
from app.tools.missing_argument_hints import format_missing_tool_arguments_message
from app.tools.registry import ToolRegistry

try:  # pragma: no cover - exercised when langgraph is installed in runtime images.
    from langgraph.graph import END, START, StateGraph
except ImportError:  # pragma: no cover
    END = START = None
    StateGraph = None


class AgentState(TypedDict, total=False):
    run_id: int
    context: RunContext
    intent: IntentResult | None
    selected_tool: ToolDescriptor | None
    tool_result: dict[str, Any] | None
    budget: BudgetState
    needs_confirmation: bool
    missing_tool_arguments: list[str]
    extracted_arguments: dict[str, Any]
    final_answer: str | None
    error_code: str | None
    error_message: str | None


class UniversalAgentGraph:
    def __init__(
        self,
        backend_client,
        model_client,
        intent_router: IntentRouter | None = None,
        budget_guard: BudgetGuard | None = None,
        prompt_guard: PromptGuard | None = None,
        subagent_router: SubagentRouter | None = None,
    ) -> None:
        self.backend = backend_client
        self.model = model_client
        self.intent_router = intent_router or IntentRouter()
        self.budget_guard = budget_guard or BudgetGuard(
            max_model_calls=settings.agent_max_model_calls,
            max_tool_calls=settings.agent_max_tool_calls,
            model_call_cost=settings.agent_model_call_cost,
            default_consumed_credits=settings.agent_default_consumed_credits,
        )
        self.prompt_guard = prompt_guard or PromptGuard()
        self.subagent_router = subagent_router or SubagentRouter()
        self.tool_bridge = BackendToolBridge(backend_client, model_client=model_client)
        self._compiled_graph = self._build_langgraph()

    async def run(self, context: RunContext) -> None:
        state: AgentState = {"run_id": context.runId, "context": context, "budget": BudgetState(credit_budget=context.creditBudget)}
        guard_result = self.prompt_guard.inspect(context.message)
        if guard_result.rejected:
            state = {
                **state,
                "intent": IntentResult(intent=Intent.SECURITY_REJECTED, confidence=1.0, reason=guard_result.error_code or "security_rejected"),
                "final_answer": guard_result.message,
            }
            await self._emit_answer_events(context.runId, guard_result.message or "")
            await self._complete_run(state)
            return
        if self._compiled_graph is not None:  # pragma: no cover - depends on optional langgraph.
            try:
                await self._compiled_graph.ainvoke(state)
            except BudgetExceeded as exception:
                await self.fail(context.runId, exception.error_code, exception.message)
            return
        try:
            await self._run_fallback(state)
        except BudgetExceeded as exception:
            await self.fail(context.runId, exception.error_code, exception.message)

    async def run_confirmed_tool(self, context: RunContext, tool_code: str) -> None:
        tool = ToolRegistry(context).get(tool_code)
        if tool is None:
            state: AgentState = {
                "run_id": context.runId,
                "context": context,
                "budget": BudgetState(credit_budget=context.creditBudget),
                "intent": IntentResult(intent=Intent.NEEDS_CLARIFICATION, confidence=0.5, reason="confirmed_tool_unavailable"),
            }
            state = await self._generate_clarifying_answer(state)
            await self._complete_run(state)
            return
        state = {
            "run_id": context.runId,
            "context": context,
            "budget": BudgetState(credit_budget=context.creditBudget),
            "intent": IntentResult(intent=Intent.TOOL_USE, confidence=1.0, selectedToolCode=tool.toolCode, reason="user_confirmed_tool"),
            "selected_tool": tool,
        }
        await self.backend.append_event(
            context.runId,
            RunEventCreate(eventType=TOOL_SELECTED, eventText=tool.toolCode, eventJson={"toolCode": tool.toolCode, "confirmed": True}),
        )
        try:
            state = await self._execute_tool(state)
        except BudgetExceeded as exception:
            await self.fail(context.runId, exception.error_code, exception.message)
            return
        if state.get("missing_tool_arguments"):
            state = await self._generate_clarifying_answer(state)
            await self._complete_run(state)
            return
        state = await self._synthesize_tool_answer(state)
        await self._complete_run(state)

    def _build_langgraph(self):
        if StateGraph is None:
            return None
        graph = StateGraph(AgentState)
        graph.add_node("classify_intent", self._classify_intent)
        graph.add_node("select_tool", self._select_tool)
        graph.add_node("check_tool_preference", self._check_tool_preference)
        graph.add_node("request_tool_confirmation", self._request_tool_confirmation)
        graph.add_node("execute_tool", self._execute_tool)
        graph.add_node("generate_chat_answer", self._generate_chat_answer)
        graph.add_node("generate_clarifying_answer", self._generate_clarifying_answer)
        graph.add_node("generate_unsupported_answer", self._generate_unsupported_answer)
        graph.add_node("synthesize_tool_answer", self._synthesize_tool_answer)
        graph.add_node("complete_run", self._complete_run)
        graph.add_edge(START, "classify_intent")
        graph.add_conditional_edges("classify_intent", self._route_after_intent)
        graph.add_edge("select_tool", "check_tool_preference")
        graph.add_conditional_edges("check_tool_preference", self._route_after_tool_preference)
        graph.add_edge("request_tool_confirmation", END)
        graph.add_edge("execute_tool", "synthesize_tool_answer")
        graph.add_edge("generate_chat_answer", "complete_run")
        graph.add_edge("generate_clarifying_answer", "complete_run")
        graph.add_edge("generate_unsupported_answer", "complete_run")
        graph.add_edge("synthesize_tool_answer", "complete_run")
        graph.add_edge("complete_run", END)
        return graph.compile()

    async def _run_fallback(self, state: AgentState) -> None:
        state = await self._classify_intent(state)
        route = self._route_after_intent(state)
        if route == "select_tool":
            state = await self._select_tool(state)
            state = await self._check_tool_preference(state)
            if state.get("missing_tool_arguments"):
                state = await self._generate_clarifying_answer(state)
                await self._complete_run(state)
                return
            if state.get("needs_confirmation"):
                await self._request_tool_confirmation(state)
                return
            try:
                state = await self._execute_tool(state)
            except BudgetExceeded as exception:
                await self.fail(state["run_id"], exception.error_code, exception.message)
                return
            state = await self._synthesize_tool_answer(state)
        elif route == "generate_clarifying_answer":
            state = await self._generate_clarifying_answer(state)
        elif route == "generate_unsupported_answer":
            state = await self._generate_unsupported_answer(state)
        else:
            state = await self._generate_chat_answer(state)
        await self._complete_run(state)

    async def _classify_intent(self, state: AgentState) -> AgentState:
        context = state["context"]
        intent = self.intent_router.classify(context)
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
        return {**state, "intent": intent}

    def _route_after_intent(self, state: AgentState) -> str:
        intent = state["intent"].intent if state.get("intent") is not None else Intent.GENERAL_CHAT
        if intent == Intent.FILE_ANALYSIS:
            return "generate_chat_answer"
        if intent == Intent.TOOL_USE:
            return "select_tool"
        if intent == Intent.NEEDS_CLARIFICATION:
            return "generate_clarifying_answer"
        if intent == Intent.UNSUPPORTED:
            return "generate_unsupported_answer"
        return "generate_chat_answer"

    def _route_after_tool_preference(self, state: AgentState) -> str:
        if state.get("missing_tool_arguments"):
            return "generate_clarifying_answer"
        return "request_tool_confirmation" if state.get("needs_confirmation") else "execute_tool"

    async def _select_tool(self, state: AgentState) -> AgentState:
        context = state["context"]
        intent = state["intent"]
        tool = ToolRegistry(context).get(intent.selectedToolCode or "") if intent is not None else None
        if tool is None:
            return await self._generate_clarifying_answer({**state, "intent": IntentResult(intent=Intent.NEEDS_CLARIFICATION, confidence=0.5, reason="tool_unavailable")})
        await self.backend.append_event(
            context.runId,
            RunEventCreate(eventType=TOOL_SELECTED, eventText=tool.toolCode, eventJson={"toolCode": tool.toolCode}),
        )
        return {**state, "selected_tool": tool}

    async def _check_tool_preference(self, state: AgentState) -> AgentState:
        context = state["context"]
        tool = state.get("selected_tool")
        if tool is None:
            return {**state, "needs_confirmation": False}
        auto_call_enabled = any(
            preference.toolCode == tool.toolCode and preference.autoCallEnabled
            for preference in context.toolPreferences
        )
        missing_arguments = self.tool_bridge.missing_required_arguments(context, tool)
        if missing_arguments:
            base_args = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=False)
            enriched = await self.tool_bridge.enrich_arguments(
                self.tool_bridge.conversation_argument_text(context),
                tool,
                existing_args=base_args,
            )
            still_missing = self._missing_from_enriched(enriched, tool)
            if not still_missing:
                return {
                    **state,
                    "needs_confirmation": not auto_call_enabled,
                    "missing_tool_arguments": [],
                    "extracted_arguments": enriched,
                }
            return {
                **state,
                "needs_confirmation": False,
                "missing_tool_arguments": still_missing,
                "extracted_arguments": enriched,
            }
        return {
            **state,
            "needs_confirmation": not auto_call_enabled,
            "missing_tool_arguments": [],
            "extracted_arguments": self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=False),
        }

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

    async def _request_tool_confirmation(self, state: AgentState) -> AgentState:
        context = state["context"]
        tool = state["selected_tool"]
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
        return state

    async def _execute_tool(self, state: AgentState) -> AgentState:
        context = state["context"]
        tool = state["selected_tool"]
        extracted_args = state.get("extracted_arguments")
        if extracted_args:
            missing = self._missing_from_enriched(extracted_args, tool)
        else:
            base_args = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=False)
            enriched = await self.tool_bridge.enrich_arguments(
                self.tool_bridge.conversation_argument_text(context),
                tool,
                existing_args=base_args,
            )
            extracted_args = enriched
            missing = self._missing_from_enriched(enriched, tool)
        if missing:
            return {**state, "missing_tool_arguments": missing}
        budget = state["budget"]
        self.budget_guard.reserve_tool_call(budget, tool.estimatedCreditCost)
        result = await self.tool_bridge.execute_with_args(context, tool, extracted_args)
        return {**state, "tool_result": result}

    async def _generate_chat_answer(self, state: AgentState) -> AgentState:
        context = state["context"]
        self.budget_guard.reserve_model_call(state["budget"])
        messages = [ChatMessage(role="system", content="You are a helpful cloud agent for an AI tool marketplace.")]
        workspace_memory_context = await self._format_workspace_memory_context(context)
        if workspace_memory_context:
            messages.append(ChatMessage(role="system", content=workspace_memory_context))
        subagent_hint = format_subagent_delegation_hint(self.subagent_router.recommend(context))
        if subagent_hint:
            messages.append(ChatMessage(role="system", content=subagent_hint))
        file_context = _format_file_context(context)
        if file_context:
            messages.append(ChatMessage(role="system", content=file_context))
        messages.extend(context.history)
        messages.append(ChatMessage(role="user", content=context.message))
        answer = await self._stream_model_answer(context.runId, state["budget"], messages)
        return {**state, "final_answer": answer}

    async def _generate_clarifying_answer(self, state: AgentState) -> AgentState:
        context = state["context"]
        intent = state.get("intent")
        missing = state.get("missing_tool_arguments") or []
        if missing:
            tool = state.get("selected_tool")
            answer = format_missing_tool_arguments_message(tool, missing)
        elif intent and intent.clarifyingQuestion:
            answer = intent.clarifyingQuestion
        elif intent and intent.intent == Intent.NEEDS_CLARIFICATION and intent.candidateToolCodes:
            registry = ToolRegistry(context)
            names = []
            for code in intent.candidateToolCodes:
                tool = registry.get(code)
                names.append(tool.toolName if tool else code)
            if len(names) >= 2:
                answer = f"你说的范围有点宽，我更想先确认你想用哪一种：{'、'.join(names)}。请补充更具体的需求或参数。"
            else:
                answer = "请补充你想完成的目标、对象和期望输出，我再帮你选择合适的工具。"
        else:
            answer = "请补充你想完成的目标、对象和期望输出，我再帮你选择合适的工具。"
        await self._emit_answer_events(context.runId, answer)
        return {**state, "final_answer": answer}

    async def _generate_unsupported_answer(self, state: AgentState) -> AgentState:
        context = state["context"]
        answer = "当前阶段暂不支持文件分析、知识库检索或复杂工作流。我可以先帮你完成通用问答或调用已开放的工具。"
        await self._emit_answer_events(context.runId, answer)
        return {**state, "final_answer": answer}

    async def _synthesize_tool_answer(self, state: AgentState) -> AgentState:
        context = state["context"]
        self.budget_guard.reserve_model_call(state["budget"])
        tool_result = state.get("tool_result") or {}
        tool_arguments = tool_result.get("arguments") if isinstance(tool_result, dict) else {}
        tool_data = tool_result.get("data") if isinstance(tool_result, dict) else {}
        content_text = tool_data.get("contentText", "") if isinstance(tool_data, dict) else ""
        messages = [
            ChatMessage(role="system", content="Summarize the tool result for the user."),
        ]
        workspace_memory_context = await self._format_workspace_memory_context(context)
        if workspace_memory_context:
            messages.append(ChatMessage(role="system", content=workspace_memory_context))
        if content_text:
            messages.append(
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
            messages.append(ChatMessage(role="user", content=f"User request: {context.message}\nTool result: {tool_result}"))
        answer = await self._stream_model_answer(context.runId, state["budget"], messages)
        return {**state, "final_answer": answer}

    async def _stream_model_answer(self, run_id: int, budget: BudgetState, messages: list[ChatMessage]) -> str:
        self.budget_guard.reserve_model_call(budget)
        parts: list[str] = []
        stream = getattr(self.model, "chat_stream", None)
        if stream is None:
            answer = await self.model.chat(messages)
            await self._emit_answer_events(run_id, answer)
            return answer
        async for chunk in self.model.chat_stream(messages):
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

    async def _format_workspace_memory_context(self, context: RunContext) -> str:
        workspace_id = context.workspaceId
        if workspace_id is None:
            return ""
        try:
            items = await self.backend.retrieve_workspace_memory(workspace_id=workspace_id, query=context.message, limit=5)
        except Exception:
            return ""
        return _format_workspace_memory_context(items)

    async def _complete_run(self, state: AgentState) -> AgentState:
        context = state["context"]
        intent = state["intent"].intent.value if state.get("intent") is not None else Intent.GENERAL_CHAT.value
        answer = state.get("final_answer") or ""
        consumed_credits = self.budget_guard.consumed_for_completion(state["budget"])
        model_name = getattr(self.model, "model_name", settings.model_name)
        await self.backend.complete_run(
            context.runId,
            RunComplete(finalAnswer=answer, intent=intent, modelProviderCode="agent-service", modelName=model_name, consumedCredits=consumed_credits),
        )
        return state

    async def fail(self, run_id: int, error_code: str, error_message: str) -> None:
        await self.backend.fail_run(run_id, RunFail(errorCode=error_code, errorMessage=error_message))

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
    active_items = [item for item in items[:5] if item.content.strip() or item.title.strip()]
    if not active_items:
        return ""
    sections = [
        "Workspace memory: Long-term memories retrieved for this workspace. Use them when relevant, and cite them with memory:<id> labels when they inform the answer."
    ]
    for item in active_items:
        title = item.title.strip() or "Untitled memory"
        content = item.content.strip()
        memory_type = item.memoryType.strip() or "memory"
        sections.append(f"\n[memory:{item.id}] {title} ({memory_type}, score={item.score})\n{content[:4000]}")
    return "\n".join(sections)
