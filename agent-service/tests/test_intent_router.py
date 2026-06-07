from app.core.intent_router import Intent, IntentResult, IntentRouter
from app.core.preferred_tool_bias import (
    apply_preferred_tool_override,
    inject_preferred_tool_hint,
    message_suggests_tool_use,
)
from app.core.schemas import AgentFileContext, ChatMessage, RunContext, ToolDescriptor


def context(message: str) -> RunContext:
    return RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message=message,
        availableTools=[
            ToolDescriptor(
                toolCode="xiaohongshu_copywriting",
                toolName="Xiaohongshu",
                description="小红书 种草 笔记",
                autoCallable=True,
            ),
        ],
    )


def test_routes_vague_request_to_clarification():
    result = IntentRouter().classify(context("帮我做一下"))

    assert result.intent == Intent.NEEDS_CLARIFICATION


def test_routes_file_analysis_when_files_ready():
    ctx = context("总结文件")
    ctx.agentFiles = [AgentFileContext(id=1, originalFilename="a.txt", status="READY")]
    result = IntentRouter().classify(ctx)

    assert result.intent == Intent.FILE_ANALYSIS
    assert result.reason == "file_analysis_request"


def test_ready_image_reference_generation_does_not_force_file_analysis():
    ctx = context("按这张参考图生成 Suno 吉祥物")
    ctx.agentFiles = [
        AgentFileContext(
            id=1,
            originalFilename="ref.jpg",
            contentType="image/jpeg",
            status="READY",
            downloadUrl="/api/v1/agent/sessions/1/files/1/content",
        )
    ]

    result = IntentRouter().classify(ctx)

    assert result.intent != Intent.FILE_ANALYSIS


def test_ready_image_explicit_analysis_routes_file_analysis():
    ctx = context("分析这张图并总结附件内容")
    ctx.agentFiles = [
        AgentFileContext(
            id=1,
            originalFilename="ref.png",
            contentType="image/png",
            status="READY",
        )
    ]

    result = IntentRouter().classify(ctx)

    assert result.intent == Intent.FILE_ANALYSIS


def test_document_summary_routes_file_analysis():
    ctx = context("总结文件")
    ctx.agentFiles = [
        AgentFileContext(
            id=2,
            originalFilename="brief.pdf",
            contentType="application/pdf",
            status="READY",
        )
    ]

    result = IntentRouter().classify(ctx)

    assert result.intent == Intent.FILE_ANALYSIS


def test_routes_file_keyword_to_file_analysis():
    result = IntentRouter().classify(context("分析我上传的文件并总结"))

    assert result.intent == Intent.FILE_ANALYSIS


def test_heuristic_tool_match_deferred_to_llm_router():
    result = IntentRouter().classify(context("帮我写一篇小红书种草笔记"))

    assert result.intent == Intent.GENERAL_CHAT
    assert result.selectedToolCode is None


def test_continues_tool_use_after_field_guidance_without_xiaohongshu_keywords():
    history = [
        ChatMessage(role="USER", content="帮我写一篇小红书种草笔记"),
        ChatMessage(
            role="ASSISTANT",
            content='如果想使用「AI 小红书文案生成器」，请在同一条或下一条消息里按下面补充：\n\n• 产品/服务名称：例如：五一肩颈护理套餐',
        ),
    ]
    ctx = RunContext(
        runId=2,
        sessionId=1,
        userId=1,
        message="你全部按照你给的例子来输入，把输出结果给我",
        history=history,
        availableTools=[
            ToolDescriptor(
                toolCode="xiaohongshu_copywriting",
                toolName="AI 小红书文案生成器",
                description="小红书 种草",
                autoCallable=True,
            ),
        ],
    )
    result = IntentRouter().classify(ctx)
    assert result.intent == Intent.TOOL_USE
    assert result.selectedToolCode == "xiaohongshu_copywriting"
    assert result.reason == "continuing_pending_tool_prompt"


def test_routes_structured_params_without_action_keywords():
    message = (
        "产品/服务名称：五一肩颈护理套餐\n"
        "• 目标用户：年轻女性、宝妈\n"
        "• 文案风格：种草\n"
        "• 核心卖点：价格划算、效果明显"
    )
    ctx = RunContext(
        runId=3,
        sessionId=1,
        userId=1,
        message=message,
        availableTools=[
            ToolDescriptor(
                toolCode="xiaohongshu_copywriting",
                toolName="AI 小红书文案生成器",
                description="小红书 种草 笔记",
                autoCallable=True,
            ),
        ],
    )
    result = IntentRouter().classify(ctx)
    assert result.intent == Intent.TOOL_USE
    assert result.selectedToolCode == "xiaohongshu_copywriting"
    assert result.reason == "structured_tool_arguments"


def test_preferred_tool_hint_for_generation_message():
    ctx = RunContext(
        runId=4,
        sessionId=1,
        userId=1,
        message="生成一张电影海报",
        preferredToolCode="ofox_gpt_image2",
        availableTools=[
            ToolDescriptor(
                toolCode="ofox_gpt_image2",
                toolName="GPT-image2",
                description="image generation",
                autoCallable=True,
            ),
            ToolDescriptor(
                toolCode="kling-image-v21",
                toolName="Kling Image",
                description="image generation",
                autoCallable=True,
            ),
        ],
    )
    result = IntentRouter().classify(ctx)
    assert result.intent == Intent.GENERAL_CHAT
    assert result.selectedToolCode == "ofox_gpt_image2"
    assert "ofox_gpt_image2" in result.candidateToolCodes


def test_preferred_tool_ignored_for_greeting():
    ctx = RunContext(
        runId=5,
        sessionId=1,
        userId=1,
        message="你好",
        preferredToolCode="ofox_gpt_image2",
        availableTools=[
            ToolDescriptor(
                toolCode="ofox_gpt_image2",
                toolName="GPT-image2",
                description="image generation",
                autoCallable=True,
            ),
        ],
    )
    result = IntentRouter().classify(ctx)
    assert result.intent == Intent.GENERAL_CHAT
    assert result.selectedToolCode is None


def test_apply_preferred_tool_override_replaces_llm_choice():
    ctx = RunContext(
        runId=6,
        sessionId=1,
        userId=1,
        message="生成一张海报",
        preferredToolCode="ofox_gpt_image2",
        availableTools=[
            ToolDescriptor(
                toolCode="ofox_gpt_image2",
                toolName="GPT-image2",
                description="image generation",
                autoCallable=True,
            ),
            ToolDescriptor(
                toolCode="kling-image-v21",
                toolName="Kling Image",
                description="image generation",
                autoCallable=True,
            ),
        ],
    )
    llm_result = IntentResult(
        intent=Intent.TOOL_USE,
        confidence=0.9,
        selectedToolCode="kling-image-v21",
        candidateToolCodes=["kling-image-v21"],
        reason="llm_router",
    )
    result = apply_preferred_tool_override(ctx, llm_result)
    assert result.selectedToolCode == "ofox_gpt_image2"


def test_message_suggests_tool_use():
    assert message_suggests_tool_use("生成一张海报") is True
    assert message_suggests_tool_use("你好") is False
