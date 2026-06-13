import pytest

from app.core.runtime import AgentRuntime
from app.core.schemas import RunContext


class FakeBackend:
    def __init__(self):
        self.events = []
        self.context = RunContext(runId=9, sessionId=2, userId=3, message="route me")

    async def append_event(self, run_id, event):
        self.events.append((run_id, event.eventType, event.eventText))

    async def get_run_context(self, run_id):
        return self.context


class FakeEngine:
    def __init__(self):
        self.run_contexts = []
        self.confirmed_tool_calls = []
        self.debug_contexts = []

    async def run(self, context):
        self.run_contexts.append(context)

    async def run_confirmed_tool(self, context, tool_code):
        self.confirmed_tool_calls.append((context, tool_code))

    async def debug_route(self, context):
        self.debug_contexts.append(context)
        return {"ok": True}


class FakeRuntimeRouter:
    instances = []

    def __init__(self, backend_client=None, model_client=None, deep_agents_enabled=False, graph_engine_enabled=False):
        self.backend_client = backend_client
        self.model_client = model_client
        self.deep_agents_enabled = deep_agents_enabled
        self.graph_engine_enabled = graph_engine_enabled
        self.engine = FakeEngine()
        self.select_calls = []
        FakeRuntimeRouter.instances.append(self)

    def select_engine(self, message, requested_runtime=None):
        self.select_calls.append((message, requested_runtime))
        return self.engine


@pytest.mark.asyncio
async def test_agent_runtime_uses_runtime_router_selected_engine():
    FakeRuntimeRouter.instances = []
    backend = FakeBackend()
    model_client = object()
    runtime = AgentRuntime(
        backend,
        model_client=model_client,
        runtime_router_factory=FakeRuntimeRouter,
    )

    await runtime.execute_run(9)

    router = FakeRuntimeRouter.instances[0]
    assert router.backend_client is backend
    assert router.model_client is model_client
    assert router.deep_agents_enabled is False
    assert router.select_calls == [("route me", None)]
    assert router.engine.run_contexts == [backend.context]
    assert backend.events == []


@pytest.mark.asyncio
async def test_agent_runtime_uses_selected_engine_for_confirmed_tools():
    FakeRuntimeRouter.instances = []
    backend = FakeBackend()
    model_client = object()
    runtime = AgentRuntime(
        backend,
        model_client=model_client,
        runtime_router_factory=FakeRuntimeRouter,
    )

    await runtime.execute_confirmed_tool(9, "xiaohongshu_copywriting")

    router = FakeRuntimeRouter.instances[0]
    assert router.select_calls == [("route me", None)]
    assert router.engine.confirmed_tool_calls == [(backend.context, "xiaohongshu_copywriting")]


@pytest.mark.asyncio
async def test_agent_runtime_uses_selected_engine_for_debug_route():
    FakeRuntimeRouter.instances = []
    backend = FakeBackend()
    model_client = object()
    runtime = AgentRuntime(
        backend,
        model_client=model_client,
        runtime_router_factory=FakeRuntimeRouter,
    )

    result = await runtime.debug_route(backend.context)

    router = FakeRuntimeRouter.instances[0]
    assert router.select_calls == [("route me", None)]
    assert router.engine.debug_contexts == [backend.context]
    assert result == {"ok": True}
