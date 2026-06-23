from handlers.image_generation_handler import _build_prompt, _resolve_reference_image_sources


def test_openai_image_reference_sources_put_base_before_references():
    params = {
        "base_image_url": "https://example.com/base.png",
        "reference_images": [
            "https://example.com/face.png",
            "https://example.com/style.png",
        ],
    }

    assert _resolve_reference_image_sources(params) == [
        "https://example.com/base.png",
        "https://example.com/face.png",
        "https://example.com/style.png",
    ]


def test_openai_image_reference_sources_keep_legacy_image_compatibility():
    params = {
        "image": [
            "https://example.com/legacy-base.png",
            "https://example.com/legacy-ref.png",
        ],
    }

    assert _resolve_reference_image_sources(params) == [
        "https://example.com/legacy-base.png",
        "https://example.com/legacy-ref.png",
    ]


def test_openai_image_reference_sources_dedupe_base_and_references():
    params = {
        "baseImageUrl": "https://example.com/a.png",
        "referenceImageUrls": [
            "https://example.com/a.png",
            "https://example.com/b.png",
        ],
        "image": "https://example.com/b.png",
    }

    assert _resolve_reference_image_sources(params) == [
        "https://example.com/a.png",
        "https://example.com/b.png",
    ]


def test_v2_lite_reference_sources_put_base_before_typed_references_by_priority():
    params = {
        "base_image_ref": "https://example.com/base.png",
        "references": [
            {"id": "style_ref_1", "role": "style_ref", "source_ref": "https://example.com/style.png"},
            {"id": "face_ref_1", "role": "face_ref", "source_ref": "https://example.com/face.png"},
            {"id": "pose_ref_1", "role": "pose_ref", "source_ref": "https://example.com/pose.png"},
        ],
    }

    assert _resolve_reference_image_sources(params) == [
        "https://example.com/base.png",
        "https://example.com/face.png",
        "https://example.com/pose.png",
        "https://example.com/style.png",
    ]


def test_v2_lite_reference_sources_put_base_image_url_before_typed_references():
    params = {
        "base_image_url": "https://example.com/base.png",
        "references": [
            {"id": "face_ref_1", "role": "face_ref", "source_ref": "https://example.com/face.png"},
        ],
    }

    assert _resolve_reference_image_sources(params) == [
        "https://example.com/base.png",
        "https://example.com/face.png",
    ]


def test_v2_lite_edit_prompt_composes_base_delta_and_reference_routing():
    params = {
        "operation": "edit",
        "base_prompt": "original cinematic poster prompt",
        "modification_prompt": "Change only the background to cyberpunk.",
        "references": [
            {
                "id": "face_ref_1",
                "role": "face_ref",
                "source_ref": "https://example.com/face.png",
                "notes": "Keep this face only.",
            }
        ],
    }

    prompt = _build_prompt(params, include_style=False)

    assert "original cinematic poster prompt" in prompt
    assert "EDIT INSTRUCTION:" in prompt
    assert "Change only the background to cyberpunk." in prompt
    assert "REFERENCE ROUTING:" in prompt
    assert "face_ref_1" in prompt
    assert "STRICT PRESERVATION:" in prompt


def test_v2_lite_edit_prompt_sanitizes_toxic_base_prompt():
    params = {
        "operation": "edit",
        "base_prompt": (
            "冷蓝电影海报，柔焦真实光影。 "
            "参考图角色约束（必须严格执行，不可交换）：1) 主体身份参考：@图片1。"
            "最终输出需明确保证：主体来自1号参考。"
            "\n\nSTRICT PRESERVATION:\nDo not change identity."
        ),
        "modification_prompt": "只调整背景。",
    }

    prompt = _build_prompt(params, include_style=False)

    assert "冷蓝电影海报" in prompt
    assert "参考图角色约束" not in prompt
    assert "主体来自1号参考" not in prompt


def test_v2_lite_face_replacement_prompt_allows_identity_change():
    params = {
        "operation": "edit",
        "base_prompt": "冷蓝电影海报，柔焦真实光影。",
        "modification_prompt": "将人物模特替换为[当前参考图_1]中的女性，人物自然融入场景。",
        "references": [
            {
                "id": "face_ref_1",
                "role": "face_ref",
                "source_ref": "https://example.com/new-face.png",
                "notes": "新的身份和脸部参考。",
            }
        ],
    }

    prompt = _build_prompt(params, include_style=False)

    assert "Allow identity and face to change" in prompt
    assert "Do not change identity" not in prompt


def test_v2_lite_background_edit_with_face_ref_still_preserves_identity():
    params = {
        "operation": "edit",
        "base_prompt": "冷蓝电影海报，柔焦真实光影。",
        "modification_prompt": "把背景换成赛博朋克夜景，但保留图1的脸。",
        "references": [
            {"id": "face_ref_1", "role": "face_ref", "source_ref": "https://example.com/face.png"}
        ],
    }

    prompt = _build_prompt(params, include_style=False)

    assert "Do not change identity" in prompt
    assert "Allow identity and face to change" not in prompt
