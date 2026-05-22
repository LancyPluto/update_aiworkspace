import base64
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import requests

from config import settings


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
        self.output_dir = Path(settings.generated_media_dir)
        self.public_base_url = settings.generated_media_public_base_url.rstrip("/")
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
        audio_data_url: str,
        subtitle_text: str,
    ) -> DigitalHumanPostprocessResult:
        ffmpeg_binary = self._resolve_ffmpeg_binary()

        task_dir = self.output_dir / "digital-human" / str(task_id)
        task_dir.mkdir(parents=True, exist_ok=True)
        source_video = task_dir / "source.mp4"
        audio_path = task_dir / "voice.mp3"
        subtitle_path = task_dir / "subtitle.srt"
        final_video = task_dir / "final.mp4"

        self._download(video_url, source_video)
        self._write_data_url(audio_data_url, audio_path)
        audio_duration = self._probe_duration(audio_path)
        source_duration = self._probe_duration(source_video)
        duration = audio_duration or source_duration or 5.0
        subtitle_path.write_text(self._build_srt(subtitle_text, duration), encoding="utf-8")
        self._run_ffmpeg(ffmpeg_binary, source_video, audio_path, subtitle_path, final_video, duration)

        return DigitalHumanPostprocessResult(
            video_path=final_video,
            video_url=self._public_url(final_video),
            subtitle_path=subtitle_path,
            subtitle_url=self._public_url(subtitle_path),
            audio_path=audio_path,
        )

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
        try:
            with requests.get(url, stream=True, timeout=self.timeout) as response:
                response.raise_for_status()
                with destination.open("wb") as file:
                    for chunk in response.iter_content(chunk_size=1024 * 512):
                        if chunk:
                            file.write(chunk)
        except requests.RequestException as exc:
            raise DigitalHumanPostprocessError(f"download generated video failed: {exc}") from exc

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

    def _public_url(self, final_video: Path) -> str:
        relative_path = final_video.relative_to(self.output_dir).as_posix()
        return f"{self.public_base_url}/{relative_path}"
