import pytest

from app.core.agent_decision import AgentDecisionService
from app.core.intent_router import Intent, IntentResult
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
async def test_file_analysis_goes_to_llm_not_infrastructure_short_circuit():
    service = AgentDecisionService()
    context = _context("分析文件")
    context.agentFiles = [AgentFileContext(id=1, originalFilename="a.txt", status="READY")]

    async def llm_router(ctx, guard_intent):
        assert guard_intent.reason == "awaiting_semantic_router"
        return IntentResult(
            intent=Intent.FILE_ANALYSIS,
            confidence=0.92,
            reason="file_analysis_request",
            decisionSource="llm_classifier",
        )

    decision = await service.decide(context, llm_router=llm_router)

    assert decision.intent == Intent.UNSUPPORTED
    assert "capability_file_analysis_disabled" in decision.reason
    assert any(signal["source"] == "llm_classifier" for signal in decision.signals)


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
    assert decision.signals[-1]["source"] == "llm_classifier"


@pytest.mark.asyncio
async def test_llm_router_failure_falls_back_to_rule_tool_use_when_enabled():
    class RuleRouter:
        def classify(self, context):
            return IntentResult(
                intent=Intent.TOOL_USE,
                confidence=0.85,
                selectedToolCode="ofox_gpt_image2",
                candidateToolCodes=["ofox_gpt_image2"],
                reason="ranked_tool_match",
            )

    service = AgentDecisionService(intent_router=RuleRouter())

    async def llm_router(context, rule_intent):
        return None

    decision = await service.decide(
        _context("帮我生成一张图片"),
        llm_router=llm_router,
    )

    assert decision.intent == Intent.TOOL_USE
    assert decision.selectedToolCode == "ofox_gpt_image2"
    assert decision.reason == "router_fallback_to_rules:ranked_tool_match"


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


@pytest.mark.asyncio
async def test_preferred_tool_overrides_rule_structured_tool_selection():
    class RuleRouter:
        def classify(self, context):
            return IntentResult(
                intent=Intent.TOOL_USE,
                confidence=0.85,
                selectedToolCode="kling-image-generation",
                candidateToolCodes=["kling-image-generation", "gpt_image2"],
                reason="structured_tool_arguments",
            )

    context = RunContext(
        runId=272,
        sessionId=1,
        userId=1,
        message="别用可灵，用GPT",
        preferredToolCode="gpt_image2",
        availableTools=[
            ToolDescriptor(
                toolCode="kling-image-generation",
                toolName="可灵生图 V3",
                description="图片生成工具",
                autoCallable=True,
            ),
            ToolDescriptor(
                toolCode="gpt_image2",
                toolName="GPT-image2",
                description="图片生成工具",
                autoCallable=True,
            ),
        ],
    )
    service = AgentDecisionService(intent_router=RuleRouter())

    async def llm_router(ctx, rule_intent):
        raise AssertionError("structured rule path should short-circuit after preferred override")

    decision = await service.decide(context, llm_router=llm_router)

    assert decision.intent == Intent.TOOL_USE
    assert decision.selectedToolCode == "gpt_image2"
    assert decision.candidateToolCodes[0] == "gpt_image2"
    assert decision.reason == "preferred_tool_selected"
    assert decision.signals[-1]["source"] == "preferred_tool"


@pytest.mark.asyncio
async def test_preferred_tool_generation_request_does_not_fall_back_to_chat_when_router_returns_none():
    context = RunContext(
        runId=273,
        sessionId=1,
        userId=1,
        message="生成一张电影海报",
        preferredToolCode="gpt_image2",
        availableTools=[
            ToolDescriptor(
                toolCode="gpt_image2",
                toolName="GPT-image2",
                description="图片生成工具",
                autoCallable=True,
            ),
        ],
    )
    service = AgentDecisionService()

    async def llm_router(ctx, rule_intent):
        raise AssertionError("explicit preferred tool request should not need LLM router")

    decision = await service.decide(context, llm_router=llm_router)

    assert decision.intent == Intent.TOOL_USE
    assert decision.selectedToolCode == "gpt_image2"
    assert decision.reason == "preferred_tool_selected"
