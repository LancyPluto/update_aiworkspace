from app.runtime.engine import AgentRuntimeEngine
from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine
from app.runtime.langgraph_engine import LangGraphRuntimeEngine


class RuntimeRouter:
    def __init__(self, backend_client=None, model_client=None, deep_agents_enabled: bool = False) -> None:
        self.backend_client = backend_client
        self.model_client = model_client
        self.deep_agents_enabled = deep_agents_enabled

    def select_engine(self, message: str, requested_runtime: str | None = None) -> AgentRuntimeEngine:
        if requested_runtime == "deep_agents" and self.deep_agents_enabled:
            return DeepAgentsRuntimeEngine(
                self.backend_client,
                self.model_client,
                deep_agents_enabled=True,
            )
        return LangGraphRuntimeEngine(self.backend_client, self.model_client)
