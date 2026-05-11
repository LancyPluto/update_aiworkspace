import pytest

from app.core.event_types import RUN_STARTED
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

    async def run(self, context):
        self.run_contexts.append(context)


class FakeRuntimeRouter:
    instances = []

    def __init__(self, backend_client=None, model_client=None, deep_agents_enabled=False):
        self.backend_client = backend_client
        self.model_client = model_client
        self.deep_agents_enabled = deep_agents_enabled
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
        default_settings=type("Settings", (), {"agent_deep_agents_enabled": False})(),
    )

    await runtime.execute_run(9)

    router = FakeRuntimeRouter.instances[0]
    assert router.backend_client is backend
    assert router.model_client is model_client
    assert router.deep_agents_enabled is False
    assert router.select_calls == [("route me", None)]
    assert router.engine.run_contexts == [backend.context]
    assert backend.events[0][1] == RUN_STARTED


@pytest.mark.asyncio
async def test_agent_runtime_requests_deep_agents_when_feature_flag_enabled():
    FakeRuntimeRouter.instances = []
    backend = FakeBackend()
    runtime = AgentRuntime(
        backend,
        model_client=object(),
        runtime_router_factory=FakeRuntimeRouter,
        default_settings=type("Settings", (), {"agent_deep_agents_enabled": True})(),
    )

    await runtime.execute_run(9)

    router = FakeRuntimeRouter.instances[0]
    assert router.select_calls == [("route me", "deep_agents")]
