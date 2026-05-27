"""BackendToolBridge: required-field detection must not be short-circuited by placeholder defaults."""

import pytest

from app.core.schemas import ChatMessage, RunContext, TaskDetailResponse, ToolDescriptor
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


def test_defaultable_execution_required_field_does_not_trigger_clarification():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="image_generation",
        toolName="图片生成",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "required": ["aspectRatio"],
            "properties": {
                "userRequest": {"type": "string"},
                "aspectRatio": {
                    "type": "string",
                    "title": "画面比例",
                    "default": "3:4",
                    "x-user-required": False,
                    "x-agent-fill-strategy": "default",
                },
            },
        },
        fields=[
            {
                "fieldKey": "aspectRatio",
                "fieldName": "画面比例",
                "fieldType": "radio",
                "required": True,
                "executionRequired": True,
                "userRequired": False,
                "defaultValue": "3:4",
                "agentFillStrategy": "default",
                "riskLevel": "LOW",
            },
        ],
    )
    ctx = RunContext(runId=1, sessionId=1, userId=1, message="我要生成一张石原里美的图片")

    assert bridge.missing_required_arguments(ctx, tool) == []
    assert bridge.build_arguments(ctx, tool)["aspectRatio"] == "3:4"


def test_user_required_field_still_triggers_clarification():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="account_binding",
        toolName="账号绑定",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "required": ["accountId"],
            "properties": {"accountId": {"type": "string", "title": "账号 ID", "x-user-required": True}},
        },
        fields=[
            {
                "fieldKey": "accountId",
                "fieldName": "账号 ID",
                "required": True,
                "executionRequired": True,
                "userRequired": True,
                "agentFillStrategy": "ask_user",
                "riskLevel": "HIGH",
            },
        ],
    )
    ctx = RunContext(runId=1, sessionId=1, userId=1, message="帮我绑定账号")

    assert bridge.missing_required_arguments(ctx, tool) == ["accountId"]


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


def test_extract_strips_example_prefix_from_chinese_labels():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="xiaohongshu_copywriting",
        toolName="AI 小红书文案生成器",
        description="种草",
        autoCallable=True,
        inputSchema=_xiaohongshu_like_schema(),
    )
    msg = (
        "产品/服务名称：例如：五一肩颈护理套餐\n"
        "• 目标用户：例如：年轻女性、宝妈\n"
        "• 文案风格：例如：种草\n"
        "• 核心卖点：例如：价格划算、效果明显"
    )
    ctx = RunContext(runId=1, sessionId=1, userId=1, message=msg)
    args = bridge.build_arguments(ctx, tool, apply_placeholder_defaults=False)
    assert args["productName"] == "五一肩颈护理套餐"
    assert args["targetCustomer"] == "年轻女性、宝妈"
    assert args["style"] == "种草"
    assert args["sellingPoints"] == "价格划算、效果明显"


@pytest.mark.asyncio
async def test_wait_for_task_keeps_polling_if_run_is_already_success():
    class Backend:
        def __init__(self) -> None:
            self.polls = 0

        async def get_run_context(self, run_id: int) -> RunContext:
            return RunContext(runId=run_id, sessionId=1, userId=1, message="generate image", status="SUCCESS")

        async def get_task_detail(self, user_id: int, task_id: int) -> TaskDetailResponse:
            self.polls += 1
            return TaskDetailResponse(
                taskId=task_id,
                status="SUCCESS",
                progress=100,
                progressMessage="done",
            )

    backend = Backend()
    bridge = BackendToolBridge(backend_client=backend, timeout_seconds=1, poll_interval_seconds=0.01)  # type: ignore[arg-type]
    context = RunContext(runId=9, sessionId=1, userId=1, message="generate image", status="RUNNING")

    detail = await bridge._wait_for_task(context, "image_generation", 71)

    assert detail.status == "SUCCESS"
    assert backend.polls == 1
