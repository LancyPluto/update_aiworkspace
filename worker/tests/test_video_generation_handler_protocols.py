from handlers.video_generation_handler import VideoGenerationHandler


class FakeBackendClient:
    def __init__(self) -> None:
        self.success_payload: dict | None = None
        self.failed_payload: dict | None = None

    def get_execution_context(self, task_id: int, trace_id: str | None = None) -> dict:
        return {
            "traceId": trace_id,
            "status": "QUEUED",
            "params": {
                "prompt": "扣篮",
                "duration": 3,
                "aspectRatio": "16:9",
            },
            "modelConfig": {
                "provider": "seedance",
                "modelName": "doubao-seedance-1-5-pro-251215",
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
        return {}

    def mark_success(self, task_id: int, payload: dict, trace_id: str | None = None) -> dict:
        self.success_payload = payload
        return {}

    def mark_failed(self, task_id: int, payload: dict, trace_id: str | None = None) -> dict:
        self.failed_payload = payload
        return {}


class StrictSeedanceClient:
    def __init__(self) -> None:
        self.request: dict | None = None

    def generate_video(
        self,
        *,
        prompt: str,
        image_size: str,
        negative_prompt: str = "",
        model: str | None = None,
        image: str = "",
        seed: int | None = None,
        duration: str = "",
        aspect_ratio: str = "",
        resolution: str = "",
    ) -> dict:
        self.request = {
            "prompt": prompt,
            "image_size": image_size,
            "negative_prompt": negative_prompt,
            "model": model,
            "image": image,
            "seed": seed,
            "duration": duration,
            "aspect_ratio": aspect_ratio,
            "resolution": resolution,
        }
        return {
            "videoUrl": "https://cdn.example/seedance.mp4",
            "requestId": "seedance-task-1",
            "status": "succeeded",
            "provider": "seedance",
            "model": model,
            "resolution": resolution,
        }


class FakeVideoPersister:
    def find_existing_task_video(self, *, task_id: int) -> dict[str, str] | None:
        return None

    def persist_video_url(self, *, task_id: int, source_url: str) -> dict[str, str]:
        return {"url": f"/generated/video/{task_id}/video-1.mp4", "sourceUrl": source_url}


def test_seedance_video_handler_omits_tail_frame_protocol_field() -> None:
    backend = FakeBackendClient()
    seedance = StrictSeedanceClient()
    handler = VideoGenerationHandler(
        backend_client=backend,
        seedance_client=seedance,
        video_persister=FakeVideoPersister(),
    )

    result = handler.handle({"taskId": 129, "traceId": "trace-seedance"})

    assert result["status"] == "SUCCESS"
    assert backend.failed_payload is None
    assert backend.success_payload is not None
    assert seedance.request is not None
    assert seedance.request["prompt"] == "扣篮"
    assert seedance.request["duration"] == "3"
    assert seedance.request["aspect_ratio"] == "16:9"
