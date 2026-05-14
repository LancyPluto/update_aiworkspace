from app.core.intent_router import Intent, IntentRouter
from app.core.schemas import RunContext, ToolDescriptor


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
            ToolDescriptor(
                toolCode="product_title_optimizer",
                toolName="Title",
                description="商品标题 标题优化",
                autoCallable=True,
            ),
        ],
    )


def test_routes_xiaohongshu_to_tool_use():
    result = IntentRouter().classify(context("帮我写一篇小红书种草笔记"))

    assert result.intent == Intent.TOOL_USE
    assert result.selectedToolCode == "xiaohongshu_copywriting"


def test_routes_product_title_to_tool_use():
    result = IntentRouter().classify(context("优化一下这个商品标题"))

    assert result.intent == Intent.TOOL_USE
    assert result.selectedToolCode == "product_title_optimizer"


def test_routes_vague_request_to_clarification():
    result = IntentRouter().classify(context("帮我做一下"))

    assert result.intent == Intent.NEEDS_CLARIFICATION


def test_routes_short_chat_to_general_chat():
    result = IntentRouter().classify(context("你是谁"))

    assert result.intent == Intent.GENERAL_CHAT


def test_routes_file_analysis_to_file_analysis():
    result = IntentRouter().classify(context("分析我上传的文件并总结"))

    assert result.intent == Intent.FILE_ANALYSIS


def test_routes_ambiguous_tool_request_to_clarification():
    ambiguous_context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="帮我优化这个商品内容",
        availableTools=[
            ToolDescriptor(
                toolCode="product_title_optimizer",
                toolName="标题优化",
                description="商品 标题 优化",
                autoCallable=True,
            ),
            ToolDescriptor(
                toolCode="wechat_longform_generator",
                toolName="长文生成",
                description="商品 长文 内容",
                autoCallable=True,
            ),
        ],
    )

    result = IntentRouter().classify(ambiguous_context)

    assert result.intent == Intent.NEEDS_CLARIFICATION
    assert set(result.candidateToolCodes) == {"product_title_optimizer", "wechat_longform_generator"}


def test_routes_normal_question_to_general_chat():
    result = IntentRouter().classify(context("你能介绍一下这个平台怎么用吗"))

    assert result.intent == Intent.GENERAL_CHAT
