"""BackendToolBridge: required-field detection must not be short-circuited by placeholder defaults."""

from app.core.schemas import RunContext, ToolDescriptor
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
