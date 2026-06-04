import base64

from utils.input_image import (
    InputImageError,
    decode_reference_image_data_url,
    resolve_reference_image_data_url,
)


def test_resolve_reference_image_data_url_from_raw_base64() -> None:
    raw = base64.b64encode(b"fake-image").decode("ascii")
    resolved = resolve_reference_image_data_url(raw)

    assert resolved.startswith("data:image/png;base64,"), resolved
    assert resolved.endswith(raw), resolved


def test_decode_reference_image_data_url() -> None:
    data, mime = decode_reference_image_data_url("data:image/png;base64,ZmFrZQ==")
    assert data == b"fake"
    assert mime == "image/png"


def test_resolve_reference_image_data_url_keeps_existing_data_url() -> None:
    data_url = "data:image/jpeg;base64,ZmFrZQ=="
    assert resolve_reference_image_data_url(data_url) == data_url


def test_resolve_reference_image_data_url_rejects_empty() -> None:
    try:
        resolve_reference_image_data_url("   ")
        raise AssertionError("expected InputImageError")
    except InputImageError as exc:
        assert "empty" in str(exc).lower()
