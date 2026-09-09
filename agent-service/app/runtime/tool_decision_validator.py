from dataclasses import dataclass, field
from typing import Any

from app.routing.types import IntentResult
from app.core.schemas import RunContext, ToolDescriptor
from app.tools.registry import infer_output_modality, requested_output_modality


@dataclass
class ToolDecisionValidation:
    accepted: bool
    reason: str
    requires_confirmation: bool = False
    warnings: list[str] = field(default_factory=list)
    arguments: dict[str, Any] = field(default_factory=dict)


class ToolDecisionValidator:
    """Backend-side guardrail for LLM router tool decisions."""

    def validate(self, context: RunContext, intent: IntentResult, tool: ToolDescriptor) -> ToolDecisionValidation:
        requested_modality = requested_output_modality(context.message)
        tool_modality = infer_output_modality(tool)
        warnings: list[str] = []
        if requested_modality and tool_modality and requested_modality != tool_modality:
            return ToolDecisionValidation(
                accepted=False,
                reason="output_modality_mismatch",
                warnings=[f"requested={requested_modality}", f"tool={tool_modality}"],
                arguments=dict(intent.arguments or {}),
            )

        requires_confirmation = bool(intent.requiresConfirmation)
        if self._is_high_cost_or_risk(tool):
            requires_confirmation = True
            warnings.append("high_cost_or_risk_requires_confirmation")

        return ToolDecisionValidation(
            accepted=True,
            reason="validated",
            requires_confirmation=requires_confirmation,
            warnings=warnings,
            arguments=dict(intent.arguments or {}),
        )

    @staticmethod
    def _is_high_cost_or_risk(tool: ToolDescriptor) -> bool:
        if tool.estimatedCreditCost and tool.estimatedCreditCost > 20:
            return True
        high_risk = {"high", "critical", "danger"}
        return any((field.riskLevel or "").strip().lower() in high_risk for field in tool.fields)
