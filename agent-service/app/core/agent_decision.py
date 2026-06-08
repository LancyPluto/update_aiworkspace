import logging
from collections.abc import Awaitable, Callable

from pydantic import BaseModel

from app.config import settings
from app.core.intent_router import Intent, IntentResult, IntentRouter
from app.core.preferred_tool_bias import (
    apply_preferred_tool_override,
    message_suggests_tool_use,
    preferred_tool_code,
    resolve_preferred_tool,
)
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
        preferred = preferred_tool_code(context)
        if preferred and rule_intent.intent == Intent.TOOL_USE:
            before_tool = rule_intent.selectedToolCode
            rule_intent = apply_preferred_tool_override(context, rule_intent)
            if rule_intent.selectedToolCode != before_tool:
                signals.append(_signal("preferred_tool", Intent.TOOL_USE.value, 1.0, "preferred_tool_applied"))
        if is_infrastructure_rule_reason(rule_intent.reason):
            return _with_signals(rule_intent, signals)

        if (
            preferred
            and rule_intent.intent not in {Intent.FILE_ANALYSIS, Intent.UNSUPPORTED, Intent.SECURITY_REJECTED}
            and message_suggests_tool_use(context.message)
            and resolve_preferred_tool(context) is not None
        ):
            preferred_intent = IntentResult(
                intent=Intent.TOOL_USE,
                confidence=max(rule_intent.confidence, 0.9),
                selectedToolCode=preferred,
                candidateToolCodes=[
                    preferred,
                    *[code for code in rule_intent.candidateToolCodes if code != preferred],
                ][:3],
                decisionSource="preferred_tool",
                reason="preferred_tool_selected",
                arguments=rule_intent.arguments,
            )
            signals.append(_signal("preferred_tool", Intent.TOOL_USE.value, 1.0, "preferred_tool_selected"))
            return _with_signals(preferred_intent, signals)

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
