import json

from handlers import video_generation_handler
from handlers.video_generation_handler import VideoGenerationHandler


class FakeBackendClient:
    def __init__(self) -> None:
        self.success_payload: dict | None = None
        self.failed_payload: dict | None = None
        self.processing: list[dict] = []

    def get_execution_context(self, task_id: int, trace_id: str | None = None) -> dict:
        return {
            "taskId": task_id,
            "status": "QUEUED",
            "traceId": trace_id,
            "params": {
                "prompt": "A glossy product video with smooth camera movement",
                "referenceImages": ["data:image/png;base64,ZmFrZS0x", "data:image/png;base64,ZmFrZS0y"],
                "resolution": "720P",
                "ratio": "16:9",
                "duration": 6,
                "watermark": False,
                "seed": 42,
            },
            "modelConfig": {
                "provider": "bailian_happyhorse",
                "modelName": "happyhorse-1.1-r2v",
                "baseUrl": "https://dashscope.aliyuncs.com",
                "apiKey": "fake-key",
                "timeoutSeconds": 30,
            },
        }

    def mark_processing(
        self,
        task_id: int,
        *,
        progress: int | None = None,
        progress_message: str | None = None,
        trace_id: str | None = None,
    ) -> dict:
        self.processing.append({"progress": progress, "progressMessage": progress_message, "traceId": trace_id})
        return {}

    def mark_success(self, task_id: int, payload: dict, trace_id: str | None = None) -> dict:
        self.success_payload = payload
        return {}

    def mark_failed(self, task_id: int, payload: dict, trace_id: str | None = None) -> dict:
        self.failed_payload = payload
        return {}


class FakeDashScopeClient:
    def __init__(self) -> None:
        self.payload: dict | None = None

    def generate_video(self, payload: dict) -> dict:
        self.payload = payload
        return {
            "provider": "bailian_happyhorse",
            "model": payload["model"],
            "requestId": "req-1",
            "dashscopeTaskId": "task-1",
            "status": "SUCCEEDED",
            "videoUrl": "https://example.com/result.mp4",
            "usage": {"output_video_duration": 6},
        }


class FakeVideoPersister:
    def find_existing_task_video(self, *, task_id: int) -> dict[str, str] | None:
        return None

    def persist_video_url(self, *, task_id: int, source_url: str) -> dict[str, str]:
        return {"url": f"/generated/video/{task_id}/video-1.mp4", "sourceUrl": source_url}


def test_happyhorse_handler_builds_multi_reference_payload_and_bills_seconds() -> None:
    backend = FakeBackendClient()
    dashscope = FakeDashScopeClient()
    handler = VideoGenerationHandler(
        backend_client=backend,
        dashscope_client=dashscope,
        video_persister=FakeVideoPersister(),
    )

    result = handler.handle({"taskId": 12001, "traceId": "trace-happyhorse"})

    assert result["status"] == "SUCCESS"
    assert backend.failed_payload is None
    assert backend.success_payload is not None
    assert backend.success_payload["billableUnits"] == 6
    content = json.loads(backend.success_payload["contentText"])
    assert content["dashscopeTaskId"] == "task-1"
    assert content["videos"][0]["url"] == "/generated/video/12001/video-1.mp4"
    assert dashscope.payload is not None
    assert dashscope.payload["model"] == "happyhorse-1.1-r2v"
    assert dashscope.payload["input"]["media"] == [
        {"type": "reference_image", "url": "data:image/png;base64,ZmFrZS0x"},
        {"type": "reference_image", "url": "data:image/png;base64,ZmFrZS0y"},
    ]
    assert "reference_images" not in dashscope.payload["input"]
    assert dashscope.payload["parameters"]["duration"] == 6
    assert dashscope.payload["parameters"]["ratio"] == "16:9"


def test_happyhorse_video_edit_maps_audio_setting_and_omits_unsupported_params() -> None:
    payload = video_generation_handler._build_happyhorse_payload(
        {
            "prompt": "Apply watercolor style",
            "sourceVideo": "https://example.com/source.mp4",
            "audioSetting": "keep",
            "resolution": "720P",
            "ratio": "16:9",
            "duration": 8,
            "watermark": "false",
        },
        "happyhorse-1.0-video-edit",
    )
    assert payload["parameters"]["audio_setting"] == "origin"
    assert payload["parameters"]["resolution"] == "720P"
    assert payload["parameters"]["watermark"] is False
    assert "ratio" not in payload["parameters"]
    assert "duration" not in payload["parameters"]
    assert payload["input"]["media"][0]["type"] == "video"
    assert "video_url" not in payload["input"]
    assert "source_video_url" not in payload["input"]

    mute_payload = video_generation_handler._build_happyhorse_payload(
        {"prompt": "edit", "sourceVideo": "https://example.com/source.mp4", "audioSetting": "mute"},
        "happyhorse-1.0-video-edit",
    )
    mute_params = mute_payload.get("parameters")
    assert mute_params is None or "audio_setting" not in mute_params


def test_happyhorse_handler_converts_reference_images_to_base64_data_urls(monkeypatch) -> None:
    backend = FakeBackendClient()
    dashscope = FakeDashScopeClient()
    original_get_execution_context = backend.get_execution_context

    def get_execution_context(task_id: int, trace_id: str | None = None) -> dict:
        context = original_get_execution_context(task_id, trace_id)
        context["params"]["referenceImages"] = [
            "http://backend:8080/generated/uploads/20260607/ref-1.png",
            "http://backend:8080/generated/uploads/20260607/ref-2.png",
        ]
        return context

    backend.get_execution_context = get_execution_context

    def fake_resolve(value: str) -> str:
        return f"data:image/png;base64,{value.rsplit('/', 1)[-1].encode().hex()}"

    monkeypatch.setattr(video_generation_handler, "resolve_reference_image_data_url", fake_resolve)
    handler = VideoGenerationHandler(
        backend_client=backend,
        dashscope_client=dashscope,
        video_persister=FakeVideoPersister(),
    )

    result = handler.handle({"taskId": 12002, "traceId": "trace-happyhorse-base64"})

    assert result["status"] == "SUCCESS"
    assert dashscope.payload is not None
    media = dashscope.payload["input"]["media"]
    assert media == [
        {"type": "reference_image", "url": "data:image/png;base64,7265662d312e706e67"},
        {"type": "reference_image", "url": "data:image/png;base64,7265662d322e706e67"},
    ]
    assert all(not item["url"].startswith("http://backend:8080/generated/") for item in media)


def test_current_happyhorse_i2v_uses_media_without_legacy_img_url(monkeypatch) -> None:
    monkeypatch.setattr(video_generation_handler, "_resolve_happyhorse_image_data_url", lambda value, _field: value)

    payload = video_generation_handler._build_happyhorse_payload(
        {"prompt": "animate", "firstFrameImage": "https://example.com/first.png"},
        "happyhorse-1.1-i2v",
    )

    assert payload["input"]["media"] == [
        {"type": "first_frame", "url": "https://example.com/first.png"}
    ]
    assert "img_url" not in payload["input"]


def test_unknown_legacy_happyhorse_model_keeps_compatibility_fields(monkeypatch) -> None:
    monkeypatch.setattr(video_generation_handler, "_resolve_happyhorse_image_data_url", lambda value, _field: value)

    payload = video_generation_handler._build_happyhorse_payload(
        {"prompt": "animate", "firstFrameImage": "https://example.com/first.png"},
        "happyhorse-legacy-i2v",
    )

    assert payload["input"]["media"][0]["type"] == "first_frame"
    assert payload["input"]["img_url"] == "https://example.com/first.png"
