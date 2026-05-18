from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine
from app.runtime.langgraph_engine import LangGraphRuntimeEngine
from app.runtime.router import RuntimeRouter


def test_runtime_router_defaults_to_langgraph_runtime_engine():
    router = RuntimeRouter(backend_client=object(), model_client=object())

    engine = router.select_engine(message="hello")

    assert isinstance(engine, LangGraphRuntimeEngine)


def test_runtime_router_requires_feature_flag_for_deep_agents():
    disabled_router = RuntimeRouter(backend_client=object(), model_client=object(), deep_agents_enabled=False)
    enabled_router = RuntimeRouter(backend_client=object(), model_client=object(), deep_agents_enabled=True)

    disabled_engine = disabled_router.select_engine(message="hello", requested_runtime="deep_agents")
    enabled_engine = enabled_router.select_engine(message="hello", requested_runtime="deep_agents")

    assert isinstance(disabled_engine, LangGraphRuntimeEngine)
    assert isinstance(enabled_engine, DeepAgentsRuntimeEngine)
