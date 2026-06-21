"""Function-calling tool surface for unified semantic router."""

from __future__ import annotations

from typing import Any

from app.config import settings
from app.core.schemas import RunContext, ToolDescriptor
from app.runtime.product_tool_call_loop import _ToolAlias, build_tool_definitions
from app.runtime.tool_disclosure import EXPAND_TOOL, build_disclosed_definitions, expand_tool_definition


def build_router_tool_defs(
    tools: list[ToolDescriptor],
    context: RunContext,
    *,
    expanded_codes: set[str] | None = None,
) -> tuple[list[dict[str, Any]], dict[str, _ToolAlias]]:
    if settings.agent_tool_disclosure_enabled and tools:
        defs, aliases = build_disclosed_definitions(
            tools,
            context,
            k=settings.agent_tool_shortlist_k,
            expanded_codes=expanded_codes,
        )
        names = {d.get("function", {}).get("name") for d in defs}
        if EXPAND_TOOL not in names:
            defs = [*defs, expand_tool_definition()]
        return defs, aliases
    return build_tool_definitions(tools, context)
