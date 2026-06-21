from __future__ import annotations



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

from app.routing.policy_validator import PolicyValidator

from app.routing.state_guard import StateGuard

from app.routing.types import Intent, IntentResult



LOGGER = logging.getLogger(__name__)





class DecisionSignal(BaseModel):

    source: str

    verdict: str

    confidence: float

    reason: str





class DecisionPipeline:

    """StateGuard -> preferred_tool -> UnifiedSemanticRouter | legacy LLM -> Policy."""



    def __init__(

        self,

        state_guard: StateGuard | None = None,

        policy_validator: PolicyValidator | None = None,

    ) -> None:

        self.state_guard = state_guard or StateGuard()

        self.policy_validator = policy_validator or PolicyValidator()



    async def decide(

        self,

        context: RunContext,

        *,

        unified_router: Callable[[RunContext, IntentResult], Awaitable[IntentResult | None]] | None = None,

        llm_classifier: Callable[[RunContext, IntentResult], Awaitable[IntentResult | None]] | None = None,

        tool_resolver: Callable[[RunContext, IntentResult], Awaitable[IntentResult | None]] | None = None,

    ) -> IntentResult:

        signals: list[DecisionSignal] = []



        guard_intent = self.state_guard.classify(context)

        signals.append(_signal("state_guard", guard_intent.intent.value, guard_intent.confidence, guard_intent.reason))



        preferred = preferred_tool_code(context)

        if preferred and guard_intent.intent == Intent.TOOL_USE:

            before_tool = guard_intent.selectedToolCode

            guard_intent = apply_preferred_tool_override(context, guard_intent)

            if guard_intent.selectedToolCode != before_tool:

                signals.append(_signal("preferred_tool", Intent.TOOL_USE.value, 1.0, "preferred_tool_applied"))



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

                confidence=max(guard_intent.confidence, 0.9),

                selectedToolCode=preferred,

                candidateToolCodes=[preferred, *[code for code in guard_intent.candidateToolCodes if code != preferred]][:3],

                decisionSource="preferred_tool",

                reason="preferred_tool_selected",

                arguments=guard_intent.arguments,

            )

            signals.append(_signal("preferred_tool", Intent.TOOL_USE.value, 1.0, "preferred_tool_selected"))

            return _with_signals(self.policy_validator.validate(context, preferred_intent), signals)



        router_enabled = _router_enabled(context)

        if router_enabled and context.availableTools:
            routed: IntentResult | None = None
            if bool(getattr(settings, "agent_unified_router_enabled", True)) and unified_router is not None:
                routed = await unified_router(context, guard_intent)
            if routed is not None:
                signals.append(
                    _signal("unified_router", routed.intent.value, routed.confidence, routed.reason)
                )
                validated = self.policy_validator.validate(context, routed)
                if validated.decisionSource == "policy_validator":
                    signals.append(
                        _signal("policy_validator", validated.intent.value, validated.confidence, validated.reason)
                    )
                return _with_signals(validated, signals)
            if llm_classifier is not None:

                llm_intent = await llm_classifier(context, guard_intent)

                if llm_intent is not None:

                    signals.append(

                        _signal("llm_classifier", llm_intent.intent.value, llm_intent.confidence, llm_intent.reason)

                    )

                    result = llm_intent

                    if (

                        result.intent == Intent.TOOL_USE

                        and tool_resolver is not None

                        and not bool(getattr(settings, "agent_routing_v2_llm_only", False))

                    ):

                        resolved = await tool_resolver(context, result)

                        if resolved is not None:

                            signals.append(

                                _signal("tool_resolver", resolved.intent.value, resolved.confidence, resolved.reason)

                            )

                            result = resolved

                    validated = self.policy_validator.validate(context, result)

                    if validated.decisionSource == "policy_validator":

                        signals.append(

                            _signal("policy_validator", validated.intent.value, validated.confidence, validated.reason)

                        )

                    return _with_signals(validated, signals)



        if _fallback_to_rules(context) and _should_use_rule_fallback(guard_intent):

            rule_fallback = guard_intent.model_copy(

                update={

                    "decisionSource": "state_guard",

                    "reason": f"router_fallback_to_rules:{guard_intent.reason}",

                }

            )

            signals.append(_signal("fallback", rule_fallback.intent.value, rule_fallback.confidence, rule_fallback.reason))

            return _with_signals(self.policy_validator.validate(context, rule_fallback), signals)



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





def _router_enabled(context: RunContext) -> bool:

    if bool(getattr(settings, "agent_unified_router_enabled", True)):

        return True

    if not bool(getattr(settings, "agent_llm_router_enabled", False)):

        return False

    if context.routerSettings is not None and context.routerSettings.enabled is False:

        return False

    return True





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





def _fallback_to_rules(context: RunContext) -> bool:

    if context.routerSettings is None:

        return True

    return bool(context.routerSettings.fallbackToRules)





def _should_use_rule_fallback(guard_intent: IntentResult) -> bool:

    if guard_intent.intent == Intent.TOOL_USE and guard_intent.selectedToolCode:

        return True

    if guard_intent.intent == Intent.NEEDS_CLARIFICATION and guard_intent.clarifyingQuestion:

        return True

    return False





def routing_results_differ(left: IntentResult, right: IntentResult) -> bool:

    if left.intent != right.intent:

        return True

    if (left.selectedToolCode or "") != (right.selectedToolCode or ""):

        return True

    return False


