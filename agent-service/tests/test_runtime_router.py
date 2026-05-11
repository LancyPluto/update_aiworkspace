from app.runtime.langgraph_engine import LangGraphRuntimeEngine
from app.runtime.router import RuntimeRouter


def test_runtime_router_defaults_to_langgraph_runtime_engine():
    router = RuntimeRouter(backend_client=object(), model_client=object())

    engine = router.select_engine(message="hello")

    assert isinstance(engine, LangGraphRuntimeEngine)


def test_runtime_router_returns_langgraph_when_deep_agents_requested_but_disabled():
    router = RuntimeRouter(backend_client=object(), model_client=object(), deep_agents_enabled=False)

    engine = router.select_engine(message="hello", requested_runtime="deep_agents")

    assert isinstance(engine, LangGraphRuntimeEngine)
