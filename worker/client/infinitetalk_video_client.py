import json
import time
from typing import Any
from urllib.parse import quote

import requests

from config import resolve_infinitetalk_api_key, settings


class InfiniteTalkVideoError(RuntimeError):
    pass


class InfiniteTalkVideoTimeoutError(InfiniteTalkVideoError):
    pass


class InfiniteTalkVideoClient:
    """Client for a deployed InfiniteTalk-compatible HTTP service.

    The preferred service contract is:
      POST /api/v1/generate -> {taskId,status} or {videoUrl,status}
      GET  /api/v1/tasks/{task_id} -> {status,videoUrl}

    A Gradio /run/predict style response is also tolerated when it returns a
    direct video URL or file path under "data".
    """

    def __init__(
        self,
        *,
        base_url: str | None = None,
        api_key: str | None = None,
        create_path: str | None = None,
        result_path: str | None = None,
        timeout_seconds: int | None = None,
    ) -> None:
        self.base_url = (base_url or settings.infinitetalk_base_url).rstrip("/")
        self.api_key = api_key if api_key is not None else resolve_infinitetalk_api_key()
        self.create_path = create_path or settings.infinitetalk_create_path
        self.result_path = result_path or settings.infinitetalk_result_path
        self.poll_interval_seconds = settings.infinitetalk_poll_interval_seconds
        self.timeout_seconds = timeout_seconds or settings.infinitetalk_timeout_seconds
        self.timeout = (10, 300)
        self.session = requests.Session()

    def generate_video(
        self,
        *,
        prompt: str,
        image: str,
        audio_data_url: str,
        source_video: str = "",
        negative_prompt: str = "",
        model: str | None = None,
        seed: int | None = None,
        duration: str = "",
        aspect_ratio: str = "",
        resolution: str = "480p",
        mode: str = "streaming",
    ) -> dict[str, Any]:
        if not self.base_url:
            raise InfiniteTalkVideoError("INFINITETALK_BASE_URL is not configured")
        payload = self._build_payload(
            prompt=prompt,
            image=image,
            audio_data_url=audio_data_url,
            source_video=source_video,
            negative_prompt=negative_prompt,
            model=model,
            seed=seed,
            duration=duration,
            aspect_ratio=aspect_ratio,
            resolution=resolution,
            mode=mode,
        )
        created = self._request("POST", self.create_path, payload)
        video_url = self._extract_video_url(created, required=False)
        if video_url:
            return self._result(created, video_url, seed, model, resolution)
        task_id = self._extract_task_id(created)
        finished = self.wait_for_video(task_id)
        return self._result(finished, self._extract_video_url(finished), seed, model, resolution, task_id=task_id)

    def wait_for_video(self, task_id: str) -> dict[str, Any]:
        deadline = time.monotonic() + self.timeout_seconds
        last_payload: dict[str, Any] = {}
        path = self.result_path.format(task_id=task_id)
        while time.monotonic() < deadline:
            last_payload = self._request("GET", path, None)
            status = self._extract_status(last_payload).lower()
            if status in {"succeeded", "succeed", "success", "completed", "done", "credited"}:
                return last_payload
            if status in {"failed", "fail", "error", "cancelled", "canceled", "closed"}:
                reason = str(last_payload.get("message") or last_payload.get("reason") or "InfiniteTalk generation failed")
                raise InfiniteTalkVideoError(reason)
            time.sleep(self.poll_interval_seconds)
        raise InfiniteTalkVideoTimeoutError(
            f"InfiniteTalk video generation timed out, taskId={task_id}, lastStatus={self._extract_status(last_payload)}"
        )

    def _request(self, method: str, path: str, payload: dict[str, Any] | None) -> dict[str, Any]:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) if payload is not None else ""
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        try:
            response = self.session.request(
                method,
                f"{self.base_url}{path}",
                data=body.encode("utf-8") if body else None,
                headers=headers,
                timeout=self.timeout,
            )
        except requests.Timeout as exc:
            raise InfiniteTalkVideoTimeoutError("InfiniteTalk request timed out") from exc
        except requests.RequestException as exc:
            raise InfiniteTalkVideoError(f"InfiniteTalk request failed: {exc}") from exc
        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise InfiniteTalkVideoError(
                f"InfiniteTalk request failed: status={response.status_code}, body={response.text}"
            ) from exc
        try:
            data = response.json()
        except ValueError as exc:
            raise InfiniteTalkVideoError("InfiniteTalk returned non-json response") from exc
        if not isinstance(data, dict):
            raise InfiniteTalkVideoError("InfiniteTalk returned invalid response")
        return data

    @staticmethod
    def _build_payload(
        *,
        prompt: str,
        image: str,
        audio_data_url: str,
        source_video: str,
        negative_prompt: str,
        model: str | None,
        seed: int | None,
        duration: str,
        aspect_ratio: str,
        resolution: str,
        mode: str,
    ) -> dict[str, Any]:
        normalized_aspect_ratio = str(aspect_ratio or "").strip().replace("：", ":")
        payload: dict[str, Any] = {
            "model": model or "MeiGen-AI/InfiniteTalk",
            "prompt": prompt,
            "negativePrompt": negative_prompt,
            "imageUrl": image,
            "audioDataUrl": audio_data_url,
            "sourceVideoUrl": source_video,
            "mode": mode or "streaming",
            "size": "infinitetalk-720" if "720" in str(resolution) else "infinitetalk-480",
            "resolution": resolution,
            "duration": duration,
            "sampleAudioGuideScale": 4,
            "sampleTextGuideScale": 5,
        }
        if normalized_aspect_ratio.lower() not in {"", "auto", "智能", "adaptive", "default"}:
            payload["aspectRatio"] = normalized_aspect_ratio
        if seed is not None:
            payload["seed"] = seed
        return payload

    def _normalize_video_url(self, value: str) -> str:
        raw = value.strip()
        if raw.startswith(("http://", "https://")):
            return raw
        if raw.startswith("/file="):
            return f"{self.base_url}{raw}"
        if raw.startswith("/"):
            return f"{self.base_url}/file={quote(raw)}"
        return f"{self.base_url}/file={quote(raw)}"

    def _extract_video_url(self, payload: dict[str, Any], required: bool = True) -> str:
        for value in self._walk(payload):
            if isinstance(value, str) and self._looks_like_video_reference(value):
                return self._normalize_video_url(value)
            if isinstance(value, dict):
                for key in ("videoUrl", "video_url", "url", "path", "output", "file"):
                    nested = value.get(key)
                    if isinstance(nested, str) and self._looks_like_video_reference(nested):
                        return self._normalize_video_url(nested)
        if required:
            raise InfiniteTalkVideoError("InfiniteTalk response missing video url")
        return ""

    @staticmethod
    def _extract_task_id(payload: dict[str, Any]) -> str:
        for key in ("taskId", "task_id", "id", "requestId", "request_id"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
            if isinstance(value, (int, float)):
                return str(int(value))
        data = payload.get("data")
        if isinstance(data, dict):
            return InfiniteTalkVideoClient._extract_task_id(data)
        raise InfiniteTalkVideoError("InfiniteTalk create response missing task id")

    @staticmethod
    def _extract_status(payload: dict[str, Any]) -> str:
        for key in ("status", "state", "taskStatus", "task_status"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        data = payload.get("data")
        if isinstance(data, dict):
            return InfiniteTalkVideoClient._extract_status(data)
        return "processing"

    @staticmethod
    def _looks_like_video_reference(value: str) -> bool:
        lowered = value.lower().split("?", 1)[0]
        return lowered.endswith((".mp4", ".mov", ".webm", ".mkv")) or "/file=" in lowered

    @classmethod
    def _walk(cls, value: Any) -> list[Any]:
        values = [value]
        if isinstance(value, dict):
            for nested in value.values():
                values.extend(cls._walk(nested))
        elif isinstance(value, list):
            for item in value:
                values.extend(cls._walk(item))
        return values

    @staticmethod
    def _result(
        payload: dict[str, Any],
        video_url: str,
        seed: int | None,
        model: str | None,
        resolution: str,
        *,
        task_id: str | None = None,
    ) -> dict[str, Any]:
        return {
            "requestId": task_id or str(payload.get("taskId") or payload.get("id") or payload.get("requestId") or "infinitetalk"),
            "status": InfiniteTalkVideoClient._extract_status(payload),
            "videoUrl": video_url,
            "reason": str(payload.get("reason") or payload.get("message") or ""),
            "seed": seed,
            "timings": payload.get("timings") if isinstance(payload.get("timings"), dict) else {},
            "provider": "infinitetalk",
            "model": model or "MeiGen-AI/InfiniteTalk",
            "resolution": resolution,
        }
