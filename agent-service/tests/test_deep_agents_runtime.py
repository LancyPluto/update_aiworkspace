import inspect
from uuid import uuid4

import pytest

from app.config import Settings
from app.core.event_types import (
    MEMORY_CANDIDATE_CREATED,
    MESSAGE_COMPLETED,
    MESSAGE_DELTA,
    SUBAGENT_COMPLETED,
    SUBAGENT_FAILED,
    SUBAGENT_STARTED,
    WORKSPACE_FILE_CREATED,
    WORKSPACE_FILE_READ,
)
from app.core.schemas import AgentFileChunkContext, AgentFileContext, ChatMessage, RunContext, WorkspaceMemoryItem
from app.runtime.langgraph_engine import LangGraphRuntimeEngine
from app.runtime.router import RuntimeRouter


def test_deep_agents_disabled_by_default():
    assert Settings().agent_deep_agents_enabled is False


def test_router_deep_agents_feature_flag_selects_preview_engine_only_when_enabled():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = object()
    model = object()

    disabled_router = RuntimeRouter(backend_client=backend, model_client=model, deep_agents_enabled=False)
    enabled_router = RuntimeRouter(backend_client=backend, model_client=model, deep_agents_enabled=True)

    assert isinstance(disabled_router.select_engine(message="plan", requested_runtime="deep_agents"), LangGraphRuntimeEngine)
    assert isinstance(enabled_router.select_engine(message="plan", requested_runtime="deep_agents"), DeepAgentsRuntimeEngine)
    assert isinstance(enabled_router.select_engine(message="hello"), LangGraphRuntimeEngine)


class FakeBackend:
    def __init__(self):
        self.events = []
        self.failed_runs = []
        self.completed_runs = []
        self.memory_items = []
        self.memory_requests = []
        self.created_memories = []
        self.updated_memories = []
        self.created_artifacts = []

    async def append_event(self, run_id, event):
        self.events.append((run_id, event))

    async def fail_run(self, run_id, failure):
        self.failed_runs.append((run_id, failure))

    async def complete_run(self, run_id, completion):
        self.completed_runs.append((run_id, completion))

    async def retrieve_workspace_memory(self, workspace_id: int, query: str, limit: int, view: str | None = None):
        self.memory_requests.append((workspace_id, query, limit, view))
        return self.memory_items

    async def create_workspace_memory_candidate(self, **kwargs):
        self.events.append((kwargs.get("source_run_id", 0), type("Candidate", (), {"eventType": "memory.candidate.persisted", "eventJson": kwargs})()))
        return {"id": 51}

    async def create_workspace_memory(self, **kwargs):
        self.created_memories.append(kwargs)
        return {"id": 52}

    async def update_workspace_memory(self, **kwargs):
        self.updated_memories.append(kwargs)
        return {"id": kwargs.get("memory_id")}

    async def create_run_artifact(self, run_id: int, filename: str, content: str, content_type: str):
        self.created_artifacts.append((run_id, filename, content, content_type))
        return {"id": 31, "filename": filename, "contentType": content_type}


@pytest.mark.asyncio
async def test_deep_agents_engine_refuses_when_disabled():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    engine = DeepAgentsRuntimeEngine(backend, object(), deep_agents_enabled=False)
    context = RunContext(runId=7, sessionId=2, userId=3, message="plan from files")

    await engine.run(context)

    assert backend.events == []
    assert backend.failed_runs[0][1].errorCode == "DEEP_AGENTS_DISABLED"


def test_deep_agents_engine_reports_missing_optional_dependency():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    engine = DeepAgentsRuntimeEngine(object(), object(), deep_agents_enabled=True, dependency_loader=lambda: None)

    with pytest.raises(RuntimeError, match="deepagents package is not installed"):
        engine.ensure_available()


@pytest.mark.asyncio
async def test_deep_agents_engine_invokes_deep_agent_and_completes_run():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    module = FakeDeepAgentsModule(final_answer="Deep plan ready")
    engine = DeepAgentsRuntimeEngine(backend, object(), deep_agents_enabled=True, dependency_loader=lambda: module)
    context = RunContext(runId=11, sessionId=4, userId=5, message="plan a long task")

    await engine.run(context)

    assert module.created_agents[0]["system_prompt"]
    assert module.created_agents[0]["tools"] == []
    assert {subagent["name"] for subagent in module.created_agents[0]["subagents"]} >= {
        "researcher",
        "file-analyst",
        "tool-operator",
    }
    assert module.invocations[0]["messages"][-1] == {"role": "user", "content": "plan a long task"}
    assert backend.events[0][1].eventType == MESSAGE_DELTA
    assert backend.events[1][1].eventType == MESSAGE_COMPLETED
    assert backend.events[1][1].eventText == "Deep plan ready"
    assert backend.completed_runs[0][0] == 11
    assert backend.completed_runs[0][1].finalAnswer == "Deep plan ready"
    assert backend.completed_runs[0][1].intent == "deep_agents"
    memory_events = [event for _, event in backend.events if event.eventType == MEMORY_CANDIDATE_CREATED]
    assert memory_events == []


@pytest.mark.asyncio
async def test_deep_agents_engine_streams_native_agent_events():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    module = FakeDeepAgentsModule(final_answer="Deep plan ready", stream_chunks=["Deep ", "plan ", "ready"])
    engine = DeepAgentsRuntimeEngine(backend, object(), deep_agents_enabled=True, dependency_loader=lambda: module)

    await engine.run(RunContext(runId=17, sessionId=4, userId=5, message="plan a long task"))

    message_events = [event for _, event in backend.events if event.eventType.startswith("message.")]
    assert [(event.eventType, event.eventText) for event in message_events] == [
        (MESSAGE_DELTA, "Deep "),
        (MESSAGE_DELTA, "plan "),
        (MESSAGE_DELTA, "ready"),
        (MESSAGE_COMPLETED, "Deep plan ready"),
    ]
    assert message_events[0].eventJson == {"delta": "Deep "}
    assert backend.completed_runs[0][1].finalAnswer == "Deep plan ready"


@pytest.mark.asyncio
async def test_deep_agents_engine_does_not_emit_backend_lifecycle_events():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    module = FakeDeepAgentsModule(final_answer="Deep plan ready")
    engine = DeepAgentsRuntimeEngine(backend, object(), deep_agents_enabled=True, dependency_loader=lambda: module)

    await engine.run(RunContext(runId=16, sessionId=4, userId=5, message="plan a long task"))

    event_types = [event.eventType for _, event in backend.events]
    assert "run.started" not in event_types
    assert "run.completed" not in event_types
    assert "run.failed" not in event_types
    assert backend.completed_runs[0][0] == 16


@pytest.mark.asyncio
async def test_deep_agents_engine_writes_artifact_directive_and_emits_workspace_file_created():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    module = FakeDeepAgentsModule(final_answer="[artifact:summary.md]\n# Summary\n\nShip the release notes.")
    engine = DeepAgentsRuntimeEngine(backend, object(), deep_agents_enabled=True, dependency_loader=lambda: module)
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
    engine = DeepAgentsRuntimeEngine(backend, object(), deep_agents_enabled=True, dependency_loader=lambda: module)
    context = RunContext(runId=11, sessionId=4, userId=5, workspaceId=7, message="plan pricing rollout")

    await engine.run(context)

    assert backend.memory_requests[0] == (7, "plan pricing rollout", 10, "chat")
    prompt_text = "\n".join(message["content"] for message in module.invocations[0]["messages"])
    assert "Frozen workspace memory snapshot" in prompt_text
    assert "memory:11" in prompt_text
    assert "Pricing policy" in prompt_text
    assert "Use prepaid credits before invoicing." in prompt_text
    native_memory = "\n".join(module.created_agents[0]["memory"])
    assert "memory:11" in native_memory
    assert "Pricing policy" in native_memory
    assert "Use prepaid credits before invoicing." in native_memory


@pytest.mark.asyncio
async def test_deep_agents_engine_passes_workspace_file_context_to_native_runtime():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    module = FakeDeepAgentsModule(final_answer="Deep plan ready")
    engine = DeepAgentsRuntimeEngine(backend, object(), deep_agents_enabled=True, dependency_loader=lambda: module)
    context = RunContext(
        runId=21,
        sessionId=4,
        userId=5,
        message="summarize the uploaded files",
        agentFiles=[
            AgentFileContext(
                id=7,
                originalFilename="C:\\Users\\alice\\Desktop\\product.txt",
                status="READY",
                extractedText="Pricing: Pro plan includes audit exports.",
            )
        ],
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
    engine = DeepAgentsRuntimeEngine(backend, object(), deep_agents_enabled=True, dependency_loader=lambda: module)
    context = RunContext(runId=11, sessionId=4, userId=5, message="plan pricing rollout")

    await engine.run(context)

    assert backend.memory_requests == []


@pytest.mark.asyncio
async def test_memory_curator_skips_duplicate_when_tool_loop_already_saved():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    engine = DeepAgentsRuntimeEngine(backend, object())
    context = RunContext(
        runId=31,
        sessionId=4,
        userId=5,
        workspaceId=7,
        message="你觉得我是什么样的人？写入你的记忆里",
    )
    engine._memory_tool_executed_runs.add(31)

    await engine._curate_memory_after_run(context, "已记录。用户喜欢二次元梗图。")

    assert backend.created_memories == []
    assert not any(event.eventType == MEMORY_CANDIDATE_CREATED for _, event in backend.events)


@pytest.mark.asyncio
async def test_memory_consolidation_updates_existing_profile_after_enough_turns():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    backend.memory_items = [
        WorkspaceMemoryItem(
            id=91,
            workspaceId=7,
            title="用户画像与偏好摘要",
            content="旧画像",
            memoryType="user_profile",
            score=2,
        )
    ]
    engine = DeepAgentsRuntimeEngine(backend, object())
    context = RunContext(
        runId=32,
        sessionId=4,
        userId=5,
        workspaceId=7,
        message="以后继续保持这种二次元梗图风格",
        history=[ChatMessage(role="user", content=f"我喜欢第{i}种二次元梗图风格") for i in range(7)],
    )
    engine._memory_tool_executed_runs.add(32)

    await engine._curate_memory_after_run(context, "好的，继续保持。")

    assert backend.updated_memories
    assert backend.updated_memories[0]["memory_id"] == 91
    assert "二次元" in backend.updated_memories[0]["content"]


@pytest.mark.asyncio
async def test_deep_agents_engine_fails_cleanly_when_model_is_not_supported():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    module = FakeDeepAgentsModule(final_answer="unused", create_error=AttributeError("'MockChatModel' object has no attribute 'count'"))
    engine = DeepAgentsRuntimeEngine(backend, object(), deep_agents_enabled=True, dependency_loader=lambda: module)

    await engine.run(RunContext(runId=12, sessionId=4, userId=5, message="plan"))

    assert backend.events == []
    assert backend.failed_runs[0][1].errorCode == "DEEP_AGENTS_MODEL_UNSUPPORTED"


@pytest.mark.asyncio
async def test_deep_agents_engine_traces_subagent_start_and_completion_events():
    from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

    backend = FakeBackend()
    module = FakeDeepAgentsModule(final_answer="Deep plan ready", tool_events=[("start", "researcher"), ("end", "researcher")])
    engine = DeepAgentsRuntimeEngine(backend, object(), deep_agents_enabled=True, dependency_loader=lambda: module)

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
    engine = DeepAgentsRuntimeEngine(backend, object(), deep_agents_enabled=True, dependency_loader=lambda: module)

    await engine.run(RunContext(runId=14, sessionId=4, userId=5, message="analyze files"))

    failed_events = [event for _, event in backend.events if event.eventType == SUBAGENT_FAILED]
    assert failed_events[0].eventJson == {
        "subagentName": "file-analyst",
        "error": "subagent failed",
    }


class FakeDeepAgentsModule:
    def __init__(
        self,
        final_answer: str,
        create_error: Exception | None = None,
        tool_events: list[tuple[str, str]] | None = None,
        stream_chunks: list[str] | None = None,
    ):
        self.final_answer = final_answer
        self.create_error = create_error
        self.tool_events = tool_events or []
        self.stream_chunks = stream_chunks
        self.created_agents = []
        self.invocations = []

    def create_deep_agent(self, tools, system_prompt, model=None, subagents=None, memory=None):
        if self.create_error is not None:
            raise self.create_error
        self.created_agents.append({"tools": tools, "system_prompt": system_prompt, "model": model, "subagents": subagents or [], "memory": memory or []})
        if self.stream_chunks is not None:
            return FakeStreamingDeepAgent(self)
        return FakeDeepAgent(self)


class FakeDeepAgent:
    def __init__(self, module: FakeDeepAgentsModule):
        self.module = module

    async def ainvoke(self, payload, config=None):
        self.module.invocations.append(payload)
        for event_type, subagent_name in self.module.tool_events:
            await _emit_fake_tool_event(config, event_type, subagent_name)
        return {"messages": [{"role": "assistant", "content": self.module.final_answer}]}


class FakeStreamingDeepAgent(FakeDeepAgent):
    async def astream_events(self, payload, config=None, version=None):
        self.module.invocations.append(payload)
        for chunk in self.module.stream_chunks or []:
            yield {"event": "on_chat_model_stream", "data": {"chunk": type("Chunk", (), {"content": chunk})()}}
        yield {"event": "on_chain_end", "data": {"output": {"messages": [{"role": "assistant", "content": self.module.final_answer}]}}}


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
