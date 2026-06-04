import base64
import mimetypes
from pathlib import Path
from urllib.parse import urlparse

import requests

from config import settings


class InputImageError(RuntimeError):
    pass


_MAX_INPUT_IMAGE_BYTES = 20 * 1024 * 1024


def decode_reference_image_data_url(data_url: str) -> tuple[bytes, str]:
    """Decode a base64 data URL into raw bytes and image MIME type."""
    raw = data_url.strip()
    if not raw.startswith("data:"):
        raise InputImageError("reference image must be a base64 data url")
    header, separator, encoded = raw.partition(",")
    if not separator or ";base64" not in header.lower():
        raise InputImageError("reference image data url is not base64 encoded")
    _validate_base64(encoded)
    mime = _normalize_mime(header.removeprefix("data:").strip()) or "image/png"
    return base64.b64decode("".join(encoded.split()), validate=True), mime


def resolve_reference_image_data_url(value: str, *, session: requests.Session | None = None) -> str:
    """Resolve URL, local path, raw base64, or data URL to a base64 data URL for model APIs."""
    raw = value.strip()
    if not raw:
        raise InputImageError("reference image is empty")
    if raw.startswith("data:"):
        header, separator, encoded = raw.partition(",")
        if separator and ";base64" in header.lower():
            _validate_base64(encoded)
            return raw
        raise InputImageError("reference image data url is not base64 encoded")
    if _is_raw_base64(raw):
        return _bytes_to_data_url(base64.b64decode("".join(raw.split()), validate=True))
    local_path = _local_media_path(raw)
    if local_path is not None:
        return _file_to_data_url(local_path)
    if raw.startswith(("http://", "https://")) or raw.startswith("/"):
        return _download_to_data_url(raw, session=session)
    possible_path = Path(raw)
    if possible_path.exists() and possible_path.is_file():
        return _file_to_data_url(possible_path)
    raise InputImageError("reference image must be base64, data url, URL, or readable local file")


def _local_media_path(value: str) -> Path | None:
    parsed_path = value
    if value.startswith(("http://", "https://")):
        parsed_path = urlparse(value).path
    configured_base = settings.generated_media_public_base_url.rstrip("/") or "/generated"
    public_base = urlparse(configured_base).path.rstrip("/") if configured_base.startswith(("http://", "https://")) else configured_base
    public_base = public_base or "/generated"
    if not parsed_path.startswith(public_base + "/"):
        return None
    relative = parsed_path.removeprefix(public_base + "/")
    candidate = Path(settings.generated_media_dir).resolve().joinpath(relative).resolve()
    media_root = Path(settings.generated_media_dir).resolve()
    if candidate.is_file() and candidate.is_relative_to(media_root):
        return candidate
    return None


def _file_to_data_url(path: Path) -> str:
    try:
        data = path.read_bytes()
    except OSError as exc:
        raise InputImageError(f"could not read reference image: {path}") from exc
    mime, _ = mimetypes.guess_type(path.name)
    return _bytes_to_data_url(data, mime)


def _download_to_data_url(value: str, *, session: requests.Session | None = None) -> str:
    url = value
    if value.startswith("/"):
        url = f"{settings.backend_internal_base_url.rstrip('/')}{value}"
    http = session or requests.Session()
    content_type: str | None = None
    chunks: list[bytes] = []
    try:
        with http.get(url, stream=True, timeout=(10, 120)) as response:
            response.raise_for_status()
            content_type = response.headers.get("Content-Type")
            total = 0
            for chunk in response.iter_content(chunk_size=1024 * 256):
                if not chunk:
                    continue
                total += len(chunk)
                if total > _MAX_INPUT_IMAGE_BYTES:
                    raise InputImageError("reference image exceeds 20MB")
                chunks.append(chunk)
    except InputImageError:
        raise
    except requests.RequestException as exc:
        raise InputImageError(f"could not download reference image: {value}") from exc
    return _bytes_to_data_url(b"".join(chunks), content_type)


def _bytes_to_data_url(data: bytes, content_type: str | None = None) -> str:
    if not data:
        raise InputImageError("reference image is empty")
    if len(data) > _MAX_INPUT_IMAGE_BYTES:
        raise InputImageError("reference image exceeds 20MB")
    mime = _normalize_mime(content_type) or _detect_image_mime(data) or "image/png"
    encoded = base64.b64encode(data).decode("ascii")
    return f"data:{mime};base64,{encoded}"


def _normalize_mime(content_type: str | None) -> str | None:
    if not content_type:
        return None
    mime = content_type.split(";", 1)[0].strip().lower()
    if mime.startswith("image/"):
        return mime
    return None


def _detect_image_mime(data: bytes) -> str | None:
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data[:6] in {b"GIF87a", b"GIF89a"}:
        return "image/gif"
    if data.startswith(b"RIFF") and len(data) >= 12 and data[8:12] == b"WEBP":
        return "image/webp"
    return None


def _is_raw_base64(value: str) -> bool:
    compact = "".join(value.split())
    if len(compact) < 16 or compact.startswith(("http://", "https://", "/")):
        return False
    try:
        base64.b64decode(compact, validate=True)
        return True
    except Exception:
        return False


def _validate_base64(value: str) -> None:
    compact = "".join(value.split())
    try:
        base64.b64decode(compact, validate=True)
    except Exception as exc:
        raise InputImageError("reference image is not valid base64") from exc
