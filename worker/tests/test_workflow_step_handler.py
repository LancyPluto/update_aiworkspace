from handlers.workflow_step_handler import _extract_json, _fallback_script, _merge_form


def test_extract_json_from_markdown_wrapped_payload():
    raw = """```json
{"sceneTitle": "温情漫剧", "dialogue": "奶就放心了。"}
```"""
    parsed = _extract_json(raw)
    assert parsed is not None
    assert parsed["sceneTitle"] == "温情漫剧"


def test_merge_form_from_field_input():
    workflow_inputs = {
        "field-input": {"fields": {"storyTheme": "祖孙温情", "plotOutline": "奶奶安心"}},
        "form": {},
    }
    form = _merge_form(workflow_inputs)
    assert form["storyTheme"] == "祖孙温情"
    assert form["plotOutline"] == "奶奶安心"


def test_fallback_script_has_bilingual_subtitles():
    script = _fallback_script({"storyTheme": "测试"})
    assert script["subtitleZh"]
    assert script["subtitleEn"]
    assert "sceneDescription" in script


def test_seedance_client_uses_model_config_api_key():
    from client.seedance_video_client import SeedanceVideoClient

    client = SeedanceVideoClient.from_model_config({
        "apiKey": "ark-test-key",
        "baseUrl": "https://ark.example.com",
        "modelName": "doubao-seedance-test",
    })
    assert client.api_key == "ark-test-key"
    assert client.base_url == "https://ark.example.com"
    assert client.default_model == "doubao-seedance-test"


def test_build_delivery_markdown_contains_video_and_ai_tag():
    from handlers.workflow_step_handler import _build_delivery_markdown

    content = _build_delivery_markdown(
        title="温情漫剧",
        final_video_url="https://example.com/final.mp4",
        subtitle_zh="奶就放心了",
        subtitle_en="so Grandma won't worry.",
        image_url="https://example.com/frame.png",
    )
    assert "final.mp4" in content
    assert "奶就放心了" in content
    assert "AI 制作" in content
