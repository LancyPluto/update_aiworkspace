import json
import base64
import sys
import tempfile
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from handlers.image_generation_handler import ImageGenerationHandler
from handlers.video_generation_handler import VideoGenerationHandler
from client.kling_video_client import KlingVideoClient


class FakeBackendClient:
    def __init__(self, *, tool_type: str) -> None:
        self.tool_type = tool_type
        self.processing: list[dict] = []
        self.success_payload: dict | None = None
        self.failed_payload: dict | None = None

    def get_execution_context(self, task_id: int, trace_id: str | None = None) -> dict:
        return {
            "taskId": task_id,
            "taskNo": f"T{task_id}",
            "toolCode": "kling_test_tool",
            "toolType": self.tool_type,
            "executionHandler": self.tool_type,
            "status": "QUEUED",
            "traceId": trace_id,
            "params": {
                "prompt": "A cinematic product launch video with soft studio lighting",
                "aspectRatio": "16:9",
                "duration": "5",
                "resolution": "720p",
                "count": 2,
                "imageUrl": "https://example.com/start.png",
                "imageTail": "https://example.com/end.png",
                "sound": "off",
            },
            "modelConfig": {
                "provider": "kling_video",
                "modelName": "kling-v2-6",
                "baseUrl": "https://api-beijing.klingai.com",
                "extraAuthJson": '{"accessKey":"fake-ak","secretKey":"fake-sk"}',
                "timeoutSeconds": 30,
            },
            "fields": [],
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


class FakeKlingClient:
    def __init__(self) -> None:
        self.video_request: dict | None = None
        self.image_request: dict | None = None

    def generate_video(self, **kwargs) -> dict:
        self.video_request = kwargs
        return {
            "requestId": "kling_fake_video_001",
            "status": "succeed",
            "videoUrl": "https://example.com/kling-video.mp4",
            "provider": "kling_video",
            "model": kwargs.get("model") or "kling-v2-6",
            "resolution": kwargs.get("resolution"),
        }

    def generate_images(self, **kwargs) -> list[str]:
        self.image_request = kwargs
        return [
            "https://example.com/kling-image-1.png",
            "https://example.com/kling-image-2.png",
        ]


class FakeVideoPersister:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    def persist_video_url(self, *, task_id: int, source_url: str) -> dict[str, str]:
        self.calls.append({"taskId": task_id, "sourceUrl": source_url})
        return {
            "url": f"/generated/video/{task_id}/video-1.mp4",
            "sourceUrl": source_url,
            "contentType": "video/mp4",
        }


class FakeImagePersister:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    def persist_images(self, *, task_id: int, urls: list[str]) -> list[dict[str, str]]:
        self.calls.append({"taskId": task_id, "urls": urls})
        return [
            {"url": f"/generated/images/{task_id}/image-{index}.png", "sourceUrl": url}
            for index, url in enumerate(urls, start=1)
        ]


def test_kling_video_handler() -> None:
    backend = FakeBackendClient(tool_type="VIDEO_GENERATION")
    client = FakeKlingClient()
    persister = FakeVideoPersister()
    handler = VideoGenerationHandler(
        backend_client=backend,
        kling_client=client,
        video_persister=persister,
    )

    result = handler.handle({"taskId": 99201, "traceId": "fake-kling-video"})

    assert result["status"] == "SUCCESS", result
    assert backend.failed_payload is None, backend.failed_payload
    assert backend.success_payload is not None
    assert backend.success_payload["resourceType"] == "VIDEO"
    assert backend.success_payload["billableUnits"] == 1
    content = json.loads(backend.success_payload["contentText"])
    assert content["provider"] == "kling_video", content
    assert content["videos"][0]["url"] == "/generated/video/99201/video-1.mp4", content
    assert client.video_request is not None
    assert client.video_request["model"] == "kling-v2-6"
    assert client.video_request["image"] == "https://example.com/start.png"
    assert client.video_request["image_tail"] == "https://example.com/end.png"
    assert client.video_request["sound"] == "off"
    assert client.video_request["resolution"] == "720p"
    assert client.video_request["duration"] == "5"
    assert persister.calls[0]["sourceUrl"] == "https://example.com/kling-video.mp4"


def test_kling_image_handler() -> None:
    backend = FakeBackendClient(tool_type="IMAGE_GENERATION")
    client = FakeKlingClient()
    persister = FakeImagePersister()
    handler = ImageGenerationHandler(
        backend_client=backend,
        image_client=client,
        image_persister=persister,
    )

    result = handler.handle({"taskId": 99202, "traceId": "fake-kling-image"})

    assert result["status"] == "SUCCESS", result
    assert backend.failed_payload is None, backend.failed_payload
    assert backend.success_payload is not None
    assert backend.success_payload["resourceType"] == "IMAGE"
    assert backend.success_payload["billableUnits"] == 2
    assert client.image_request is not None
    assert client.image_request["model"] == "kling-v2-6"
    assert client.image_request["batch_size"] == 2
    assert persister.calls[0]["urls"] == [
        "https://example.com/kling-image-1.png",
        "https://example.com/kling-image-2.png",
    ]


def test_kling_client_encodes_input_images() -> None:
    client = KlingVideoClient(access_key="fake-ak", secret_key="fake-sk")
    expected_start = base64.b64encode(b"start-image").decode("ascii")
    expected_tail = base64.b64encode(b"tail-image").decode("ascii")
    with tempfile.TemporaryDirectory() as tmp:
        start = Path(tmp) / "start.png"
        tail = Path(tmp) / "tail.png"
        start.write_bytes(b"start-image")
        tail.write_bytes(b"tail-image")
        payload = client._build_video_payload(
            prompt="镜头拉远",
            image_size="1280x720",
            negative_prompt="",
            model="kling-v2-6",
            image=str(start),
            image_tail=f"data:image/png;base64,{expected_tail}",
            seed=None,
            duration="5",
            aspect_ratio="16:9",
            resolution="",
            mode="pro",
            sound="off",
            callback_url="",
            external_task_id="",
        )
    assert payload["image"] == expected_start
    assert payload["image_tail"] == expected_tail


def test_kling_client_uses_image2video_result_path() -> None:
    class RecordingKlingClient(KlingVideoClient):
        def __init__(self) -> None:
            super().__init__(access_key="fake-ak", secret_key="fake-sk")
            self.requests: list[tuple[str, str]] = []

        def _image_to_base64(self, value: str) -> str:
            return base64.b64encode(value.encode("utf-8")).decode("ascii")

        def _request(self, method: str, path: str, payload: dict | None) -> dict:
            self.requests.append((method, path))
            if method == "POST":
                return {"data": {"task_id": "task-123"}}
            return {"data": {"task_status": "succeed", "task_result": {"videos": [{"url": "https://example.com/out.mp4"}]}}}

    client = RecordingKlingClient()
    result = client.generate_video(prompt="镜头拉远", image_size="1280x720", image="https://example.com/start.png")
    assert result["videoUrl"] == "https://example.com/out.mp4"
    assert client.requests == [
        ("POST", "/v1/videos/image2video"),
        ("GET", "/v1/videos/image2video/task-123"),
    ]


if __name__ == "__main__":
    test_kling_video_handler()
    test_kling_image_handler()
    test_kling_client_encodes_input_images()
    test_kling_client_uses_image2video_result_path()
    print("FAKE_KLING_INTEGRATION_TEST_PASSED")
