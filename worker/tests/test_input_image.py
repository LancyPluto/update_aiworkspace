import base64
import struct

import pytest

from utils.input_image import (
    InputImageError,
    decode_reference_image_data_url,
    get_image_dimensions,
    resolve_reference_image_data_url,
    validate_min_resolution,
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


def _make_png(width: int, height: int) -> bytes:
    header = b"\x89PNG\r\n\x1a\n"
    ihdr_data = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return header + b"\x00" * 4 + b"IHDR" + ihdr_data


def _make_jpeg(width: int, height: int) -> bytes:
    sof_data = struct.pack(">HBHH", 11, 8, height, width) + b"\x00"
    return b"\xff\xd8" + b"\xff\xc0" + sof_data


def _to_data_url(data: bytes, mime: str = "image/png") -> str:
    return f"data:{mime};base64,{base64.b64encode(data).decode()}"


def test_get_image_dimensions_png() -> None:
    assert get_image_dimensions(_make_png(400, 223)) == (400, 223)


def test_get_image_dimensions_jpeg() -> None:
    assert get_image_dimensions(_make_jpeg(1920, 1080)) == (1920, 1080)


def test_get_image_dimensions_unknown_returns_none() -> None:
    assert get_image_dimensions(b"not an image") is None


def test_validate_min_resolution_passes_for_large_image() -> None:
    data_url = _to_data_url(_make_png(500, 500))
    validate_min_resolution(data_url, 300, 300)


def test_validate_min_resolution_rejects_width_too_small() -> None:
    data_url = _to_data_url(_make_png(200, 500))
    with pytest.raises(InputImageError, match="200x500"):
        validate_min_resolution(data_url, 300, 300)


def test_validate_min_resolution_rejects_height_too_small() -> None:
    data_url = _to_data_url(_make_png(400, 223))
    with pytest.raises(InputImageError, match="400x223"):
        validate_min_resolution(data_url, 300, 300)


def test_validate_min_resolution_skips_non_data_url() -> None:
    validate_min_resolution("https://example.com/image.png", 300, 300)
