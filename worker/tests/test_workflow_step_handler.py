import json

import pytest

import handlers.workflow_step_handler as workflow_step_handler
from client.model_client import ModelClientError
from handlers.workflow_step_handler import (
    WorkflowStepHandler,
    _extract_json,
    _fallback_script,
    _merge_form,
)


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


def test_resolve_scene_count_from_episode_length():
    from handlers.workflow_step_handler import _resolve_scene_count

    assert _resolve_scene_count({"episodeLength": "30s"}) == 6
    assert _resolve_scene_count({"episodeLength": "60s"}) == 12
    assert _resolve_scene_count({"episodeLength": "90s"}) == 18
    assert _resolve_scene_count({}) == 6  # 默认 30 秒
    assert _resolve_scene_count({"episodeLength": "abc"}) == 6
    assert _resolve_scene_count({"episodeLength": "5"}) == 1


def test_parse_per_scene_feedback_plain_text():
    from handlers.workflow_step_handler import _parse_per_scene_feedback

    overall, per_scene = _parse_per_scene_feedback("节奏快一点")
    assert overall == "节奏快一点"
    assert per_scene == {}


def test_parse_per_scene_feedback_json_mapping():
    from handlers.workflow_step_handler import _parse_per_scene_feedback

    overall, per_scene = _parse_per_scene_feedback('{"all": "整体更温馨", "1": "镜头拉近", "scene-3": "换夜景"}')
    assert overall == "整体更温馨"
    assert per_scene == {1: "镜头拉近", 3: "换夜景"}


def test_normalize_scenes_pads_to_requested_count():
    from handlers.workflow_step_handler import _normalize_scenes

    parsed = {
        "title": "测试剧",
        "scenes": [
            {"index": 1, "sceneTitle": "开场", "sceneDescription": "城市清晨", "dialogue": "新的一天", "subtitleEn": "A new day"},
            {"index": 2, "sceneDescription": "地铁站", "dialogue": "出发了"},
        ],
    }
    scenes = _normalize_scenes(parsed, 6, {"storyTheme": "测试剧"})
    assert len(scenes) == 6
    assert scenes[0]["sceneTitle"] == "开场"
    assert scenes[1]["dialogue"] == "出发了"
    assert scenes[1]["subtitleZh"] == "出发了"
    # 不足的分镜用兜底脚本补齐且编号连续
    assert [scene["index"] for scene in scenes] == [1, 2, 3, 4, 5, 6]
    assert all(scene["durationSeconds"] == 5 for scene in scenes)


def test_scenes_from_script_prefers_scene_list():
    from handlers.workflow_step_handler import _scenes_from_script

    script = {"scenes": [{"index": 1, "dialogue": "你好"}], "dialogue": "旧字段"}
    scenes = _scenes_from_script(script, {})
    assert len(scenes) == 1
    assert scenes[0]["dialogue"] == "你好"
    # 旧版单镜结构仍然兼容
    legacy = _scenes_from_script({"sceneDescription": "单镜", "dialogue": "旧"}, {})
    assert len(legacy) == 1


def test_build_delivery_markdown_multi_scene():
    from handlers.workflow_step_handler import _build_delivery_markdown

    content = _build_delivery_markdown(
        title="多镜成片",
        final_video_url="https://example.com/final-combined.mp4",
        subtitle_zh="第一句",
        subtitle_en="first line",
        image_url=None,
        scenes=[
            {"index": 1, "subtitleZh": "第一句", "subtitleEn": "first line"},
            {"index": 2, "subtitleZh": "第二句", "subtitleEn": "second line"},
        ],
    )
    assert "分镜1" in content and "分镜2" in content
    assert "final-combined.mp4" in content


def test_scene_loop_node_overrides_episode_length():
    from handlers.workflow_step_handler import _resolve_scene_count

    workflow_inputs = {
        "scene-loop": {"sceneCount": 4, "indices": [1, 2, 3, 4], "secondsPerScene": 5},
        "field-input": {"fields": {"episodeLength": "90s"}},
    }
    # scene_loop 节点显式拆分优先于时长字段推断
    assert _resolve_scene_count({"episodeLength": "90s"}, workflow_inputs) == 4
    # 没有 scene_loop 输出时回退到时长推断
    assert _resolve_scene_count({"episodeLength": "90s"}, {"form": {}}) == 18


def _valid_script(scene_count: int = 2) -> dict:
    scenes = []
    for index in range(1, scene_count + 1):
        scenes.append(
            {
                "index": index,
                "sceneTitle": f"剧情推进{index}",
                "durationSeconds": 5,
                "characterScene": f"主角 / 场景{index}",
                "cameraLanguage": f"{'全景' if index == 1 else '特写'}，平视，缓慢推进",
                "sceneDescription": (
                    f"Cinematic scene {index}: the protagonist performs action {index} "
                    f"in a distinct environment with unique lighting and composition."
                ),
                "plot": f"第{index}镜发生独立的剧情事件并推动冲突发展。",
                "dialogue": f"第{index}镜的独立台词。",
                "narration": "",
                "voiceDirection": f"【说话人=主角｜男｜青年】第{index}镜的独立台词。",
                "subtitleZh": f"第{index}镜的独立台词。",
                "subtitleEn": f"Unique line for scene {index}.",
                "presenterGender": "male",
            }
        )
    return {
        "title": "完整测试剧本",
        "synopsis": "主角遭遇危机，采取行动并完成反转。",
        "screenplay": "".join(
            f"第{index}幕：主角在场景{index}经历独立事件，动作、情绪和环境持续变化。"
            for index in range(1, scene_count + 1)
        )
        * 5,
        "genre": "都市逆袭",
        "characters": [{"name": "主角", "appearance": "黑发青年，深色夹克", "personality": "果断"}],
        "locations": [{"name": "城市", "description": "雨夜霓虹街道"}],
        "scenes": scenes,
    }



def test_normalize_scenes_keeps_dialogue_empty_when_narration_exists():
    from handlers.workflow_step_handler import _normalize_scenes

    scenes = _normalize_scenes(
        {
            "scenes": [
                {
                    "sceneTitle": "旁白镜头",
                    "sceneDescription": "A detailed cinematic establishing shot with atmospheric light.",
                    "plot": "主角发现关键线索。",
                    "dialogue": "",
                    "narration": "在混乱中，主角注意到了角落里的异常。",
                    "cameraLanguage": "全景，缓慢推进",
                }
            ]
        },
        1,
        {"storyTheme": "测试故事"},
    )

    assert scenes[0]["dialogue"] == ""
    assert scenes[0]["narration"] == "在混乱中，主角注意到了角落里的异常。"
    assert scenes[0]["subtitleZh"] == ""


class SequenceModelClient:
    def __init__(self, responses: list[str]):
        self.responses = list(responses)
        self.prompts: list[str] = []

    def generate(self, prompt: str, **kwargs):
        self.prompts.append(prompt)
        return self.responses.pop(0)


def test_script_planner_retries_invalid_output_and_returns_detailed_script():
    valid = _valid_script()
    model_client = SequenceModelClient(["not-json", json.dumps(valid, ensure_ascii=False)])
    handler = WorkflowStepHandler(model_client=model_client)

    result = handler._run_script_planner(
        {"storyTheme": "测试逆袭", "episodeLength": "10s"},
        {"parameters": {"prompt": "强调强冲突和结尾反转"}},
        {"provider": "agnes_chat", "modelName": "agnes-2.0-flash"},
    )

    assert len(model_client.prompts) == 2
    assert "强调强冲突和结尾反转" in model_client.prompts[0]
    assert "只输出合法 JSON" in model_client.prompts[0]
    assert result["screenplay"] == valid["screenplay"]
    assert result["scenes"][0]["plot"] != result["scenes"][1]["plot"]


def test_script_planner_does_not_report_template_success_after_two_invalid_outputs():
    model_client = SequenceModelClient(["not-json", "{}"])
    handler = WorkflowStepHandler(model_client=model_client)

    with pytest.raises(ModelClientError, match="剧本模型连续两次未返回可交付内容"):
        handler._run_script_planner(
            {"storyTheme": "测试逆袭", "episodeLength": "10s"},
            {},
            {"provider": "agnes_chat", "modelName": "agnes-2.0-flash"},
        )


def test_keyframe_generates_white_background_three_view_reference_assets(monkeypatch):
    generated_prompts: list[str] = []

    def fake_image_generator(prompt: str) -> str:
        generated_prompts.append(prompt)
        return f"https://images.example.com/{len(generated_prompts)}.png"

    class FakePersister:
        def persist_images(self, *, task_id, urls):
            return [
                {"url": f"/api/v1/assets/private/images/{task_id}/image-{index}.png", "sourceUrl": url}
                for index, url in enumerate(urls, start=1)
            ]

    monkeypatch.setattr(workflow_step_handler, "_resolve_image_generator", lambda model_config: fake_image_generator)
    monkeypatch.setattr(workflow_step_handler, "GeneratedImagePersister", FakePersister)

    handler = WorkflowStepHandler(backend_client=NoopBackendClient())
    script = _valid_script(1)
    script["characters"] = [{"id": "hero", "name": "Lin Che", "appearance": "silver-haired teen in a blue coat"}]
    script["props"] = [{"id": "key", "name": "star key", "description": "glowing bronze key"}]
    script["locations"] = [{"id": "observatory", "name": "rooftop observatory", "description": "white dome and star map"}]
    script["scenes"][0]["characterRefs"] = ["hero"]
    script["scenes"][0]["propRefs"] = ["key"]
    script["scenes"][0]["locationRefs"] = ["observatory"]

    result = handler._run_keyframe(
        {"visualStyle": "cinematic comic"},
        {"script-planner": script},
        {"provider": "agnes_images", "modelName": "test-image"},
        task_id=987,
        trace_id=None,
    )

    reference_assets = result["referenceAssets"]
    assert [asset["assetType"] for asset in reference_assets] == ["character", "prop", "location"]
    assert [asset["assetId"] for asset in reference_assets] == ["hero", "key", "observatory"]
    assert all(asset["imageUrl"].startswith("/api/v1/assets/private/images/987/") for asset in reference_assets)
    assert all("pure white background" in asset["prompt"].lower() for asset in reference_assets)
    assert all("three-view" in asset["prompt"].lower() for asset in reference_assets)
    assert result["images"][0]["referenceAssetIds"] == ["hero", "key", "observatory"]
    assert any("front view" in prompt.lower() and "side view" in prompt.lower() and "back view" in prompt.lower() for prompt in generated_prompts)


def test_video_node_injects_scene_specific_reference_images_for_agnes_multi_image(monkeypatch):
    captured_calls: list[dict] = []

    def fake_video_generator(**kwargs):
        captured_calls.append(kwargs)
        return {"videoUrl": f"https://videos.example.com/{len(captured_calls)}.mp4"}

    class FakeVideoPersister:
        def persist_video_url(self, *, task_id, source_url, index=1):
            return {"url": f"/api/v1/assets/private/video/{task_id}/video-{index}.mp4", "sourceUrl": source_url}

    monkeypatch.setattr(workflow_step_handler, "_resolve_video_generator", lambda model_config: fake_video_generator)
    monkeypatch.setattr(workflow_step_handler, "GeneratedVideoPersister", FakeVideoPersister)

    handler = WorkflowStepHandler(backend_client=NoopBackendClient())
    script = _valid_script(2)
    script["scenes"][0]["characterRefs"] = ["hero"]
    script["scenes"][0]["propRefs"] = ["key"]
    script["scenes"][0]["locationRefs"] = ["observatory"]
    script["scenes"][1]["characterRefs"] = ["villain"]
    script["scenes"][1]["propRefs"] = []
    script["scenes"][1]["locationRefs"] = ["alley"]

    result = handler._run_video(
        {"storyTheme": "multi-image injection test"},
        {
            "script-planner": script,
            "keyframe": {
                "images": [
                    {"sceneIndex": 1, "imageUrl": "/scene-1.png", "referenceAssetIds": ["hero", "key", "observatory"]},
                    {"sceneIndex": 2, "imageUrl": "/scene-2.png", "referenceAssetIds": ["villain", "alley"]},
                ],
                "referenceAssets": [
                    {"assetId": "hero", "assetType": "character", "imageUrl": "/hero-board.png"},
                    {"assetId": "key", "assetType": "prop", "imageUrl": "/key-board.png"},
                    {"assetId": "observatory", "assetType": "location", "imageUrl": "/observatory-board.png"},
                    {"assetId": "villain", "assetType": "character", "imageUrl": "/villain-board.png"},
                    {"assetId": "alley", "assetType": "location", "imageUrl": "/alley-board.png"},
                ],
            },
        },
        {"provider": "agnes_video", "modelName": "test-agnes-video"},
        task_id=654,
        trace_id=None,
    )

    assert result["clips"][0]["referenceImages"] == ["/hero-board.png", "/key-board.png", "/observatory-board.png"]
    assert result["clips"][1]["referenceImages"] == ["/villain-board.png", "/alley-board.png"]
    assert captured_calls[0]["image"] == "/scene-1.png"
    assert captured_calls[0]["reference_images"] == ["/hero-board.png", "/key-board.png", "/observatory-board.png"]
    assert captured_calls[1]["image"] == "/scene-2.png"
    assert captured_calls[1]["reference_images"] == ["/villain-board.png", "/alley-board.png"]


class NoopBackendClient:
    def mark_processing(self, *args, **kwargs):
        return None
