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
                description="图片生成工具，支持文生图、写真、海报、插画",
                autoCallable=True,
                hints={"modality": "image_generation"},
            ),
            ToolDescriptor(
                toolCode="kling_image_to_video",
                toolName="可灵生视频V2.6",
                description="视频生成工具，支持文生视频、图生视频、短视频",
                autoCallable=True,
                hints={"modality": "video_generation"},
            ),
            ToolDescriptor(
                toolCode="text_deepseek",
                toolName="文本生成-DeepSeek-V4-flash",
                description="文本生成工具，支持文案、标题、总结和普通问答",
                autoCallable=True,
                hints={"modality": "text_generation"},
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


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("message", "expected_intent", "expected_tool", "expected_reason"),
    [
        ("生成一张石原里美在漫展穿火影忍者晓袍的写真", Intent.TOOL_USE, "ofox_gpt_image2", None),
        ("帮我做一张商品主图，白底，高级感", Intent.TOOL_USE, "ofox_gpt_image2", None),
        ("来一张 18 年一家人除夕夜合影的老照片", Intent.TOOL_USE, "ofox_gpt_image2", None),
        ("生成一段科比投篮的短视频", Intent.TOOL_USE, "kling_image_to_video", None),
        ("帮我做一个 5 秒产品展示视频", Intent.TOOL_USE, "kling_image_to_video", None),
        ("把这个画面生成视频", Intent.TOOL_USE, "kling_image_to_video", None),
        ("你好", Intent.GENERAL_CHAT, None, "short_general_chat"),
        ("你是谁", Intent.GENERAL_CHAT, None, "short_general_chat"),
        ("帮我总结一下刚才的对话", Intent.GENERAL_CHAT, None, "session_recap_question"),
        ("你刚刚用什么生成的？", Intent.GENERAL_CHAT, None, "follow_up_or_meta_question"),
        ("刚才那张图是哪个工具做的？", Intent.GENERAL_CHAT, None, "follow_up_or_meta_question"),
        ("你猜猜我为什么让你生成科比的图片", Intent.GENERAL_CHAT, None, "follow_up_or_meta_question"),
        ("你觉得上面那张图怎么样？", Intent.GENERAL_CHAT, None, "follow_up_or_meta_question"),
        ("分析一下之前那张图片为什么不像本人", Intent.GENERAL_CHAT, None, "follow_up_or_meta_question"),
        ("刚才那张照片背景可以怎么优化？", Intent.GENERAL_CHAT, None, "follow_up_or_meta_question"),
        ("为什么叫你生成这张图你知道吗", Intent.GENERAL_CHAT, None, "follow_up_or_meta_question"),
        ("给我一张赛博朋克城市海报", Intent.TOOL_USE, "ofox_gpt_image2", None),
        ("拍摄一张咖啡杯近景产品照", Intent.TOOL_USE, "ofox_gpt_image2", None),
        ("做一段动漫风转场视频", Intent.TOOL_USE, "kling_image_to_video", None),
        ("生成一张图，然后不要问我比例", Intent.TOOL_USE, "ofox_gpt_image2", None),
        ("我想要一个宣传片成片", Intent.TOOL_USE, "kling_image_to_video", None),
        ("图片生成失败一般是什么原因？", Intent.GENERAL_CHAT, None, None),
        ("你刚才为什么没有直接调用工具？", Intent.GENERAL_CHAT, None, "follow_up_or_meta_question"),
        ("上一轮那个结果可以继续改吗？", Intent.GENERAL_CHAT, None, "follow_up_or_meta_question"),
    ],
)
async def test_routing_eval_set(message, expected_intent, expected_tool, expected_reason):
    service = AgentDecisionService()

    decision = await service.decide(
        _context(message),
        hard_rule=_hard_rule,
        llm_router=None,
    )

    assert decision.intent == expected_intent
    assert decision.selectedToolCode == expected_tool
    if expected_reason:
        assert decision.reason == expected_reason
    assert decision.signals
