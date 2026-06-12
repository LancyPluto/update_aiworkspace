import base64
import mimetypes
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import requests

from storage.asset_storage import asset_storage


class GeneratedImagePersistError(RuntimeError):
    pass


@dataclass(slots=True)
class PersistedImage:
    url: str
    source_url: str
    path: Path

    def to_result_item(self) -> dict[str, str]:
        return {
            "url": self.url,
            "sourceUrl": _safe_source_url_for_result(self.source_url),
        }


class GeneratedImagePersister:
    _CONTENT_TYPE_EXTENSIONS = {
        "image/jpeg": ".jpg",
        "image/jpg": ".jpg",
        "image/png": ".png",
        "image/webp": ".webp",
        "image/gif": ".gif",
    }

    def __init__(self) -> None:
        self.output_dir = asset_storage.local_root
        self.timeout = (10, 120)

    def persist_images(self, *, task_id: int, urls: list[str]) -> list[dict[str, str]]:
        persisted: list[PersistedImage] = []
        for index, source_url in enumerate(urls, start=1):
            image_bytes, content_type = self._read_image(source_url)
            extension = self._resolve_extension(source_url, content_type)
            relative_key = f"images/{task_id}/image-{index}{extension}"
            try:
                url = asset_storage.put_bytes(relative_key, image_bytes, content_type)
            except Exception as exc:
                raise GeneratedImagePersistError(f"write generated image failed: {exc}") from exc
            path = asset_storage.local_path(relative_key)
            persisted.append(PersistedImage(url=url, source_url=source_url, path=path))

        return [item.to_result_item() for item in persisted]

    def _read_image(self, source_url: str) -> tuple[bytes, str | None]:
        if source_url.startswith("data:"):
            return self._read_data_url(source_url)
        return self._download(source_url)

    def _download(self, source_url: str) -> tuple[bytes, str | None]:
        try:
            with requests.get(source_url, stream=True, timeout=self.timeout) as response:
                response.raise_for_status()
                content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
                chunks: list[bytes] = []
                for chunk in response.iter_content(chunk_size=1024 * 512):
                    if chunk:
                        chunks.append(chunk)
        except requests.RequestException as exc:
            raise GeneratedImagePersistError(f"download generated image failed: {exc}") from exc
        return b"".join(chunks), content_type or None

    def _read_data_url(self, data_url: str) -> tuple[bytes, str | None]:
        header, separator, encoded = data_url.partition(",")
        if not separator or ";base64" not in header:
            raise GeneratedImagePersistError("generated image data url is not base64 encoded")
        content_type = header.removeprefix("data:").split(";", 1)[0].strip().lower() or None
        try:
            return base64.b64decode(encoded), content_type
        except ValueError as exc:
            raise GeneratedImagePersistError("decode generated image data url failed") from exc

    def _resolve_extension(self, source_url: str, content_type: str | None) -> str:
        if content_type:
            extension = self._CONTENT_TYPE_EXTENSIONS.get(content_type) or mimetypes.guess_extension(content_type)
            if extension:
                return ".jpg" if extension == ".jpe" else extension
        path_extension = Path(urlparse(source_url).path).suffix.lower()
        if path_extension in {".jpg", ".jpeg", ".png", ".webp", ".gif"}:
            return ".jpg" if path_extension == ".jpeg" else path_extension
        return ".png"

def _safe_source_url_for_result(source_url: str) -> str:
    if source_url.startswith("data:") and ";base64" in source_url[:128]:
        return "[inline-image-base64-omitted]"
    if len(source_url) > 2048:
        return source_url[:2032] + "...[truncated]"
    return source_url
