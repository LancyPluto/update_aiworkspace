from app.core.budget_guard import BudgetExceeded
from app.core.schemas import RunContext, ToolDescriptor


def assert_task_dispatch_credits(context: RunContext, tool: ToolDescriptor) -> None:
    """Reject tool dispatch early when the backend run budget cannot cover the tool."""
    credit_budget = max(0, int(context.creditBudget or 0))
    estimated_cost = max(0, int(tool.estimatedCreditCost or 0))
    if credit_budget > 0 and estimated_cost > credit_budget:
        raise BudgetExceeded("AGENT_RUN_BUDGET_EXCEEDED", "Agent credit budget exceeded")
