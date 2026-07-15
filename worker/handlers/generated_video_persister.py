import logging
import mimetypes
import os
import subprocess
import tempfile
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import requests

from storage.asset_storage import asset_storage
from observability.metrics import record_media_derivative, record_media_persist, record_video_preview
from utils.url_security import safe_get, UrlSecurityError

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
    _PREVIEW_THRESHOLD_BYTES = 5 * 1024 * 1024
    _PREVIEW_MAX_DURATION = 10
    _PREVIEW_TARGET_WIDTH = 480
    _PREVIEW_CRF = 28
    _PREVIEW_SLOTS = threading.BoundedSemaphore(value=2)
    _MAX_DOWNLOAD_BYTES = 50 * 1024 * 1024
    _MAX_DOWNLOAD_ATTEMPTS = 3
    _RETRY_BASE_DELAY_SECONDS = 0.5

    def __init__(self) -> None:
        self.output_dir = asset_storage.local_root
        self.timeout = (10, 300)
        self.session = requests.Session()
        self.session.trust_env = False

    def persist_video_url(self, *, task_id: int, source_url: str, index: int = 1) -> dict[str, str]:
        video_bytes, content_type = self._download(source_url)
        extension = self._resolve_extension(source_url, content_type)
        relative_key = f"video/{task_id}/video-{max(1, index)}{extension}"
        try:
            url = asset_storage.put_bytes_public(relative_key, video_bytes, content_type)
        except Exception as exc:
            record_media_persist("video", "failed", len(video_bytes))
            raise GeneratedVideoPersistError(f"write generated video failed: {exc}") from exc
        record_media_persist("video", "success", len(video_bytes))
        path = asset_storage.local_path(relative_key)
        result = PersistedVideo(
            url=url,
            source_url=source_url,
            path=path,
            content_type=content_type,
        ).to_result_item()
        self._schedule_preview(
            original_url=url,
            original_bytes=video_bytes,
        )
        return result

    def _schedule_preview(self, *, original_url: str, original_bytes: bytes) -> None:
        if str(os.getenv("MEDIA_VIDEO_PREVIEW_ENABLED", "true")).strip().lower() in {"0", "false", "no", "off"}:
            record_video_preview("skipped", "disabled")
            return
        if len(original_bytes) <= self._PREVIEW_THRESHOLD_BYTES:
            record_video_preview("skipped", "below_threshold")
            return
        if not self._PREVIEW_SLOTS.acquire(blocking=False):
            record_video_preview("skipped", "capacity")
            return
        threading.Thread(
            target=self._generate_preview_in_background,
            kwargs={"original_url": original_url, "original_bytes": original_bytes},
            name="media-preview",
            daemon=True,
        ).start()

    def _generate_preview_in_background(self, *, original_url: str, original_bytes: bytes) -> None:
        try:
            self._maybe_generate_preview(original_url=original_url, original_bytes=original_bytes)
        finally:
            self._PREVIEW_SLOTS.release()

    def _maybe_generate_preview(self, *, original_url: str, original_bytes: bytes) -> None:
        started = time.perf_counter()
        try:
            preview_bytes = self._compress_to_480p(original_bytes)
            if not preview_bytes:
                record_video_preview("failed", "empty_output")
                record_media_derivative("video", "failed", time.perf_counter() - started, len(original_bytes), 0)
                return
            preview_key = self._derive_preview_key(original_url)
            if not preview_key:
                record_video_preview("failed", "invalid_key")
                return
            asset_storage.put_bytes_public_raw(preview_key, preview_bytes, "video/mp4")
            record_video_preview("success")
            record_media_derivative(
                "video", "success", time.perf_counter() - started, len(original_bytes), len(preview_bytes)
            )
            logger.info(
                "Generated 480p preview: original_size=%d, preview_size=%d",
                len(original_bytes), len(preview_bytes),
            )
        except Exception as exc:
            record_video_preview("failed", exc.__class__.__name__)
            record_media_derivative("video", "failed", time.perf_counter() - started, len(original_bytes), 0)
            logger.warning("Failed to generate 480p video preview: %s", exc)

    @staticmethod
    def _derive_preview_key(original_url: str) -> str:
        parsed = urlparse(original_url)
        url_path = parsed.path.lstrip("/")
        prefix = asset_storage.oss_key_prefix
        if prefix and url_path.startswith(prefix):
            url_path = url_path[len(prefix):]
        dot_index = url_path.rfind(".")
        slash_index = url_path.rfind("/")
        if dot_index > slash_index:
            return url_path[:dot_index] + ".preview-480p.mp4"
        return url_path + ".preview-480p.mp4"

    def _compress_to_480p(self, video_bytes: bytes) -> bytes | None:
        try:
            from imageio_ffmpeg import get_ffmpeg_exe
        except ImportError:
            logger.warning("imageio-ffmpeg not installed, skipping preview generation")
            return None
        ffmpeg_exe = get_ffmpeg_exe()
        with tempfile.TemporaryDirectory() as tmpdir:
            input_path = os.path.join(tmpdir, "input.mp4")
            output_path = os.path.join(tmpdir, "preview-480p.mp4")
            with open(input_path, "wb") as f:
                f.write(video_bytes)
            cmd = [
                ffmpeg_exe,
                "-i", input_path,
                "-vf", f"scale='if(gt(iw,{self._PREVIEW_TARGET_WIDTH}),{self._PREVIEW_TARGET_WIDTH},iw)':-2",
                "-c:v", "libx264",
                "-crf", str(self._PREVIEW_CRF),
                "-preset", "fast",
                "-t", str(self._PREVIEW_MAX_DURATION),
                "-an",
                "-movflags", "+faststart",
                "-y",
                output_path,
            ]
            result = subprocess.run(cmd, capture_output=True, timeout=120)
            if result.returncode != 0:
                logger.warning(
                    "ffmpeg preview compression failed: %s",
                    result.stderr.decode(errors="replace")[-500:],
                )
                return None
            with open(output_path, "rb") as f:
                return f.read()

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
        for attempt in range(1, self._MAX_DOWNLOAD_ATTEMPTS + 1):
            try:
                with safe_get(
                    source_url,
                    session=self.session,
                    stream=True,
                    timeout=self.timeout,
                ) as response:
                    response.raise_for_status()
                    content_length = self._content_length(response.headers.get("Content-Length"))
                    if content_length is not None and content_length > self._MAX_DOWNLOAD_BYTES:
                        raise GeneratedVideoPersistError("generated video exceeds 50MB download limit")
                    content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
                    chunks: list[bytes] = []
                    total_bytes = 0
                    for chunk in response.iter_content(chunk_size=1024 * 1024):
                        if not chunk:
                            continue
                        total_bytes += len(chunk)
                        if total_bytes > self._MAX_DOWNLOAD_BYTES:
                            raise GeneratedVideoPersistError("generated video exceeds 50MB download limit")
                        chunks.append(chunk)
                return b"".join(chunks), content_type or None
            except UrlSecurityError as exc:
                raise GeneratedVideoPersistError(f"video URL rejected for security: {exc}") from exc
            except GeneratedVideoPersistError:
                raise
            except (requests.ConnectionError, requests.Timeout, requests.exceptions.ChunkedEncodingError) as exc:
                if attempt >= self._MAX_DOWNLOAD_ATTEMPTS:
                    raise GeneratedVideoPersistError(f"download generated video failed: {exc}") from exc
                delay = self._RETRY_BASE_DELAY_SECONDS * attempt
                logger.warning(
                    "Generated video download interrupted, retrying attempt=%s/%s delay=%.1fs: %s",
                    attempt,
                    self._MAX_DOWNLOAD_ATTEMPTS,
                    delay,
                    exc,
                )
                time.sleep(delay)
            except requests.RequestException as exc:
                raise GeneratedVideoPersistError(f"download generated video failed: {exc}") from exc
        raise GeneratedVideoPersistError("download generated video failed")

    @staticmethod
    def _content_length(value: str | None) -> int | None:
        if not value:
            return None
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            return None
        return parsed if parsed >= 0 else None

    def _resolve_extension(self, source_url: str, content_type: str | None) -> str:
        if content_type:
            extension = self._CONTENT_TYPE_EXTENSIONS.get(content_type) or mimetypes.guess_extension(content_type)
            if extension and extension.lower() in self._ALLOWED_EXTENSIONS:
                return extension.lower()
        path_extension = Path(urlparse(source_url).path).suffix.lower()
        if path_extension in self._ALLOWED_EXTENSIONS:
            return path_extension
        return ".mp4"

