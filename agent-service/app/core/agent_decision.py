import logging
from collections.abc import Awaitable, Callable

from pydantic import BaseModel

from app.config import settings
from app.core.intent_router import Intent, IntentResult, IntentRouter
from app.core.routing_constants import is_infrastructure_rule_reason
from app.core.schemas import RunContext

LOGGER = logging.getLogger(__name__)


class DecisionSignal(BaseModel):
    source: str
    verdict: str
    confidence: float
    reason: str


class AgentDecisionService:
    """LLM router is the primary intent decision; rules only handle infrastructure paths."""

    def __init__(self, intent_router: IntentRouter | None = None) -> None:
        self.intent_router = intent_router or IntentRouter()

    async def decide(
        self,
        context: RunContext,
        *,
        llm_router: Callable[[RunContext, IntentResult], Awaitable[IntentResult | None]] | None = None,
    ) -> IntentResult:
        signals: list[DecisionSignal] = []

        rule_intent = self.intent_router.classify(context)
        signals.append(_signal("rule_router", rule_intent.intent.value, rule_intent.confidence, rule_intent.reason))
        if is_infrastructure_rule_reason(rule_intent.reason):
            return _with_signals(rule_intent, signals)

        router_enabled = bool(getattr(settings, "agent_llm_router_enabled", True))
        if context.routerSettings is not None and context.routerSettings.enabled is False:
            router_enabled = False

        if router_enabled and llm_router is not None and context.availableTools:
            llm_intent = await llm_router(context, rule_intent)
            if llm_intent is not None:
                signals.append(_signal("llm_router", llm_intent.intent.value, llm_intent.confidence, llm_intent.reason))
                return _with_signals(llm_intent, signals)

        fallback = IntentResult(
            intent=Intent.GENERAL_CHAT,
            confidence=0.55,
            selectedToolCode=None,
            candidateToolCodes=[],
            decisionSource="decision_layer",
            reason="router_fallback_general_chat",
        )
        signals.append(_signal("fallback", fallback.intent.value, fallback.confidence, fallback.reason))
        return _with_signals(fallback, signals)


def _signal(source: str, verdict: str, confidence: float, reason: str) -> DecisionSignal:
    return DecisionSignal(source=source, verdict=verdict, confidence=confidence, reason=reason)


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
