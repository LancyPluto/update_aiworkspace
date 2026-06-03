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


def test_runtime_router_normalizes_default_and_deep_agents_aliases():
    router = RuntimeRouter(backend_client=object(), model_client=object(), deep_agents_enabled=True)

    assert isinstance(router.select_engine(message="hello", requested_runtime="langgraph"), LangGraphRuntimeEngine)
    assert isinstance(router.select_engine(message="hello", requested_runtime="default"), LangGraphRuntimeEngine)
    assert isinstance(router.select_engine(message="hello", requested_runtime="deep-agents"), DeepAgentsRuntimeEngine)


def test_runtime_router_debug_selection_uses_same_engine_path():
    router = RuntimeRouter(backend_client=object(), model_client=object(), deep_agents_enabled=True)

    engine = router.select_debug_engine(message="hello", requested_runtime="deep_agents")

    assert isinstance(engine, DeepAgentsRuntimeEngine)
