from app.core.schemas import RunContext
from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine


class LegacyDispatcherEngine:
    """Pre-refactor single-turn router + single-tool dispatcher.

    Named honestly: this engine does NOT use a LangGraph state graph. It
    classifies intent once and dispatches to at most one product tool. It is
    retained as the rollback path while the multi-step graph engine rolls out.

    A fresh underlying engine is created per call so request-scoped runtime
    settings never mutate a shared instance.
    """

    def __init__(self, backend_client, model_client, engine_factory=DeepAgentsRuntimeEngine) -> None:
        self.backend_client = backend_client
        self.model_client = model_client
        self.engine_factory = engine_factory

    async def run(self, context: RunContext) -> None:
        await self.engine_factory(self.backend_client, self.model_client).run(context)

    async def run_confirmed_tool(self, context: RunContext, tool_code: str) -> None:
        await self.engine_factory(self.backend_client, self.model_client).run_confirmed_tool(context, tool_code)

    async def debug_route(self, context: RunContext):
        return await self.engine_factory(self.backend_client, self.model_client).debug_route(context)
