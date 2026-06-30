import base64
import mimetypes
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import requests

from storage.asset_storage import asset_storage
from utils.url_security import safe_get, UrlSecurityError


class GeneratedAudioPersistError(RuntimeError):
    pass


@dataclass(slots=True)
class PersistedAudio:
    url: str
    source_url: str
    path: Path
    content_type: str | None

    def to_result_item(self) -> dict[str, str]:
        item = {
            "url": self.url,
            "sourceUrl": self.source_url,
        }
        if self.content_type:
            item["contentType"] = self.content_type
        return item


class GeneratedAudioPersister:
    _CONTENT_TYPE_EXTENSIONS = {
        "audio/mpeg": ".mp3",
        "audio/mp3": ".mp3",
        "audio/wav": ".wav",
        "audio/x-wav": ".wav",
        "audio/flac": ".flac",
        "audio/x-flac": ".flac",
        "audio/ogg": ".ogg",
        "audio/aac": ".aac",
        "audio/webm": ".webm",
        "application/octet-stream": ".mp3",
    }

    _ALLOWED_EXTENSIONS = {".mp3", ".wav", ".flac", ".ogg", ".aac", ".m4a", ".webm", ".pcm"}

    def __init__(self) -> None:
        self.output_dir = asset_storage.local_root
        self.timeout = (10, 120)

    def persist_audio_bytes(
        self,
        *,
        task_id: int,
        audio_bytes: bytes,
        content_type: str | None = None,
        extension: str | None = None,
        source_url: str = "generated",
        index: int = 1,
    ) -> dict[str, str]:
        if not audio_bytes:
            raise GeneratedAudioPersistError("generated audio is empty")

        resolved_extension = self._normalize_extension(extension) or self._resolve_extension(source_url, content_type)
        safe_index = max(int(index or 1), 1)
        relative_key = f"audio/{task_id}/audio-{safe_index}{resolved_extension}"
        try:
            url = asset_storage.put_bytes_public(relative_key, audio_bytes, content_type)
        except Exception as exc:
            raise GeneratedAudioPersistError(f"write generated audio failed: {exc}") from exc
        path = asset_storage.local_path(relative_key)
        return PersistedAudio(
            url=url,
            source_url=source_url,
            path=path,
            content_type=content_type,
        ).to_result_item()

    def persist_audio_url(self, *, task_id: int, source_url: str, index: int = 1) -> dict[str, str]:
        audio_bytes, content_type = self._read_audio(source_url)
        return self.persist_audio_bytes(
            task_id=task_id,
            audio_bytes=audio_bytes,
            content_type=content_type,
            source_url=source_url,
            index=index,
        )

    def _read_audio(self, source_url: str) -> tuple[bytes, str | None]:
        if source_url.startswith("data:"):
            return self._read_data_url(source_url)
        return self._download(source_url)

    def _download(self, source_url: str) -> tuple[bytes, str | None]:
        try:
            with safe_get(source_url, stream=True, timeout=self.timeout) as response:
                response.raise_for_status()
                content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
                chunks: list[bytes] = []
                for chunk in response.iter_content(chunk_size=1024 * 512):
                    if chunk:
                        chunks.append(chunk)
        except UrlSecurityError as exc:
            raise GeneratedAudioPersistError(f"audio URL rejected for security: {exc}") from exc
        except requests.RequestException as exc:
            raise GeneratedAudioPersistError(f"download generated audio failed: {exc}") from exc
        return b"".join(chunks), content_type or None

    def _read_data_url(self, data_url: str) -> tuple[bytes, str | None]:
        header, separator, encoded = data_url.partition(",")
        if not separator or ";base64" not in header:
            raise GeneratedAudioPersistError("generated audio data url is not base64 encoded")
        content_type = header.removeprefix("data:").split(";", 1)[0].strip().lower() or None
        try:
            return base64.b64decode(encoded), content_type
        except ValueError as exc:
            raise GeneratedAudioPersistError("decode generated audio data url failed") from exc

    def _resolve_extension(self, source_url: str, content_type: str | None) -> str:
        if content_type:
            extension = self._CONTENT_TYPE_EXTENSIONS.get(content_type) or mimetypes.guess_extension(content_type)
            if extension:
                return self._normalize_extension(extension) or ".mp3"
        path_extension = Path(urlparse(source_url).path).suffix.lower()
        return self._normalize_extension(path_extension) or ".mp3"

    def _normalize_extension(self, extension: str | None) -> str | None:
        if not extension:
            return None
        value = extension.strip().lower()
        if not value:
            return None
        if not value.startswith("."):
            value = f".{value}"
        if value == ".mpeg":
            return ".mp3"
        if value in self._ALLOWED_EXTENSIONS:
            return value
        return None

