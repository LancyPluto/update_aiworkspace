from app.runtime.engine import AgentRuntimeEngine
from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine


class RuntimeRouter:
    def __init__(self, backend_client=None, model_client=None) -> None:
        self.backend_client = backend_client
        self.model_client = model_client

    def select_engine(self, message: str, requested_runtime: str | None = None) -> AgentRuntimeEngine:
        return DeepAgentsRuntimeEngine(
            self.backend_client,
            self.model_client,
        )
