from app.core.schemas import AgentFileContext, RunContext, ToolDescriptor
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
