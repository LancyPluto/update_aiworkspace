from app.core.schemas import RunContext
from app.graphs.universal_agent_graph import UniversalAgentGraph


class LangGraphRuntimeEngine:
    def __init__(self, backend_client, model_client, graph_factory=UniversalAgentGraph) -> None:
        self.backend_client = backend_client
        self.model_client = model_client
        self.graph_factory = graph_factory

    async def run(self, context: RunContext) -> None:
        graph = self.graph_factory(self.backend_client, self.model_client)
        await graph.run(context)
