from app.config import settings as default_settings
from app.runtime.engine import AgentRuntimeEngine
from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine
from app.runtime.legacy_engine import LegacyDispatcherEngine


class RuntimeRouter:
    """Selects which agent execution engine handles a run.

    Selection precedence:
      1. Explicitly requested runtime (``requested_runtime``) when supported.
      2. The multi-step graph engine when ``graph_engine_enabled`` is on.
      3. ``deep_agents`` native engine when ``deep_agents_enabled`` is on.
      4. The legacy single-tool dispatcher as the safe default / rollback path.
    """

    def __init__(
        self,
        backend_client=None,
        model_client=None,
        deep_agents_enabled: bool = False,
        graph_engine_enabled: bool | None = None,
    ) -> None:
        self.backend_client = backend_client
        self.model_client = model_client
        self.deep_agents_enabled = deep_agents_enabled
        self.graph_engine_enabled = (
            default_settings.agent_graph_engine_enabled if graph_engine_enabled is None else graph_engine_enabled
        )

    def select_engine(self, message: str, requested_runtime: str | None = None) -> AgentRuntimeEngine:
        runtime = self._normalize_runtime(requested_runtime)
        if runtime == "agent_graph" or (runtime == "default" and self.graph_engine_enabled):
            return self._build_graph_engine()
        if runtime == "deep_agents" and self.deep_agents_enabled:
            return DeepAgentsRuntimeEngine(
                self.backend_client,
                self.model_client,
                deep_agents_enabled=True,
            )
        return LegacyDispatcherEngine(self.backend_client, self.model_client)

    def select_debug_engine(self, message: str, requested_runtime: str | None = None) -> AgentRuntimeEngine:
        return self.select_engine(message=message, requested_runtime=requested_runtime)

    def _build_graph_engine(self) -> AgentRuntimeEngine:
        # Imported lazily so the legacy/deep paths do not pay the import cost and
        # so optional graph dependencies fail closed to the safe default.
        try:
            from app.runtime.agent_graph import AgentGraphEngine
        except Exception:  # pragma: no cover - defensive fallback when deps missing.
            return LegacyDispatcherEngine(self.backend_client, self.model_client)
        return AgentGraphEngine(self.backend_client, self.model_client)

    @staticmethod
    def _normalize_runtime(requested_runtime: str | None) -> str:
        if requested_runtime is None:
            return "default"
        runtime = requested_runtime.strip().lower()
        if runtime in {"", "default", "langgraph", "legacy"}:
            return "default"
        if runtime in {"agent_graph", "agent-graph", "graph"}:
            return "agent_graph"
        if runtime in {"deep_agents", "deep-agents", "deepagents"}:
            return "deep_agents"
        return "default"
