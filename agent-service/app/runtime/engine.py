from typing import Protocol

from app.core.schemas import RunContext


class AgentRuntimeEngine(Protocol):
    async def run(self, context: RunContext) -> None:
        pass
