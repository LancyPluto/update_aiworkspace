"""Dry-run readiness assessment for admin route-debug (OpenHarness --dry-run style)."""



from __future__ import annotations



from typing import Any



from app.config import settings

from app.core.schemas import ChatMessage, RunContext


from app.routing.types import IntentResult

from app.routing.v2.routing_context import build_routing_messages

from app.routing.v2.thread_state import build_thread_state


from app.runtime.tool_disclosure import select_relevant_tools

from app.tools.registry import requested_output_modality



READINESS_READY = "ready"

READINESS_WARNING = "warning"

READINESS_BLOCKED = "blocked"



ROUTER_PROMPT_WARNING_BYTES = 50_000

ROUTER_PROMPT_BLOCKED_BYTES = 200_000





def semantic_router_enabled(context: RunContext) -> bool:
    if context.routerSettings is None:
        return True
    return bool(context.routerSettings.enabled)





def product_tool_loop_enabled(context: RunContext) -> bool:

    runtime = context.runtimeSettings

    if runtime is not None and runtime.productToolLoopEnabled is not None:

        return bool(runtime.productToolLoopEnabled)

    return bool(settings.agent_product_tool_loop_enabled)





def estimate_disclosed_tool_count(context: RunContext) -> int:

    tools = list(context.availableTools or [])

    if not tools:

        return 0

    if not settings.agent_tool_disclosure_enabled:

        return len(tools)

    k = settings.agent_tool_shortlist_k

    if requested_output_modality(context.message or "") in {"image", "video", "audio"}:

        k = settings.agent_tool_shortlist_k_media

    shortlist = select_relevant_tools(context, tools, k)

    return len(shortlist) + 1  # expand_tool meta-tool





def estimate_router_prompt_bytes(

    context: RunContext,

    *,

    workspace_memory_context: str = "",

) -> int:
    if not context.availableTools:
        return 0
    messages = build_routing_messages(
        context,
        thread_state=build_thread_state(context),
        workspace_memory_context=workspace_memory_context,
    )
    return sum(len((m.content or "").encode("utf-8")) for m in messages)





async def probe_model_connectivity(model_client) -> bool:

    provider = str(getattr(model_client.settings, "model_provider", "") or "").strip().lower()

    if provider == "mock":

        return True

    try:

        await model_client.chat([ChatMessage(role="user", content="ping")])

        return True

    except Exception:

        return False





def build_next_actions(

    *,

    model_connectivity: bool,

    available_tool_count: int,

    router_prompt_bytes: int,

    router_on: bool,

    product_loop_on: bool,

    tool_disclosure_on: bool,

    intent_result: IntentResult | None,

) -> list[str]:

    actions: list[str] = []

    if not model_connectivity:

        actions.append("fix model baseUrl / API key / network connectivity")

    if available_tool_count <= 0:

        actions.append("enable agent tools for this user or workspace")

    if router_on and router_prompt_bytes >= ROUTER_PROMPT_WARNING_BYTES:

        actions.append("lower AGENT_ROUTER_HISTORY_CLIP or AGENT_TOOL_SHORTLIST_K")

    if not product_loop_on:

        actions.append("enable productToolLoop for tool-calling fallback")

    if not tool_disclosure_on and available_tool_count > 8:

        actions.append("enable tool disclosure to shrink function-calling schema")

    if intent_result is not None and intent_result.reason == "router_fallback_general_chat":

        actions.append("check router.fallback failureClass and enable product tool loop")

    return actions[:5]





def assess_readiness_level(

    *,

    model_connectivity: bool,

    available_tool_count: int,

    router_prompt_bytes: int,

    router_on: bool,

) -> str:

    if not model_connectivity or available_tool_count <= 0:

        return READINESS_BLOCKED

    if router_on and router_prompt_bytes >= ROUTER_PROMPT_BLOCKED_BYTES:

        return READINESS_BLOCKED

    if router_on and router_prompt_bytes >= ROUTER_PROMPT_WARNING_BYTES:

        return READINESS_WARNING

    return READINESS_READY





async def build_route_readiness(

    context: RunContext,

    model_client,

    *,

    guard_intent: IntentResult | None = None,

    workspace_memory_context: str = "",

    intent_result: IntentResult | None = None,

) -> dict[str, Any]:

    available_tool_count = len(context.availableTools or [])

    disclosed_tool_count = estimate_disclosed_tool_count(context)

    router_prompt_bytes = estimate_router_prompt_bytes(

        context,

        workspace_memory_context=workspace_memory_context,

    )

    router_on = semantic_router_enabled(context)

    product_loop_on = product_tool_loop_enabled(context)

    tool_disclosure_on = bool(settings.agent_tool_disclosure_enabled)

    model_connectivity = await probe_model_connectivity(model_client)

    readiness = assess_readiness_level(

        model_connectivity=model_connectivity,

        available_tool_count=available_tool_count,

        router_prompt_bytes=router_prompt_bytes,

        router_on=router_on,

    )

    next_actions = build_next_actions(

        model_connectivity=model_connectivity,

        available_tool_count=available_tool_count,

        router_prompt_bytes=router_prompt_bytes,

        router_on=router_on,

        product_loop_on=product_loop_on,

        tool_disclosure_on=tool_disclosure_on,

        intent_result=intent_result,

    )

    return {

        "readiness": readiness,

        "modelConnectivity": model_connectivity,

        "availableToolCount": available_tool_count,

        "disclosedToolCount": disclosed_tool_count,

        "estimatedRouterPromptBytes": router_prompt_bytes,

        "nextActions": next_actions,

        "llmRouterEnabled": router_on,

        "unifiedRouterEnabled": router_on,

        "productToolLoopEnabled": product_loop_on,

        "toolDisclosureEnabled": tool_disclosure_on,

    }
