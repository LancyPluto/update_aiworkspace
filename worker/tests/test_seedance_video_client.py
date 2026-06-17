from client.seedance_video_client import SeedanceVideoClient


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
