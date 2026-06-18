import json

import pytest

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
