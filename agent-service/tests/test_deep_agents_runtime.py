import inspect
from uuid import uuid4

import pytest

from app.core.event_types import (
    INTENT_DETECTED,
    MEMORY_CANDIDATE_CREATED,
    MESSAGE_COMPLETED,
    MESSAGE_DELTA,
    RUN_STARTED,
    SUBAGENT_COMPLETED,
    SUBAGENT_FAILED,
    SUBAGENT_STARTED,
    TOOL_CONFIRMATION_REQUIRED,
    TOOL_RECOMMENDATIONS,
    TOOL_SELECTED,
    WORKSPACE_FILE_CREATED,
    WORKSPACE_FILE_READ,
)
from app.core.schemas import AgentFileChunkContext, AgentFileContext, RunContext, ToolDescriptor, ToolPreference, WorkspaceMemoryItem


class FakeBackend:
    def __init__(self):
        self.events = []
        self.failed_runs = []
        self.completed_runs = []
        self.memory_items = []
        self.memory_requests = []
        self.created_artifacts = []

    async def append_event(self, run_id, event):
        self.events.append((run_id, event))

    async def fail_run(self, run_id, failure):
        self.failed_runs.append((run_id, failure))

    async def complete_run(self, run_id, completion):
        self.completed_runs.append((run_id, completion))

    async def retrieve_workspace_memory(self, workspace_id: int, query: str, limit: int):
        self.memory_requests.append((workspace_id, query, limit))
        return self.memory_items

    async def create_run_artifact(self, run_id: int, filename: str, content: str, content_type: str):
        self.created_artifacts.append((run_id, filename, content, content_type))
        return {"id": 31, "filename": filename, "contentType": content_type}


class FakeModel:
    def __init__(self, response: str = ""):
        self.response = response

    async def chat(self, messages):
        return self.response

    @property
    def chat_stream(self):
        return None

    @property
    def model_name(self):
        return "test-model"


def test_deep_agents_engine_reports_missing_optional_dependency():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    engine = DeepAgentsRuntimeEngine(object(), object(), dependency_loader=lambda: None)

    with pytest.raises(RuntimeError, match="deepagents package is not installed"):
        engine._get_available_module()


@pytest.mark.asyncio
async def test_deep_agents_engine_invokes_deep_agent_and_completes_run():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    module = FakeDeepAgentsModule(final_answer="Deep plan ready")
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(), dependency_loader=lambda: module)
    context = RunContext(runId=11, sessionId=4, userId=5, message="plan a long task")

    await engine.run(context)

    # RUN_STARTED emitted first
    assert any(event.eventType == RUN_STARTED for _, event in backend.events)
    assert module.created_agents[0]["system_prompt"]
    assert {subagent["name"] for subagent in module.created_agents[0]["subagents"]} >= {
        "researcher",
        "file-analyst",
        "tool-operator",
    }
    assert module.invocations[0]["messages"][-1] == {"role": "user", "content": "plan a long task"}
    # MESSAGE_DELTA and MESSAGE_COMPLETED emitted
    delta_events = [event for _, event in backend.events if event.eventType == MESSAGE_DELTA]
    assert len(delta_events) > 0
    completed_events = [event for _, event in backend.events if event.eventType == MESSAGE_COMPLETED]
    assert completed_events[0].eventText == "Deep plan ready"
    assert backend.completed_runs[0][0] == 11
    assert backend.completed_runs[0][1].finalAnswer == "Deep plan ready"
    assert backend.completed_runs[0][1].intent == "deep_agents"
    memory_events = [event for _, event in backend.events if event.eventType == MEMORY_CANDIDATE_CREATED]
    assert memory_events[0].eventJson == {
        "title": "Deep plan ready",
        "content": "Deep plan ready",
        "sourceRunId": 11,
    }


@pytest.mark.asyncio
async def test_deep_agents_engine_writes_artifact_directive_and_emits_workspace_file_created():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    module = FakeDeepAgentsModule(final_answer="[artifact:summary.md]\n# Summary\n\nShip the release notes.")
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(), dependency_loader=lambda: module)
    context = RunContext(runId=15, sessionId=4, userId=5, message="write release notes")

    await engine.run(context)

    assert backend.created_artifacts == [(15, "summary.md", "# Summary\n\nShip the release notes.", "text/markdown")]
    created_events = [event for _, event in backend.events if event.eventType == WORKSPACE_FILE_CREATED]
    assert created_events[0].eventText == "summary.md"
    assert created_events[0].eventJson == {
        "artifactId": 31,
        "filename": "summary.md",
        "contentType": "text/markdown",
        "sourceRunId": 15,
    }


@pytest.mark.asyncio
async def test_deep_agents_engine_injects_workspace_memory_into_messages():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    backend.memory_items = [
        WorkspaceMemoryItem(
            id=11,
            workspaceId=7,
            title="Pricing policy",
            content="Use prepaid credits before invoicing.",
            memoryType="PROJECT",
            score=2,
        )
    ]
    module = FakeDeepAgentsModule(final_answer="Deep plan ready")
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(), dependency_loader=lambda: module)
    context = RunContext(runId=11, sessionId=4, userId=5, workspaceId=7, message="plan pricing rollout")

    await engine.run(context)

    assert backend.memory_requests == [(7, "plan pricing rollout", 5)]
    prompt_text = "\n".join(message["content"] for message in module.invocations[0]["messages"])
    assert "Workspace memory" in prompt_text
    assert "memory:11" in prompt_text
    assert "Pricing policy" in prompt_text
    assert "Use prepaid credits before invoicing." in prompt_text
    assert module.created_agents[0]["memory"] == [
        "[memory:11] Pricing policy (PROJECT, score=2.0)\nUse prepaid credits before invoicing."
    ]


@pytest.mark.asyncio
async def test_deep_agents_engine_passes_workspace_file_context_to_native_runtime():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    module = FakeDeepAgentsModule(final_answer="Deep plan ready")
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(), dependency_loader=lambda: module)
    context = RunContext(
        runId=21,
        sessionId=4,
        userId=5,
            message="plan a long task using the uploaded files",
            agentFiles=[],
            agentFileChunks=[
            AgentFileChunkContext(
                id=11,
                fileId=7,
                originalFilename="C:\\Users\\alice\\Desktop\\product.txt",
                chunkIndex=2,
                contentText="Security: SSO and audit logs are included.",
                score=4,
            )
        ],
    )

    await engine.run(context)

    native_memory = "\n".join(module.created_agents[0]["memory"])
    prompt_text = "\n".join(message["content"] for message in module.invocations[0]["messages"])
    assert "Workspace files (read-only)" in native_memory
    assert "workspace_file:7" in native_memory
    assert "product.txt" in native_memory
    assert "Security: SSO and audit logs are included." in native_memory
    assert "C:\\Users\\alice" not in native_memory
    assert "C:\\Users\\alice" not in prompt_text
    read_events = [event for _, event in backend.events if event.eventType == WORKSPACE_FILE_READ]
    assert read_events[0].eventJson == {"fileIds": [7], "filenames": ["product.txt"]}


@pytest.mark.asyncio
async def test_deep_agents_engine_skips_workspace_memory_without_workspace_id():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    backend.memory_items = [
        WorkspaceMemoryItem(
            id=11,
            title="Pricing policy",
            content="Use prepaid credits before invoicing.",
            memoryType="PROJECT",
            score=2,
        )
    ]
    module = FakeDeepAgentsModule(final_answer="Deep plan ready")
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(), dependency_loader=lambda: module)
    context = RunContext(runId=11, sessionId=4, userId=5, message="plan pricing rollout")

    await engine.run(context)

    assert backend.memory_requests == []


@pytest.mark.asyncio
async def test_deep_agents_engine_fails_cleanly_when_model_is_not_supported():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    module = FakeDeepAgentsModule(final_answer="unused", create_error=AttributeError("'MockChatModel' object has no attribute 'count'"))
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(), dependency_loader=lambda: module)

    await engine.run(RunContext(runId=12, sessionId=4, userId=5, message="plan"))

    assert backend.failed_runs[0][1].errorCode == "DEEP_AGENTS_MODEL_UNSUPPORTED"


@pytest.mark.asyncio
async def test_deep_agents_engine_traces_subagent_start_and_completion_events():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    module = FakeDeepAgentsModule(final_answer="Deep plan ready", tool_events=[("start", "researcher"), ("end", "researcher")])
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(), dependency_loader=lambda: module)

    await engine.run(RunContext(runId=13, sessionId=4, userId=5, message="research competitors"))

    traced_events = [(event.eventType, event.eventJson) for _, event in backend.events if event.eventType.startswith("subagent.")]
    assert traced_events == [
        (
            SUBAGENT_STARTED,
            {
                "subagentName": "researcher",
                "taskDescription": "research competitors",
            },
        ),
        (
            SUBAGENT_COMPLETED,
            {
                "subagentName": "researcher",
            },
        ),
    ]


@pytest.mark.asyncio
async def test_deep_agents_engine_traces_subagent_failure_events():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    module = FakeDeepAgentsModule(final_answer="Recovered", tool_events=[("start", "file-analyst"), ("error", "file-analyst")])
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(), dependency_loader=lambda: module)

    await engine.run(RunContext(runId=14, sessionId=4, userId=5, message="analyze files"))

    failed_events = [event for _, event in backend.events if event.eventType == SUBAGENT_FAILED]
    assert failed_events[0].eventJson == {
        "subagentName": "file-analyst",
        "error": "subagent failed",
    }


@pytest.mark.asyncio
async def test_deep_agents_engine_chat_for_general_message():
    """When intent is GENERAL_CHAT and no deepagents package, fall back to chat."""
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    model = FakeModel(response="Hello! How can I help?")
    engine = DeepAgentsRuntimeEngine(backend, model, dependency_loader=lambda: None)
    context = RunContext(runId=20, sessionId=4, userId=5, message="你好")

    await engine.run(context)

    # Should emit RUN_STARTED, INTENT_DETECTED, MESSAGE_DELTA*, MESSAGE_COMPLETED
    intent_events = [event for _, event in backend.events if event.eventType == INTENT_DETECTED]
    assert len(intent_events) == 1
    assert intent_events[0].eventText == "general_chat"
    completed_events = [event for _, event in backend.events if event.eventType == MESSAGE_COMPLETED]
    assert len(completed_events) > 0
    assert backend.completed_runs[0][1].finalAnswer
    assert backend.completed_runs[0][1].intent == "general_chat"


@pytest.mark.asyncio
async def test_deep_agents_engine_tool_use_routes_to_confirmation():
    """When a tool is not auto-callable, emit confirmation-required event."""
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    model = FakeModel()

    all_tools = [
        ToolDescriptor(
            toolCode="xiaohongshu_copywriting",
            toolName="小红书文案生成",
            description="Generate Xiaohongshu-style copywriting for products",
            inputSchema={
                "type": "object",
                "properties": {"userRequest": {"type": "string", "title": "用户请求"}},
                "required": [],
            },
        )
    ]

    engine = DeepAgentsRuntimeEngine(backend, model)
    context = RunContext(
        runId=30,
        sessionId=4,
        userId=5,
        message="帮我生成小红书文案",
        availableTools=all_tools,
        toolPreferences=[ToolPreference(toolCode="xiaohongshu_copywriting", autoCallEnabled=False)],
    )

    await engine.run(context)

    # Should have emitted intent detected for tool use
    assert any(event.eventText == "tool_use" for _, event in backend.events)
    # TOOL_SELECTED should be emitted
    select_events = [event for _, event in backend.events if event.eventType == TOOL_SELECTED]
    assert len(select_events) > 0
    # TOOL_CONFIRMATION_REQUIRED should be emitted
    confirm_events = [event for _, event in backend.events if event.eventType == TOOL_CONFIRMATION_REQUIRED]
    assert len(confirm_events) > 0


@pytest.mark.asyncio
async def test_deep_agents_engine_routes_file_analysis_to_chat():
    """FILE_ANALYSIS intent routes directly to chat mode."""
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    model = FakeModel(response="Here is my analysis of the file.")
    engine = DeepAgentsRuntimeEngine(backend, model, dependency_loader=lambda: None)
    context = RunContext(
        runId=25,
        sessionId=4,
        userId=5,
        message="分析这个文件",
        agentFiles=[AgentFileContext(id=1, originalFilename="test.txt", status="READY", extractedText="file content")],
    )

    await engine.run(context)

    completed_events = [event for _, event in backend.events if event.eventType == MESSAGE_COMPLETED]
    assert len(completed_events) > 0
    assert backend.completed_runs[0][1].intent == "file_analysis"


class FakeDeepAgentsModule:
    def __init__(self, final_answer: str, create_error: Exception | None = None, tool_events: list[tuple[str, str]] | None = None):
        self.final_answer = final_answer
        self.create_error = create_error
        self.tool_events = tool_events or []
        self.created_agents = []
        self.invocations = []

    def create_deep_agent(self, tools, system_prompt, model=None, subagents=None, memory=None):
        if self.create_error is not None:
            raise self.create_error
        self.created_agents.append({"tools": tools, "system_prompt": system_prompt, "model": model, "subagents": subagents or [], "memory": memory or []})
        return FakeDeepAgent(self)


class FakeDeepAgent:
    def __init__(self, module: FakeDeepAgentsModule):
        self.module = module

    async def ainvoke(self, payload, config=None):
        self.module.invocations.append(payload)
        for event_type, subagent_name in self.module.tool_events:
            await _emit_fake_tool_event(config, event_type, subagent_name)
        return {"messages": [{"role": "assistant", "content": self.module.final_answer}]}


async def _emit_fake_tool_event(config, event_type: str, subagent_name: str):
    callbacks = (config or {}).get("callbacks", [])
    if not hasattr(_emit_fake_tool_event, "run_ids"):
        _emit_fake_tool_event.run_ids = {}
    tool_run_id = _emit_fake_tool_event.run_ids.setdefault(subagent_name, uuid4())
    for callback in callbacks:
        if event_type == "start":
            result = callback.on_tool_start(
                {"name": "task"},
                "",
                run_id=tool_run_id,
                inputs={"subagent_type": subagent_name, "description": "research competitors" if subagent_name == "researcher" else "analyze files"},
            )
        elif event_type == "end":
            result = callback.on_tool_end("subagent result", run_id=tool_run_id)
            _emit_fake_tool_event.run_ids.pop(subagent_name, None)
        else:
            result = callback.on_tool_error(RuntimeError("subagent failed"), run_id=tool_run_id)
            _emit_fake_tool_event.run_ids.pop(subagent_name, None)
        if inspect.isawaitable(result):
            await result
