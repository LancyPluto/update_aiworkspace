from app.core.schemas import RunContext, ToolDescriptor
from app.tools.backend_tool import ToolExecutionError


def assert_task_dispatch_credits(context: RunContext, tool: ToolDescriptor) -> None:
    """Block tool dispatch when the run budget cannot cover the tool estimate."""
    required = max(0, int(tool.estimatedCreditCost or 0))
    if required <= 0:
        return
    budget = max(0, int(context.creditBudget or 0))
    if budget > 0 and required > budget:
        raise ToolExecutionError(
            "可用算力不足，无法完成本次工具调用",
            error_code="CREDIT_NOT_ENOUGH",
        )
