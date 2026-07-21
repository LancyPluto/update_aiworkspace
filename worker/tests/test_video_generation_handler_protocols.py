from client.seedance_video_client import SeedanceVideoTimeoutError
from handlers.video_generation_handler import VideoGenerationHandler


class FakeBackendClient:
    def __init__(self) -> None:
        self.success_payload: dict | None = None
        self.failed_payload: dict | None = None
        self.checkpoints: list[dict] = []

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

    def save_provider_checkpoint(
        self,
        task_id: int,
        checkpoint: dict,
        *,
        expected_version: int,
        trace_id: str | None = None,
    ) -> dict:
        self.checkpoints.append(checkpoint)
        return {"version": expected_version + 1, "checkpoint": checkpoint}


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
        resume: dict | None = None,
        submitted_callback=None,
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
            "resume": resume,
        }
        if submitted_callback and not resume:
            submitted_callback({"taskId": "seedance-task-1", "requestId": "seedance-task-1"})
        return {
            "videoUrl": "https://cdn.example/seedance.mp4",
            "requestId": "seedance-task-1",
            "status": "succeeded",
            "provider": "seedance",
            "model": model,
            "resolution": resolution,
        }


class TimeoutSeedanceClient:
    def generate_video(self, **_kwargs) -> dict:
        raise SeedanceVideoTimeoutError("seedance poll timed out")


class FakeVideoPersister:
    def find_existing_task_video(self, *, task_id: int) -> dict[str, str] | None:
        return None

    def persist_video_url(self, *, task_id: int, source_url: str) -> dict[str, str]:
        return {"url": f"/generated/video/{task_id}/video-1.mp4", "sourceUrl": source_url}


def test_seedance_video_handler_uses_params_model_override() -> None:
    backend = FakeBackendClient()
    backend.get_execution_context = lambda task_id, trace_id=None: {
        "traceId": trace_id,
        "status": "QUEUED",
        "params": {
            "prompt": "扣篮",
            "duration": 3,
            "aspectRatio": "16:9",
            "model": "doubao-seedance-1-0-pro-fast-251015",
        },
        "modelConfig": {
            "provider": "seedance",
            "modelName": "doubao-seedance-1-5-pro-251215",
        },
    }
    seedance = StrictSeedanceClient()
    handler = VideoGenerationHandler(
        backend_client=backend,
        seedance_client=seedance,
        video_persister=FakeVideoPersister(),
    )

    result = handler.handle({"taskId": 130, "traceId": "trace-seedance-model"})

    assert result["status"] == "SUCCESS"
    assert seedance.request is not None
    assert seedance.request["model"] == "doubao-seedance-1-0-pro-fast-251015"


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
    assert backend.checkpoints[0]["requestId"] == "seedance-task-1"


def test_seedance_video_handler_resumes_saved_submission_without_new_checkpoint() -> None:
    backend = FakeBackendClient()
    original_context = backend.get_execution_context

    def context_with_checkpoint(task_id: int, trace_id: str | None = None) -> dict:
        context = original_context(task_id, trace_id)
        context["providerCheckpoint"] = {
            "kind": "VIDEO_SUBMISSION",
            "provider": "seedance",
            "model": "doubao-seedance-1-5-pro-251215",
            "status": "SUBMITTED",
            "taskId": "seedance-existing",
            "requestId": "seedance-existing",
        }
        context["providerCheckpointVersion"] = 1
        return context

    backend.get_execution_context = context_with_checkpoint
    seedance = StrictSeedanceClient()
    handler = VideoGenerationHandler(
        backend_client=backend,
        seedance_client=seedance,
        video_persister=FakeVideoPersister(),
    )

    result = handler.handle({"taskId": 132, "traceId": "trace-resume"})

    assert result["status"] == "SUCCESS"
    assert seedance.request["resume"]["taskId"] == "seedance-existing"
    assert backend.checkpoints == []


def test_video_handler_timeout_marks_failed_for_credit_release() -> None:
    backend = FakeBackendClient()
    handler = VideoGenerationHandler(
        backend_client=backend,
        seedance_client=TimeoutSeedanceClient(),
        video_persister=FakeVideoPersister(),
    )

    result = handler.handle({"taskId": 131, "traceId": "trace-timeout"})

    assert result["status"] == "FAILED"
    assert result["errorCode"] == "MODEL_TIMEOUT"
    assert backend.success_payload is None
    assert backend.failed_payload == {
        "errorCode": "MODEL_TIMEOUT",
        "errorMessage": "seedance poll timed out",
        "deliveryState": "UNKNOWN",
        "retryScope": "NONE",
        "failureStage": "PROVIDER_SUBMITTED",
    }
