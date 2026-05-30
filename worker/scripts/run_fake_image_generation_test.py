import json
import sys
import tempfile
from pathlib import Path
from requests.exceptions import SSLError


sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from handlers.image_generation_handler import ImageGenerationHandler, _model_call_error_code
from handlers.generated_image_persister import GeneratedImagePersister
from client.openai_images_client import OpenAIImagesClient
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
            "fields": [
                {
                    "fieldKey": "style",
                    "options": [
                        {
                            "label": "电商",
                            "value": "电商",
                            "promptPrefix": "commercial product photography, clean background",
                        }
                    ],
                }
            ],
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
    assert content["provider"] == "siliconflow_images", content
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
    assert image_client.calls[0]["prompt"].startswith("commercial product photography, clean background, ")
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
    assert backend.failed_payload["errorCode"] == "MODEL_PROVIDER_UNAVAILABLE", backend.failed_payload


def test_model_auth_failure_error_code_is_specific() -> None:
    assert _model_call_error_code('siliconflow request failed: status=401, body="Invalid token"') == "MODEL_AUTH_FAILED"
    assert _model_call_error_code("API Key is required for model provider") == "MODEL_AUTH_FAILED"
    assert _model_call_error_code("task_status_msg=Failure to pass the risk control system") == "MODEL_RISK_CONTROL_REJECTED"


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
                    "sourceUrl": "[inline-image-base64-omitted]",
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


def test_openai_images_gateway_handler_reports_image_tokens() -> None:
    class FakeOpenAIImagesClient:
        def __init__(self) -> None:
            self.last_usage = {"promptTokens": 8, "completionTokens": 2112, "totalTokens": 2120}
            self.calls = []

        def generate_images(self, **kwargs):
            self.calls.append(kwargs)
            return ["data:image/png;base64,ZmFrZQ=="]

    backend = FakeBackendClient()
    context = backend.get_execution_context(99124)
    context["modelConfig"] = {
        "provider": "ofox_openai_images",
        "modelName": "openai/gpt-image-2",
        "baseUrl": "https://api.ofox.ai/v1",
        "apiKey": "fake-ofox-key",
    }
    context["params"]["count"] = 1
    context["params"]["quality"] = "low"
    context["params"]["style"] = "natural"
    context["params"]["outputFormat"] = "url"
    client = FakeOpenAIImagesClient()
    image_handler = ImageGenerationHandler(
        backend_client=backend,
        image_client=client,
        image_persister=FakeImagePersister(),
    )

    result = image_handler.handle({"taskId": 99124, "traceId": "fake-openai-images-test", "__executionContext": context})

    assert result["status"] == "SUCCESS", result
    assert backend.success_payload["promptTokens"] == 8, backend.success_payload
    assert backend.success_payload["completionTokens"] == 2112, backend.success_payload
    assert backend.success_payload["billableUnits"] == 1, backend.success_payload
    assert client.calls[0]["model"] == "openai/gpt-image-2"
    assert client.calls[0]["quality"] == "low", client.calls
    assert client.calls[0]["style"] == "natural", client.calls
    assert client.calls[0]["output_format"] == "url", client.calls
    assert "Style:" not in client.calls[0]["prompt"], client.calls


def test_openai_images_client_parses_url_and_usage() -> None:
    class FakeResponse:
        status_code = 200
        text = "{}"

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict:
            return {
                "data": [{"url": "https://example.com/openai-image.png"}],
                "usage": {"input_tokens": 12, "output_tokens": 2048, "total_tokens": 2060},
            }

    client = OpenAIImagesClient(base_url="https://api.ofox.ai/v1", api_key="fake-key")
    posted = {}

    def fake_post(url, json, timeout):
        posted["url"] = url
        posted["json"] = json
        posted["timeout"] = timeout
        return FakeResponse()

    client.session.post = fake_post
    urls = client.generate_images(
        prompt="draw a cat",
        model="openai/gpt-image-2",
        image_size="1024x1024",
        batch_size=1,
        quality="medium",
        style="natural",
        output_format="url",
    )

    assert urls == ["https://example.com/openai-image.png"], urls
    assert posted["url"] == "https://api.ofox.ai/v1/images/generations", posted
    assert posted["json"]["model"] == "openai/gpt-image-2", posted
    assert posted["json"]["quality"] == "medium", posted
    assert posted["json"]["style"] == "natural", posted
    assert posted["json"]["output_format"] == "url", posted
    assert posted["timeout"] == (10, 300), posted
    assert client.last_usage == {"promptTokens": 12, "completionTokens": 2048, "totalTokens": 2060}


def test_openai_images_client_timeout_can_be_configured() -> None:
    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="fake-key",
        timeout_seconds=5,
        extra_auth_json='{"connectTimeoutSeconds":3,"readTimeoutSeconds":180}',
    )
    assert client.timeout == (3, 180), client.timeout


def test_openai_images_client_proxy_can_be_disabled() -> None:
    client = OpenAIImagesClient(
        base_url="https://shiyunapi.com/v1",
        api_key="fake-key",
        extra_auth_json='{"trustEnv":false}',
    )
    assert client.session.trust_env is False


def test_openai_images_client_proxy_can_be_configured() -> None:
    client = OpenAIImagesClient(
        base_url="https://shiyunapi.com/v1",
        api_key="fake-key",
        extra_auth_json='{"proxyUrl":"http://127.0.0.1:7890"}',
    )
    assert client.session.proxies["https"] == "http://127.0.0.1:7890"


def test_openai_images_client_can_retry_ssl_eof_when_enabled() -> None:
    class FakeResponse:
        status_code = 200
        text = "{}"

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict:
            return {"data": [{"url": "https://example.com/recovered.png"}]}

    client = OpenAIImagesClient(
        base_url="https://api.ofox.ai/v1",
        api_key="fake-key",
        extra_auth_json='{"sslEofRetries":1}',
    )
    calls = {"count": 0}

    def fake_post(url, json, timeout):
        calls["count"] += 1
        if calls["count"] == 1:
            raise SSLError("EOF occurred in violation of protocol")
        return FakeResponse()

    client.session.post = fake_post
    urls = client.generate_images(prompt="draw", model="openai/gpt-image-2")

    assert urls == ["https://example.com/recovered.png"], urls
    assert calls["count"] == 2, calls


if __name__ == "__main__":
    test_terminal_task_is_skipped_before_handler()
    test_failure_is_marked_processing_before_failed()
    test_model_auth_failure_error_code_is_specific()
    test_data_url_image_is_persisted()
    test_openai_images_gateway_handler_reports_image_tokens()
    test_openai_images_client_parses_url_and_usage()
    test_openai_images_client_timeout_can_be_configured()
    test_openai_images_client_proxy_can_be_disabled()
    test_openai_images_client_proxy_can_be_configured()
    test_openai_images_client_can_retry_ssl_eof_when_enabled()
    main()
