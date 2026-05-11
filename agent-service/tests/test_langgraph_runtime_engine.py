import pytest

from app.core.schemas import RunContext
from app.runtime.langgraph_engine import LangGraphRuntimeEngine


class FakeGraph:
    instances = []

    def __init__(self, backend_client, model_client):
        self.backend_client = backend_client
        self.model_client = model_client
        self.run_contexts = []
        FakeGraph.instances.append(self)

    async def run(self, context):
        self.run_contexts.append(context)


@pytest.mark.asyncio
async def test_langgraph_runtime_engine_delegates_run_to_graph():
    FakeGraph.instances = []
    backend = object()
    model = object()
    context = RunContext(runId=1, sessionId=2, userId=3, message="hello")
    engine = LangGraphRuntimeEngine(backend, model, graph_factory=FakeGraph)

    await engine.run(context)

    assert len(FakeGraph.instances) == 1
    assert FakeGraph.instances[0].backend_client is backend
    assert FakeGraph.instances[0].model_client is model
    assert FakeGraph.instances[0].run_contexts == [context]
