from app.core.attachment_catalog import (
    build_reference_plan,
    extract_at_tokens,
    llm_token_for_mention,
    reference_mentions_payload,
    reference_readiness_hint,
    resolve_media_argument_pointers,
    user_message_for_llm,
)
from app.core.schemas import AgentFileContext, RecentToolCallContext, ReferenceMention, RunContext


def test_extract_at_tokens_dedupes_and_preserves_order():
    message = "用 @图片1-角色 和 @图片2-风格 生成，再次 @图片1-角色"
    assert extract_at_tokens(message) == ["@图片1-角色", "@图片2-风格"]


def test_build_reference_plan_prefers_structured_reference_mentions():
    ctx = RunContext(
        runId=840,
        sessionId=1,
        userId=1,
        message="为角色生成风格图",
        referenceMentions=[
            ReferenceMention(
                token="@图片1-角色",
                refLabel="@图片1-角色",
                url="/generated/uploads/a.png",
                kind="image",
                source="current_turn",
            ),
            ReferenceMention(
                token="@图片2-风格",
                refLabel="@图片2-风格",
                url="/generated/uploads/b.png",
                kind="image",
                source="current_turn",
            ),
        ],
        agentFiles=[
            AgentFileContext(
                id=-1,
                originalFilename="@图片1-角色",
                contentType="image/png",
                status="READY",
                downloadUrl="/generated/uploads/a.png",
            ),
        ],
    )

    plan = build_reference_plan(ctx)

    assert plan.has_explicit_references is True
    assert len(plan.ordered_urls) == 2
    assert plan.ordered_urls[0].endswith("/generated/uploads/a.png")
    assert plan.ordered_urls[1].endswith("/generated/uploads/b.png")
    assert len(reference_mentions_payload(plan)) == 2


def test_build_reference_plan_falls_back_to_message_tokens_and_agent_files():
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="参考 @图片2-风格 生成",
        agentFiles=[
            AgentFileContext(
                id=-1,
                originalFilename="@图片1-角色",
                contentType="image/png",
                status="READY",
                downloadUrl="/generated/uploads/a.png",
            ),
            AgentFileContext(
                id=-2,
                originalFilename="@图片2-风格",
                contentType="image/png",
                status="READY",
                downloadUrl="/generated/uploads/b.png",
            ),
        ],
    )

    plan = build_reference_plan(ctx)

    assert plan.has_explicit_references is True
    assert len(plan.ordered_urls) == 1
    assert plan.ordered_urls[0].endswith("/generated/uploads/b.png")


def test_fuzzy_match_resolves_short_at_token():
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="用 @图片2 做风格",
        agentFiles=[
            AgentFileContext(
                id=-2,
                originalFilename="@图片2-风格参考",
                contentType="image/png",
                status="READY",
                downloadUrl="/generated/uploads/style.png",
            ),
        ],
    )

    plan = build_reference_plan(ctx)

    assert plan.has_explicit_references is True
    assert plan.ordered_urls[0].endswith("/generated/uploads/style.png")


def test_user_message_for_llm_replaces_display_tokens_with_ref_labels():
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="为 @立绘_夕_1 生成 @图片5 风格的插画",
        referenceMentions=[
            ReferenceMention(
                token="@立绘_夕_1",
                refLabel="@图片1-立绘_夕_1.png",
                url="/generated/uploads/char.png",
                kind="image",
                source="current_turn",
            ),
            ReferenceMention(
                token="@图片5",
                refLabel="@图片5-style_ref.png",
                url="/generated/images/835/style.png",
                kind="image",
                source="session_asset",
            ),
        ],
    )

    resolved = user_message_for_llm(ctx)

    assert "@立绘_夕_1" not in resolved
    assert "@图片5 " not in resolved and "@图片5风" not in resolved
    assert "@图片1-立绘_夕_1.png" in resolved
    assert "@图片5-style_ref.png" in resolved


def test_llm_token_for_mention_collapses_nested_ref_label():
    mention = ReferenceMention(
        token="@立绘_夕_1",
        refLabel="@图片7-@图片1-立绘_夕_1.png",
        url="/generated/images/7.png",
    )
    assert llm_token_for_mention(mention) == "@图片7-立绘_夕_1.png"


def test_reference_readiness_hint_includes_urls_and_no_reupload():
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="使用 @立绘_夕_1 替换 @图片2 中的人物",
        referenceMentions=[
            ReferenceMention(
                token="@立绘_夕_1",
                refLabel="@图片1-立绘_夕_1.png",
                url="/generated/uploads/char.png",
                kind="image",
                source="session_asset",
            ),
            ReferenceMention(
                token="@图片2",
                refLabel="@图片2-style_ref.png",
                url="/generated/uploads/style.png",
                kind="image",
                source="session_asset",
            ),
        ],
    )

    hint = reference_readiness_hint(ctx)

    assert "do not ask the user to re-upload" in hint
    assert "@图片1-立绘_夕_1.png" in hint
    assert "/generated/uploads/char.png" in hint


def test_reference_readiness_hint_includes_ordered_content_parts():
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="把 @图1 的脸换给 @图2",
        positionalPrompt="把 {asset-a} 的脸换给 {asset-b}",
        contentParts=[
            {"type": "text", "text": "把 "},
            {"type": "image", "asset_key": "asset-a", "url": "/generated/uploads/a.png", "name": "图1"},
            {"type": "text", "text": " 的脸换给 "},
            {"type": "image", "asset_key": "asset-b", "url": "/generated/uploads/b.png", "name": "图2"},
        ],
        referenceMentions=[
            ReferenceMention(
                token="@图1",
                refLabel="@图片1-图1.png",
                assetKey="asset-a",
                url="/generated/uploads/a.png",
                kind="image",
            ),
            ReferenceMention(
                token="@图2",
                refLabel="@图片2-图2.png",
                assetKey="asset-b",
                url="/generated/uploads/b.png",
                kind="image",
            ),
        ],
    )

    hint = reference_readiness_hint(ctx)
    resolved = user_message_for_llm(ctx)

    assert "Ordered multimodal user prompt parts" in hint
    assert "1. text: 把 " in hint
    assert "2. image: label=@图片1-图1.png" in hint
    assert "4. image: label=@图片2-图2.png" in hint
    assert "Readable positional prompt: 把 @图片1-图1.png 的脸换给 @图片2-图2.png" in hint
    assert resolved == "把 @图片1-图1.png 的脸换给 @图片2-图2.png"


def test_resolve_media_argument_pointers_maps_at_labels_to_urls():
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="基于上一张，参考 @图片2 修改",
        referenceMentions=[
            ReferenceMention(
                token="@图片2",
                refLabel="@图片2-face.png",
                assetKey="asset-face",
                fileId=88,
                url="/generated/uploads/face.png",
                kind="image",
                source="current_turn",
            )
        ],
    )

    args = resolve_media_argument_pointers(
        ctx,
        {
            "reference_images": ["@图片2-face.png", "asset-face", "88"],
            "base_image_url": "@图片2",
            "prompt": "keep this text @图片2 literal",
        },
    )

    assert all(item.endswith("/generated/uploads/face.png") for item in args["reference_images"])
    assert args["base_image_url"].endswith("/generated/uploads/face.png")
    assert args["prompt"] == "keep this text @图片2 literal"


def test_resolve_media_argument_pointers_maps_latest_generated_image_alias():
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        recentToolCalls=[
            RecentToolCallContext(
                id=7,
                toolCode="gpt_image2",
                taskId=701,
                argumentsJson={"prompt": "previous prompt"},
                resultJson={},
                resourceType="IMAGE",
                mediaUrls=["/generated/images/701/image-1.png"],
            )
        ],
    )

    args = resolve_media_argument_pointers(ctx, {"base_image_url": "latest_generated_image.url"})

    assert args["base_image_url"] == "/generated/images/701/image-1.png"

