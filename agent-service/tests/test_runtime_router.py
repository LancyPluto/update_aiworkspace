from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine
from app.runtime.legacy_engine import LegacyDispatcherEngine
from app.runtime.router import RuntimeRouter


def test_runtime_router_defaults_to_legacy_dispatcher_engine():
    router = RuntimeRouter(backend_client=object(), model_client=object(), graph_engine_enabled=False)

    engine = router.select_engine(message="hello")

    assert isinstance(engine, LegacyDispatcherEngine)


def test_runtime_router_requires_feature_flag_for_deep_agents():
    disabled_router = RuntimeRouter(backend_client=object(), model_client=object(), deep_agents_enabled=False, graph_engine_enabled=False)
    enabled_router = RuntimeRouter(backend_client=object(), model_client=object(), deep_agents_enabled=True, graph_engine_enabled=False)

    disabled_engine = disabled_router.select_engine(message="hello", requested_runtime="deep_agents")
    enabled_engine = enabled_router.select_engine(message="hello", requested_runtime="deep_agents")

    assert isinstance(disabled_engine, LegacyDispatcherEngine)
    assert isinstance(enabled_engine, DeepAgentsRuntimeEngine)


def test_runtime_router_normalizes_default_and_deep_agents_aliases():
    router = RuntimeRouter(backend_client=object(), model_client=object(), deep_agents_enabled=True, graph_engine_enabled=False)

    assert isinstance(router.select_engine(message="hello", requested_runtime="langgraph"), LegacyDispatcherEngine)
    assert isinstance(router.select_engine(message="hello", requested_runtime="default"), LegacyDispatcherEngine)
    assert isinstance(router.select_engine(message="hello", requested_runtime="deep-agents"), DeepAgentsRuntimeEngine)


def test_runtime_router_selects_graph_engine_when_enabled():
    from app.runtime.agent_graph import AgentGraphEngine

    router = RuntimeRouter(backend_client=object(), model_client=object(), graph_engine_enabled=True)

    assert isinstance(router.select_engine(message="hello"), AgentGraphEngine)
    # Explicit request works even when the flag is off.
    off_router = RuntimeRouter(backend_client=object(), model_client=object(), graph_engine_enabled=False)
    assert isinstance(off_router.select_engine(message="hi", requested_runtime="agent_graph"), AgentGraphEngine)


def test_runtime_router_debug_selection_uses_same_engine_path():
    router = RuntimeRouter(backend_client=object(), model_client=object(), deep_agents_enabled=True, graph_engine_enabled=False)

    engine = router.select_debug_engine(message="hello", requested_runtime="deep_agents")

    assert isinstance(engine, DeepAgentsRuntimeEngine)
