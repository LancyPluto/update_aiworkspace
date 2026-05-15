import json
import sys
import tempfile
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from handlers.image_generation_handler import ImageGenerationHandler
from handlers.generated_image_persister import GeneratedImagePersister
from task_queue.redis_consumer import TaskHandlerRouter


class FakeBackendClient:
    def __init__(self) -> None:
        self.success_payload = None
        self.failed_payload = None
        self.processing = []
        self.status = "QUEUED"

    def get_execution_context(self, task_id: int, trace_id: str | None = None) -> dict:
        return {
            "taskId": task_id,
            "taskNo": "T202605150001",
            "toolCode": "siliconflow_image_generator",
            "toolType": "IMAGE_GENERATION",
            "inputModality": "TEXT",
            "outputModality": "IMAGE",
            "status": self.status,
            "traceId": trace_id,
            "params": {
                "prompt": "一张适合新品朋友圈发布的精致香薰蜡烛海报",
                "aspectRatio": "1:1",
                "style": "电商",
                "count": 2,
                "negativePrompt": "模糊, 低清晰度",
            },
            "modelConfig": {
                "provider": "siliconflow_images",
                "modelName": "Tongyi-MAI/Z-Image-Turbo",
                "baseUrl": "https://api.siliconflow.cn",
                "apiKey": "fake-key",
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


class FakeImageClient:
    def __init__(self) -> None:
        self.calls = []

    def generate_images(self, **kwargs):
        self.calls.append(kwargs)
        return [
            "https://example.com/image-1.png",
            "https://example.com/image-2.png",
        ]


class FakeImagePersister:
    def __init__(self) -> None:
        self.calls = []

    def persist_images(self, *, task_id: int, urls: list[str]) -> list[dict[str, str]]:
        self.calls.append({"taskId": task_id, "urls": urls})
        return [
            {"url": f"/generated/images/{task_id}/image-{index}.png", "sourceUrl": url}
            for index, url in enumerate(urls, start=1)
        ]


class FailingTextHandler:
    def handle(self, message: dict) -> dict:
        raise AssertionError("image generation should not be routed to text handler")


class FailingImageHandler:
    def handle(self, message: dict) -> dict:
        raise AssertionError("terminal task should not be routed to image handler")


def main() -> None:
    backend = FakeBackendClient()
    image_client = FakeImageClient()
    image_persister = FakeImagePersister()
    image_handler = ImageGenerationHandler(
        backend_client=backend,
        image_client=image_client,
        image_persister=image_persister,
    )
    router = TaskHandlerRouter(
        text_handler=FailingTextHandler(),
        image_generation_handler=image_handler,
        backend_client=backend,
    )

    result = router.handle({"taskId": 99120, "traceId": "fake-image-generation-test"})

    assert result["status"] == "SUCCESS", result
    assert len(backend.processing) == 3, backend.processing
    assert backend.failed_payload is None, backend.failed_payload
    assert backend.success_payload["resourceType"] == "IMAGE", backend.success_payload
    assert backend.success_payload["billableUnits"] == 2, backend.success_payload
    content = json.loads(backend.success_payload["contentText"])
    assert content["provider"] == "siliconflow", content
    assert [item["url"] for item in content["images"]] == [
        "/generated/images/99120/image-1.png",
        "/generated/images/99120/image-2.png",
    ]
    assert [item["sourceUrl"] for item in content["images"]] == [
        "https://example.com/image-1.png",
        "https://example.com/image-2.png",
    ]
    assert image_persister.calls[0]["taskId"] == 99120
    assert image_client.calls[0]["model"] == "Tongyi-MAI/Z-Image-Turbo"
    assert image_client.calls[0]["prompt"].endswith("Style: 电商")
    assert image_client.calls[0]["image_size"] == "1024x1024"
    assert image_client.calls[0]["batch_size"] == 2
    assert image_client.calls[0]["negative_prompt"] == "模糊, 低清晰度"

    print("FAKE_IMAGE_GENERATION_TEST_PASSED")
    print(json.dumps({"success_payload": backend.success_payload, "image_call": image_client.calls[0]}, ensure_ascii=False, indent=2))


def test_failure_is_marked_processing_before_failed() -> None:
    backend = FakeBackendClient()
    context = backend.get_execution_context(99121)
    context["modelConfig"]["provider"] = "openai_compatible"
    image_handler = ImageGenerationHandler(backend_client=backend, image_client=FakeImageClient())

    result = image_handler.handle({"taskId": 99121, "traceId": "fake-image-generation-failure-test", "__executionContext": context})

    assert result["status"] == "FAILED", result
    assert backend.processing, "task should be moved to PROCESSING before failed callback"
    assert backend.failed_payload["errorCode"] == "MODEL_CALL_FAILED", backend.failed_payload


def test_data_url_image_is_persisted() -> None:
    persister = GeneratedImagePersister()
    original_output_dir = persister.output_dir
    original_public_base_url = persister.public_base_url
    try:
        with tempfile.TemporaryDirectory() as temp_dir:
            persister.output_dir = Path(temp_dir)
            persister.public_base_url = "/generated"
            result = persister.persist_images(
                task_id=99123,
                urls=[
                    "data:image/png;base64,"
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
                ],
            )

            assert result == [
                {
                    "url": "/generated/images/99123/image-1.png",
                    "sourceUrl": (
                        "data:image/png;base64,"
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
                    ),
                }
            ], result
            assert (Path(temp_dir) / "images" / "99123" / "image-1.png").exists()
    finally:
        persister.output_dir = original_output_dir
        persister.public_base_url = original_public_base_url


def test_terminal_task_is_skipped_before_handler() -> None:
    backend = FakeBackendClient()
    backend.status = "FAILED"
    router = TaskHandlerRouter(
        text_handler=FailingTextHandler(),
        image_generation_handler=FailingImageHandler(),
        backend_client=backend,
    )

    result = router.handle({"taskId": 99122, "traceId": "fake-image-generation-terminal-test"})

    assert result["status"] == "SKIPPED", result
    assert backend.processing == [], backend.processing
    assert backend.failed_payload is None, backend.failed_payload


if __name__ == "__main__":
    test_terminal_task_is_skipped_before_handler()
    test_failure_is_marked_processing_before_failed()
    test_data_url_image_is_persisted()
    main()
