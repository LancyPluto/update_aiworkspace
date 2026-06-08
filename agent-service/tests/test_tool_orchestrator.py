import pytest

from app.core.budget_guard import BudgetGuard, BudgetState
from app.core.schemas import RunContext, ToolDescriptor
from app.runtime.tool_orchestrator import ToolOrchestrator


class FakeBridge:
    def __init__(self):
        self.executed = False

    def build_arguments(self, context, tool, *, apply_placeholder_defaults=True):
        return {"userRequest": context.message}

    def conversation_argument_text(self, context):
        return context.message

    async def enrich_arguments(self, message, tool, existing_args=None):
        return existing_args or {}

    async def execute_with_args(self, context, tool, arguments):
        self.executed = True
        return {"success": True}


@pytest.mark.asyncio
async def test_tool_orchestrator_missing_execution_arguments_does_not_dispatch_bridge():
    bridge = FakeBridge()
    guard = BudgetGuard(max_tool_calls=1)
    orchestrator = ToolOrchestrator(bridge, lambda: guard)  # type: ignore[arg-type]
    context = RunContext(runId=1, sessionId=1, userId=1, message="执行工具", creditBudget=10)
    tool = ToolDescriptor(
        toolCode="requires_account",
        toolName="高风险工具",
        estimatedCreditCost=1,
        inputSchema={
            "type": "object",
            "required": ["accountId"],
            "properties": {"accountId": {"type": "string"}},
        },
    )

    result = await orchestrator.execute_with_guard(context, tool, BudgetState(credit_budget=10))

    assert result == {"missing_tool_arguments": ["accountId"]}
    assert bridge.executed is False


@pytest.mark.asyncio
async def test_tool_orchestrator_allows_tool_cost_above_agent_run_budget():
    bridge = FakeBridge()
    guard = BudgetGuard(max_tool_calls=1)
    orchestrator = ToolOrchestrator(bridge, lambda: guard)  # type: ignore[arg-type]
    context = RunContext(runId=1, sessionId=1, userId=1, message="生成一段音乐", creditBudget=20)
    tool = ToolDescriptor(
        toolCode="suno",
        toolName="Suno",
        estimatedCreditCost=55,
        inputSchema={"type": "object", "properties": {}},
    )
    budget = BudgetState(credit_budget=20)

    result = await orchestrator.execute_with_guard(context, tool, budget)

    assert result == {"success": True}
    assert bridge.executed is True
    assert budget.tool_calls == 1
    assert budget.consumed_credits == 0
