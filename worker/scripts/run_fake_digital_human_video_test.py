import sys
from dataclasses import dataclass
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from config import settings
from client.seedance_video_client import SeedanceVideoError
from handlers.digital_human_postprocessor import DigitalHumanPostprocessor
from handlers.digital_human_video_handler import DigitalHumanVideoHandler
from task_queue.redis_consumer import TaskHandlerRouter


class FakeBackendClient:
    def __init__(self) -> None:
        self.events: list[str] = []
        self.success_payload: dict | None = None
        self.failed_payload: dict | None = None
        self.progress_messages: list[str] = []

    def get_execution_context(self, task_id: int) -> dict:
        self.events.append("execution-context")
        return {
            "taskId": task_id,
            "toolCode": "digital_human_agent",
            "modelProviderCode": "siliconflow_images",
            "params": {
                "videoTopic": "Digital human launch video",
                "script": "Hello, this is a short digital human video used for worker validation.",
                "avatarStyle": "Professional host",
                "scene": "Product demo studio",
                "aspectRatio": "9:16 vertical",
                "duration": "5 seconds",
                "resolution": "480p",
                "brandName": "Test Lab",
                "referenceImageUrl": "https://example.com/avatar.png",
                "visualRequirements": "Half-body shot, bright lighting, facing the camera.",
                "negativePrompt": "low resolution, distorted hands, broken subtitles",
            },
        }

    def mark_processing(self, task_id: int, *, progress: int | None = None, progress_message: str | None = None) -> dict:
        self.events.append("processing")
        self.progress_messages.append(progress_message or "")
        return {}

    def mark_success(self, task_id: int, payload: dict) -> dict:
        self.events.append("success")
        self.success_payload = payload
        return {}

    def mark_failed(self, task_id: int, payload: dict) -> dict:
        self.events.append("failed")
        self.failed_payload = payload
        return {}


class FakeSiliconFlowClient:
    def __init__(self) -> None:
        self.request_payload: dict | None = None
        self.image_prompts: list[str] = []
        self.speech_calls = 0
        self.speech_payload: dict | None = None

    def generate_speech_data_url(self, **kwargs) -> str:
        self.speech_calls += 1
        self.speech_payload = kwargs
        return "data:audio/mpeg;base64,ZmFrZQ=="

    def generate_image(self, **kwargs) -> str:
        self.image_prompts.append(kwargs["prompt"])
        return f"https://example.com/generated-{len(self.image_prompts)}.png"

    def generate_video(self, **kwargs) -> dict:
        self.request_payload = kwargs
        return fake_video_result("siliconflow_fake_001", provider="siliconflow")


class FakeSeedanceClient:
    def __init__(self) -> None:
        self.request_payload: dict | None = None

    def generate_video(self, **kwargs) -> dict:
        self.request_payload = kwargs
        return fake_video_result("seedance_fake_001", provider="seedance", resolution=kwargs.get("resolution", "480p"))


class FailingSeedanceClient:
    def generate_video(self, **kwargs) -> dict:
        raise SeedanceVideoError("seedance unauthorized")


def fake_video_result(request_id: str, *, provider: str, resolution: str = "480p") -> dict:
    return {
        "requestId": request_id,
        "status": "Succeed",
        "videoUrl": "https://example.com/fake-video.mp4",
        "reason": "",
        "seed": 123,
        "timings": {"inference": 456},
        "provider": provider,
        "model": "doubao-seedance-1-5-pro-251215",
        "resolution": resolution,
    }


@dataclass(slots=True)
class FakePostprocessResult:
    video_url: str
    subtitle_url: str = "/generated/digital-human/99002/subtitle.srt"


class FakePostprocessor:
    def __init__(self) -> None:
        self.payload: dict | None = None

    def process(self, **kwargs) -> FakePostprocessResult:
        self.payload = kwargs
        return FakePostprocessResult(video_url="/generated/digital-human/99002/final.mp4")


class SpyDigitalHumanHandler:
    def __init__(self) -> None:
        self.message: dict | None = None

    def handle(self, message: dict) -> dict:
        self.message = message
        return {"status": "SUCCESS", "taskId": message["taskId"]}


class UnexpectedTextHandler:
    def handle(self, message: dict) -> dict:
        raise AssertionError(f"text handler should not handle this task: {message}")


def main() -> None:
    original_provider = settings.digital_human_video_provider
    settings.digital_human_video_provider = "seedance"
    try:
        backend = FakeBackendClient()
        siliconflow = FakeSiliconFlowClient()
        seedance = FakeSeedanceClient()
        postprocessor = FakePostprocessor()
        handler = DigitalHumanVideoHandler(
            backend_client=backend,
            video_client=siliconflow,
            seedance_video_client=seedance,
            postprocessor=postprocessor,
        )

        result = handler.handle({"taskId": 99002, "toolCode": "digital_human_agent"})
    finally:
        settings.digital_human_video_provider = original_provider

    assert result["status"] == "SUCCESS", result
    assert backend.events == [
        "execution-context",
        "processing",
        "processing",
        "processing",
        "processing",
        "processing",
        "processing",
        "success",
    ], backend.events
    assert siliconflow.request_payload is None
    assert siliconflow.speech_calls == 1, siliconflow.speech_calls
    assert siliconflow.speech_payload["voice"] == "FunAudioLLM/CosyVoice2-0.5B:anna", siliconflow.speech_payload
    assert seedance.request_payload is not None
    assert seedance.request_payload["image_size"] == "480x854", seedance.request_payload
    assert seedance.request_payload["image"] == "https://example.com/avatar.png", seedance.request_payload
    assert seedance.request_payload["model"] == "doubao-seedance-1-5-pro-251215", seedance.request_payload
    assert seedance.request_payload["audio_data_url"].startswith("data:audio/mpeg;base64,"), seedance.request_payload
    assert seedance.request_payload["duration"] == "5 seconds", seedance.request_payload
    assert seedance.request_payload["resolution"] == "480p", seedance.request_payload
    assert len(siliconflow.image_prompts) == 0, siliconflow.image_prompts
    assert "Create a realistic digital human presenter video" in seedance.request_payload["prompt"]
    assert "Test Lab" in seedance.request_payload["prompt"]
    assert postprocessor.payload is not None
    assert postprocessor.payload["video_url"] == "https://example.com/fake-video.mp4"
    assert postprocessor.payload["subtitle_text"] == "Hello, this is a short digital human video used for worker validation."
    assert backend.success_payload is not None
    assert backend.success_payload["resourceType"] == "MARKDOWN"
    assert "/generated/digital-human/99002/final.mp4" in backend.success_payload["contentText"]
    assert "https://example.com/fake-video.mp4" in backend.success_payload["contentText"]
    assert "seedance_fake_001" in backend.success_payload["contentText"]
    assert "480p" in backend.success_payload["contentText"]
    assert "subtitle.srt" in backend.success_payload["contentText"]

    failing_backend = FakeBackendClient()
    failing_siliconflow = FakeSiliconFlowClient()
    failing_handler = DigitalHumanVideoHandler(
        backend_client=failing_backend,
        video_client=failing_siliconflow,
        seedance_video_client=FailingSeedanceClient(),
        postprocessor=FakePostprocessor(),
    )
    original_provider = settings.digital_human_video_provider
    settings.digital_human_video_provider = "seedance"
    try:
        failed_result = failing_handler.handle({"taskId": 99003, "toolCode": "digital_human_agent"})
    finally:
        settings.digital_human_video_provider = original_provider
    assert failed_result["status"] == "FAILED", failed_result
    assert failing_backend.failed_payload is not None
    assert failing_backend.failed_payload["errorCode"] == "MODEL_CALL_FAILED"
    assert failing_siliconflow.speech_calls == 1, failing_siliconflow.speech_calls
    assert failing_siliconflow.image_prompts == [], failing_siliconflow.image_prompts

    srt = DigitalHumanPostprocessor._build_srt("First sentence!Second sentence!Third sentence!", 9.0)
    assert "00:00:00,000 -->" in srt
    assert "First sentence!" in srt
    assert "Third sentence!" in srt

    router_backend = FakeBackendClient()
    spy_digital_handler = SpyDigitalHumanHandler()
    router = TaskHandlerRouter(
        text_handler=UnexpectedTextHandler(),
        digital_human_handler=spy_digital_handler,
        backend_client=router_backend,
    )
    routed_result = router.handle({"taskId": 99002})
    assert routed_result["status"] == "SUCCESS", routed_result
    assert spy_digital_handler.message is not None
    assert spy_digital_handler.message["__executionContext"]["toolCode"] == "digital_human_agent"
    print("FAKE_DIGITAL_HUMAN_VIDEO_TEST_PASSED")


if __name__ == "__main__":
    main()
