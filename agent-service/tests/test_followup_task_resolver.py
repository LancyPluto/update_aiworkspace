from app.core.schemas import RecentToolCallContext, RunContext, ToolDescriptor
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
