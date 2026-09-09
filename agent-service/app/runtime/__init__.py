__all__ = [
    "AgentRuntimeEngine",
    "AgentGraphEngine",
]


def __getattr__(name: str):
    if name == "AgentRuntimeEngine":
        from app.runtime.engine import AgentRuntimeEngine

        return AgentRuntimeEngine
    if name == "AgentGraphEngine":
        from app.runtime.agent_graph import AgentGraphEngine

        return AgentGraphEngine
    raise AttributeError(name)
