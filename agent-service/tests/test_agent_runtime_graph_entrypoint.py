import pytest

from app.core.runtime import AgentRuntime
from app.core.schemas import RunContext


class FakeBackend:
    def __init__(self):
        self.context = RunContext(runId=9, sessionId=2, userId=3, message="route me")

    async def get_run_context(self, run_id):
        return self.context

    async def get_active_model_config(self):
        return None


class FakeEngine:
    instances = []

    def __init__(self, backend, model):
        self.backend = backend
        self.model = model
        self.runs = []
        self.confirmed = []
        self.debug = []
        FakeEngine.instances.append(self)

    async def run(self, context):
        self.runs.append(context)

    async def run_confirmed_tool(self, context, tool_code):
        self.confirmed.append((context, tool_code))

    async def debug_route(self, context):
        self.debug.append(context)
        return {"ok": True}


@pytest.fixture
def graph_engine(monkeypatch):
    FakeEngine.instances = []
    monkeypatch.setattr("app.core.runtime.AgentGraphEngine", FakeEngine)
    return FakeEngine


@pytest.mark.asyncio
async def test_agent_runtime_always_uses_graph_engine(graph_engine):
    backend = FakeBackend()
    model = object()
    runtime = AgentRuntime(backend, model_client=model)

    await runtime.execute_run(9)

    engine = graph_engine.instances[0]
    assert engine.backend is backend
    assert engine.model is model
    assert engine.runs == [backend.context]


@pytest.mark.asyncio
async def test_confirmed_tool_and_debug_route_use_graph_engine(graph_engine):
    backend = FakeBackend()
    runtime = AgentRuntime(backend, model_client=object())

    await runtime.execute_confirmed_tool(9, "xiaohongshu_copywriting")
    result = await runtime.debug_route(backend.context)

    assert graph_engine.instances[0].confirmed == [(backend.context, "xiaohongshu_copywriting")]
    assert graph_engine.instances[1].debug == [backend.context]
    assert result == {"ok": True}
