import pytest

from app.core.schemas import ChatMessage, RunContext, ToolDescriptor
from app.routing.state_guard import StateGuard
from app.routing.types import IntentResult, Intent
from app.runtime.route_readiness import (
    READINESS_BLOCKED,
    READINESS_READY,
    READINESS_WARNING,
    assess_readiness_level,
    build_next_actions,
    estimate_disclosed_tool_count,
    estimate_router_prompt_bytes,
    llm_router_enabled,
)
from app.routing.llm_classifier import LLMClassifier


def _tool(code: str) -> ToolDescriptor:
    return ToolDescriptor(toolCode=code, toolName=code, autoCallable=True)


def test_estimate_disclosed_tool_count_uses_shortlist_k():
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="生成一张海报",
        availableTools=[_tool(f"t{i}") for i in range(10)],
    )
    assert estimate_disclosed_tool_count(context) == 9  # media K=8 + expand_tool


def test_estimate_disclosed_tool_count_without_disclosure():
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="hello",
        availableTools=[_tool("a"), _tool("b")],
    )
    from app.config import settings

    original = settings.agent_tool_disclosure_enabled
    settings.agent_tool_disclosure_enabled = False
    try:
        assert estimate_disclosed_tool_count(context) == 2
    finally:
        settings.agent_tool_disclosure_enabled = original


def test_estimate_router_prompt_bytes_zero_when_legacy_router_disabled_and_unified_off():
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="hello",
        availableTools=[_tool("a")],
        routerSettings=None,
    )
    from app.config import settings

    original_llm = settings.agent_llm_router_enabled
    original_unified = settings.agent_unified_router_enabled
    settings.agent_llm_router_enabled = False
    settings.agent_unified_router_enabled = False
    try:
        guard = StateGuard().classify(context)
        assert estimate_router_prompt_bytes(context, LLMClassifier(None, object()), guard) == 0
    finally:
        settings.agent_llm_router_enabled = original_llm
        settings.agent_unified_router_enabled = original_unified


def test_estimate_router_prompt_bytes_positive_when_enabled():
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="生成一张校园海报",
        availableTools=[_tool("img_tool")],
    )
    guard = StateGuard().classify(context)
    size = estimate_router_prompt_bytes(context, LLMClassifier(None, object()), guard)
    assert size > 500


def test_assess_readiness_level_blocked_without_tools():
    assert (
        assess_readiness_level(
            model_connectivity=True,
            available_tool_count=0,
            router_prompt_bytes=0,
            router_on=True,
        )
        == READINESS_BLOCKED
    )


def test_assess_readiness_level_warning_on_large_router_prompt():
    assert (
        assess_readiness_level(
            model_connectivity=True,
            available_tool_count=5,
            router_prompt_bytes=60_000,
            router_on=True,
        )
        == READINESS_WARNING
    )


def test_assess_readiness_level_ready_by_default():
    assert (
        assess_readiness_level(
            model_connectivity=True,
            available_tool_count=5,
            router_prompt_bytes=10_000,
            router_on=True,
        )
        == READINESS_READY
    )


def test_build_next_actions_suggests_fixes():
    actions = build_next_actions(
        model_connectivity=False,
        available_tool_count=0,
        router_prompt_bytes=80_000,
        router_on=True,
        product_loop_on=False,
        tool_disclosure_on=False,
        intent_result=IntentResult(
            intent=Intent.GENERAL_CHAT,
            confidence=0.4,
            reason="router_fallback_general_chat",
            decisionSource="rule",
        ),
    )
    assert any("baseUrl" in item for item in actions)
    assert any("enable agent tools" in item for item in actions)
    assert any("AGENT_ROUTER_HISTORY_CLIP" in item or "disable LLM router" in item for item in actions)


@pytest.mark.asyncio
async def test_build_route_readiness_mock_provider_connectivity():
    from app.clients.model_client import ModelClient
    from app.config import Settings
    from app.runtime.route_readiness import build_route_readiness

    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="hello",
        availableTools=[_tool("a")],
    )
    client = ModelClient(Settings(model_provider="mock", model_name="mock"))
    result = await build_route_readiness(context, client)
    assert result["modelConnectivity"] is True
    assert result["readiness"] in {READINESS_READY, READINESS_WARNING}
