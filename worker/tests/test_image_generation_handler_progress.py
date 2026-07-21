import time

from client.openai_images_client import OpenAIImagesRequestNotSentError
from handlers.image_generation_handler import ImageGenerationHandler, ImageProgressTicker, _resolve_openai_image_size


def test_openai_gateway_4k_image_model_uses_allowed_sizes():
    model_config = {"provider": "openai_images_gateway", "modelName": "gpt-image-2-4k"}

    assert _resolve_openai_image_size({}, model_config) == "3072x2048"
    assert _resolve_openai_image_size({"aspectRatio": "16:9"}, model_config) == "3072x1728"
    assert _resolve_openai_image_size({"aspectRatio": "9:16"}, model_config) == "1728x3072"


def test_openai_gateway_prefers_preserved_frontend_size_field():
    model_config = {"provider": "openai_images_gateway", "modelName": "gpt-image-2"}

    assert _resolve_openai_image_size(
        {"size": "1024x1536", "aspectRatio": "16:9"},
        model_config,
    ) == "1024x1536"


def test_volcengine_seedream_auto_size_uses_aspect_ratio_size():
    model_config = {
        "provider": "volcengine_images",
        "modelName": "doubao-seedream-4-5-251128",
        "baseUrl": "https://ark.cn-beijing.volces.com/api/v3",
    }

    assert _resolve_openai_image_size({"imageSize": "auto", "aspectRatio": "16:9"}, model_config) == "2560x1440"
    assert _resolve_openai_image_size({"imageSize": "auto", "aspectRatio": "9:16"}, model_config) == "1440x2560"
    assert _resolve_openai_image_size({"aspectRatio": "16:9"}, model_config) == "2560x1440"


class RecordingBackendClient:
    def __init__(self) -> None:
        self.processing = []
        self.success_payload = None
        self.failed_payload = None

    def get_execution_context(self, task_id, trace_id=None):
        return {
            "taskId": task_id,
            "taskNo": "T202606080001",
            "toolCode": "gpt_image_text_to_image",
            "toolType": "IMAGE_GENERATION",
            "inputModality": "TEXT",
            "outputModality": "IMAGE",
            "status": "QUEUED",
            "traceId": trace_id,
            "params": {"prompt": "a clean product photo", "count": 1},
            "modelConfig": {
                "provider": "agnes_images",
                "modelName": "agnes-image-2.1-flash",
                "baseUrl": "https://apihub.agnes-ai.com/v1",
                "apiKey": "fake-key",
            },
            "fields": [],
        }

    def mark_processing(self, task_id, *, progress=None, progress_message=None, trace_id=None):
        self.processing.append(
            {
                "progress": progress,
                "progressMessage": progress_message,
                "traceId": trace_id,
            }
        )
        return {}

    def mark_success(self, task_id, payload, trace_id=None):
        self.success_payload = payload
        return {}

    def mark_failed(self, task_id, payload, trace_id=None):
        self.failed_payload = payload
        return {}


class SlowImageClient:
    def __init__(self, backend: RecordingBackendClient) -> None:
        self.backend = backend

    def generate_images(self, **kwargs):
        deadline = time.time() + 0.3
        while len(self.backend.processing) < 4 and time.time() < deadline:
            time.sleep(0.001)
        return ["https://cdn.example/image.png"]


class PassthroughImagePersister:
    def persist_images(self, *, task_id, urls):
        return [{"url": url, "sourceUrl": url} for url in urls]


class RecordingImageClient:
    def __init__(self) -> None:
        self.calls = []

    def generate_images(self, **kwargs):
        self.calls.append(kwargs)
        return ["https://cdn.example/image.png"]


class NotSentImageClient:
    def generate_images(self, **_kwargs):
        raise OpenAIImagesRequestNotSentError("connect refused before request was sent")


def test_image_progress_ticker_finishes_without_large_jump_or_regression():
    progress_values: list[int] = []
    ticker = ImageProgressTicker(progress_values.append, current_progress=13, interval_seconds=999)

    ticker.finish_smoothly(interval_seconds=0)

    assert progress_values[:3] == [14, 15, 16]
    assert progress_values[-1] == 99
    assert all(next_value - value == 1 for value, next_value in zip([13, *progress_values], progress_values))


def test_image_generation_handler_reports_one_percent_progress_until_saved():
    backend = RecordingBackendClient()
    handler = ImageGenerationHandler(
        backend_client=backend,
        image_client=SlowImageClient(backend),
        image_persister=PassthroughImagePersister(),
        progress_interval_seconds=0.005,
        final_progress_interval_seconds=0,
    )

    result = handler.handle({"taskId": 99140, "traceId": "image-progress-test"})

    progress_values = [item["progress"] for item in backend.processing if item["progress"] is not None]
    assert result["status"] == "SUCCESS"
    assert backend.failed_payload is None
    assert progress_values[:4] == [1, 2, 3, 4]
    assert progress_values[-1] == 99
    assert 20 in progress_values
    assert 90 in progress_values
    assert all(next_value - value == 1 for value, next_value in zip(progress_values, progress_values[1:]))
    assert backend.processing[0]["progressMessage"] == "实时进度：1%"
    assert backend.processing[-1]["progressMessage"] == "实时进度：99%"


def test_image_generation_handler_reports_safe_failover_metadata_for_not_sent_request():
    backend = RecordingBackendClient()
    handler = ImageGenerationHandler(
        backend_client=backend,
        image_client=NotSentImageClient(),
        image_persister=PassthroughImagePersister(),
        final_progress_interval_seconds=0,
    )

    result = handler.handle({"taskId": 99142, "traceId": "image-not-sent-test"})

    assert result["status"] == "FAILED"
    assert backend.failed_payload["deliveryState"] == "NOT_SENT"
    assert backend.failed_payload["retryScope"] == "ACCOUNT"
    assert backend.failed_payload["failureStage"] == "BEFORE_PROVIDER"


def test_image_generation_handler_renders_tool_prompt_template_for_image_tools():
    backend = RecordingBackendClient()
    context = backend.get_execution_context(99141, trace_id="image-template-test")
    context["toolCode"] = "image_outpainting"
    context["inputModality"] = "IMAGE"
    context["params"] = {
        "sourceImageUrl": "data:image/png;base64,ZmFrZQ==",
    }
    context["userPromptTemplate"] = (
        "Extend the image from {{sourceImageUrl}} outward in all directions. "
        "Keep the original subject and lighting exactly as in the input."
    )
    image_client = RecordingImageClient()
    handler = ImageGenerationHandler(
        backend_client=backend,
        image_client=image_client,
        image_persister=PassthroughImagePersister(),
        final_progress_interval_seconds=0,
    )

    result = handler.handle({"taskId": 99141, "traceId": "image-template-test", "__executionContext": context})

    assert result["status"] == "SUCCESS"
    assert image_client.calls[0]["prompt"].startswith("Extend the image from data:image/png;base64,ZmFrZQ==")
    assert "Extend the uploaded image canvas" not in image_client.calls[0]["prompt"]
