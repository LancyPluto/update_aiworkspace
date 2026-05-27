from app.core.intent_router import Intent, IntentRouter
from app.core.schemas import ChatMessage, RunContext, ToolDescriptor


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


def test_routes_session_recap_to_general_chat_not_tool():
    result = IntentRouter().classify(context("你之前帮我做了什么"))

    assert result.intent == Intent.GENERAL_CHAT
    assert result.reason == "session_recap_question"
    assert result.selectedToolCode is None


def test_session_recap_does_not_trigger_tool_action_keywords():
    result = IntentRouter().classify(context("你刚刚帮我完成了那些任务"))

    assert result.intent == Intent.GENERAL_CHAT
    assert result.reason == "session_recap_question"


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


def test_routes_repeat_xiaohongshu_request_to_tool_use():
    result = IntentRouter().classify(context("再给我一个小红书文案"))

    assert result.intent == Intent.TOOL_USE
    assert result.selectedToolCode == "xiaohongshu_copywriting"


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


def test_continues_after_weak_tool_clarification():
    history = [
        ChatMessage(role="USER", content="小红书"),
        ChatMessage(
            role="ASSISTANT",
            content="看起来你想使用「AI 小红书文案生成器」工具。\n这个工具需要补充以下信息：「产品/服务名称」。",
        ),
    ]
    ctx = RunContext(
        runId=4,
        sessionId=1,
        userId=1,
        message="产品/服务名称：五一肩颈护理套餐\n目标用户：年轻女性\n文案风格：种草\n核心卖点：价格划算",
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


def test_routes_image_generation_request_to_non_auto_callable_tool():
    ctx = RunContext(
        runId=5,
        sessionId=1,
        userId=1,
        message="我想要生成石原里美在漫展穿着火影忍者的晓袍的写真",
        availableTools=[
            ToolDescriptor(
                toolCode="kling_image_v21",
                toolName="可灵生图 V2.1",
                description="高质量图片生成，适合照片、写真、海报、文生图",
                autoCallable=False,
            ),
        ],
    )

    result = IntentRouter().classify(ctx)

    assert result.intent == Intent.TOOL_USE
    assert result.selectedToolCode == "kling_image_v21"
