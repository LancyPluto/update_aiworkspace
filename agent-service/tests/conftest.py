import pytest


@pytest.fixture
def legacy_llm_router_settings():
    """Enable deprecated JSON router and disable unified router for legacy integration tests."""
    from app.config import settings

    original_unified = settings.agent_unified_router_enabled
    original_llm = settings.agent_llm_router_enabled
    settings.agent_unified_router_enabled = False
    settings.agent_llm_router_enabled = True
    try:
        yield
    finally:
        settings.agent_unified_router_enabled = original_unified
        settings.agent_llm_router_enabled = original_llm


@pytest.fixture
def disable_unified_graph_router():
    """Keep agent-graph tests on the in-graph FC loop without an extra routing turn."""
    from app.config import settings

    original = settings.agent_unified_router_enabled
    settings.agent_unified_router_enabled = False
    try:
        yield
    finally:
        settings.agent_unified_router_enabled = original
