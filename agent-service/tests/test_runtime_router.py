from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine
from app.runtime.router import RuntimeRouter


def test_runtime_router_defaults_to_deep_agents_runtime_engine():
    router = RuntimeRouter(backend_client=object(), model_client=object())

    engine = router.select_engine(message="hello")

    assert isinstance(engine, DeepAgentsRuntimeEngine)


def test_runtime_router_requested_runtime_is_ignored():
    """requested_runtime parameter is ignored since DeepAgents is now the only runtime."""
    router = RuntimeRouter(backend_client=object(), model_client=object())

    engine = router.select_engine(message="hello", requested_runtime="deep_agents")

    assert isinstance(engine, DeepAgentsRuntimeEngine)
