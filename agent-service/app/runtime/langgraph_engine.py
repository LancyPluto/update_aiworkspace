from app.core.schemas import RunContext
from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine


class LangGraphRuntimeEngine:
    def __init__(self, backend_client, model_client, graph_factory=DeepAgentsRuntimeEngine) -> None:
        self.backend_client = backend_client
        self.model_client = model_client
        self.graph_factory = graph_factory

    async def run(self, context: RunContext) -> None:
        graph = self.graph_factory(self.backend_client, self.model_client)
        await graph.run(context)

    async def run_confirmed_tool(self, context: RunContext, tool_code: str) -> None:
        graph = self.graph_factory(self.backend_client, self.model_client)
        await graph.run_confirmed_tool(context, tool_code)

    async def debug_route(self, context: RunContext):
        graph = self.graph_factory(self.backend_client, self.model_client)
        return await graph.debug_route(context)
