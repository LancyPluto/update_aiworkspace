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
