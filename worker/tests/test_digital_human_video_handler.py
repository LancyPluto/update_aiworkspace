from types import SimpleNamespace

import pytest

from config import settings
from handlers import digital_human_video_handler as handler_module
from handlers.digital_human_video_handler import DigitalHumanVideoHandler
from providers.registry import ProviderRegistryError
from task_queue.task_handler_router import TaskHandlerRouter


def _handler(*, seedance_video_client=None):
    return DigitalHumanVideoHandler(
        backend_client=object(),
        seedance_video_client=seedance_video_client,
        postprocessor=object(),
    )


def test_legacy_siliconflow_context_always_uses_seedance(monkeypatch):
    monkeypatch.setattr(settings, "digital_human_video_provider", "seedance")
    assert DigitalHumanVideoHandler._resolve_video_provider(
        {"modelProviderCode": "siliconflow_images"},
        {"provider": "siliconflow_images"},
    ) == "seedance"

    monkeypatch.setattr(settings, "digital_human_video_provider", "infinitetalk")
    assert DigitalHumanVideoHandler._resolve_video_provider(
        {},
        {"provider": "siliconflow"},
    ) == "seedance"


def test_video_provider_is_preserved_for_current_contexts():
    assert DigitalHumanVideoHandler._resolve_video_provider(
        {},
        {"provider": "infinitetalk"},
    ) == "infinitetalk"

    assert DigitalHumanVideoHandler._resolve_video_provider(
        {"modelProviderCode": "seedance"},
        {},
    ) == "seedance"


def test_misclassified_volcengine_seedance_snapshot_uses_video_provider():
    assert DigitalHumanVideoHandler._resolve_video_provider(
        {},
        {
            "provider": "volcengine_images",
            "modelName": "doubao-seedance-2-0-260128",
        },
    ) == "seedance"

    with pytest.raises(ProviderRegistryError, match="not supported by digital human"):
        DigitalHumanVideoHandler._resolve_video_provider(
            {},
            {
                "provider": "volcengine_images",
                "modelName": "doubao-seedream-4-5-251128",
            },
        )

    assert DigitalHumanVideoHandler._resolve_model(
        {},
        {
            "provider": "volcengine_images",
            "modelName": "doubao-seedance-2-0-260128",
        },
        "seedance",
    ) == "doubao-seedance-2-0-260128"


def test_legacy_siliconflow_image_model_is_not_used_as_seedance_video_model():
    assert DigitalHumanVideoHandler._resolve_model(
        {},
        {
            "provider": "siliconflow_images",
            "modelName": "Tongyi-MAI/Z-Image-Turbo",
        },
        "seedance",
    ) != "Tongyi-MAI/Z-Image-Turbo"


@pytest.mark.parametrize("provider", ["kling_video", "agnes_video", "bailian_happyhorse"])
def test_digital_human_rejects_unimplemented_video_providers(provider):
    with pytest.raises(ProviderRegistryError, match="not supported by digital human"):
        DigitalHumanVideoHandler._resolve_video_provider({}, {"provider": provider})


def test_seedance_snapshot_builds_client_from_model_config():
    client = _handler()._video_generation_client(
        {
            "provider": "seedance",
            "baseUrl": "https://seedance.example/api/v3",
            "apiKey": "snapshot-key",
            "modelName": "snapshot-model",
        }
    )

    assert client.base_url == "https://seedance.example/api/v3"
    assert client.api_key == "snapshot-key"
    assert client.default_model == "snapshot-model"


def test_misclassified_seedance_snapshot_builds_client_from_model_config():
    client = _handler()._video_generation_client(
        {
            "provider": "volcengine_images",
            "baseUrl": "https://ark.example/api/v3",
            "apiKey": "legacy-seedance-key",
            "modelName": "doubao-seedance-2-0-260128",
        }
    )

    assert client.base_url == "https://ark.example/api/v3"
    assert client.api_key == "legacy-seedance-key"
    assert client.default_model == "doubao-seedance-2-0-260128"


def test_legacy_siliconflow_snapshot_uses_seedance_environment(monkeypatch):
    monkeypatch.setattr(settings, "seedance_base_url", "https://seedance-env.example/api/v3")
    monkeypatch.setattr(settings, "seedance_api_key", "seedance-env-key")
    monkeypatch.setattr(settings, "seedance_video_model", "seedance-env-model")

    client = _handler()._video_generation_client(
        {
            "provider": "siliconflow_images",
            "baseUrl": "https://siliconflow.example/v1",
            "apiKey": "siliconflow-key",
            "modelName": "Tongyi-MAI/Z-Image-Turbo",
        }
    )

    assert client.base_url == "https://seedance-env.example/api/v3"
    assert client.api_key == "seedance-env-key"
    assert client.default_model == "seedance-env-model"


def test_injected_seedance_client_is_preserved():
    injected = object()

    assert _handler(seedance_video_client=injected)._video_generation_client(
        {"provider": "seedance"}
    ) is injected


def test_digital_human_handler_executes_with_video_generation_capability(monkeypatch):
    class Backend:
        def __init__(self):
            self.successes = []

        def mark_processing(self, task_id, **kwargs):
            return None

        def mark_success(self, task_id, payload):
            self.successes.append((task_id, payload))

        def mark_failed(self, task_id, payload):
            raise AssertionError(payload)

    class SiliconFlowClient:
        def generate_speech_data_url(self, **kwargs):
            return "data:audio/mpeg;base64,YXVkaW8="

        def generate_image(self, **kwargs):
            return "https://example.test/avatar.png"

    class SeedanceClient:
        def generate_video(self, **kwargs):
            return {
                "videoUrl": "https://example.test/raw.mp4",
                "requestId": "seedance-request",
                "status": "succeeded",
                "provider": "seedance",
                "model": kwargs["model"],
                "resolution": kwargs["resolution"],
            }

    class Postprocessor:
        def process(self, **kwargs):
            return SimpleNamespace(
                video_url="https://example.test/final.mp4",
                subtitle_url="https://example.test/subtitles.srt",
            )

    capability_checks = []
    monkeypatch.setattr(
        handler_module.provider_registry,
        "require_capability",
        lambda provider, capability: capability_checks.append((provider, capability)),
    )
    backend = Backend()
    handler = DigitalHumanVideoHandler(
        backend_client=backend,
        video_client=SiliconFlowClient(),
        seedance_video_client=SeedanceClient(),
        postprocessor=Postprocessor(),
    )

    result = handler.handle(
        {
            "taskId": 901,
            "__executionContext": {
                "modelConfig": {
                    "provider": "seedance",
                    "modelName": "doubao-seedance-2-0-260128",
                },
                "params": {"script": "Hello from the digital human handler."},
            },
        }
    )

    assert result == {"status": "SUCCESS", "taskId": 901}
    assert capability_checks == [("seedance", "VIDEO_GENERATION")]
    assert backend.successes[0][1]["resourceType"] == "MARKDOWN"


def test_router_keeps_digital_human_on_dedicated_handler():
    class RecordingHandler:
        def __init__(self):
            self.messages = []

        def handle(self, message):
            self.messages.append(message)
            return {"status": "ROUTED"}

    dedicated_handler = RecordingHandler()
    router = object.__new__(TaskHandlerRouter)
    router.digital_human_handler = dedicated_handler

    message = {"taskId": 902}
    result = router._dispatch(
        message,
        {"executionHandler": "DIGITAL_HUMAN", "params": {}},
    )

    assert result == {"status": "ROUTED"}
    assert dedicated_handler.messages == [message]
