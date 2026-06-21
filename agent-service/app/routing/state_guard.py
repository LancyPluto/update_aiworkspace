from __future__ import annotations

from app.core.preferred_tool_bias import inject_preferred_tool_hint
from app.core.schemas import RunContext
from app.routing.types import Intent, IntentResult


class StateGuard:
    """Infrastructure-only routing guards — no NLP on user message text."""

    def classify(self, context: RunContext) -> IntentResult:
        message = context.message.strip()
        if not message:
            return IntentResult(
                intent=Intent.NEEDS_CLARIFICATION,
                confidence=0.8,
                reason="empty_request",
                decisionSource="state_guard",
            )

        continued = self._tool_use_from_pending_tool_context(context)
        if continued is not None:
            return continued

        baseline = IntentResult(
            intent=Intent.GENERAL_CHAT,
            confidence=0.5,
            reason="awaiting_semantic_router",
            decisionSource="state_guard",
        )
        return inject_preferred_tool_hint(context, baseline)

    def _tool_use_from_pending_tool_context(self, context: RunContext) -> IntentResult | None:
        pending = context.pendingToolContext
        if pending is None or pending.status != "ACTIVE":
            return None
        if not pending.selectedToolCode:
            return None
        return IntentResult(
            intent=Intent.TOOL_USE,
            confidence=0.9,
            selectedToolCode=pending.selectedToolCode,
            candidateToolCodes=[pending.selectedToolCode],
            reason="restored_from_pending_tool_context",
            decisionSource="state_guard",
            clarifyingQuestion=pending.clarifyingQuestion,
            arguments=dict(pending.collectedArgumentsJson or {}),
            missingFields=list(pending.missingArgumentsJson or []),
        )
