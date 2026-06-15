from app.core.schemas import AgentFileContext, RecentToolCallContext, RunContext, ToolDescriptor
from app.runtime.followup_task_resolver import FollowupTaskResolver


def image_tool() -> ToolDescriptor:
    return ToolDescriptor(
        toolCode="kling_image_v21",
        toolName="可灵生图 V2.1",
        description="图片生成，写真，海报",
        autoCallable=False,
        inputSchema={
            "type": "object",
            "required": ["prompt"],
            "properties": {
                "prompt": {"type": "string", "title": "提示词"},
                "aspectRatio": {"type": "string", "title": "比例"},
            },
        },
    )


def video_tool() -> ToolDescriptor:
    return ToolDescriptor(
        toolCode="kling_image_to_video",
        toolName="可灵图生视频",
        description="图生视频，视频生成",
        autoCallable=False,
        inputSchema={
            "type": "object",
            "required": ["prompt", "imageUrl"],
            "properties": {
                "prompt": {"type": "string", "title": "提示词"},
                "imageUrl": {"type": "string", "title": "参考图"},
            },
        },
    )


def test_followup_inherits_image_arguments_and_replaces_subject():
    context = RunContext(
        runId=2,
        sessionId=1,
        userId=1,
        message="给科比也来一张",
        recentToolCalls=[
            RecentToolCallContext(
                id=11,
                runId=1,
                toolCode="kling_image_v21",
                taskId=71,
                argumentsJson={
                    "prompt": "石原里美在漫展穿着火影忍者晓袍的远景写真",
                    "aspectRatio": "3:4",
                },
                resultJson={},
                resourceType="IMAGE",
                mediaUrls=["/generated/images/71/image-1.png"],
            )
        ],
    )

    result = FollowupTaskResolver().resolve(context, image_tool())

    assert result.accepted
    assert result.tool_code == "kling_image_v21"
    assert result.inherited_from_tool_call_id == 11
    assert result.patched_arguments["aspectRatio"] == "3:4"
    assert "科比" in result.patched_arguments["prompt"]
    assert result.patched_arguments["prompt"].startswith("本轮用户改写要求优先：")
    assert "上一轮提示词仅作风格参考" in result.patched_arguments["prompt"]


def test_followup_rewrite_keeps_full_user_request_as_priority():
    context = RunContext(
        runId=4,
        sessionId=1,
        userId=1,
        message="改成代言《全面战争：战锤3》，cos基斯里夫的女沙皇",
        recentToolCalls=[
            RecentToolCallContext(
                id=13,
                runId=3,
                toolCode="kling_image_v21",
                taskId=73,
                argumentsJson={
                    "prompt": "斋藤飞鸟日常风杂志封面，白衬衫，咖啡厅街道",
                    "aspectRatio": "3:4",
                    "quality": "low",
                },
                resultJson={},
                resourceType="IMAGE",
                mediaUrls=["/generated/images/73/image-1.png"],
            )
        ],
    )

    result = FollowupTaskResolver().resolve(context, image_tool())

    prompt = result.patched_arguments["prompt"]
    assert result.accepted
    assert result.patched_arguments["quality"] == "low"
    assert "改成代言《全面战争：战锤3》，cos基斯里夫的女沙皇" in prompt
    assert "不要保留与本轮要求冲突" in prompt
    assert "斋藤飞鸟日常风杂志封面" in prompt


def test_followup_can_switch_from_image_result_to_video_tool():
    context = RunContext(
        runId=3,
        sessionId=1,
        userId=1,
        message="把这张做成视频",
        recentToolCalls=[
            RecentToolCallContext(
                id=12,
                runId=2,
                toolCode="kling_image_v21",
                taskId=72,
                argumentsJson={
                    "prompt": "科比穿着海贼王大将披风的远景写真",
                    "aspectRatio": "16:9",
                },
                resultJson={},
                resourceType="IMAGE",
                mediaUrls=["/generated/images/72/image-1.png"],
            )
        ],
    )

    result = FollowupTaskResolver().resolve(context, video_tool())

    assert result.accepted
    assert result.tool_code == "kling_image_to_video"
    assert result.inherited_from_tool_call_id == 12
    assert result.patched_arguments["imageUrl"] == "/generated/images/72/image-1.png"
    assert result.patched_arguments["prompt"] == "把这张做成视频"


def test_followup_rejects_without_recent_tool_call():
    context = RunContext(runId=2, sessionId=1, userId=1, message="给科比也来一张")

    result = FollowupTaskResolver().resolve(context, image_tool())

    assert not result.accepted
    assert result.reason == "no_recent_tool_calls"


def test_non_followup_is_ignored():
    context = RunContext(
        runId=2,
        sessionId=1,
        userId=1,
        message="帮我生成一张新的图片",
        recentToolCalls=[
            RecentToolCallContext(
                id=11,
                toolCode="kling_image_v21",
                argumentsJson={"prompt": "old"},
                resultJson={},
            )
        ],
    )

    result = FollowupTaskResolver().resolve(context, image_tool())

    assert not result.accepted
    assert result.reason == "not_followup"


def gpt_image_tool() -> ToolDescriptor:
    return ToolDescriptor(
        toolCode="gpt_image2",
        toolName="GPT-image2",
        description="图片编辑",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "required": ["prompt", "image"],
            "properties": {
                "prompt": {"type": "string", "title": "提示词"},
                "image": {"type": "string", "title": "参考图"},
            },
        },
    )


def test_followup_keeps_explicit_preferred_tool_over_recent_kling_call():
    context = RunContext(
        runId=6,
        sessionId=1,
        userId=1,
        message="给科比也来一张",
        preferredToolCode="gpt_image2",
        recentToolCalls=[
            RecentToolCallContext(
                id=11,
                runId=1,
                toolCode="kling-image-generation",
                taskId=71,
                argumentsJson={"prompt": "previous"},
                resultJson={},
                resourceType="IMAGE",
                mediaUrls=[],
            )
        ],
        availableTools=[gpt_image_tool(), image_tool()],
    )

    result = FollowupTaskResolver().resolve(context, gpt_image_tool())

    assert result.accepted
    assert result.tool_code == "gpt_image2"


def test_followup_prefers_user_dragged_image_over_recent_tool_media():
    context = RunContext(
        runId=5,
        sessionId=1,
        userId=1,
        message="让飞鸟换成狛枝凪斗的服装",
        agentFiles=[
            AgentFileContext(
                id=-1,
                originalFilename="@图片1-飞鸟",
                contentType="image/png",
                status="READY",
                downloadUrl="/generated/images/217/user-selected.png",
            )
        ],
        recentToolCalls=[
            RecentToolCallContext(
                id=11,
                runId=1,
                toolCode="gpt_image2",
                taskId=71,
                argumentsJson={"prompt": "previous"},
                resultJson={},
                resourceType="IMAGE",
                mediaUrls=["/generated/images/301/image-1.png"],
            )
        ],
    )

    result = FollowupTaskResolver().resolve(context, gpt_image_tool())

    assert result.accepted
    assert result.patched_arguments["image"].endswith("/generated/images/217/user-selected.png")
