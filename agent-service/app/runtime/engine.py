from typing import Protocol

from app.core.schemas import RunContext


class AgentRuntimeEngine(Protocol):
    async def run(self, context: RunContext) -> None:
        pass

    async def run_confirmed_tool(self, context: RunContext, tool_code: str) -> None:
        pass
