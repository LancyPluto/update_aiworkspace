import sys
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from handlers.digital_human_video_handler import DigitalHumanVideoHandler
from task_queue.redis_consumer import TaskHandlerRouter


class FakeBackendClient:
    def __init__(self) -> None:
        self.events: list[str] = []
        self.success_payload: dict | None = None
        self.progress_messages: list[str] = []

    def get_execution_context(self, task_id: int) -> dict:
        self.events.append("execution-context")
        return {
            "taskId": task_id,
            "toolCode": "digital_human_agent",
            "params": {
                "videoTopic": "新品精华数字人口播",
                "script": "大家好，今天给大家介绍这款适合通勤补水的精华。",
                "avatarStyle": "职业主播",
                "scene": "产品展示台",
                "aspectRatio": "9:16 竖屏",
                "brandName": "澄光实验室",
                "referenceImageUrl": "https://example.com/avatar.png",
                "visualRequirements": "半身出镜，画面明亮，产品放在右侧。",
                "negativePrompt": "低清晰度、手部异常、字幕错乱",
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
        raise AssertionError(payload)


class FakeVideoClient:
    def __init__(self) -> None:
        self.request_payload: dict | None = None
        self.image_prompts: list[str] = []

    def generate_speech_data_url(self, **kwargs) -> str:
        return "data:audio/mpeg;base64,ZmFrZQ=="

    def generate_image(self, **kwargs) -> str:
        self.image_prompts.append(kwargs["prompt"])
        return f"https://example.com/generated-{len(self.image_prompts)}.png"

    def generate_video(self, **kwargs) -> dict:
        self.request_payload = kwargs
        return {
            "requestId": "req_fake_001",
            "status": "Succeed",
            "videoUrl": "https://example.com/fake-video.mp4",
            "reason": "",
            "seed": 123,
            "timings": {"inference": 456},
        }


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
    backend = FakeBackendClient()
    video = FakeVideoClient()
    handler = DigitalHumanVideoHandler(backend_client=backend, video_client=video)

    result = handler.handle({"taskId": 99002, "toolCode": "digital_human_agent"})

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
    assert video.request_payload["image_size"] == "720x1280", video.request_payload
    assert video.request_payload["image"] == "https://example.com/avatar.png", video.request_payload
    assert video.request_payload["model"] == "Wan-AI/Wan2.2-I2V-A14B", video.request_payload
    assert len(video.image_prompts) == 1, video.image_prompts
    assert "Create a realistic digital human presenter video" in video.request_payload["prompt"]
    assert "澄光实验室" in video.request_payload["prompt"]
    assert backend.success_payload is not None
    assert backend.success_payload["resourceType"] == "MARKDOWN"
    assert "https://example.com/fake-video.mp4" in backend.success_payload["contentText"]
    assert "req_fake_001" in backend.success_payload["contentText"]
    assert "口播音频" in backend.success_payload["contentText"]

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
