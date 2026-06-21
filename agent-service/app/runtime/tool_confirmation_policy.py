from __future__ import annotations

from app.core.intent_router import IntentRouter
from app.core.schemas import RunContext, ToolDescriptor
from app.tools.registry import requested_output_modality, tool_supports_modality


class ToolConfirmationPolicy:
    """Decides whether a product tool may run without explicit user confirmation."""

    def __init__(self, intent_router: IntentRouter | None = None) -> None:
        self.intent_router = intent_router or IntentRouter()

    def should_auto_call(
        self,
        context: RunContext,
        tool: ToolDescriptor,
        *,
        requires_confirmation: bool = False,
    ) -> bool:
        if requires_confirmation:
            return False
        if any(p.toolCode == tool.toolCode and p.autoCallEnabled for p in context.toolPreferences):
            return True
        if tool.autoCallable:
            return True
        return self._is_direct_generation_request(context, tool)

    def _is_direct_generation_request(self, context: RunContext, tool: ToolDescriptor) -> bool:
        modality = requested_output_modality(context.message)
        if not modality or not tool_supports_modality(tool, modality):
            return False
        return self.intent_router._looks_like_tool_request(context.message)
