import json
import time
from typing import Any

import requests

from config import settings


class SeedanceVideoError(RuntimeError):
    pass


class SeedanceVideoTimeoutError(SeedanceVideoError):
    pass


class SeedanceVideoClient:
    def __init__(self) -> None:
        self.base_url = settings.seedance_base_url.rstrip("/")
        self.api_key = settings.seedance_api_key
        self.default_model = settings.seedance_video_model
        self.create_path = settings.seedance_video_create_path
        self.poll_interval_seconds = settings.seedance_video_poll_interval_seconds
        self.timeout_seconds = settings.seedance_video_timeout_seconds
        self.timeout = (10, 300)
        self.session = requests.Session()

    def generate_video(
        self,
        *,
        prompt: str,
        image_size: str,
        negative_prompt: str = "",
        model: str | None = None,
        image: str = "",
        audio_data_url: str = "",
        seed: int | None = None,
        duration: str = "",
        aspect_ratio: str = "",
        resolution: str = "480p",
    ) -> dict[str, Any]:
        if not self._has_auth():
            raise SeedanceVideoError("SEEDANCE_API_KEY is not configured")

        payload = self._build_payload(
            prompt=prompt,
            image_size=image_size,
            negative_prompt=negative_prompt,
            model=model or self.default_model,
            image=image,
            audio_data_url=audio_data_url,
            seed=seed,
            duration=duration,
            aspect_ratio=aspect_ratio,
            resolution=resolution,
        )
        created = self._request("POST", self.create_path, payload)
        task_id = self._extract_task_id(created)
        finished = self.wait_for_video(task_id)
        return {
            "requestId": task_id,
            "status": self._extract_status(finished),
            "videoUrl": self._extract_video_url(finished),
            "reason": str(finished.get("reason") or finished.get("message") or ""),
            "seed": seed,
            "timings": {},
            "provider": "seedance",
            "model": model or self.default_model,
            "resolution": resolution,
        }

    def wait_for_video(self, task_id: str) -> dict[str, Any]:
        deadline = time.monotonic() + self.timeout_seconds
        last_payload: dict[str, Any] = {}
        path = f"{self.create_path.rstrip('/')}/{task_id}"
        while time.monotonic() < deadline:
            last_payload = self._request("GET", path, None)
            status = self._extract_status(last_payload).lower()
            if status in {"succeeded", "succeed", "success", "completed", "done"}:
                return last_payload
            if status in {"failed", "fail", "error", "cancelled", "canceled"}:
                reason = str(last_payload.get("message") or last_payload.get("reason") or "seedance video generation failed")
                raise SeedanceVideoError(reason)
            time.sleep(self.poll_interval_seconds)

        raise SeedanceVideoTimeoutError(
            f"seedance video generation timed out, taskId={task_id}, lastStatus={self._extract_status(last_payload)}"
        )

    def _build_payload(
        self,
        *,
        prompt: str,
        image_size: str,
        negative_prompt: str,
        model: str,
        image: str,
        audio_data_url: str,
        seed: int | None,
        duration: str,
        aspect_ratio: str,
        resolution: str,
    ) -> dict[str, Any]:
        text = prompt.strip()
        if negative_prompt.strip():
            text = f"{text}\nNegative prompt: {negative_prompt.strip()}"
        content: list[dict[str, Any]] = [{"type": "text", "text": text}]
        if image.strip():
            content.append({"type": "image_url", "image_url": {"url": image.strip()}})
        audio_payload = self._audio_payload(audio_data_url)
        if audio_payload:
            content.append(audio_payload)

        payload: dict[str, Any] = {
            "model": model,
            "content": content,
            "resolution": resolution,
        }
        if str(image_size or "").strip().lower() != "auto":
            payload["size"] = image_size
            payload["image_size"] = image_size
        duration_seconds = self._duration_seconds(duration)
        if duration_seconds is not None:
            payload["duration"] = duration_seconds
            payload["duration_seconds"] = duration_seconds
        ratio = self._aspect_ratio(aspect_ratio, image_size)
        if ratio:
            payload["ratio"] = ratio
            payload["aspect_ratio"] = ratio
        if seed is not None:
            payload["seed"] = seed
        return payload

    @staticmethod
    def _audio_payload(audio_data_url: str) -> dict[str, Any] | None:
        raw = (audio_data_url or "").strip()
        if not raw:
            return None
        marker = "base64,"
        if marker in raw:
            media_type = raw.split(";", 1)[0].replace("data:", "") or "audio/mpeg"
            audio_format = "mp3"
            if "/" in media_type:
                audio_format = media_type.rsplit("/", 1)[-1].replace("mpeg", "mp3")
            return {
                "type": "input_audio",
                "input_audio": {
                    "data": raw.split(marker, 1)[1],
                    "format": audio_format,
                },
            }
        return {"type": "audio_url", "audio_url": {"url": raw}}

    def _request(self, method: str, path: str, payload: dict[str, Any] | None) -> dict[str, Any]:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) if payload is not None else ""
        url = f"{self.base_url}{path}"
        headers = self._headers(method, path, body)
        try:
            response = self.session.request(method, url, data=body.encode("utf-8") if body else None, headers=headers, timeout=self.timeout)
        except requests.Timeout as exc:
            raise SeedanceVideoTimeoutError("seedance video request timed out") from exc
        except requests.RequestException as exc:
            raise SeedanceVideoError(f"seedance video request failed: {exc}") from exc

        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise SeedanceVideoError(
                f"seedance video request failed: status={response.status_code}, body={response.text}"
            ) from exc
        try:
            data = response.json()
        except ValueError as exc:
            raise SeedanceVideoError("seedance returned non-json response") from exc
        if not isinstance(data, dict):
            raise SeedanceVideoError("seedance returned invalid response")
        return data

    def _headers(self, method: str, path: str, body: str) -> dict[str, str]:
        return {
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Authorization": f"Bearer {self.api_key.strip()}",
        }

    def _has_auth(self) -> bool:
        return bool(self.api_key.strip())

    @staticmethod
    def _extract_task_id(payload: dict[str, Any]) -> str:
        for key in ("id", "task_id", "taskId", "request_id", "requestId"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        data = payload.get("data")
        if isinstance(data, dict):
            return SeedanceVideoClient._extract_task_id(data)
        raise SeedanceVideoError("seedance create response missing task id")

    @staticmethod
    def _extract_status(payload: dict[str, Any]) -> str:
        for key in ("status", "state", "task_status", "taskStatus"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        data = payload.get("data")
        if isinstance(data, dict):
            return SeedanceVideoClient._extract_status(data)
        return "processing"

    @classmethod
    def _extract_video_url(cls, payload: dict[str, Any]) -> str:
        for value in cls._walk(payload):
            if isinstance(value, str) and cls._looks_like_video_url(value):
                return value.strip()
        raise SeedanceVideoError("seedance response missing video url")

    @staticmethod
    def _looks_like_video_url(value: str) -> bool:
        lowered = value.lower().split("?", 1)[0]
        return lowered.startswith(("http://", "https://")) and lowered.endswith((".mp4", ".mov", ".webm"))

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
    def _duration_seconds(duration: str) -> int | None:
        digits = "".join(char for char in str(duration) if char.isdigit())
        if not digits:
            return None
        return max(int(digits), 1)

    @staticmethod
    def _aspect_ratio(aspect_ratio: str, image_size: str) -> str:
        raw = str(aspect_ratio or "").strip().replace("：", ":").lower()
        if raw in {"", "auto", "智能", "adaptive", "default"} or str(image_size or "").strip().lower() == "auto":
            return ""
        if "9:16" in raw or image_size in {"480x854", "720x1280"}:
            return "9:16"
        if "1:1" in raw or image_size in {"480x480", "960x960"}:
            return "1:1"
        return "16:9"
