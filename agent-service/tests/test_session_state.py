from app.core.schemas import RecentToolCallContext, RunContext
from app.runtime.session_state import format_session_state_context, hydrate_session_state, latest_generated_image_state


def test_hydrate_session_state_uses_recent_tool_call_media_and_prompt():
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="arbitrary user text",
        recentToolCalls=[
            RecentToolCallContext(
                id=42,
                runId=9,
                toolCode="gpt_image2",
                taskId=870,
                argumentsJson={"prompt": "gothic magazine cover prompt", "quality": "low"},
                resultJson={},
                resourceType="IMAGE",
                mediaUrls=["/generated/images/870/image-1.png"],
                createdAt="2026-06-21T16:00:00",
            )
        ],
    )

    state = hydrate_session_state(context)

    assert state["latest_generated_image"]["url"] == "/generated/images/870/image-1.png"
    assert state["latest_generated_image"]["prompt"] == "gothic magazine cover prompt"
    assert state["latest_generated_image"]["tool_code"] == "gpt_image2"


def test_latest_generated_image_state_ignores_user_message_text():
    calls = [
        RecentToolCallContext(
            id=1,
            toolCode="gpt_image2",
            taskId=900,
            argumentsJson={"prompt": "previous prompt"},
            resultJson={},
            resourceType="IMAGE",
            mediaUrls=["/generated/images/900/image-1.png"],
        )
    ]

    latest = latest_generated_image_state(calls)

    assert latest is not None
    assert latest.prompt == "previous prompt"


def test_format_session_state_context_contains_system_block():
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        recentToolCalls=[
            RecentToolCallContext(
                id=7,
                toolCode="gpt_image2",
                taskId=701,
                argumentsJson={"prompt": "cover prompt"},
                resultJson={},
                resourceType="IMAGE",
                mediaUrls=["/generated/images/701/image-1.png"],
            )
        ],
    )

    rendered = format_session_state_context(context)

    assert "<SessionState>" in rendered
    assert "latest_generated_image:" in rendered
    assert "url: /generated/images/701/image-1.png" in rendered
    assert "prompt: cover prompt" in rendered
