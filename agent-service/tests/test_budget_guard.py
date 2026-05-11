import pytest

from app.core.budget_guard import BudgetGuard, BudgetState
from app.core.schemas import RunContext, ToolDescriptor
from app.graphs.universal_agent_graph import UniversalAgentGraph
from tests.test_universal_graph import FakeBackend, FakeModel


@pytest.mark.asyncio
async def test_graph_fails_when_selected_tool_exceeds_credit_budget():
    backend = FakeBackend()
    graph = UniversalAgentGraph(backend, FakeModel())
    context = RunContext(
        runId=7,
        sessionId=1,
        userId=1,
        message="please use expensive_tool",
        creditBudget=2,
        availableTools=[
            ToolDescriptor(
                toolCode="expensive_tool",
                toolName="Expensive Tool",
                description="expensive_tool copywriting",
                estimatedCreditCost=5,
                autoCallable=True,
            )
        ],
    )

    await graph.run_confirmed_tool(context, "expensive_tool")

    assert backend.tool_calls == []
    assert backend.completed == []
    assert backend.failed == [(7, "AGENT_RUN_BUDGET_EXCEEDED")]


@pytest.mark.asyncio
async def test_graph_fails_when_model_call_limit_is_exceeded():
    backend = FakeBackend()
    graph = UniversalAgentGraph(backend, FakeModel(), budget_guard=BudgetGuard(max_model_calls=0))
    context = RunContext(runId=8, sessionId=1, userId=1, message="tell me about this platform", creditBudget=20)

    await graph.run(context)

    assert backend.completed == []
    assert backend.failed == [(8, "AGENT_MODEL_CALL_LIMIT")]


def test_budget_state_records_consumed_credit_without_exceeding_budget():
    guard = BudgetGuard(model_call_cost=2)
    state = BudgetState(credit_budget=5)

    guard.reserve_model_call(state)

    assert state.model_calls == 1
    assert state.consumed_credits == 2
