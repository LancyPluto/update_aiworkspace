import json
import base64
import sys
import tempfile
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from handlers.image_generation_handler import ImageGenerationHandler
from handlers.video_generation_handler import VideoGenerationHandler
from client.kling_video_client import KlingVideoClient, KlingVideoError


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

    def find_existing_task_video(self, *, task_id: int) -> dict[str, str] | None:
        return None

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
    assert backend.success_payload["billableUnits"] == 5
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
    assert client.image_request["image"] == "https://example.com/start.png"
    assert persister.calls[0]["urls"] == [
        "https://example.com/kling-image-1.png",
        "https://example.com/kling-image-2.png",
    ]


def test_kling_video_handler_uses_params_model_override() -> None:
    backend = FakeBackendClient(tool_type="VIDEO_GENERATION")
    context = backend.get_execution_context(99203)
    context["params"]["model"] = "kling-v3"
    backend.get_execution_context = lambda task_id, trace_id=None: context
    client = FakeKlingClient()
    handler = VideoGenerationHandler(
        backend_client=backend,
        kling_client=client,
        video_persister=FakeVideoPersister(),
    )

    result = handler.handle({"taskId": 99203, "traceId": "fake-kling-model-override"})

    assert result["status"] == "SUCCESS", result
    assert client.video_request is not None
    assert client.video_request["model"] == "kling-v3"


def test_kling_image_handler_uses_params_model_override() -> None:
    backend = FakeBackendClient(tool_type="IMAGE_GENERATION")
    context = backend.get_execution_context(99204)
    context["params"]["model"] = "kling-v3"
    backend.get_execution_context = lambda task_id, trace_id=None: context
    client = FakeKlingClient()
    handler = ImageGenerationHandler(
        backend_client=backend,
        image_client=client,
        image_persister=FakeImagePersister(),
    )

    result = handler.handle({"taskId": 99204, "traceId": "fake-kling-image-model"})

    assert result["status"] == "SUCCESS", result
    assert client.image_request is not None
    assert client.image_request["model"] == "kling-v3"


def test_kling_client_uses_motion_control_path() -> None:
    class RecordingKlingClient(KlingVideoClient):
        def __init__(self) -> None:
            super().__init__(access_key="fake-ak", secret_key="fake-sk")
            self.requests: list[tuple[str, str]] = []

        def _image_to_base64(self, value: str) -> str:
            return base64.b64encode(value.encode("utf-8")).decode("ascii")

        def _request(self, method: str, path: str, payload: dict | None) -> dict:
            self.requests.append((method, path))
            if method == "POST":
                return {"data": {"task_id": "motion-123"}}
            return {"data": {"task_status": "succeed", "task_result": {"videos": [{"url": "https://example.com/motion.mp4"}]}}}

    client = RecordingKlingClient()
    result = client.generate_video(
        prompt="",
        image_size="1280x720",
        image="https://example.com/person.png",
        video_url="https://example.com/action.mp4",
        create_path="/v1/videos/motion-control",
        result_path_template="/v1/videos/motion-control/{task_id}",
    )
    assert result["videoUrl"] == "https://example.com/motion.mp4"
    assert client.requests == [
        ("POST", "/v1/videos/motion-control"),
        ("GET", "/v1/videos/motion-control/motion-123"),
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


def test_kling_client_polls_async_image_generation() -> None:
    class RecordingKlingClient(KlingVideoClient):
        def __init__(self) -> None:
            super().__init__(access_key="fake-ak", secret_key="fake-sk", poll_interval_seconds=0.01, timeout_seconds=1)
            self.requests: list[tuple[str, str, dict | None]] = []

        def _request(self, method: str, path: str, payload: dict | None) -> dict:
            self.requests.append((method, path, payload))
            if method == "POST":
                return {"code": 0, "data": {"task_id": "image-task-123", "task_status": "submitted"}}
            return {
                "code": 0,
                "data": {
                    "task_id": "image-task-123",
                    "task_status": "succeed",
                    "task_result": {
                        "images": [
                            {"url": "https://example.com/kling-result-1.png"},
                            {"url": "https://example.com/kling-result-2.webp"},
                        ]
                    },
                },
            }

    client = RecordingKlingClient()
    expected_image = base64.b64encode(b"reference-image").decode("ascii")
    with tempfile.TemporaryDirectory() as tmp:
        reference = Path(tmp) / "reference.png"
        reference.write_bytes(b"reference-image")
        result = client.generate_images(
            prompt="画一只猫",
            model="kling-v3",
            image_size="1280x720",
            batch_size=2,
            image=str(reference),
            image_reference="subject",
            image_fidelity=0.75,
            human_fidelity=1,
        )
    assert result == [
        "https://example.com/kling-result-1.png",
        "https://example.com/kling-result-2.webp",
    ]
    assert client.requests[0] == (
        "POST",
        "/v1/images/generations",
        {
            "model_name": "kling-v3",
            "prompt": "画一只猫",
            "n": 2,
            "image": expected_image,
            "image_reference": "subject",
            "image_fidelity": 0.75,
            "human_fidelity": 1,
        },
    )
    assert client.requests[1] == ("GET", "/v1/images/generations/image-task-123", None)


def test_kling_client_polls_async_omni_image_generation() -> None:
    class RecordingKlingClient(KlingVideoClient):
        def __init__(self) -> None:
            super().__init__(
                access_key="fake-ak",
                secret_key="fake-sk",
                poll_interval_seconds=0.01,
                timeout_seconds=1,
                image_generation_path="/v1/images/omni-image",
                image_generation_result_path="/v1/images/omni-image/{task_id}",
            )
            self.requests: list[tuple[str, str, dict | None]] = []

        def _request(self, method: str, path: str, payload: dict | None) -> dict:
            self.requests.append((method, path, payload))
            if method == "POST":
                return {"code": 0, "data": {"task_id": "omni-image-task-1", "task_status": "submitted"}}
            return {
                "code": 0,
                "data": {
                    "task_id": "omni-image-task-1",
                    "task_status": "succeed",
                    "task_result": {
                        "images": [{"url": "https://example.com/omni-image.png"}],
                    },
                },
            }

        def _image_to_base64(self, value: str) -> str:
            return "ZmFrZQ=="

    client = RecordingKlingClient()
    result = client.generate_images(
        prompt="海边跳舞",
        model="kling-image-o1",
        batch_size=2,
        aspect_ratio="16:9",
        resolution="2k",
        result_type="single",
        image_list=[
            {"image": "https://example.com/ref.png"},
            "http://backend:8080/generated/uploads/20260614/ref.png",
        ],
    )
    assert result == ["https://example.com/omni-image.png"]
    assert client.requests[0] == (
        "POST",
        "/v1/images/omni-image",
        {
            "model_name": "kling-image-o1",
            "prompt": "海边跳舞",
            "n": 2,
            "aspect_ratio": "16:9",
            "image_list": [{"image": "ZmFrZQ=="}, {"image": "ZmFrZQ=="}],
            "resolution": "2k",
            "result_type": "single",
        },
    )
    assert client.requests[1] == ("GET", "/v1/images/omni-image/omni-image-task-1", None)


def test_kling_image_generation_accepts_success_reason_with_failed_status() -> None:
    class RecordingKlingClient(KlingVideoClient):
        def __init__(self) -> None:
            super().__init__(access_key="fake-ak", secret_key="fake-sk", poll_interval_seconds=0.01, timeout_seconds=1)

        def _request(self, method: str, path: str, payload: dict | None) -> dict:
            if method == "POST":
                return {"code": 0, "data": {"task_id": "image-task-456", "task_status": "submitted"}}
            return {
                "code": 0,
                "data": {
                    "task_id": "image-task-456",
                    "task_status": "failed",
                    "task_status_msg": "SUCCEED",
                    "task_result": {
                        "images": [{"url": "https://example.com/kling-result.png"}],
                    },
                },
            }

    client = RecordingKlingClient()

    assert client.generate_images(prompt="画一只猫", model="kling-v3") == ["https://example.com/kling-result.png"]


def test_kling_image_generation_accepts_signed_image_url_without_extension() -> None:
    class RecordingKlingClient(KlingVideoClient):
        def __init__(self) -> None:
            super().__init__(access_key="fake-ak", secret_key="fake-sk", poll_interval_seconds=0.01, timeout_seconds=1)

        def _request(self, method: str, path: str, payload: dict | None) -> dict:
            if method == "POST":
                return {"code": 0, "data": {"task_id": "image-task-789", "task_status": "submitted"}}
            return {
                "code": 0,
                "data": {
                    "task_id": "image-task-789",
                    "task_status": "succeed",
                    "task_result": {
                        "images": [{"url": "https://cdn.example.com/download?id=abc&token=signed"}],
                    },
                },
            }

    client = RecordingKlingClient()

    assert client.generate_images(prompt="画一只猫", model="kling-v3") == ["https://cdn.example.com/download?id=abc&token=signed"]


def test_kling_image_generation_accepts_plural_result_urls_without_extension() -> None:
    class RecordingKlingClient(KlingVideoClient):
        def __init__(self) -> None:
            super().__init__(access_key="fake-ak", secret_key="fake-sk", poll_interval_seconds=0.01, timeout_seconds=1)

        def _request(self, method: str, path: str, payload: dict | None) -> dict:
            if method == "POST":
                return {"code": 0, "data": {"task_id": "image-task-790", "task_status": "submitted"}}
            return {
                "code": 0,
                "data": {
                    "task_id": "image-task-790",
                    "task_status": "failed",
                    "task_status_msg": "SUCCEED",
                    "task_result": {
                        "result_urls": [
                            "https://cdn.example.com/download?id=abc&token=signed",
                            "https://cdn.example.com/download?id=def&token=signed",
                        ],
                    },
                },
            }

    client = RecordingKlingClient()

    assert client.generate_images(prompt="draw a cat", model="kling-v3") == [
        "https://cdn.example.com/download?id=abc&token=signed",
        "https://cdn.example.com/download?id=def&token=signed",
    ]


def test_kling_video_generation_accepts_success_reason_with_failed_status() -> None:
    class RecordingKlingClient(KlingVideoClient):
        def __init__(self) -> None:
            super().__init__(access_key="fake-ak", secret_key="fake-sk", poll_interval_seconds=0.01, timeout_seconds=1)

        def _request(self, method: str, path: str, payload: dict | None) -> dict:
            if method == "POST":
                return {"code": 0, "data": {"task_id": "video-task-456", "task_status": "submitted"}}
            return {
                "code": 0,
                "data": {
                    "task_id": "video-task-456",
                    "task_status": "failed",
                    "task_status_msg": "SUCCEED",
                    "task_result": {
                        "videos": [{"url": "https://cdn.example.com/video-download?id=abc&token=signed"}],
                    },
                },
            }

    client = RecordingKlingClient()

    result = client.generate_video(prompt="闀滃ご鎷夎繙", image_size="1280x720")
    assert result["videoUrl"] == "https://cdn.example.com/video-download?id=abc&token=signed"


def test_kling_video_generation_ignores_transport_success_message_while_submitted() -> None:
    class RecordingKlingClient(KlingVideoClient):
        def __init__(self) -> None:
            super().__init__(access_key="fake-ak", secret_key="fake-sk", poll_interval_seconds=0.01, timeout_seconds=1)
            self.polls = 0

        def _request(self, method: str, path: str, payload: dict | None) -> dict:
            if method == "POST":
                return {"code": 0, "data": {"task_id": "video-task-submitted", "task_status": "submitted"}}
            self.polls += 1
            if self.polls == 1:
                return {
                    "code": 0,
                    "message": "SUCCEED",
                    "data": {
                        "task_id": "video-task-submitted",
                        "task_status": "submitted",
                        "task_result": {},
                    },
                }
            return {
                "code": 0,
                "message": "SUCCEED",
                "data": {
                    "task_id": "video-task-submitted",
                    "task_status": "succeed",
                    "task_result": {
                        "videos": [{"url": "https://cdn.example.com/video-ready.mp4"}],
                    },
                },
            }

    client = RecordingKlingClient()

    result = client.generate_video(prompt="normal prompt", image_size="1280x720")
    assert client.polls == 2
    assert result["videoUrl"] == "https://cdn.example.com/video-ready.mp4"


def test_kling_video_generation_reports_nested_failure_reason() -> None:
    class RecordingKlingClient(KlingVideoClient):
        def __init__(self) -> None:
            super().__init__(access_key="fake-ak", secret_key="fake-sk", poll_interval_seconds=0.01, timeout_seconds=1)

        def _request(self, method: str, path: str, payload: dict | None) -> dict:
            if method == "POST":
                return {"code": 0, "data": {"task_id": "video-task-safety", "task_status": "submitted"}}
            return {
                "code": 0,
                "data": {
                    "task_id": "video-task-safety",
                    "task_status": "failed",
                    "task_status_msg": "SAFETY_CHECK_FAILED",
                    "error": {
                        "code": "content_policy",
                        "message": "prompt rejected by safety policy",
                    },
                },
            }

    client = RecordingKlingClient()

    try:
        client.generate_video(prompt="unsafe prompt", image_size="1280x720")
    except KlingVideoError as exc:
        message = str(exc)
        assert "kling video generation failed" in message
        assert "data.task_status=failed" in message
        assert "data.task_status_msg=SAFETY_CHECK_FAILED" in message
        assert "data.error.message=prompt rejected by safety policy" in message
    else:
        raise AssertionError("expected KlingVideoError")


def test_kling_video_generation_reports_success_without_url_payload() -> None:
    class RecordingKlingClient(KlingVideoClient):
        def __init__(self) -> None:
            super().__init__(access_key="fake-ak", secret_key="fake-sk", poll_interval_seconds=0.01, timeout_seconds=1)

        def _request(self, method: str, path: str, payload: dict | None) -> dict:
            if method == "POST":
                return {"code": 0, "data": {"task_id": "video-task-empty", "task_status": "submitted"}}
            return {
                "code": 0,
                "data": {
                    "task_id": "video-task-empty",
                    "task_status": "succeed",
                    "task_status_msg": "content blocked by safety policy",
                    "task_result": {
                        "videos": [],
                    },
                },
            }

    client = RecordingKlingClient()

    try:
        client.generate_video(prompt="blocked prompt", image_size="1280x720")
    except KlingVideoError as exc:
        message = str(exc)
        assert "terminal success but no video url" in message
        assert "data.task_status=succeed" in message
        assert "data.task_status_msg=content blocked by safety policy" in message
    else:
        raise AssertionError("expected KlingVideoError")


if __name__ == "__main__":
    test_kling_video_handler()
    test_kling_video_handler_uses_params_model_override()
    test_kling_image_handler()
    test_kling_image_handler_uses_params_model_override()
    test_kling_client_uses_motion_control_path()
    test_kling_client_encodes_input_images()
    test_kling_client_uses_image2video_result_path()
    test_kling_client_polls_async_image_generation()
    test_kling_client_polls_async_omni_image_generation()
    test_kling_image_generation_accepts_success_reason_with_failed_status()
    test_kling_image_generation_accepts_signed_image_url_without_extension()
    test_kling_image_generation_accepts_plural_result_urls_without_extension()
    test_kling_video_generation_accepts_success_reason_with_failed_status()
    test_kling_video_generation_ignores_transport_success_message_while_submitted()
    test_kling_video_generation_reports_nested_failure_reason()
    test_kling_video_generation_reports_success_without_url_payload()
    print("FAKE_KLING_INTEGRATION_TEST_PASSED")
