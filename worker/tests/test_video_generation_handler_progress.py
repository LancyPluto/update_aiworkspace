from handlers.video_generation_handler import VideoGenerationHandler


class FakeBackendClient:
    def __init__(self):
        self.processing_updates = []
        self.success_payloads = []

    def get_execution_context(self, task_id, trace_id=None):
        return {
            "traceId": trace_id,
            "status": "QUEUED",
            "params": {"prompt": "A cinematic product reveal"},
            "modelConfig": {
                "provider": "agnes_video",
                "modelName": "agnes-video-v2.0",
                "apiKey": "test-key",
            },
        }

    def mark_processing(self, task_id, *, progress=None, progress_message=None, trace_id=None):
        self.processing_updates.append(
            {
                "taskId": task_id,
                "progress": progress,
                "progressMessage": progress_message,
                "traceId": trace_id,
            }
        )
        return {}

    def mark_success(self, task_id, payload, trace_id=None):
        self.success_payloads.append({"taskId": task_id, "payload": payload, "traceId": trace_id})
        return {}

    def mark_failed(self, task_id, payload, trace_id=None):
        raise AssertionError(f"task should not fail: {payload}")


class FakeAgnesClient:
    def generate_video(self, **kwargs):
        progress_callback = kwargs["progress_callback"]
        progress_callback(56)
        return {
            "videoUrl": "https://cdn.example/video.mp4",
            "requestId": "req_1",
            "status": "completed",
            "provider": "agnes_video",
            "model": kwargs.get("model"),
            "resolution": kwargs.get("image_size"),
        }


class FakeVideoPersister:
    def find_existing_task_video(self, *, task_id):
        return None

    def persist_video_url(self, *, task_id, source_url):
        return {"url": f"/generated/videos/{task_id}.mp4", "sourceUrl": source_url}


def test_video_generation_handler_writes_provider_realtime_progress():
    backend = FakeBackendClient()
    handler = VideoGenerationHandler(
        backend_client=backend,
        agnes_client=FakeAgnesClient(),
        video_persister=FakeVideoPersister(),
    )

    result = handler.handle({"taskId": 42, "traceId": "trace-1"})

    assert result["status"] == "SUCCESS"
    assert {
        "taskId": 42,
        "progress": 56,
        "progressMessage": "实时进度：56%",
        "traceId": "trace-1",
    } in backend.processing_updates
