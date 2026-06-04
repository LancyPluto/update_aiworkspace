from app.core.intent_router import Intent, IntentRouter
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
    assert result.reason == "ready_file_context_available"


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
