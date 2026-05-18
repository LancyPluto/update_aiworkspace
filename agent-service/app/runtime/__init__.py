__all__ = ["AgentRuntimeEngine", "DeepAgentsRuntimeEngine", "RuntimeRouter"]


def __getattr__(name: str):
    if name == "AgentRuntimeEngine":
        from app.runtime.engine import AgentRuntimeEngine

        return AgentRuntimeEngine
    if name == "DeepAgentsRuntimeEngine":
        from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine

        return DeepAgentsRuntimeEngine
    if name == "RuntimeRouter":
        from app.runtime.router import RuntimeRouter

        return RuntimeRouter
    raise AttributeError(name)
