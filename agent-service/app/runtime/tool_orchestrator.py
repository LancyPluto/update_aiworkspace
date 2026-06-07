from __future__ import annotations

from collections.abc import Callable
from typing import Any

from app.core.budget_guard import BudgetState, BudgetGuard
from app.core.schemas import RunContext, ToolDescriptor
from app.core.user_attachment_priority import apply_user_selected_attachment_priority
from app.tools.backend_tool import BackendToolBridge
from app.tools.task_dispatch_credit import assert_task_dispatch_credits


class ToolOrchestrator:
    """Orchestrates local guard checks before delegating real execution to the backend bridge.

    Agent Service is not the credit source of truth. It only performs a local run budget guard
    and a preflight dispatch check; actual task creation, credit freezing, settlement, and worker
    execution must stay behind BackendToolBridge -> backend task APIs.
    """

    def __init__(self, tool_bridge: BackendToolBridge, budget_guard_provider: Callable[[], BudgetGuard]) -> None:
        self.tool_bridge = tool_bridge
        self._budget_guard_provider = budget_guard_provider

    async def execute_with_guard(
        self,
        context: RunContext,
        tool: ToolDescriptor,
        budget: BudgetState,
        *,
        arguments: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        prepared = await self._prepare_execution_arguments(context, tool, arguments=arguments)
        missing = missing_execution_arguments(prepared, tool)
        if missing:
            return {"missing_tool_arguments": missing}

        assert_task_dispatch_credits(context, tool)
        self._budget_guard_provider().reserve_tool_call(budget, tool.estimatedCreditCost)
        return await self.tool_bridge.execute_with_args(context, tool, prepared)

    async def _prepare_execution_arguments(
        self,
        context: RunContext,
        tool: ToolDescriptor,
        *,
        arguments: dict[str, Any] | None,
    ) -> dict[str, Any]:
        if arguments:
            prepared = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=True)
            prepared.update({key: value for key, value in arguments.items() if value not in (None, "")})
            return apply_user_selected_attachment_priority(context, tool, prepared)

        base_args = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=False)
        enriched = await self.tool_bridge.enrich_arguments(
            self.tool_bridge.conversation_argument_text(context),
            tool,
            existing_args=base_args,
        )
        prepared = self.tool_bridge.build_arguments(context, tool, apply_placeholder_defaults=True)
        prepared.update({key: value for key, value in enriched.items() if value not in (None, "")})
        return apply_user_selected_attachment_priority(context, tool, prepared)


def missing_execution_arguments(arguments: dict[str, Any], tool: ToolDescriptor) -> list[str]:
    if tool.fields:
        return [
            field.fieldKey
            for field in tool.fields
            if bool(field.executionRequired if field.executionRequired is not None else field.required)
            and (field.fieldKey not in arguments or arguments[field.fieldKey] in (None, ""))
        ]
    required = tool.inputSchema.get("required", [])
    if not isinstance(required, list):
        return []
    return [
        name
        for name in required
        if isinstance(name, str) and (name not in arguments or arguments[name] in (None, ""))
    ]
