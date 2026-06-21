"""Backward-compatible wrapper — delegates to app.routing.LLMClassifier (deprecated)."""

import warnings

from app.routing.llm_classifier import (
    DEFAULT_ROUTER_PROMPT_V2,
    LLMClassifier,
    _normalize_router_intent,
)
from app.routing.state_guard import StateGuard

DEFAULT_ROUTER_PROMPT = DEFAULT_ROUTER_PROMPT_V2


class AgentRouterService(LLMClassifier):
    """Deprecated alias for LLMClassifier — use UnifiedSemanticRouter instead."""

    def __init__(self, backend_client, model_client, *, intent_router: StateGuard | None = None) -> None:
        warnings.warn(
            "AgentRouterService is deprecated; use UnifiedSemanticRouter",
            DeprecationWarning,
            stacklevel=2,
        )
        super().__init__(backend_client, model_client)


__all__ = ["AgentRouterService", "DEFAULT_ROUTER_PROMPT", "_normalize_router_intent"]
