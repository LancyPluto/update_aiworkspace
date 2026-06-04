import pytest

from app.core.agent_decision import AgentDecisionService
from app.core.intent_router import Intent
from app.core.schemas import AgentFileContext, RunContext, ToolDescriptor


def _context(message: str) -> RunContext:
    return RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message=message,
        availableTools=[
            ToolDescriptor(
                toolCode="ofox_gpt_image2",
                toolName="GPT-image2.0",
                description="图片生成工具",
                autoCallable=True,
            ),
        ],
        creditBudget=20,
    )


@pytest.mark.asyncio
async def test_infrastructure_file_analysis_short_circuits_without_llm():
    service = AgentDecisionService()
    context = _context("分析文件")
    context.agentFiles = [AgentFileContext(id=1, originalFilename="a.txt", status="READY")]

    async def llm_router(ctx, rule_intent):
        raise AssertionError("infrastructure path should not call LLM router")
    decision = await service.decide(context, llm_router=llm_router)

    assert decision.intent == Intent.FILE_ANALYSIS
    assert decision.reason == "ready_file_context_available"


@pytest.mark.asyncio
async def test_llm_router_selects_tool():
    service = AgentDecisionService()

    async def llm_router(context, rule_intent):
        return rule_intent.model_copy(
            update={
                "intent": Intent.TOOL_USE,
                "confidence": 0.95,
                "selectedToolCode": "ofox_gpt_image2",
                "candidateToolCodes": ["ofox_gpt_image2"],
                "decisionSource": "llm_router",
                "reason": "image_request",
            }
        )

    decision = await service.decide(
        _context("帮我生成一张科比穿湖人球衣的图片"),
        llm_router=llm_router,
    )

    assert decision.intent == Intent.TOOL_USE
    assert decision.selectedToolCode == "ofox_gpt_image2"
    assert decision.signals[-1]["source"] == "llm_router"


@pytest.mark.asyncio
async def test_llm_router_failure_falls_back_to_general_chat():
    service = AgentDecisionService()

    async def llm_router(context, rule_intent):
        return None

    decision = await service.decide(
        _context("帮我生成一张图片"),
        llm_router=llm_router,
    )

    assert decision.intent == Intent.GENERAL_CHAT
    assert decision.reason == "router_fallback_general_chat"
    assert decision.selectedToolCode is None
