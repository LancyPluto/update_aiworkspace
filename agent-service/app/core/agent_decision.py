import logging
from collections.abc import Awaitable, Callable

from pydantic import BaseModel

from app.config import settings
from app.core.preferred_tool_bias import (
    apply_preferred_tool_override,
    message_suggests_tool_use,
    preferred_tool_code,
    resolve_preferred_tool,
)
from app.core.schemas import RunContext
from app.routing.constants import is_infrastructure_rule_reason
from app.routing.decision_pipeline import DecisionPipeline, DecisionSignal, routing_results_differ
from app.routing.state_guard import StateGuard
from app.routing.types import Intent, IntentResult

LOGGER = logging.getLogger(__name__)


class AgentDecisionService:
    """Routes via DecisionPipeline (StateGuard -> Unified Router | legacy LLM -> Policy)."""

    def __init__(self, intent_router: StateGuard | None = None) -> None:
        guard = intent_router if intent_router is not None else StateGuard()
        self.pipeline = DecisionPipeline(state_guard=guard)

    async def decide(
        self,
        context: RunContext,
        *,
        unified_router: Callable[[RunContext, IntentResult], Awaitable[IntentResult | None]] | None = None,
        llm_router: Callable[[RunContext, IntentResult], Awaitable[IntentResult | None]] | None = None,
        tool_resolver: Callable[[RunContext, IntentResult], Awaitable[IntentResult | None]] | None = None,
    ) -> IntentResult:
        if bool(getattr(settings, "agent_routing_v2_shadow_mode", False)) and llm_router is not None:
            legacy = await self._decide_legacy(context, llm_router=llm_router)
            try:
                v2 = await self.pipeline.decide(
                    context,
                    unified_router=unified_router,
                    llm_classifier=llm_router,
                    tool_resolver=tool_resolver,
                )
            except Exception as exc:
                LOGGER.warning("routing v2 shadow failed runId=%s error=%s", context.runId, exc)
                return legacy
            if routing_results_differ(legacy, v2):
                LOGGER.info(
                    "routing disagreement runId=%s legacy=%s/%s v2=%s/%s",
                    context.runId,
                    legacy.intent.value,
                    legacy.selectedToolCode or "-",
                    v2.intent.value,
                    v2.selectedToolCode or "-",
                )
            return legacy

        return await self.pipeline.decide(
            context,
            unified_router=unified_router,
            llm_classifier=llm_router,
            tool_resolver=tool_resolver,
        )

    async def _decide_legacy(
        self,
        context: RunContext,
        *,
        llm_router: Callable[[RunContext, IntentResult], Awaitable[IntentResult | None]] | None,
    ) -> IntentResult:
        """Pre-v2 path without tool resolver — for shadow comparison only."""
        signals: list[DecisionSignal] = []
        guard_intent = self.pipeline.state_guard.classify(context)
        signals.append(
            DecisionSignal(
                source="state_guard",
                verdict=guard_intent.intent.value,
                confidence=guard_intent.confidence,
                reason=guard_intent.reason,
            )
        )
        preferred = preferred_tool_code(context)
        if preferred and guard_intent.intent == Intent.TOOL_USE:
            guard_intent = apply_preferred_tool_override(context, guard_intent)
        if is_infrastructure_rule_reason(guard_intent.reason):
            return _with_signals(guard_intent, signals)
        if (
            preferred
            and guard_intent.intent not in {Intent.FILE_ANALYSIS, Intent.UNSUPPORTED, Intent.SECURITY_REJECTED}
            and message_suggests_tool_use(context.message)
            and resolve_preferred_tool(context) is not None
        ):
            preferred_intent = IntentResult(
                intent=Intent.TOOL_USE,
                confidence=0.9,
                selectedToolCode=preferred,
                candidateToolCodes=[preferred],
                decisionSource="preferred_tool",
                reason="preferred_tool_selected",
            )
            signals.append(
                DecisionSignal(source="preferred_tool", verdict="tool_use", confidence=1.0, reason="preferred_tool_selected")
            )
            return _with_signals(preferred_intent, signals)

        router_enabled = bool(getattr(settings, "agent_llm_router_enabled", False))
        if context.routerSettings is not None and context.routerSettings.enabled is False:
            router_enabled = False
        if router_enabled and llm_router is not None and context.availableTools:
            llm_intent = await llm_router(context, guard_intent)
            if llm_intent is not None:
                signals.append(
                    DecisionSignal(
                        source="llm_classifier",
                        verdict=llm_intent.intent.value,
                        confidence=llm_intent.confidence,
                        reason=llm_intent.reason,
                    )
                )
                return _with_signals(llm_intent, signals)

        fallback = IntentResult(
            intent=Intent.GENERAL_CHAT,
            confidence=0.55,
            reason="router_fallback_general_chat",
            decisionSource="decision_layer",
        )
        signals.append(DecisionSignal(source="fallback", verdict="general_chat", confidence=0.55, reason=fallback.reason))
        return _with_signals(fallback, signals)


def _with_signals(intent: IntentResult, signals: list[DecisionSignal]) -> IntentResult:
    intent.signals = [signal.model_dump() for signal in signals]
    LOGGER.info(
        "agent decision selected intent=%s confidence=%.2f selectedTool=%s reason=%s signals=%s",
        intent.intent.value,
        intent.confidence,
        intent.selectedToolCode or "-",
        intent.reason,
        intent.signals,
    )
    return intent
