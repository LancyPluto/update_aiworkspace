from app.core.schemas import AgentFileContext, RecentToolCallContext, RunContext, ToolDescriptor
from app.core.user_attachment_priority import apply_user_selected_attachment_priority


def test_user_dragged_image_overrides_router_history_image():
    tool = ToolDescriptor(
        toolCode="gpt_image2",
        toolName="GPT-image2",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "properties": {
                "prompt": {"type": "string"},
                "image": {"type": "string"},
            },
        },
    )
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="让飞鸟换上狛枝凪斗的服装",
        agentFiles=[
            AgentFileContext(
                id=-1,
                originalFilename="@图片1-飞鸟",
                contentType="image/png",
                status="READY",
                downloadUrl="/generated/images/217/user-selected.png",
            ),
            AgentFileContext(
                id=1,
                originalFilename="history.png",
                contentType="image/png",
                status="READY",
                downloadUrl="/generated/images/301/image-1.png",
            ),
        ],
    )

    args = apply_user_selected_attachment_priority(
        ctx,
        tool,
        {"prompt": "换装", "image": "/generated/images/301/image-1.png"},
    )

    assert args["image"].endswith("/generated/images/217/user-selected.png")


def test_multi_image_schema_receives_all_ready_images():
    tool = ToolDescriptor(
        toolCode="gpt_image2",
        toolName="GPT-image2",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "properties": {
                "prompt": {"type": "string"},
                "referenceImageUrls": {"type": "array", "items": {"type": "string"}},
            },
        },
    )
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="用这两张参考图生成",
        agentFiles=[
            AgentFileContext(
                id=-1,
                originalFilename="@图片1",
                contentType="image/png",
                status="READY",
                downloadUrl="/generated/images/1.png",
            ),
            AgentFileContext(
                id=-2,
                originalFilename="@图片2",
                contentType="image/jpeg",
                status="READY",
                downloadUrl="/generated/images/2.jpg",
            ),
        ],
    )

    args = apply_user_selected_attachment_priority(ctx, tool, {"prompt": "生成"})

    assert args["referenceImageUrls"][0].endswith("/generated/images/1.png")
    assert args["referenceImageUrls"][1].endswith("/generated/images/2.jpg")


def test_multi_image_schema_supports_ofox_image_key():
    tool = ToolDescriptor(
        toolCode="ofox_gpt_image2",
        toolName="GPT-image2",
        autoCallable=True,
        inputSchema={"type": "object", "properties": {"image": {"type": "array", "items": {"type": "string"}}}},
    )
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="融合两张图",
        agentFiles=[
            AgentFileContext(id=-1, originalFilename="@图片1", contentType="image/png", status="READY", downloadUrl="/generated/images/1.png"),
            AgentFileContext(id=-2, originalFilename="@图片2", contentType="image/png", status="READY", downloadUrl="/generated/images/2.png"),
        ],
    )

    args = apply_user_selected_attachment_priority(ctx, tool, {})

    assert len(args["image"]) == 2
    assert args["image"][0].endswith("/generated/images/1.png")
    assert args["image"][1].endswith("/generated/images/2.png")


def test_multi_image_field_receives_images_when_schema_is_missing_property():
    tool = ToolDescriptor(
        toolCode="ofox_gpt_image2",
        toolName="GPT-image2",
        autoCallable=True,
        inputSchema={"type": "object", "properties": {"prompt": {"type": "string"}}},
        fields=[
            {
                "fieldKey": "image",
                "fieldName": "参考图",
                "fieldType": "multi_image",
                "required": False,
            }
        ],
    )
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="参考该图片生成一组海贼王真人版剧照",
        agentFiles=[
            AgentFileContext(
                id=-1,
                originalFilename="@图片1",
                contentType="image/png",
                status="READY",
                downloadUrl="/generated/images/1.png",
            ),
        ],
    )

    args = apply_user_selected_attachment_priority(ctx, tool, {"prompt": "生成剧照"})

    assert args["image"][0].endswith("/generated/images/1.png")


def test_single_image_schema_keeps_first_ready_image():
    tool = ToolDescriptor(
        toolCode="gpt_image2",
        toolName="GPT-image2",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "properties": {
                "prompt": {"type": "string"},
                "referenceImageUrl": {"type": "string"},
            },
        },
    )
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="用这两张参考图生成",
        agentFiles=[
            AgentFileContext(id=-1, originalFilename="@图片1", contentType="image/png", status="READY", downloadUrl="/generated/images/1.png"),
            AgentFileContext(id=-2, originalFilename="@图片2", contentType="image/png", status="READY", downloadUrl="/generated/images/2.png"),
        ],
    )

    args = apply_user_selected_attachment_priority(ctx, tool, {"prompt": "生成"})

    assert args["referenceImageUrl"].endswith("/generated/images/1.png")


def test_style_transfer_preserves_history_and_appends_uploaded_image():
    tool = ToolDescriptor(
        toolCode="gpt_image2",
        toolName="GPT-image2",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "properties": {
                "prompt": {"type": "string"},
                "image": {"type": "array", "items": {"type": "string"}},
            },
        },
    )
    ctx = RunContext(
        runId=349,
        sessionId=1,
        userId=1,
        message="刚刚那张改成这张图的日常风格",
        agentFiles=[
            AgentFileContext(
                id=-1,
                originalFilename="@图片1-日常",
                contentType="image/png",
                status="READY",
                downloadUrl="/generated/uploads/20260611/upload.png",
            ),
        ],
        recentToolCalls=[
            RecentToolCallContext(
                id=10,
                toolCode="gpt_image2",
                mediaUrls=["/generated/images/301/history-poster.png"],
            ),
        ],
    )

    args = apply_user_selected_attachment_priority(
        ctx,
        tool,
        {
            "prompt": "将第一张图的画面改为第二张图的日常风格，保持主体、构图不变。",
            "image": [
                "/generated/images/301/history-poster.png",
                "/generated/uploads/20260611/upload.png",
            ],
        },
    )

    assert len(args["image"]) == 2
    assert args["image"][0].endswith("/generated/images/301/history-poster.png")
    assert args["image"][1].endswith("/generated/uploads/20260611/upload.png")


def test_stale_router_image_dropped_while_history_and_user_kept():
    tool = ToolDescriptor(
        toolCode="gpt_image2",
        toolName="GPT-image2",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "properties": {
                "prompt": {"type": "string"},
                "image": {"type": "array", "items": {"type": "string"}},
            },
        },
    )
    ctx = RunContext(
        runId=350,
        sessionId=1,
        userId=1,
        message="把刚刚那张改成这张的风格",
        agentFiles=[
            AgentFileContext(
                id=-1,
                originalFilename="@图片1-上传",
                contentType="image/png",
                status="READY",
                downloadUrl="/generated/uploads/20260611/upload.png",
            ),
        ],
        recentToolCalls=[
            RecentToolCallContext(
                id=11,
                toolCode="gpt_image2",
                mediaUrls=["/generated/images/301/history-poster.png"],
            ),
        ],
    )

    args = apply_user_selected_attachment_priority(
        ctx,
        tool,
        {
            "image": [
                "/generated/images/301/history-poster.png",
                "/generated/images/999/stale-router-guess.png",
                "/generated/uploads/20260611/upload.png",
            ],
        },
    )

    urls = args["image"]
    assert len(urls) == 2
    assert any(u.endswith("/generated/images/301/history-poster.png") for u in urls)
    assert any(u.endswith("/generated/uploads/20260611/upload.png") for u in urls)
    assert all("stale-router-guess" not in u for u in urls)
