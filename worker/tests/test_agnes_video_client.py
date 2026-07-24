import base64

import pytest
import requests

from client.agnes_video_client import (
    AgnesVideoClient,
    AgnesVideoRequestNotSentError,
    AgnesVideoTimeoutError,
)


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
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


def test_agnes_connection_refused_is_marked_not_sent():
    client = AgnesVideoClient(base_url="https://apihub.agnes-ai.com", api_key="test-key")
    client.session = RecordingSession([
        requests.ConnectionError(ConnectionRefusedError("connection refused")),
    ])

    with pytest.raises(AgnesVideoRequestNotSentError) as raised:
        client._request("POST", "/v1/videos", json_payload={"model": "agnes-video-v2.0"})

    assert raised.value.delivery_state == "NOT_SENT"
    assert raised.value.retry_scope == "ACCOUNT"


def test_agnes_read_timeout_keeps_delivery_unknown():
    client = AgnesVideoClient(base_url="https://apihub.agnes-ai.com", api_key="test-key")
    client.session = RecordingSession([requests.ReadTimeout("response lost")])

    with pytest.raises(AgnesVideoTimeoutError) as raised:
        client._request("POST", "/v1/videos", json_payload={"model": "agnes-video-v2.0"})

    assert raised.value.delivery_state == "UNKNOWN"
    assert raised.value.retry_scope == "NONE"


def test_agnes_explicit_runtime_timeout_overrides_short_account_timeout():
    client = AgnesVideoClient(
        base_url="https://apihub.agnes-ai.com",
        api_key="test-key",
        timeout_seconds=900,
        extra_auth_json='{"timeoutSeconds":60}',
    )

    assert client.timeout_seconds == 900


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


def test_agnes_video_client_reports_submission_before_polling():
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
    submissions = []

    def record_submission(submission):
        submissions.append(submission)
        assert [call["method"] for call in session.calls] == ["POST"]

    client.generate_video(
        prompt="A cinematic product reveal",
        image_size="1280x720",
        model="agnes-video-v2.0",
        submitted_callback=record_submission,
    )

    assert submissions == [
        {"taskId": "task_123", "videoId": "video_456", "requestId": "task_123"}
    ]


def test_agnes_video_client_checkpoints_immediate_result_before_returning():
    session = RecordingSession(
        [FakeResponse({
            "task_id": "task_immediate",
            "video_id": "video_immediate",
            "status": "completed",
            "video_url": "https://cdn.example/immediate.mp4",
        })]
    )
    client = AgnesVideoClient(
        base_url="https://apihub.agnes-ai.com",
        api_key="test-key",
        poll_interval_seconds=0,
        timeout_seconds=2,
    )
    client.session = session
    submissions = []

    result = client.generate_video(
        prompt="A cinematic product reveal",
        image_size="1280x720",
        model="agnes-video-v2.0",
        submitted_callback=submissions.append,
    )

    assert result["videoUrl"] == "https://cdn.example/immediate.mp4"
    assert submissions == [{
        "taskId": "task_immediate",
        "videoId": "video_immediate",
        "requestId": "task_immediate",
    }]
    assert [call["method"] for call in session.calls] == ["POST"]


def test_agnes_video_client_resumes_checkpoint_with_get_only():
    session = RecordingSession(
        [FakeResponse({"status": "completed", "video_url": "https://cdn.example/video.mp4"})]
    )
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
        resume={"taskId": "task_123", "videoId": "video_456", "requestId": "request_789"},
    )

    assert result["requestId"] == "request_789"
    assert result["videoUrl"] == "https://cdn.example/video.mp4"
    assert [call["method"] for call in session.calls] == ["GET"]


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


def test_agnes_image_to_video_uses_one_top_level_image():
    client = AgnesVideoClient(base_url="https://apihub.agnes-ai.com", api_key="test-key")

    payload = client._build_payload(
        prompt="Animate this frame",
        image_size="1280x720",
        negative_prompt="",
        model="agnes-video-v2.0",
        image="https://cdn.example/frame.png",
        image_tail="",
        images=None,
        seed=None,
        duration="5",
        aspect_ratio="16:9",
        resolution="",
        mode="",
        generation_mode="image_to_video",
    )

    assert payload["image"] == "https://cdn.example/frame.png"
    assert "extra_body" not in payload


def test_agnes_video_payload_uses_explicit_frame_contract():
    client = AgnesVideoClient(base_url="https://apihub.agnes-ai.com", api_key="test-key")

    payload = client._build_payload(
        prompt="Generate a short clip",
        image_size="1280x720",
        negative_prompt="",
        model="agnes-video-v2.0",
        image="",
        image_tail="",
        images=None,
        seed=None,
        duration="",
        aspect_ratio="16:9",
        resolution="",
        mode="",
        generation_mode="text_to_video",
        num_frames=81,
        frame_rate=30,
    )

    assert payload["num_frames"] == 81
    assert payload["frame_rate"] == 30


@pytest.mark.parametrize(
    "generation_mode,image,images,error",
    [
        ("text_to_video", "https://cdn.example/frame.png", None, "does not accept"),
        ("image_to_video", "", None, "exactly one image"),
        ("keyframes", "https://cdn.example/frame.png", None, "at least two images"),
        ("unsupported", "", None, "unsupported Agnes generationMode"),
    ],
)
def test_agnes_generation_mode_rejects_mismatched_media(
    generation_mode,
    image,
    images,
    error,
):
    client = AgnesVideoClient(base_url="https://apihub.agnes-ai.com", api_key="test-key")

    with pytest.raises(Exception, match=error):
        client._build_payload(
            prompt="Generate a video",
            image_size="1280x720",
            negative_prompt="",
            model="agnes-video-v2.0",
            image=image,
            image_tail="",
            images=images,
            seed=None,
            duration="5",
            aspect_ratio="16:9",
            resolution="",
            mode="",
            generation_mode=generation_mode,
        )


def test_agnes_video_client_accepts_explicit_multi_image_list():
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
        prompt="Preserve character prop and location continuity",
        image_size="1280x720",
        model="agnes-video-v2.0",
        image="https://cdn.example/ignored-when-list-present.png",
        images=[
            "https://cdn.example/scene.png",
            "https://cdn.example/character-board.png",
            "https://cdn.example/prop-board.png",
            "https://cdn.example/location-board.png",
        ],
    )

    payload = session.calls[0]["json"]
    assert "image" not in payload
    assert payload["extra_body"]["image"] == [
        "https://cdn.example/scene.png",
        "https://cdn.example/character-board.png",
        "https://cdn.example/prop-board.png",
        "https://cdn.example/location-board.png",
    ]


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


def test_agnes_video_client_retries_poll_timeout_without_repeating_create(monkeypatch):
    session = RecordingSession(
        [
            FakeResponse({"task_id": "task_123", "video_id": "video_456", "status": "queued"}),
            requests.Timeout("temporary poll timeout"),
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
    monkeypatch.setattr("client.agnes_video_client.time.sleep", lambda _seconds: None)

    result = client.generate_video(
        prompt="A cinematic product reveal",
        image_size="1280x720",
        model="agnes-video-v2.0",
    )

    assert result["videoUrl"] == "https://cdn.example/video.mp4"
    assert [call["method"] for call in session.calls] == ["POST", "GET", "GET"]


def test_agnes_poll_retries_respect_total_deadline_and_clip_request_timeout(monkeypatch):
    class FakeClock:
        def __init__(self):
            self.now = 0.0
            self.sleeps = []

        def monotonic(self):
            return self.now

        def sleep(self, seconds):
            self.sleeps.append(seconds)
            self.now += seconds

    clock = FakeClock()
    session = RecordingSession(
        [
            FakeResponse({"task_id": "task_123", "video_id": "video_456", "status": "queued"}),
            requests.Timeout("poll consumed most of the total budget"),
        ]
    )
    original_request = session.request

    def timed_request(method, url, **kwargs):
        if method == "GET":
            clock.now += 1.5
        return original_request(method, url, **kwargs)

    session.request = timed_request
    client = AgnesVideoClient(
        base_url="https://apihub.agnes-ai.com",
        api_key="test-key",
        poll_interval_seconds=0,
        timeout_seconds=2,
    )
    client.session = session
    monkeypatch.setattr("client.agnes_video_client.time.monotonic", clock.monotonic)
    monkeypatch.setattr("client.agnes_video_client.time.sleep", clock.sleep)

    with pytest.raises(AgnesVideoTimeoutError, match="generation timed out"):
        client.generate_video(
            prompt="A cinematic product reveal",
            image_size="1280x720",
            model="agnes-video-v2.0",
        )

    get_calls = [call for call in session.calls if call["method"] == "GET"]
    assert len(get_calls) == 1
    request_timeout = get_calls[0]["timeout"]
    assert request_timeout.total == 2.0
    assert request_timeout.connect_timeout == 2.0
    assert request_timeout.read_timeout == 2.0
    assert clock.sleeps == [0.5]
