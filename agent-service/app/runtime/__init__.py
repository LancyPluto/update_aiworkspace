__all__ = [
    "AgentRuntimeEngine",
    "AgentGraphEngine",
    "DeepAgentsRuntimeEngine",
    "LegacyDispatcherEngine",
    "RuntimeRouter",
]


def __getattr__(name: str):
    if name == "AgentRuntimeEngine":
        from app.runtime.engine import AgentRuntimeEngine

        return AgentRuntimeEngine
    if name == "AgentGraphEngine":
        from app.runtime.agent_graph import AgentGraphEngine

        return AgentGraphEngine
    if name == "DeepAgentsRuntimeEngine":
        from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

        return DeepAgentsRuntimeEngine
    if name == "LegacyDispatcherEngine":
        from app.runtime.legacy_engine import LegacyDispatcherEngine

        return LegacyDispatcherEngine
    if name == "RuntimeRouter":
        from app.runtime.router import RuntimeRouter

        return RuntimeRouter
    raise AttributeError(name)
