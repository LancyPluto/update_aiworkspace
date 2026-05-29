import pytest

from app.core.agent_decision import AgentDecisionService
from app.core.intent_router import Intent
from app.core.schemas import RunContext, ToolDescriptor


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
            ToolDescriptor(
                toolCode="text_deepseek",
                toolName="文本生成-DeepSeek-V4-flash",
                description="文本生成工具",
                autoCallable=True,
            ),
        ],
        creditBudget=20,
    )


def _hard_rule(intent) -> bool:
    return intent.reason in {
        "follow_up_or_meta_question",
        "session_recap_question",
        "short_general_chat",
        "empty_request",
    }


@pytest.mark.asyncio
async def test_follow_up_meta_question_does_not_trigger_image_tool():
    service = AgentDecisionService()

    async def llm_router(context, rule_intent):
        raise AssertionError("conversation guard should avoid the LLM router")

    decision = await service.decide(
        _context("你猜猜我为什么让你生成科比的图片"),
        hard_rule=_hard_rule,
        llm_router=llm_router,
    )

    assert decision.intent == Intent.GENERAL_CHAT
    assert decision.selectedToolCode is None
    assert decision.reason == "follow_up_or_meta_question"
    assert decision.signals[0]["source"] == "conversation_guard"


@pytest.mark.asyncio
async def test_direct_image_generation_still_routes_to_tool():
    service = AgentDecisionService()

    decision = await service.decide(
        _context("帮我生成一张科比穿湖人球衣的图片"),
        hard_rule=_hard_rule,
        llm_router=None,
    )

    assert decision.intent == Intent.TOOL_USE
    assert decision.selectedToolCode == "ofox_gpt_image2"


@pytest.mark.asyncio
async def test_llm_tool_call_is_blocked_when_message_is_follow_up():
    service = AgentDecisionService()

    async def llm_router(context, rule_intent):
        return rule_intent.model_copy(
            update={
                "intent": Intent.TOOL_USE,
                "confidence": 0.99,
                "selectedToolCode": "ofox_gpt_image2",
                "candidateToolCodes": ["ofox_gpt_image2"],
                "decisionSource": "llm_router",
                "reason": "incorrect_tool_call",
            }
        )

    decision = await service.decide(
        _context("刚才那张图你觉得像不像科比本人？"),
        hard_rule=lambda intent: False,
        llm_router=llm_router,
    )

    assert decision.intent == Intent.GENERAL_CHAT
    assert decision.selectedToolCode is None
    assert decision.reason in {"follow_up_or_meta_question", "llm_tool_call_blocked_for_follow_up"}
