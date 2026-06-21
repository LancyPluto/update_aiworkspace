"""Description-driven tool recall — embedding index with lexical fallback."""



from __future__ import annotations



from app.config import settings

from app.core.schemas import RunContext

from app.routing.tool_index import ToolEmbeddingIndex, build_recall_query, recall_tool_codes_by_embedding

from app.tools.registry import ToolRegistry



_MODEL_CLIENT: object | None = None





def set_recall_model_client(model_client: object | None) -> None:

    global _MODEL_CLIENT

    _MODEL_CLIENT = model_client





def recall_tool_codes(context: RunContext, *, limit: int | None = None) -> list[str]:

    """Rank tools for router candidate narrowing (sync lexical path)."""

    registry = ToolRegistry(context)

    message = (context.message or "").strip()

    if not message:

        return [tool.toolCode for tool in registry.list_tools()[:_limit(limit)]]



    index = ToolEmbeddingIndex.from_context(context)

    query = build_recall_query(context)

    ranked = index.rank(query, limit=_limit(limit))

    if ranked:

        return ranked



    return [tool.toolCode for tool in registry.list_tools()[:_limit(limit)]]





async def recall_tool_codes_async(context: RunContext, *, limit: int | None = None) -> list[str]:

    """Async recall that can use remote embedding API when available."""

    registry = ToolRegistry(context)

    message = (context.message or "").strip()

    if not message:

        return [tool.toolCode for tool in registry.list_tools()[:_limit(limit)]]



    ranked = await recall_tool_codes_by_embedding(context, _MODEL_CLIENT, limit=_limit(limit))

    if ranked:

        return ranked

    return recall_tool_codes(context, limit=limit)





def _limit(limit: int | None) -> int:

    if limit is not None:

        return max(1, limit)

    return max(1, int(getattr(settings, "agent_router_candidate_limit", 15)))
