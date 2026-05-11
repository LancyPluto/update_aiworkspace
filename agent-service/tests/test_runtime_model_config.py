import pytest

from app.config import Settings
from app.core.runtime import AgentRuntime
from app.core.schemas import AgentModelConfig, RunContext


class FakeBackend:
    def __init__(self):
        self.events = []
        self.completed = []
        self.failed = []

    async def append_event(self, run_id, event):
        self.events.append((run_id, event.eventType))

    async def get_active_model_config(self):
        return AgentModelConfig(
            provider="minimax",
            modelName="MiniMax-M2.7",
            baseUrl="https://api.minimax.io/v1",
            apiKey="secret",
            minimaxGroupId="group",
            timeoutSeconds=45,
            enabled=True,
        )

    async def get_run_context(self, run_id):
        return RunContext(runId=run_id, sessionId=1, userId=1, message="hello")

    async def complete_run(self, run_id, request):
        self.completed.append((run_id, request.modelProviderCode, request.modelName, request.finalAnswer))

    async def fail_run(self, run_id, request):
        self.failed.append((run_id, request.errorCode, request.errorMessage))


class FakeModelClient:
    created_settings = []

    def __init__(self, settings):
        FakeModelClient.created_settings.append(settings)
        self.model_name = settings.model_name

    async def chat(self, messages):
        return "real model answer"


@pytest.mark.asyncio
async def test_runtime_builds_model_client_from_backend_active_model_config():
    FakeModelClient.created_settings = []
    backend = FakeBackend()
    runtime = AgentRuntime(backend, model_client_factory=FakeModelClient, default_settings=Settings(model_provider="mock"))

    await runtime.execute_run(7)

    assert FakeModelClient.created_settings[0].model_provider == "minimax"
    assert FakeModelClient.created_settings[0].model_name == "MiniMax-M2.7"
    assert FakeModelClient.created_settings[0].model_api_key == "secret"
    assert FakeModelClient.created_settings[0].minimax_group_id == "group"
    assert backend.completed[0][1] == "agent-service"
    assert backend.completed[0][2] == "MiniMax-M2.7"
