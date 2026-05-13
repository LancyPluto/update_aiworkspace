from dataclasses import dataclass


class BudgetExceeded(RuntimeError):
    def __init__(self, error_code: str, message: str) -> None:
        super().__init__(message)
        self.error_code = error_code
        self.message = message


@dataclass(slots=True)
class BudgetState:
    credit_budget: int = 0
    consumed_credits: int = 0
    model_calls: int = 0
    tool_calls: int = 0


class BudgetGuard:
    def __init__(
        self,
        *,
        max_model_calls: int = 5,
        max_tool_calls: int = 3,
        model_call_cost: int = 1,
        default_consumed_credits: int = 1,
    ) -> None:
        self.max_model_calls = max_model_calls
        self.max_tool_calls = max_tool_calls
        self.model_call_cost = max(0, model_call_cost)
        self.default_consumed_credits = max(0, default_consumed_credits)

    def reserve_model_call(self, state: BudgetState) -> None:
        if state.model_calls >= self.max_model_calls:
            raise BudgetExceeded("AGENT_MODEL_CALL_LIMIT", "Agent model call limit exceeded")
        self._ensure_credit(state, self.model_call_cost)
        state.model_calls += 1
        state.consumed_credits += self.model_call_cost

    def reserve_tool_call(self, state: BudgetState, estimated_credit_cost: int) -> None:
        if state.tool_calls >= self.max_tool_calls:
            raise BudgetExceeded("AGENT_TOOL_CALL_LIMIT", "Agent tool call limit exceeded")
        cost = max(0, estimated_credit_cost)
        self._ensure_credit(state, cost)
        state.tool_calls += 1
        state.consumed_credits += cost

    def consumed_for_completion(self, state: BudgetState) -> int:
        if state.consumed_credits > 0:
            return state.consumed_credits
        return self.default_consumed_credits

    @staticmethod
    def _ensure_credit(state: BudgetState, amount: int) -> None:
        if state.credit_budget <= 0 or amount <= 0:
            return
        if state.consumed_credits + amount > state.credit_budget:
            raise BudgetExceeded("AGENT_RUN_BUDGET_EXCEEDED", "Agent credit budget exceeded")
