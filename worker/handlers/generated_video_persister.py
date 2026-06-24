import logging
import mimetypes
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import requests

from storage.asset_storage import asset_storage

logger = logging.getLogger(__name__)


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
        self.output_dir = asset_storage.local_root
        self.timeout = (10, 300)

    def persist_video_url(self, *, task_id: int, source_url: str, index: int = 1) -> dict[str, str]:
        video_bytes, content_type = self._download(source_url)
        extension = self._resolve_extension(source_url, content_type)
        video_bytes = self._compress_video(video_bytes, extension)
        relative_key = f"video/{task_id}/video-{max(1, index)}{extension}"
        try:
            url = asset_storage.put_bytes_public(relative_key, video_bytes, content_type)
        except Exception as exc:
            raise GeneratedVideoPersistError(f"write generated video failed: {exc}") from exc
        path = asset_storage.local_path(relative_key)
        return PersistedVideo(
            url=url,
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
            relative_key = path.relative_to(self.output_dir).as_posix()
            public_url = asset_storage.public_url(relative_key)
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

    def _resolve_ffmpeg_binary(self) -> str | None:
        if shutil.which("ffmpeg"):
            return "ffmpeg"
        try:
            import imageio_ffmpeg
            return imageio_ffmpeg.get_ffmpeg_exe()
        except Exception:
            return None

    def _compress_video(self, video_bytes: bytes, extension: str) -> bytes:
        ffmpeg = self._resolve_ffmpeg_binary()
        if not ffmpeg:
            logger.warning("ffmpeg not found, skipping video compression")
            return video_bytes

        with tempfile.TemporaryDirectory() as tmp_dir:
            input_path = Path(tmp_dir) / f"input{extension}"
            output_path = Path(tmp_dir) / f"output{extension}"
            input_path.write_bytes(video_bytes)

            cmd = [
                ffmpeg, "-y", "-i", str(input_path),
                "-c:v", "libx264", "-crf", "28",
                "-preset", "medium",
                "-c:a", "aac", "-b:a", "128k",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                "-loglevel", "warning",
                str(output_path),
            ]
            try:
                completed = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
                if completed.returncode != 0:
                    logger.warning("ffmpeg compression failed: %s", completed.stderr.strip())
                    return video_bytes
            except (subprocess.TimeoutExpired, OSError) as exc:
                logger.warning("ffmpeg compression error: %s", exc)
                return video_bytes

            compressed = output_path.read_bytes()
            original_size = len(video_bytes)
            compressed_size = len(compressed)
            if compressed_size < original_size:
                logger.info(
                    "video compressed: %d -> %d bytes (%.0f%% reduction)",
                    original_size, compressed_size,
                    (1 - compressed_size / original_size) * 100,
                )
                return compressed
            logger.info("compression did not reduce size, using original")
            return video_bytes

    def _resolve_extension(self, source_url: str, content_type: str | None) -> str:
        if content_type:
            extension = self._CONTENT_TYPE_EXTENSIONS.get(content_type) or mimetypes.guess_extension(content_type)
            if extension and extension.lower() in self._ALLOWED_EXTENSIONS:
                return extension.lower()
        path_extension = Path(urlparse(source_url).path).suffix.lower()
        if path_extension in self._ALLOWED_EXTENSIONS:
            return path_extension
        return ".mp4"

