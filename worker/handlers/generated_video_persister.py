import mimetypes
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import requests

from config import settings


class GeneratedVideoPersistError(RuntimeError):
    pass


@dataclass(slots=True)
class PersistedVideo:
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


class GeneratedVideoPersister:
    _CONTENT_TYPE_EXTENSIONS = {
        "video/mp4": ".mp4",
        "video/quicktime": ".mov",
        "video/webm": ".webm",
        "application/octet-stream": ".mp4",
    }

    _ALLOWED_EXTENSIONS = {".mp4", ".mov", ".webm", ".m4v"}

    def __init__(self) -> None:
        self.output_dir = Path(settings.generated_media_dir)
        self.public_base_url = settings.generated_media_public_base_url.rstrip("/")
        self.timeout = (10, 300)

    def persist_video_url(self, *, task_id: int, source_url: str) -> dict[str, str]:
        video_bytes, content_type = self._download(source_url)
        task_dir = self.output_dir / "video" / str(task_id)
        task_dir.mkdir(parents=True, exist_ok=True)
        extension = self._resolve_extension(source_url, content_type)
        path = task_dir / f"video-1{extension}"
        try:
            path.write_bytes(video_bytes)
        except OSError as exc:
            raise GeneratedVideoPersistError(f"write generated video failed: {exc}") from exc
        return PersistedVideo(
            url=self._public_url(path),
            source_url=source_url,
            path=path,
            content_type=content_type,
        ).to_result_item()

    def find_existing_task_video(self, *, task_id: int) -> dict[str, str] | None:
        task_dir = self.output_dir / "video" / str(task_id)
        if not task_dir.exists():
            return None
        for path in sorted(task_dir.glob("video-1.*")):
            if not path.is_file():
                continue
            content_type = mimetypes.guess_type(path.name)[0]
            public_url = self._public_url(path)
            return PersistedVideo(
                url=public_url,
                source_url=public_url,
                path=path,
                content_type=content_type,
            ).to_result_item()
        return None

    def _download(self, source_url: str) -> tuple[bytes, str | None]:
        try:
            with requests.get(source_url, stream=True, timeout=self.timeout) as response:
                response.raise_for_status()
                content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
                chunks: list[bytes] = []
                for chunk in response.iter_content(chunk_size=1024 * 1024):
                    if chunk:
                        chunks.append(chunk)
        except requests.RequestException as exc:
            raise GeneratedVideoPersistError(f"download generated video failed: {exc}") from exc
        return b"".join(chunks), content_type or None

    def _resolve_extension(self, source_url: str, content_type: str | None) -> str:
        if content_type:
            extension = self._CONTENT_TYPE_EXTENSIONS.get(content_type) or mimetypes.guess_extension(content_type)
            if extension and extension.lower() in self._ALLOWED_EXTENSIONS:
                return extension.lower()
        path_extension = Path(urlparse(source_url).path).suffix.lower()
        if path_extension in self._ALLOWED_EXTENSIONS:
            return path_extension
        return ".mp4"

    def _public_url(self, video_path: Path) -> str:
        relative_path = video_path.relative_to(self.output_dir).as_posix()
        return f"{self.public_base_url}/{relative_path}"
