from app.runtime.engine import AgentRuntimeEngine
from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine
from app.runtime.langgraph_engine import LangGraphRuntimeEngine


class RuntimeRouter:
    def __init__(self, backend_client=None, model_client=None, deep_agents_enabled: bool = False) -> None:
        self.backend_client = backend_client
        self.model_client = model_client
        self.deep_agents_enabled = deep_agents_enabled

    def select_engine(self, message: str, requested_runtime: str | None = None) -> AgentRuntimeEngine:
        runtime = self._normalize_runtime(requested_runtime)
        if runtime == "deep_agents" and self.deep_agents_enabled:
            return DeepAgentsRuntimeEngine(
                self.backend_client,
                self.model_client,
                deep_agents_enabled=True,
            )
        return LangGraphRuntimeEngine(self.backend_client, self.model_client)

    def select_debug_engine(self, message: str, requested_runtime: str | None = None) -> AgentRuntimeEngine:
        return self.select_engine(message=message, requested_runtime=requested_runtime)

    @staticmethod
    def _normalize_runtime(requested_runtime: str | None) -> str:
        if requested_runtime is None:
            return "default"
        runtime = requested_runtime.strip().lower()
        if runtime in {"", "default", "langgraph"}:
            return "default"
        if runtime in {"deep_agents", "deep-agents", "deepagents"}:
            return "deep_agents"
        return "default"
