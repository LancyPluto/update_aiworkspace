import pytest


@pytest.fixture
def disable_unified_graph_router():
    """Compatibility fixture retained for graph tests."""
    yield
