"""Phase 1 unified semantic routing package."""

from app.routing.v2.thread_state import RoutingThreadState, build_thread_state

__all__ = ["RoutingThreadState", "UnifiedSemanticRouter", "build_thread_state"]


def __getattr__(name: str):
    if name == "UnifiedSemanticRouter":
        from app.routing.v2.unified_router import UnifiedSemanticRouter

        return UnifiedSemanticRouter
    raise AttributeError(name)
