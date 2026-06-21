from __future__ import annotations

from app.core.preferred_tool_bias import apply_preferred_tool_override
from app.core.schemas import RunContext
from app.routing.types import Intent, IntentResult
from app.tools.registry import infer_output_modality, resolve_canonical_tool_code


class PolicyValidator:
    """Hard policy checks after semantic routing — availability, modality, capabilities."""

    def validate(self, context: RunContext, intent: IntentResult) -> IntentResult:
        result = self._apply_capability_policy(context, intent)
        result = apply_preferred_tool_override(context, result)
        if result.intent != Intent.TOOL_USE:
            return result
        return self._validate_tool_use(context, result)

    def _apply_capability_policy(self, context: RunContext, intent: IntentResult) -> IntentResult:
        from app.routing.context_builder import default_capability_flags

        flags = default_capability_flags()
        if intent.intent == Intent.FILE_ANALYSIS and not flags.file_analysis:
            return intent.model_copy(
                update={
                    "intent": Intent.UNSUPPORTED,
                    "reason": f"{intent.reason}; capability_file_analysis_disabled",
                    "decisionSource": "policy_validator",
                }
            )
        if intent.intent == Intent.RAG and not flags.rag:
            return intent.model_copy(
                update={
                    "intent": Intent.UNSUPPORTED,
                    "reason": f"{intent.reason}; capability_rag_disabled",
                    "decisionSource": "policy_validator",
                }
            )
        if intent.intent == Intent.WORKFLOW and not flags.workflow:
            return intent.model_copy(
                update={
                    "intent": Intent.UNSUPPORTED,
                    "reason": f"{intent.reason}; capability_workflow_disabled",
                    "decisionSource": "policy_validator",
                }
            )
        return intent

    def _validate_tool_use(self, context: RunContext, intent: IntentResult) -> IntentResult:
        available = {tool.toolCode for tool in context.availableTools}
        selected = intent.selectedToolCode
        if selected:
            resolved = resolve_canonical_tool_code(selected, context.availableTools)
            if resolved:
                selected = resolved
            if selected not in available:
                return intent.model_copy(
                    update={
                        "intent": Intent.GENERAL_CHAT,
                        "selectedToolCode": None,
                        "confidence": min(intent.confidence, 0.55),
                        "reason": f"{intent.reason}; policy_tool_not_available:{selected}",
                        "decisionSource": "policy_validator",
                    }
                )
            intent = intent.model_copy(update={"selectedToolCode": selected})

        selected_tool = intent.selectedToolCode
        if not selected_tool:
            return intent

        descriptor = next((tool for tool in context.availableTools if tool.toolCode == selected_tool), None)
        if descriptor is None:
            return intent
        return intent
