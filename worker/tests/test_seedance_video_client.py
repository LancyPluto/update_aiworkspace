from client.seedance_video_client import SeedanceVideoClient


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


def test_normalize_endpoint_avoids_double_api_v3_prefix() -> None:
    base, path = SeedanceVideoClient._normalize_endpoint(
        "https://ark.cn-beijing.volces.com/api/v3",
        "/api/v3/contents/generations/tasks",
    )
    assert base == "https://ark.cn-beijing.volces.com/api/v3"
    assert path == "/contents/generations/tasks"


def test_normalize_endpoint_upgrades_host_only_base_url() -> None:
    base, path = SeedanceVideoClient._normalize_endpoint(
        "https://ark.cn-beijing.volces.com",
        "/api/v3/contents/generations/tasks",
    )
    assert base == "https://ark.cn-beijing.volces.com/api/v3"
    assert path == "/contents/generations/tasks"


def test_seedance_inlines_internal_generated_image(monkeypatch) -> None:
    captured: dict[str, str] = {}

    def fake_resolve(value: str, *, session=None) -> str:
        captured["value"] = value
        captured["has_session"] = str(session is not None)
        return "data:image/png;base64,ZmFrZQ=="

    monkeypatch.setattr("client.seedance_video_client.resolve_reference_image_data_url", fake_resolve)
    client = SeedanceVideoClient(api_key="test-key")

    payload = client._build_payload(
        prompt="动起来",
        image_size="auto",
        negative_prompt="",
        model="doubao-seedance-1-5-pro-251215",
        image="http://backend:8080/generated/uploads/20260617/input.png",
        audio_data_url="",
        seed=None,
        duration="5",
        aspect_ratio="16:9",
        resolution="480p",
    )

    assert captured == {
        "value": "http://backend:8080/generated/uploads/20260617/input.png",
        "has_session": "True",
    }
    assert payload["content"][1]["image_url"]["url"] == "data:image/png;base64,ZmFrZQ=="


def test_seedance_keeps_public_image_url(monkeypatch) -> None:
    def fail_resolve(value: str, *, session=None) -> str:
        raise AssertionError("public URLs should not be inlined")

    monkeypatch.setattr("client.seedance_video_client.resolve_reference_image_data_url", fail_resolve)
    client = SeedanceVideoClient(api_key="test-key")

    payload = client._build_payload(
        prompt="动起来",
        image_size="auto",
        negative_prompt="",
        model="doubao-seedance-1-5-pro-251215",
        image="https://cdn.example.com/input.png",
        audio_data_url="",
        seed=None,
        duration="5",
        aspect_ratio="16:9",
        resolution="480p",
    )

    assert payload["content"][1]["image_url"]["url"] == "https://cdn.example.com/input.png"


def test_seedance_payload_includes_audio_and_watermark_flags() -> None:
    client = SeedanceVideoClient(api_key="test-key")

    payload = client._build_payload(
        prompt="动起来",
        image_size="auto",
        negative_prompt="",
        model="doubao-seedance-2-0-260128",
        image="",
        audio_data_url="",
        seed=None,
        duration="5",
        aspect_ratio="16:9",
        resolution="720p",
        generate_audio=True,
        watermark=False,
    )

    assert payload["generate_audio"] is True
    assert payload["watermark"] is False


def test_seedance_payload_supports_multi_reference_images_video_and_camera_fixed() -> None:
    client = SeedanceVideoClient(api_key="test-key")

    payload = client._build_payload(
        prompt="让角色在街头转身",
        image_size="auto",
        negative_prompt="",
        model="doubao-seedance-2-0-260128",
        image="",
        images=["https://cdn.example.com/ref-a.png", "https://cdn.example.com/ref-b.png"],
        video_url="https://cdn.example.com/ref.mov",
        audio_data_url="https://cdn.example.com/ref.mp3",
        seed=42,
        duration="10",
        aspect_ratio="adaptive",
        resolution="4k",
        camera_fixed=True,
    )

    image_items = [item for item in payload["content"] if item["type"] == "image_url"]
    assert [item["role"] for item in image_items] == ["reference_image", "reference_image"]
    assert payload["content"][3] == {
        "type": "video_url",
        "video_url": {"url": "https://cdn.example.com/ref.mov"},
        "role": "reference_video",
    }
    assert payload["content"][4] == {"type": "audio_url", "audio_url": {"url": "https://cdn.example.com/ref.mp3"}}
    assert payload["ratio"] == "adaptive"
    assert payload["camera_fixed"] is True


def test_seedance_async_poll_does_not_repeat_create_request() -> None:
    session = RecordingSession(
        [
            FakeResponse({"id": "seedance-task-1", "status": "queued"}),
            FakeResponse({"id": "seedance-task-1", "status": "processing"}),
            FakeResponse({"id": "seedance-task-1", "status": "running"}),
            FakeResponse({"id": "seedance-task-1", "status": "succeeded", "video_url": "https://cdn.example.com/out.mp4"}),
        ]
    )
    client = SeedanceVideoClient(
        api_key="test-key",
        poll_interval_seconds=0,
        timeout_seconds=2,
    )
    client.session = session

    result = client.generate_video(
        prompt="动起来",
        image_size="auto",
        model="doubao-seedance-1-5-pro-251215",
        duration="5",
        aspect_ratio="16:9",
        resolution="480p",
    )

    assert result["videoUrl"] == "https://cdn.example.com/out.mp4"
    methods = [call["method"] for call in session.calls]
    assert methods == ["POST", "GET", "GET", "GET"]
