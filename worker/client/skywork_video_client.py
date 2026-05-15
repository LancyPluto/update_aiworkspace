import json
import time
from typing import Any

import requests

from config import settings


class SkyworkVideoError(RuntimeError):
    pass


class SkyworkVideoConfigurationError(SkyworkVideoError):
    pass


class SkyworkVideoTimeoutError(SkyworkVideoError):
    pass


class SkyworkVideoClient:
    def __init__(self) -> None:
        self.base_url = settings.skywork_base_url.rstrip("/")
        self.api_key = settings.skywork_api_key
        self.default_model = settings.skywork_video_model
        self.endpoint = settings.skywork_video_endpoint
        self.timeout_seconds = settings.skywork_video_timeout_seconds
        self.timeout = (10, 300)
        self.session = requests.Session()
        self.session.headers.update(
            {
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
                "Authorization": f"Bearer {self.api_key}",
            }
        )

    def generate_video(
        self,
        *,
        prompt: str,
        image_size: str,
        negative_prompt: str = "",
        model: str | None = None,
        image: str = "",
        seed: int | None = None,
        duration: str = "",
        aspect_ratio: str = "",
    ) -> dict[str, Any]:
        if not self.api_key or self.api_key.startswith("replace-with-"):
            raise SkyworkVideoError("SKYWORK_API_KEY is not configured")

        payload = self._build_payload(
            prompt=prompt,
            image_size=image_size,
            negative_prompt=negative_prompt,
            model=model or self.default_model,
            image=image,
            seed=seed,
            duration=duration,
            aspect_ratio=aspect_ratio,
        )
        response = self._post_sse(self.endpoint, payload)
        video_url = self._extract_video_url(response)
        request_id = self._extract_request_id(response)
        return {
            "requestId": request_id,
            "status": self._extract_status(response),
            "videoUrl": video_url,
            "reason": "",
            "seed": seed,
            "timings": {},
            "provider": "skywork",
            "model": model or self.default_model,
        }

    def _build_payload(
        self,
        *,
        prompt: str,
        image_size: str,
        negative_prompt: str,
        model: str,
        image: str,
        seed: int | None,
        duration: str,
        aspect_ratio: str,
    ) -> dict[str, Any]:
        ratio = self._aspect_ratio(aspect_ratio, image_size)
        duration_seconds = self._duration_seconds(duration)
        style: dict[str, Any] = {"aspect_ratio": ratio}
        options: dict[str, Any] = {
            "model": model,
            "image_size": image_size,
        }
        if duration_seconds is not None:
            options["duration"] = duration_seconds
            options["duration_seconds"] = duration_seconds
        if seed is not None:
            options["seed"] = seed
        if negative_prompt.strip():
            options["negative_prompt"] = negative_prompt.strip()
        if image.strip():
            options["image"] = image.strip()
            options["image_url"] = image.strip()

        return {
            "title": prompt[:60],
            "content": prompt,
            "style": style,
            "options": options,
            "source_platform": "",
        }

    def _post_sse(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        deadline = time.monotonic() + self.timeout_seconds
        try:
            response = self.session.post(
                f"{self.base_url}{path}",
                json=payload,
                stream=True,
                timeout=self.timeout,
            )
        except requests.Timeout as exc:
            raise SkyworkVideoTimeoutError("skywork video request timed out") from exc
        except requests.RequestException as exc:
            raise SkyworkVideoError(f"skywork video request failed: {exc}") from exc

        success_data: dict[str, Any] | None = None
        with response:
            if response.status_code >= 400 and "text/event-stream" not in response.headers.get("Content-Type", ""):
                raise SkyworkVideoError(
                    f"skywork video request failed: status={response.status_code}, body={response.text}"
                )
            for event_type, event_data in self._parse_sse_stream(response):
                if time.monotonic() > deadline:
                    raise SkyworkVideoTimeoutError("skywork video generation timed out")
                if event_type == "success":
                    success_data = event_data
                elif event_type == "error":
                    raise self._error_from_event(event_data)

            if response.status_code >= 400:
                raise SkyworkVideoError(
                    f"skywork video request failed: status={response.status_code}, no SSE error event returned"
                )

        if not success_data:
            raise SkyworkVideoError("skywork video response missing success event")
        return success_data

    @staticmethod
    def _parse_sse_stream(response: requests.Response):
        event_type: str | None = None
        event_data = ""
        for raw_line in response.iter_lines(decode_unicode=True):
            line = raw_line.rstrip("\r\n") if isinstance(raw_line, str) else ""
            if line == "":
                if event_type is not None:
                    yield event_type, SkyworkVideoClient._parse_event_data(event_data)
                event_type = None
                event_data = ""
                continue
            if line.startswith("event:"):
                event_type = line[6:].strip()
            elif line.startswith("data:"):
                event_data = line[5:].strip()
        if event_type is not None:
            yield event_type, SkyworkVideoClient._parse_event_data(event_data)

    @staticmethod
    def _parse_event_data(event_data: str) -> dict[str, Any]:
        try:
            parsed = json.loads(event_data) if event_data else {}
        except json.JSONDecodeError:
            return {}
        return parsed if isinstance(parsed, dict) else {}

    @staticmethod
    def _error_from_event(event_data: dict[str, Any]) -> SkyworkVideoError:
        code = event_data.get("code")
        message = str(event_data.get("message") or "unknown error")
        if code == 630104 or "get host proxy config is null" in message:
            return SkyworkVideoConfigurationError(
                "Skywork video endpoint is not configured on theme-gateway. "
                "Please verify SKYWORK_VIDEO_ENDPOINT with Skywork official video API documentation."
            )
        return SkyworkVideoError(f"skywork video generation failed: code={code}, message={message}")

    @classmethod
    def _extract_video_url(cls, payload: dict[str, Any]) -> str:
        for value in cls._walk(payload):
            if isinstance(value, str) and cls._looks_like_video_url(value):
                return value.strip()
        raise SkyworkVideoError("skywork response missing video url")

    @staticmethod
    def _extract_request_id(payload: dict[str, Any]) -> str:
        for key in ("id", "file_id", "request_id", "requestId", "task_id", "taskId"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        return "skywork-video"

    @staticmethod
    def _extract_status(payload: dict[str, Any]) -> str:
        value = payload.get("status") or payload.get("state")
        if isinstance(value, str) and value.strip():
            return value.strip()
        return "Succeed"

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
        raw = str(aspect_ratio or "")
        if "9:16" in raw or image_size == "720x1280":
            return "9:16"
        if "1:1" in raw or image_size == "960x960":
            return "1:1"
        return "16:9"
