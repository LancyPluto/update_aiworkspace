from app.core.schemas import RecentToolCallContext, ReferenceMention, RunContext
from app.runtime.session_state import format_session_state_context, hydrate_session_state, latest_generated_image_state, sanitize_visual_prompt


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
    assert "latest_generated_images:" in rendered
    assert "label: latest_generated_image" in rendered
    assert "url: /generated/images/701/image-1.png" in rendered
    assert "      cover prompt" in rendered


def test_session_state_prunes_to_two_generated_images_and_visible_attachments():
    calls = [
        RecentToolCallContext(
            id=index,
            toolCode="gpt_image2",
            taskId=700 + index,
            argumentsJson={
                "generation_prompt": f"prompt {index}",
                "routing_notes": "x" * 400,
                "references": [
                    {"id": "face_ref_1", "role": "face_ref", "source_ref": "@图片1", "notes": "n" * 400}
                ],
            },
            resultJson={},
            resourceType="IMAGE",
            mediaUrls=[f"/generated/images/{index}/image-1.png"],
        )
        for index in range(1, 4)
    ]
    mentions = [
        ReferenceMention(token=f"@图片{index}", refLabel=f"@图片{index}-ref.png", url=f"/uploads/{index}.png")
        for index in range(1, 10)
    ]
    context = RunContext(runId=1, sessionId=1, userId=1, recentToolCalls=calls, referenceMentions=mentions)

    state = hydrate_session_state(context)

    assert len(state["latest_generated_images"]) == 2
    assert state["latest_generated_images"][0]["label"] == "latest_generated_image"
    assert state["latest_generated_images"][1]["label"] == "previous_generated_image"
    assert len(state["visible_attachments"]) == 8
    assert state["visible_attachments"][0]["alias"] == "[当前参考图_1]"
    assert state["visible_attachments"][0]["original_label"] == "@图片1-ref.png"
    assert len(state["latest_generated_images"][0]["routing_notes"]) <= 300
    assert len(state["latest_generated_images"][0]["references"][0]["notes"]) <= 300


def test_sanitize_visual_prompt_removes_reference_routing_rules():
    prompt = (
        "@图片1 @图片2 为女性角色生成电影海报，冷蓝胶片色调。 "
        "参考图角色约束（必须严格执行，不可交换）："
        "1) 主体身份参考：@图片1；2) 动作与构图参考：@图片2。"
        "最终输出需明确保证：主体来自1号参考。"
        "\n\nEDIT INSTRUCTION:\n换背景。"
        "\n\nSTRICT PRESERVATION:\nDo not change identity."
    )

    sanitized = sanitize_visual_prompt(prompt)

    assert "为女性角色生成电影海报" in sanitized
    assert "参考图角色约束" not in sanitized
    assert "最终输出需明确保证" not in sanitized
    assert "EDIT INSTRUCTION" not in sanitized
    assert "STRICT PRESERVATION" not in sanitized


def test_hydrate_session_state_stores_visual_only_prompt():
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        recentToolCalls=[
            RecentToolCallContext(
                id=7,
                toolCode="gpt_image2",
                taskId=701,
                argumentsJson={
                    "prompt": "电影海报，冷蓝胶片色调。 参考图角色约束：@图片1 为主体参考图。优先保留主体身份特征；"
                },
                resultJson={},
                resourceType="IMAGE",
                mediaUrls=["/generated/images/701/image-1.png"],
            )
        ],
    )

    state = hydrate_session_state(context)

    assert "电影海报" in state["latest_generated_image"]["prompt"]
    assert "参考图角色约束" not in state["latest_generated_image"]["prompt"]
