"""BackendToolBridge: required-field detection must not be short-circuited by placeholder defaults."""

from app.core.schemas import ChatMessage, RunContext, ToolDescriptor
from app.tools.backend_tool import BackendToolBridge


def _xiaohongshu_like_schema() -> dict:
    return {
        "type": "object",
        "required": ["productName", "targetCustomer", "style", "sellingPoints"],
        "properties": {
            "userRequest": {"type": "string"},
            "productName": {"type": "string", "title": "产品/服务名称"},
            "targetCustomer": {"type": "string", "title": "目标用户"},
            "style": {"type": "string", "title": "风格"},
            "sellingPoints": {"type": "string", "title": "卖点"},
            "extraInfo": {"type": "string"},
        },
    }


def test_missing_required_skips_xiaohongshu_placeholders():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="xiaohongshu_copywriting",
        toolName="小红书",
        description="种草",
        autoCallable=True,
        inputSchema=_xiaohongshu_like_schema(),
    )
    ctx = RunContext(runId=1, sessionId=1, userId=1, message="帮我写一篇小红书种草笔记")
    missing = bridge.missing_required_arguments(ctx, tool)
    assert set(missing) == {"productName", "targetCustomer", "style", "sellingPoints"}


def test_missing_respects_labeled_fields():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="xiaohongshu_copywriting",
        toolName="小红书",
        description="种草",
        autoCallable=True,
        inputSchema=_xiaohongshu_like_schema(),
    )
    msg = "帮我写笔记 productName: 防晒喷雾 targetCustomer: 年轻女性 style: 种草 sellingPoints: 清爽不油腻"
    ctx = RunContext(runId=1, sessionId=1, userId=1, message=msg)
    assert bridge.missing_required_arguments(ctx, tool) == []


def test_execute_arguments_fill_xiaohongshu_placeholders():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="xiaohongshu_copywriting",
        toolName="小红书",
        description="种草",
        autoCallable=True,
        inputSchema=_xiaohongshu_like_schema(),
    )
    ctx = RunContext(runId=1, sessionId=1, userId=1, message="随便写点")
    args = bridge.build_arguments(ctx, tool, apply_placeholder_defaults=True)
    for key in ("productName", "targetCustomer", "style", "sellingPoints"):
        assert args.get(key), f"missing filled {key}"


def test_missing_skipped_when_user_accepts_builtin_examples():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="xiaohongshu_copywriting",
        toolName="小红书",
        description="种草",
        autoCallable=True,
        inputSchema=_xiaohongshu_like_schema(),
    )
    ctx = RunContext(runId=1, sessionId=1, userId=1, message="你全部按照你给的例子来输入，把输出结果给我")
    assert bridge.missing_required_arguments(ctx, tool) == []


def _moments_schema() -> dict:
    return {
        "type": "object",
        "required": ["topic", "targetAudience", "tone", "scene", "sellingPoints", "lengthLevel"],
        "properties": {
            "topic": {"type": "string", "title": "文案主题"},
            "targetAudience": {"type": "string", "title": "目标人群"},
            "tone": {"type": "string", "title": "文案风格"},
            "scene": {"type": "string", "title": "发布场景"},
            "sellingPoints": {"type": "string", "title": "核心卖点"},
            "lengthLevel": {"type": "string", "title": "文案长度"},
        },
    }


def test_build_arguments_merges_recent_user_followups_with_chinese_labels():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="moments_copywriting_generator",
        toolName="AI 朋友圈文案生成器",
        description="朋友圈文案",
        autoCallable=True,
        inputSchema=_moments_schema(),
    )
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="目标人群：久坐上班族、老会员、附近新客\n文案风格：亲切\n发布场景：活动宣传\n核心卖点：限时体验价、到店即用、适合上班族放松\n文案长度：短",
        history=[
            ChatMessage(role="user", content="给朋友圈生成一段新品文案"),
            ChatMessage(role="assistant", content="如果想使用「AI 朋友圈文案生成器」，请在同一条或下一条消息里按下面补充（可直接复制条目改写成你的内容）：\n\n• 文案主题：例如：周末肩颈放松活动"),
            ChatMessage(role="user", content="文案主题：周末肩颈放松活动"),
            ChatMessage(role="assistant", content="如果想使用「AI 朋友圈文案生成器」，请在同一条或下一条消息里按下面补充（可直接复制条目改写成你的内容）：\n\n• 目标人群：例如：久坐上班族"),
        ],
    )

    arguments = bridge.build_arguments(ctx, tool, apply_placeholder_defaults=False)

    assert arguments["topic"] == "周末肩颈放松活动"
    assert arguments["targetAudience"] == "久坐上班族、老会员、附近新客"
    assert arguments["tone"] == "亲切"
    assert arguments["scene"] == "活动宣传"
    assert arguments["sellingPoints"] == "限时体验价、到店即用、适合上班族放松"
    assert arguments["lengthLevel"] == "短"
