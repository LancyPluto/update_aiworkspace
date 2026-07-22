import json
from pathlib import Path
from types import SimpleNamespace

import pytest

import handlers.workflow_step_handler as workflow_step_handler
from client.text_to_speech_client import SpeechGenerationResult
from handlers.workflow_step_handler import WorkflowStepHandler


class RecordingBackend:
    def __init__(self):
        self.version = 0
        self.checkpoints: list[dict] = []
        self.success_payload: dict | None = None
        self.failed_payload: dict | None = None

    def mark_processing(self, *args, **kwargs):
        return None

    def save_provider_checkpoint(
        self,
        task_id,
        checkpoint,
        *,
        expected_version,
        trace_id=None,
        claim_token=None,
    ):
        assert expected_version == self.version
        self.version += 1
        copied = json.loads(json.dumps(checkpoint))
        self.checkpoints.append(copied)
        return {"version": self.version, "checkpoint": copied}

    def mark_success(self, task_id, payload, trace_id=None):
        self.success_payload = payload

    def mark_failed(self, task_id, payload, trace_id=None):
        self.failed_payload = payload


def _context(handler_key: str, operation_input: dict, *, node_type: str = "IMAGE_MODEL") -> dict:
    billing_unit = {
        "LLM_TEXT": "TOKEN_PER_M",
        "VIDEO_MODEL": "PER_SECOND",
        "TTS_MODEL": "PER_CALL",
    }.get(node_type, "PER_CALL")
    return {
        "status": "PROCESSING",
        "params": {
            "workflowStep": True,
            "nodeDefType": node_type,
            "parameters": {"handlerKey": handler_key},
            "workflowInputs": {"operationInput": operation_input},
        },
        "modelConfig": {
            "provider": "test-provider",
            "modelName": "test-model",
            "apiKey": "test-key",
            "billingUnit": billing_unit,
        },
    }


def _output(backend: RecordingBackend) -> dict:
    assert backend.success_payload is not None
    return json.loads(backend.success_payload["contentText"])


def _shot() -> dict:
    return {
        "shotId": "ep01-shot003",
        "shotVersionId": "ep01-shot003:v2",
        "order": 3,
        "timecode": {"startMs": 3000, "endMs": 4000, "targetDurationMs": 1000},
        "camera": {
            "shotSize": "MEDIUM",
            "angle": "EYE_LEVEL",
            "movement": "STATIC",
            "composition": "两位老人坐在长椅两侧",
        },
        "performance": {"emotion": "惊讶错愕", "action": "老人紧握拐杖"},
        "visualDescription": "Two elderly women sit apart on a long bench while a girl steps back.",
        "audio": {
            "dialogue": "别过来。",
            "narration": "",
            "subtitleZh": "别过来。",
            "subtitleEn": "Stay back.",
            "sfx": ["细碎私语声"],
            "bgmMood": "紧张",
        },
        "references": {
            "characterVersionIds": ["char-old-woman:v3"],
            "sceneVersionIds": ["scene-bench:v2"],
        },
        "prompts": {
            "image": "Cinematic medium shot on the bench.",
            "video": "The old woman tightens her grip while the girl steps back.",
            "negative": "identity drift, text, watermark",
        },
    }


def test_parameters_handler_key_dispatches_imported_script_without_model_call():
    backend = RecordingBackend()
    handler = WorkflowStepHandler(backend_client=backend)
    context = _context(
        "comic.script",
        {
            "script": {
                "scriptVersionId": "script:v7",
                "title": "完整剧本",
                "screenplay": "第一幕，女孩进入庭院。第二幕，她发现长椅上的秘密。",
            }
        },
        node_type="LLM_TEXT",
    )
    context["params"]["workflowInputs"]["parameters"] = context["params"].pop("parameters")

    result = handler.handle(
        {
            "taskId": 801,
            "__executionContext": context,
        }
    )

    assert result["status"] == "SUCCESS"
    assert result["handlerKey"] == "comic.script"
    output = _output(backend)
    assert output["script"]["scriptVersionId"] == "script:v7"
    assert "screenplay" not in output
    assert backend.checkpoints == []


def test_script_generation_is_separate_from_storyboard_and_checkpointed():
    screenplay = "第一幕，女孩进入庭院并发现异常。冲突逐步升级，她最终揭开秘密并作出选择。" * 10

    class FakeModel:
        def __init__(self):
            self.prompt = ""

        def generate_with_usage(self, prompt, **kwargs):
            self.prompt = prompt
            return {
                "content": json.dumps(
                    {
                        "title": "庭院秘密",
                        "synopsis": "女孩揭开庭院秘密。",
                        "screenplay": screenplay,
                        "genre": "悬疑",
                        "characters": [{"id": "hero", "name": "小雨", "appearance": "短发蓝衣"}],
                        "locations": [{"id": "yard", "name": "庭院", "description": "老旧庭院"}],
                        "props": [],
                    },
                    ensure_ascii=False,
                ),
                "promptTokens": 120,
                "completionTokens": 240,
            }

    backend = RecordingBackend()
    model = FakeModel()
    handler = WorkflowStepHandler(backend_client=backend, model_client=model)

    result = handler.handle(
        {
            "taskId": 807,
            "__executionContext": _context(
                "comic.script",
                {"projectId": "comic-1", "storyTheme": "庭院秘密", "episodeLength": "60s"},
                node_type="LLM_TEXT",
            ),
        }
    )

    assert result["status"] == "SUCCESS"
    assert "不要输出分镜表" in model.prompt
    output = _output(backend)
    assert "shots" not in output["script"]
    assert output["script"]["characters"][0]["assetId"] == "hero"
    assert output["script"]["screenplay"] == screenplay
    assert "screenplay" not in output
    assert [item["status"] for item in backend.checkpoints] == ["STARTED", "COMPLETED"]
    assert "screenplay" not in backend.checkpoints[-1]["result"]

    class ReplayMustNotCallModel:
        def generate_with_usage(self, *_args, **_kwargs):
            raise AssertionError("completed checkpoint replay must not call the provider")

    replay_backend = RecordingBackend()
    replay_backend.version = backend.version
    replay_context = _context(
        "comic.script",
        {"projectId": "comic-1", "storyTheme": "庭院秘密", "episodeLength": "60s"},
        node_type="LLM_TEXT",
    )
    replay_context["providerCheckpoint"] = backend.checkpoints[-1]
    replay_context["providerCheckpointVersion"] = backend.version
    replay_handler = WorkflowStepHandler(
        backend_client=replay_backend,
        model_client=ReplayMustNotCallModel(),
    )

    replay_result = replay_handler.handle({"taskId": 807, "__executionContext": replay_context})

    assert replay_result["status"] == "SUCCESS"
    replay_output = _output(replay_backend)
    assert replay_output["promptTokens"] == 120
    assert replay_output["completionTokens"] == 240
    assert replay_backend.checkpoints == []


def test_storyboard_normalizes_time_camera_emotion_picture_and_audio_fields():
    backend = RecordingBackend()
    handler = WorkflowStepHandler(backend_client=backend)
    raw = {
        "storyboard": {
            "storyboardVersionId": "storyboard:v4",
            "title": "庭院秘密",
            "shots": [_shot()],
        }
    }

    result = handler.handle(
        {
            "taskId": 802,
            "__executionContext": _context("comic.storyboard", raw, node_type="LLM_TEXT"),
        }
    )

    assert result["status"] == "SUCCESS"
    shot = _output(backend)["shots"][0]
    assert shot["timecode"] == {"startMs": 3000, "endMs": 4000, "targetDurationMs": 1000}
    assert shot["camera"]["shotSize"] == "MEDIUM"
    assert shot["camera"]["angle"] == "EYE_LEVEL"
    assert shot["performance"]["emotion"] == "惊讶错愕"
    assert shot["visualDescription"].startswith("Two elderly women")
    assert shot["audio"]["sfx"] == ["细碎私语声"]
    assert shot["audio"]["bgmMood"] == "紧张"


def test_storyboard_preserves_named_character_and_scene_references_for_asset_drafts():
    backend = RecordingBackend()
    handler = WorkflowStepHandler(backend_client=backend)
    source_shot = {
        **_shot(),
        "characters": [
            {"name": "小雨", "description": "短发蓝衣的年轻调查员"},
            "character-id-only",
        ],
        "scene": {"name": "旧庭院", "description": "斑驳砖墙笼罩在冷色月光下"},
    }

    result = handler.handle(
        {
            "taskId": 803,
            "__executionContext": _context(
                "comic.storyboard",
                {"storyboard": {"shots": [source_shot]}},
                node_type="LLM_TEXT",
            ),
        }
    )

    assert result["status"] == "SUCCESS"
    references = _output(backend)["shots"][0]["references"]
    assert references["characters"] == [{"name": "小雨", "description": "短发蓝衣的年轻调查员"}]
    assert references["scenes"] == [{"name": "旧庭院", "description": "斑驳砖墙笼罩在冷色月光下"}]


def test_storyboard_splits_imported_script_with_checkpointed_model_call():
    class FakeModel:
        def __init__(self):
            self.calls = 0

        def generate_with_usage(self, prompt, **kwargs):
            self.calls += 1
            assert "完整剧本" in prompt
            return {
                "content": json.dumps({"title": "庭院秘密", "shots": [_shot()]}, ensure_ascii=False),
                "promptTokens": 80,
                "completionTokens": 160,
            }

    backend = RecordingBackend()
    model = FakeModel()
    handler = WorkflowStepHandler(backend_client=backend, model_client=model)
    operation_input = {
        "script": {
            "scriptVersionId": "script:v7",
            "screenplay": "完整剧本：女孩走入庭院并发现两位老人守护的秘密。",
        },
        "shotCount": 1,
    }

    result = handler.handle(
        {"taskId": 809, "__executionContext": _context("comic.storyboard", operation_input, node_type="LLM_TEXT")}
    )

    assert result["status"] == "SUCCESS"
    assert model.calls == 1
    assert _output(backend)["scriptVersionId"] == "script:v7"
    assert [item["status"] for item in backend.checkpoints] == ["STARTED", "COMPLETED"]


@pytest.mark.parametrize(
    ("handler_key", "input_key", "asset", "expected_type", "expected_role"),
    [
        (
            "comic.character_reference",
            "character",
            {"assetId": "char-hero", "assetVersionId": "char-hero:v2", "name": "林澈", "appearance": "银发蓝衣"},
            "CHARACTER",
            "three_view_board",
        ),
        (
            "comic.scene_reference",
            "scene",
            {"assetId": "scene-roof", "assetVersionId": "scene-roof:v5", "name": "天台", "description": "白色穹顶"},
            "SCENE",
            "scene_anchor_board",
        ),
    ],
)
def test_reference_operations_emit_versioned_boards(
    monkeypatch,
    handler_key,
    input_key,
    asset,
    expected_type,
    expected_role,
):
    prompts: list[str] = []

    def fake_generator(prompt, reference_images=None):
        prompts.append(prompt)
        assert reference_images == []
        return "https://provider.example/reference.png"

    class FakePersister:
        def persist_images(self, *, task_id, urls):
            return [{"url": f"/generated/images/{task_id}/reference.png", "sourceUrl": urls[0]}]

    monkeypatch.setattr(workflow_step_handler, "_resolve_image_generator", lambda _config: fake_generator)
    monkeypatch.setattr(workflow_step_handler, "GeneratedImagePersister", FakePersister)
    backend = RecordingBackend()

    result = WorkflowStepHandler(backend_client=backend).handle(
        {"taskId": 810, "__executionContext": _context(handler_key, {input_key: asset})}
    )

    assert result["status"] == "SUCCESS"
    version = _output(backend)["referenceAssetVersion"]
    assert version["assetType"] == expected_type
    assert version["assetVersionId"] == asset["assetVersionId"]
    assert version["views"][0]["role"] == expected_role
    assert "three-view reference board" in prompts[0]


def test_single_shot_keyframe_passes_approved_character_and_scene_versions(monkeypatch):
    captured: dict = {}

    def fake_generator(prompt, reference_images=None):
        captured["prompt"] = prompt
        captured["referenceImages"] = reference_images
        return "https://provider.example/keyframe.png"

    class FakePersister:
        def persist_images(self, *, task_id, urls):
            return [{"url": f"/generated/images/{task_id}/image-1.png", "sourceUrl": urls[0]}]

    monkeypatch.setattr(workflow_step_handler, "_resolve_image_generator", lambda _config: fake_generator)
    monkeypatch.setattr(workflow_step_handler, "GeneratedImagePersister", FakePersister)
    backend = RecordingBackend()
    handler = WorkflowStepHandler(backend_client=backend)
    operation_input = {
        "shot": _shot(),
        "characterReferences": {
            "referenceAssetVersions": [
                {
                    "assetVersionId": "char-old-woman:v3",
                    "status": "APPROVED",
                    "views": {
                        "front": {"url": "/refs/old-woman-front.png"},
                        "side": {"url": "/refs/old-woman-side.png"},
                        "back": {"url": "/refs/old-woman-back.png"},
                    },
                }
            ]
        },
        "sceneReferences": {
            "referenceAssetVersions": [
                {
                    "assetVersionId": "scene-bench:v2",
                    "status": "LOCKED",
                    "anchors": [{"role": "wide", "url": "/refs/bench-wide.png"}],
                }
            ]
        },
    }

    result = handler.handle(
        {"taskId": 803, "claimToken": "claim-803", "__executionContext": _context("comic.shot_keyframe", operation_input)}
    )

    assert result["status"] == "SUCCESS"
    assert captured["referenceImages"] == [
        "/refs/old-woman-front.png",
        "/refs/old-woman-side.png",
        "/refs/old-woman-back.png",
        "/refs/bench-wide.png",
    ]
    output = _output(backend)
    assert output["keyframeVersion"]["referenceAssetVersionIds"] == [
        "char-old-woman:v3",
        "scene-bench:v2",
    ]
    assert [item["status"] for item in backend.checkpoints] == ["STARTED", "COMPLETED"]


def test_single_shot_video_checkpoints_and_reuses_itemized_provider_result(monkeypatch):
    captured: dict = {}

    def fake_generator(**kwargs):
        captured.update(kwargs)
        kwargs["submitted_callback"]({"taskId": "provider-task-1", "requestId": "provider-request-1"})
        return {
            "requestId": "provider-request-1",
            "providerCostAmount": "0.250000",
            "providerCostCurrency": "usd",
            "videoUrl": "https://provider.example/shot-3.mp4",
        }

    class FakePersister:
        def persist_video_url(self, *, task_id, source_url, index=1):
            return {"url": f"/generated/video/{task_id}/video-{index}.mp4", "sourceUrl": source_url}

    monkeypatch.setattr(workflow_step_handler, "_resolve_video_generator", lambda _config: fake_generator)
    monkeypatch.setattr(workflow_step_handler, "GeneratedVideoPersister", FakePersister)
    operation_input = {
        "shot": _shot(),
        "selectedKeyframeVersion": {
            "shotId": "ep01-shot003",
            "keyframeVersionId": "keyframe:v8",
            "imageUrl": "/frames/shot-3-v8.png",
        },
        "characterReferenceAssetUrls": ["/refs/old-woman-board.png"],
        "sceneReferenceAssetUrls": ["/refs/bench-board.png"],
    }
    backend = RecordingBackend()
    context = _context("comic.shot_video", operation_input, node_type="VIDEO_MODEL")
    context["modelConfig"]["provider"] = "agnes_video"
    handler = WorkflowStepHandler(backend_client=backend)

    result = handler.handle({"taskId": 804, "claimToken": "claim-804", "__executionContext": context})

    assert result["status"] == "SUCCESS"
    assert captured["image"] == "/frames/shot-3-v8.png"
    assert captured["reference_images"] == ["/refs/old-woman-board.png", "/refs/bench-board.png"]
    assert captured["duration"] == 1
    assert [item["status"] for item in backend.checkpoints] == ["STARTED", "SUBMITTED", "COMPLETED"]
    assert backend.success_payload["providerRequestId"] == "provider-request-1"
    assert backend.success_payload["providerCostAmount"] == "0.250000"
    completed = backend.checkpoints[-1]

    replay_backend = RecordingBackend()
    replay_backend.version = backend.version
    replay_context = _context("comic.shot_video", operation_input, node_type="VIDEO_MODEL")
    replay_context["modelConfig"]["provider"] = "agnes_video"
    replay_context["providerCheckpoint"] = completed
    replay_context["providerCheckpointVersion"] = backend.version
    monkeypatch.setattr(
        workflow_step_handler,
        "_resolve_video_generator",
        lambda _config: (_ for _ in ()).throw(AssertionError("completed shot must not call provider again")),
    )

    replay = WorkflowStepHandler(backend_client=replay_backend).handle(
        {"taskId": 804, "__executionContext": replay_context}
    )

    assert replay["status"] == "SUCCESS"
    assert replay_backend.checkpoints == []
    assert _output(replay_backend)["clipVersion"]["clipVersionId"] == _output(backend)["clipVersion"]["clipVersionId"]


def test_single_shot_tts_uses_configured_provider_and_reports_character_usage():
    calls: list[dict] = []

    class FakeSpeechClient:
        def generate(self, **kwargs):
            calls.append(kwargs)
            return SpeechGenerationResult(
                audio_url="https://provider.example/shot-audio.wav",
                extension="wav",
                metadata={
                    "providerRequestId": "dashscope-request-805",
                    "billableUnits": 10,
                },
            )

    class FakeAudioPersister:
        def persist_audio_url(self, *, task_id, source_url, extension=None, index=1):
            return {"url": f"/generated/audio/{task_id}/audio-{index}.mp3", "sourceUrl": "omitted"}

    backend = RecordingBackend()
    handler = WorkflowStepHandler(
        backend_client=backend,
        tts_client=FakeSpeechClient(),
        audio_persister=FakeAudioPersister(),
    )
    shot = _shot()
    shot["audio"] = {
        **shot["audio"],
        "dialogue": "Buy it now",
        "voiceId": "Ethan",
        "language": "English",
    }
    context = _context("comic.shot_tts", {"shot": shot}, node_type="TTS_MODEL")
    context["modelConfig"].update(
        {
            "provider": "dashscope_qwen_tts",
            "modelName": "qwen3-tts-flash",
            "baseUrl": "https://dashscope.aliyuncs.com",
            "apiKey": "dashscope-secret",
            "billingUnit": "PER_CHARACTER",
            "executionOptionsJson": json.dumps(
                {"voice": "Cherry", "languageType": "Chinese"}
            ),
        }
    )

    result = handler.handle(
        {"taskId": 805, "__executionContext": context}
    )

    assert result["status"] == "SUCCESS"
    assert calls == [
        {
            "provider": "dashscope_qwen_tts",
            "model": "qwen3-tts-flash",
            "text": "Buy it now",
            "base_url": "https://dashscope.aliyuncs.com",
            "api_key": "dashscope-secret",
            "params": {"voice": "Ethan", "languageType": "English"},
        }
    ]
    assert _output(backend)["audioVersion"]["audioUrl"] == "/generated/audio/805/audio-1.mp3"
    assert _output(backend)["audioVersion"]["voice"] == "Ethan"
    assert _output(backend)["audioVersion"]["languageType"] == "English"
    assert backend.success_payload["billableUnits"] == 10
    assert backend.success_payload["providerRequestId"] == "dashscope-request-805"
    assert _output(backend)["providerCalls"][0]["billableUnits"] == 10
    assert [item["status"] for item in backend.checkpoints] == ["STARTED", "COMPLETED"]


def test_generic_tts_model_uses_same_configured_client():
    calls: list[dict] = []

    class FakeSpeechClient:
        def generate(self, **kwargs):
            calls.append(kwargs)
            return SpeechGenerationResult(
                audio_bytes=b"generic-audio",
                content_type="audio/wav",
                extension="wav",
                metadata={"billableUnits": 10},
            )

    class FakeAudioPersister:
        def persist_audio_bytes(self, **kwargs):
            return {"url": "/generated/audio/811/audio-1.wav", "sourceUrl": "generated"}

    context = {
        "status": "PROCESSING",
        "params": {
            "workflowStep": True,
            "nodeDefType": "TTS_MODEL",
            "workflowInputs": {
                "script-planner": {
                    "scenes": [{"index": 1, "dialogue": "Launch now"}]
                }
            },
        },
        "modelConfig": {
            "provider": "dashscope_qwen_tts",
            "modelName": "qwen3-tts-flash",
            "baseUrl": "https://dashscope.aliyuncs.com",
            "apiKey": "dashscope-secret",
            "billingUnit": "PER_CHARACTER",
            "executionOptionsJson": {"voice": "Cherry", "languageType": "Chinese"},
        },
    }
    backend = RecordingBackend()
    handler = WorkflowStepHandler(
        backend_client=backend,
        tts_client=FakeSpeechClient(),
        audio_persister=FakeAudioPersister(),
    )

    result = handler.handle({"taskId": 811, "__executionContext": context})

    assert result["status"] == "SUCCESS"
    assert calls[0]["provider"] == "dashscope_qwen_tts"
    assert calls[0]["params"] == {"voice": "Cherry", "languageType": "Chinese"}
    assert _output(backend)["audios"][0]["audioUrl"] == "/generated/audio/811/audio-1.wav"
    assert backend.success_payload["billableUnits"] == 10


def test_compose_uses_only_selected_versions_in_story_order():
    processed: list[str] = []
    concatenated: list[Path] = []
    subtitle_timeline: list[tuple[list[Path], list[str]]] = []

    class FakePostprocessor:
        def process(self, *, task_id, video_url, audio_url, subtitle_text, segment):
            processed.append(video_url)
            return SimpleNamespace(
                video_path=Path(f"C:/tmp/{segment}.mp4"),
                video_url=f"/rendered/{segment}.mp4",
                subtitle_url=f"/rendered/{segment}.srt",
            )

        def concat_videos(self, *, task_id, video_paths, output_name):
            concatenated.extend(video_paths)
            return Path("C:/tmp/comic-final.mp4"), "/rendered/comic-final.mp4"

        def concat_subtitles(self, *, task_id, video_paths, subtitle_texts, output_name):
            subtitle_timeline.append((video_paths, subtitle_texts))
            return Path("C:/tmp/comic-final.srt"), "/rendered/comic-final.srt"

    selected = [
        {
            "shotId": "shot-2",
            "shotVersionId": "shot-2:v3",
            "order": 2,
            "clipVersionId": "clip-2:v7",
            "videoUrl": "/clips/shot-2-v7.mp4",
            "audioVersionId": "audio-2:v1",
            "audioUrl": "/audio/shot-2.mp3",
            "subtitleZh": "第二镜",
        },
        {
            "shotId": "shot-old",
            "shotVersionId": "shot-old:v1",
            "order": 9,
            "clipVersionId": "clip-old:v1",
            "videoUrl": "/clips/old.mp4",
            "selected": False,
        },
        {
            "shotId": "shot-1",
            "shotVersionId": "shot-1:v4",
            "order": 1,
            "clipVersionId": "clip-1:v9",
            "videoUrl": "/clips/shot-1-v9.mp4",
            "subtitleZh": "第一镜",
        },
    ]
    backend = RecordingBackend()
    handler = WorkflowStepHandler(backend_client=backend, postprocessor=FakePostprocessor())

    result = handler.handle(
        {
            "taskId": 806,
            "__executionContext": _context(
                "comic.compose",
                {"title": "版本选择测试", "selectedShotVersions": selected},
                node_type="SUBTITLE",
            ),
        }
    )

    assert result["status"] == "SUCCESS"
    assert processed == ["/clips/shot-1-v9.mp4", "/clips/shot-2-v7.mp4"]
    assert [path.name for path in concatenated] == ["shot-1-shot-1.mp4", "shot-2-shot-2.mp4"]
    assert subtitle_timeline == [(concatenated, ["第一镜", "第二镜"])]
    assert _output(backend)["subtitleUrl"] == "/rendered/comic-final.srt"
    manifest = _output(backend)["compositionManifest"]
    assert [item["clipVersionId"] for item in manifest["selectedShotVersions"]] == ["clip-1:v9", "clip-2:v7"]


def test_comic_final_srt_offsets_each_shot_by_real_duration():
    from handlers.digital_human_postprocessor import DigitalHumanPostprocessor

    content = DigitalHumanPostprocessor._build_timeline_srt(
        [("第一镜", 5.0), ("第二镜", 3.25)]
    )

    assert "00:00:00,000 --> 00:00:05,000" in content
    assert "00:00:05,000 --> 00:00:08,250" in content
    assert content.index("第一镜") < content.index("第二镜")


def test_seedance_resolver_orders_base_frame_before_reference_images(monkeypatch):
    captured: dict = {}

    class FakeSeedance:
        @classmethod
        def from_model_config(cls, _config):
            return cls()

        def generate_video(self, **kwargs):
            captured.update(kwargs)
            return {"videoUrl": "https://provider.example/result.mp4"}

    monkeypatch.setattr(workflow_step_handler.provider_registry, "provider_protocol", lambda _provider: "seedance")
    monkeypatch.setattr(workflow_step_handler, "SeedanceVideoClient", FakeSeedance)
    generate = workflow_step_handler._resolve_video_generator(
        {"provider": "seedance", "modelName": "seedance-test", "apiKey": "key"}
    )

    generate(
        prompt="animate",
        image="/frames/base.png",
        reference_images=["/refs/character.png", "/refs/scene.png"],
        duration=4,
        aspect_ratio="9:16",
    )

    assert captured["image"] == "/frames/base.png"
    assert captured["images"] == ["/frames/base.png", "/refs/character.png", "/refs/scene.png"]
    assert captured["duration"] == "4"
    assert captured["aspect_ratio"] == "9:16"


def test_openai_image_resolver_passes_reference_assets_to_edit_input(monkeypatch):
    captured: dict = {}

    class FakeImagesClient:
        def __init__(self, **kwargs):
            self.last_usage = {"billableUnits": 1}

        def generate_images(self, **kwargs):
            captured.update(kwargs)
            return ["https://provider.example/keyframe.png"]

    monkeypatch.setattr(workflow_step_handler.provider_registry, "provider_protocol", lambda _provider: "openai_images")
    monkeypatch.setattr(workflow_step_handler, "OpenAIImagesClient", FakeImagesClient)
    generate = workflow_step_handler._resolve_image_generator(
        {"provider": "agnes_images", "modelName": "agnes-image", "apiKey": "key"}
    )

    generate(
        "draw the selected shot",
        reference_images=["/refs/front.png", "/refs/side.png", "/refs/scene.png"],
    )

    assert captured["image"] == ["/refs/front.png", "/refs/side.png", "/refs/scene.png"]
