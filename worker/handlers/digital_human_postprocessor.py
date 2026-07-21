import base64
import mimetypes
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import requests

from config import settings
from storage.asset_storage import asset_storage
from utils.url_security import safe_get, UrlSecurityError


class DigitalHumanPostprocessError(RuntimeError):
    pass


@dataclass(slots=True)
class DigitalHumanPostprocessResult:
    video_path: Path
    video_url: str
    subtitle_path: Path
    subtitle_url: str
    audio_path: Path


class DigitalHumanPostprocessor:
    def __init__(self) -> None:
        self.output_dir = asset_storage.local_root
        self.ffmpeg_binary = settings.ffmpeg_binary
        self.ffprobe_binary = settings.ffprobe_binary
        self.subtitle_font_name = settings.subtitle_font_name
        self.subtitle_fonts_dir = settings.subtitle_fonts_dir
        self.timeout = (10, 120)

    def process(
        self,
        *,
        task_id: int,
        video_url: str,
        audio_data_url: str = "",
        audio_url: str = "",
        subtitle_text: str,
        segment: str | None = None,
    ) -> DigitalHumanPostprocessResult:
        ffmpeg_binary = self._resolve_ffmpeg_binary()

        task_dir = self.output_dir / "digital-human" / str(task_id)
        if segment:
            task_dir = task_dir / segment
        task_dir.mkdir(parents=True, exist_ok=True)
        source_video = task_dir / "source.mp4"
        audio_path = task_dir / "voice.mp3"
        subtitle_path = task_dir / "subtitle.srt"
        final_video = task_dir / "final.mp4"

        self._download(video_url, source_video)
        self._validate_video_file(source_video)
        has_audio = False
        if audio_data_url:
            self._write_data_url(audio_data_url, audio_path)
            has_audio = True
        elif audio_url:
            self._download(audio_url, audio_path)
            has_audio = True

        if has_audio:
            audio_duration = self._probe_duration(audio_path)
            source_duration = self._probe_duration(source_video)
            duration = audio_duration or source_duration or 5.0
        else:
            duration = self._probe_duration(source_video) or 5.0

        subtitle_path.write_text(self._build_srt(subtitle_text, duration), encoding="utf-8")
        if has_audio:
            self._run_ffmpeg(ffmpeg_binary, source_video, audio_path, subtitle_path, final_video, duration)
        else:
            self._run_ffmpeg_video_only(ffmpeg_binary, source_video, subtitle_path, final_video, duration)
        self._validate_video_file(final_video)

        return DigitalHumanPostprocessResult(
            video_path=final_video,
            video_url=self._publish_asset(final_video),
            subtitle_path=subtitle_path,
            subtitle_url=self._publish_asset(subtitle_path),
            audio_path=audio_path,
        )

    def concat_videos(
        self,
        *,
        task_id: int,
        video_paths: list[Path],
        output_name: str = "final-combined.mp4",
    ) -> tuple[Path, str]:
        """按顺序拼接多个分镜成片（同一管线产出，编码参数一致），返回 (本地路径, 发布 URL)。"""
        if not video_paths:
            raise DigitalHumanPostprocessError("no video segments to concat")
        if len(video_paths) == 1:
            return video_paths[0], self._publish_asset(video_paths[0])
        ffmpeg_binary = self._resolve_ffmpeg_binary()
        task_dir = self.output_dir / "digital-human" / str(task_id)
        task_dir.mkdir(parents=True, exist_ok=True)
        list_file = task_dir / "concat-list.txt"
        list_file.write_text(
            "\n".join(f"file '{path.as_posix()}'" for path in video_paths),
            encoding="utf-8",
        )
        output = task_dir / output_name
        command = [
            ffmpeg_binary,
            "-y",
            "-f",
            "concat",
            "-safe",
            "0",
            "-i",
            str(list_file),
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "192k",
            "-movflags",
            "+faststart",
            str(output),
        ]
        completed = subprocess.run(command, capture_output=True, text=True, timeout=1800)
        if completed.returncode != 0:
            raise DigitalHumanPostprocessError(
                f"ffmpeg concat failed: {completed.stderr.strip() or completed.stdout.strip()}"
            )
        self._validate_video_file(output)
        return output, self._publish_asset(output)

    def concat_subtitles(
        self,
        *,
        task_id: int,
        video_paths: list[Path],
        subtitle_texts: list[str],
        output_name: str = "final-combined.srt",
    ) -> tuple[Path, str]:
        if not video_paths or len(video_paths) != len(subtitle_texts):
            raise DigitalHumanPostprocessError("subtitle timeline does not match video segments")
        timeline = [
            (text, self._probe_duration(video_path) or 5.0)
            for video_path, text in zip(video_paths, subtitle_texts, strict=True)
        ]
        task_dir = self.output_dir / "digital-human" / str(task_id)
        task_dir.mkdir(parents=True, exist_ok=True)
        output = task_dir / output_name
        output.write_text(self._build_timeline_srt(timeline), encoding="utf-8")
        return output, self._publish_asset(output)

    def _run_ffmpeg(
        self,
        ffmpeg_binary: str,
        source_video: Path,
        audio_path: Path,
        subtitle_path: Path,
        final_video: Path,
        duration_seconds: float,
    ) -> None:
        subtitle_filter = (
            f"subtitles='{self._escape_filter_path(subtitle_path)}':"
            f"fontsdir='{self._escape_filter_path(Path(self.subtitle_fonts_dir))}':"
            f"force_style='FontName={self.subtitle_font_name},FontSize=20,"
            "PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,"
            "BorderStyle=1,Outline=2,Shadow=1,Alignment=2,MarginV=32'"
        )
        audio_filter = f"atrim=0:{max(duration_seconds, 1.0):.3f},asetpts=PTS-STARTPTS"
        command = [
            ffmpeg_binary,
            "-y",
            "-stream_loop",
            "-1",
            "-i",
            str(source_video),
            "-i",
            str(audio_path),
            "-vf",
            subtitle_filter,
            "-af",
            audio_filter,
            "-t",
            f"{max(duration_seconds, 1.0):.3f}",
            "-shortest",
            "-map",
            "0:v:0",
            "-map",
            "1:a:0",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "192k",
            "-movflags",
            "+faststart",
            str(final_video),
        ]
        completed = subprocess.run(command, capture_output=True, text=True, timeout=900)
        if completed.returncode != 0:
            raise DigitalHumanPostprocessError(
                f"ffmpeg failed: {completed.stderr.strip() or completed.stdout.strip()}"
            )

    def _run_ffmpeg_video_only(
        self,
        ffmpeg_binary: str,
        source_video: Path,
        subtitle_path: Path,
        final_video: Path,
        duration_seconds: float,
    ) -> None:
        subtitle_filter = (
            f"subtitles='{self._escape_filter_path(subtitle_path)}':"
            f"fontsdir='{self._escape_filter_path(Path(self.subtitle_fonts_dir))}':"
            f"force_style='FontName={self.subtitle_font_name},FontSize=20,"
            "PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,"
            "BorderStyle=1,Outline=2,Shadow=1,Alignment=2,MarginV=32'"
        )
        command = [
            ffmpeg_binary,
            "-y",
            "-i",
            str(source_video),
            "-vf",
            subtitle_filter,
            "-t",
            f"{max(duration_seconds, 1.0):.3f}",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-an",
            "-movflags",
            "+faststart",
            str(final_video),
        ]
        completed = subprocess.run(command, capture_output=True, text=True, timeout=900)
        if completed.returncode != 0:
            raise DigitalHumanPostprocessError(
                f"ffmpeg video-only failed: {completed.stderr.strip() or completed.stdout.strip()}"
            )

    def _probe_duration(self, path: Path) -> float | None:
        if shutil.which(self.ffprobe_binary) is None:
            return self._probe_duration_with_ffmpeg(path)
        command = [
            self.ffprobe_binary,
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(path),
        ]
        completed = subprocess.run(command, capture_output=True, text=True, timeout=30)
        if completed.returncode != 0:
            return None
        try:
            duration = float(completed.stdout.strip())
        except ValueError:
            return None
        return duration if duration > 0 else None

    def _probe_duration_with_ffmpeg(self, path: Path) -> float | None:
        try:
            ffmpeg_binary = self._resolve_ffmpeg_binary()
        except DigitalHumanPostprocessError:
            return None
        completed = subprocess.run(
            [ffmpeg_binary, "-hide_banner", "-i", str(path)],
            capture_output=True,
            text=True,
            timeout=30,
        )
        output = f"{completed.stderr}\n{completed.stdout}"
        match = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", output)
        if not match:
            return None
        hours = int(match.group(1))
        minutes = int(match.group(2))
        seconds = float(match.group(3))
        return hours * 3600 + minutes * 60 + seconds

    def _resolve_ffmpeg_binary(self) -> str:
        if shutil.which(self.ffmpeg_binary):
            return self.ffmpeg_binary
        try:
            import imageio_ffmpeg
        except ImportError as exc:
            raise DigitalHumanPostprocessError(f"ffmpeg executable not found: {self.ffmpeg_binary}") from exc
        return imageio_ffmpeg.get_ffmpeg_exe()

    def _download(self, url: str, destination: Path) -> None:
        local = self._resolve_local_path(url)
        if local is not None:
            shutil.copy2(local, destination)
            return
        fetch_url = url
        if url.startswith("/"):
            backend = (settings.backend_internal_base_url or "").rstrip("/")
            if not backend:
                raise DigitalHumanPostprocessError(
                    "relative media URL requires BACKEND_INTERNAL_BASE_URL"
                )
            fetch_url = f"{backend}{url}"
        try:
            with safe_get(fetch_url, stream=True, timeout=self.timeout) as response:
                response.raise_for_status()
                with destination.open("wb") as file:
                    for chunk in response.iter_content(chunk_size=1024 * 512):
                        if chunk:
                            file.write(chunk)
        except UrlSecurityError as exc:
            raise DigitalHumanPostprocessError(f"media URL rejected for security: {exc}") from exc
        except requests.RequestException as exc:
            raise DigitalHumanPostprocessError(f"download media failed: {exc}") from exc

    @staticmethod
    def _resolve_local_path(url: str) -> Path | None:
        parsed_path = url
        if url.startswith(("http://", "https://")):
            parsed_path = urlparse(url).path
        public_base = (settings.generated_media_public_base_url or "/generated").rstrip("/")
        if public_base.startswith(("http://", "https://")):
            public_base = urlparse(public_base).path.rstrip("/")
        public_base = public_base or "/generated"
        if not parsed_path.startswith(public_base + "/"):
            return None
        relative = parsed_path.removeprefix(public_base + "/")
        media_root = Path(settings.generated_media_dir).resolve()
        candidate = media_root.joinpath(relative).resolve()
        if candidate.is_file() and candidate.is_relative_to(media_root):
            return candidate
        return None

    @staticmethod
    def _validate_video_file(path: Path) -> None:
        with path.open("rb") as source:
            header = source.read(12)
        if len(header) < 8 or header[4:8] != b"ftyp":
            raise DigitalHumanPostprocessError("downloaded file is not a valid mp4 video")

    @staticmethod
    def _write_data_url(data_url: str, destination: Path) -> None:
        prefix = "base64,"
        if prefix not in data_url:
            raise DigitalHumanPostprocessError("audio data url is not base64 encoded")
        try:
            encoded = data_url.split(prefix, 1)[1]
            destination.write_bytes(base64.b64decode(encoded))
        except (ValueError, OSError) as exc:
            raise DigitalHumanPostprocessError("write generated audio failed") from exc

    @staticmethod
    def _build_srt(text: str, duration_seconds: float) -> str:
        clean_text = re.sub(r"\s+", " ", text).strip() or " "
        chunks = DigitalHumanPostprocessor._chunk_text(clean_text)
        weights = [max(len(re.sub(r"\s+", "", chunk)), 1) for chunk in chunks]
        total_weight = max(sum(weights), 1)
        lines: list[str] = []
        cursor = 0.0
        for index, chunk in enumerate(chunks, start=1):
            start = cursor
            chunk_duration = duration_seconds * weights[index - 1] / total_weight
            end = min(duration_seconds, start + max(chunk_duration, 0.35))
            cursor = end
            if end <= start:
                end = min(duration_seconds, start + 0.35)
            lines.extend(
                [
                    str(index),
                    f"{DigitalHumanPostprocessor._format_srt_time(start)} --> "
                    f"{DigitalHumanPostprocessor._format_srt_time(end)}",
                    chunk,
                    "",
                ]
            )
        return "\n".join(lines)

    @staticmethod
    def _build_timeline_srt(segments: list[tuple[str, float]]) -> str:
        lines: list[str] = []
        timeline_offset = 0.0
        cue_index = 1
        for text, raw_duration in segments:
            duration = max(float(raw_duration), 0.0)
            clean_text = re.sub(r"\s+", " ", text or "").strip()
            if clean_text:
                chunks = DigitalHumanPostprocessor._chunk_text(clean_text)
                weights = [max(len(re.sub(r"\s+", "", chunk)), 1) for chunk in chunks]
                total_weight = max(sum(weights), 1)
                local_cursor = 0.0
                for chunk, weight in zip(chunks, weights, strict=True):
                    start = local_cursor
                    chunk_duration = duration * weight / total_weight
                    end = min(duration, start + max(chunk_duration, 0.35))
                    if end <= start:
                        end = min(duration, start + 0.35)
                    local_cursor = end
                    lines.extend(
                        [
                            str(cue_index),
                            f"{DigitalHumanPostprocessor._format_srt_time(timeline_offset + start)} --> "
                            f"{DigitalHumanPostprocessor._format_srt_time(timeline_offset + end)}",
                            chunk,
                            "",
                        ]
                    )
                    cue_index += 1
            timeline_offset += duration
        return "\n".join(lines)

    @staticmethod
    def _chunk_text(text: str, max_chars: int = 28) -> list[str]:
        if len(text) <= max_chars:
            return [text]
        chunks: list[str] = []
        current = ""
        for char in text:
            current += char
            if len(current) >= max_chars or char in "。！？!?；;":
                chunks.append(current.strip())
                current = ""
        if current.strip():
            chunks.append(current.strip())
        return chunks

    @staticmethod
    def _format_srt_time(seconds: float) -> str:
        milliseconds = max(int(seconds * 1000), 0)
        hours, remainder = divmod(milliseconds, 3_600_000)
        minutes, remainder = divmod(remainder, 60_000)
        secs, millis = divmod(remainder, 1000)
        return f"{hours:02}:{minutes:02}:{secs:02},{millis:03}"

    @staticmethod
    def _escape_filter_path(path: Path) -> str:
        return path.as_posix().replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")

    def _publish_asset(self, file_path: Path) -> str:
        relative_key = file_path.relative_to(self.output_dir).as_posix()
        if asset_storage.is_oss:
            content_type = mimetypes.guess_type(file_path.name)[0]
            return asset_storage.put_file(relative_key, file_path, content_type)
        return asset_storage.public_url(relative_key)
