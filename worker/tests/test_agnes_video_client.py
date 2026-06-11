import base64

from client.agnes_video_client import AgnesVideoClient


PNG_BYTES = b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR"


class FakeResponse:
    def __init__(self, payload, status_code=200):
        self._payload = payload
        self.status_code = status_code
        self.text = str(payload)

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")

    def json(self):
        return self._payload


class RecordingSession:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def request(self, method, url, **kwargs):
        self.calls.append({"method": method, "url": url, **kwargs})
        return self.responses.pop(0)


def test_agnes_video_client_creates_task_and_retrieves_result_by_video_id():
    session = RecordingSession(
        [
            FakeResponse(
                {
                    "id": "task_123",
                    "task_id": "task_123",
                    "video_id": "video_456",
                    "status": "queued",
                    "model": "agnes-video-v2.0",
                }
            ),
            FakeResponse(
                {
                    "id": "task_123",
                    "video_id": "video_456",
                    "status": "completed",
                    "model": "agnes-video-v2.0",
                    "remixed_from_video_id": "https://cdn.example/video_456.mp4",
                }
            ),
        ]
    )
    client = AgnesVideoClient(
        base_url="https://apihub.agnes-ai.com/v1",
        api_key="test-key",
        poll_interval_seconds=0,
        timeout_seconds=2,
    )
    client.session = session

    result = client.generate_video(
        prompt="A cinematic product reveal",
        image_size="1152x768",
        model="agnes-video-v2.0",
    )

    assert result["videoUrl"] == "https://cdn.example/video_456.mp4"
    assert session.calls[0]["method"] == "POST"
    assert session.calls[0]["url"] == "https://apihub.agnes-ai.com/v1/videos"
    assert session.calls[0]["json"] == {
        "model": "agnes-video-v2.0",
        "prompt": "A cinematic product reveal",
        "height": 768,
        "width": 1152,
        "num_frames": 121,
        "frame_rate": 24,
    }
    assert session.calls[1]["method"] == "GET"
    assert session.calls[1]["url"] == "https://apihub.agnes-ai.com/agnesapi"
    assert session.calls[1]["params"] == {"video_id": "video_456", "model_name": "agnes-video-v2.0"}


def test_agnes_video_client_uses_extra_body_image_for_multi_image_requests():
    session = RecordingSession(
        [
            FakeResponse({"task_id": "task_123", "video_id": "video_456", "status": "queued"}),
            FakeResponse({"status": "completed", "video_url": "https://cdn.example/video.mp4"}),
        ]
    )
    client = AgnesVideoClient(
        base_url="https://apihub.agnes-ai.com",
        api_key="test-key",
        poll_interval_seconds=0,
        timeout_seconds=2,
    )
    client.session = session

    client.generate_video(
        prompt="Move from first keyframe to second keyframe",
        image_size="1280x720",
        model="agnes-video-v2.0",
        image="https://cdn.example/start.png",
        image_tail="https://cdn.example/end.png",
        mode="keyframes",
    )

    payload = session.calls[0]["json"]
    assert "image" not in payload
    assert payload["extra_body"] == {
        "image": ["https://cdn.example/start.png", "https://cdn.example/end.png"],
        "mode": "keyframes",
    }


def test_agnes_video_client_encodes_local_generated_images_as_base64(tmp_path, monkeypatch):
    media_root = tmp_path / "generated-media"
    image_path = media_root / "market-files" / "2" / "reference.png"
    image_path.parent.mkdir(parents=True)
    image_path.write_bytes(PNG_BYTES)
    monkeypatch.setattr("client.agnes_video_client.settings.generated_media_dir", str(media_root))
    monkeypatch.setattr("client.agnes_video_client.settings.generated_media_public_base_url", "/generated")
    session = RecordingSession(
        [
            FakeResponse(
                {
                    "task_id": "task_123",
                    "status": "completed",
                    "video_url": "https://cdn.example/video.mp4",
                }
            ),
        ]
    )
    client = AgnesVideoClient(
        base_url="https://apihub.agnes-ai.com",
        api_key="test-key",
        poll_interval_seconds=0,
        timeout_seconds=2,
    )
    client.session = session

    client.generate_video(
        prompt="Animate the reference character",
        image_size="1280x720",
        model="agnes-video-v2.0",
        image="/generated/market-files/2/reference.png",
    )

    payload = session.calls[0]["json"]
    assert payload["image"] == base64.b64encode(PNG_BYTES).decode("ascii")


def test_agnes_video_client_reports_poll_progress_when_provider_returns_percent():
    session = RecordingSession(
        [
            FakeResponse({"task_id": "task_123", "video_id": "video_456", "status": "queued"}),
            FakeResponse({"status": "processing", "data": {"progress": 56}}),
            FakeResponse({"status": "completed", "video_url": "https://cdn.example/video.mp4"}),
        ]
    )
    progress_updates = []
    client = AgnesVideoClient(
        base_url="https://apihub.agnes-ai.com",
        api_key="test-key",
        poll_interval_seconds=0,
        timeout_seconds=2,
    )
    client.session = session

    result = client.generate_video(
        prompt="A cinematic product reveal",
        image_size="1280x720",
        model="agnes-video-v2.0",
        progress_callback=progress_updates.append,
    )

    assert result["videoUrl"] == "https://cdn.example/video.mp4"
    assert progress_updates == [56]
