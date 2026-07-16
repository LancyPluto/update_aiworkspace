import json
import logging
import time
from typing import Any, Callable
from urllib.parse import urlparse

import requests
from urllib3.util import Timeout as Urllib3Timeout

from config import settings
from utils.input_image import InputImageError, resolve_reference_image_data_url
from volcengine_model import normalize_volcengine_openai_base_url


LOGGER = logging.getLogger(__name__)
POLL_REQUEST_ATTEMPTS = 3


class SeedanceVideoError(RuntimeError):
    pass


class SeedanceVideoTimeoutError(SeedanceVideoError):
    pass


class SeedanceVideoClient:
    def __init__(
        self,
        *,
        base_url: str | None = None,
        api_key: str | None = None,
        default_model: str | None = None,
        create_path: str | None = None,
        poll_interval_seconds: int | None = None,
        timeout_seconds: int | None = None,
    ) -> None:
        normalized_base, normalized_path = self._normalize_endpoint(
            (base_url or settings.seedance_base_url).rstrip("/"),
            create_path or settings.seedance_video_create_path,
        )
        self.base_url = normalized_base
        self.api_key = api_key if api_key is not None else settings.seedance_api_key
        self.default_model = default_model or settings.seedance_video_model
        self.create_path = normalized_path
        self.poll_interval_seconds = (
            poll_interval_seconds if poll_interval_seconds is not None else settings.seedance_video_poll_interval_seconds
        )
        self.timeout_seconds = (
            timeout_seconds if timeout_seconds is not None else settings.seedance_video_timeout_seconds
        )
        self.timeout = (10, 300)
        self.session = requests.Session()

    @staticmethod
    def _normalize_endpoint(base_url: str, path: str) -> tuple[str, str]:
        normalized_path = path if path.startswith("/") else f"/{path}"
        normalized_base = normalize_volcengine_openai_base_url(base_url.rstrip("/"))
        # Model configs often store baseUrl with /api/v3 while worker defaults include the same prefix.
        if normalized_base.endswith("/api/v3") and normalized_path.startswith("/api/v3/"):
            normalized_path = normalized_path[len("/api/v3") :] or "/contents/generations/tasks"
        return normalized_base, normalized_path

    @classmethod
    def from_model_config(cls, model_config: dict[str, Any] | None) -> "SeedanceVideoClient":
        config = model_config or {}
        api_key = str(config.get("apiKey") or config.get("api_key") or "").strip()
        base_url = str(config.get("baseUrl") or config.get("base_url") or settings.seedance_base_url).strip()
        model_name = str(config.get("modelName") or config.get("model_name") or settings.seedance_video_model).strip()
        if not api_key:
            raise SeedanceVideoError("Seedance model snapshot missing apiKey")
        return cls(base_url=base_url, api_key=api_key, default_model=model_name)

    def generate_video(
        self,
        *,
        prompt: str,
        image_size: str,
        negative_prompt: str = "",
        model: str | None = None,
        image: str = "",
        images: list[str] | None = None,
        image_tail: str = "",
        video_url: str = "",
        audio_data_url: str = "",
        seed: int | None = None,
        duration: str = "",
        aspect_ratio: str = "",
        resolution: str = "480p",
        generate_audio: bool | None = None,
        watermark: bool | None = None,
        camera_fixed: bool | None = None,
        mode: str = "",
        resume: dict[str, Any] | None = None,
        submitted_callback: Callable[[dict[str, Any]], None] | None = None,
    ) -> dict[str, Any]:
        if not self._has_auth():
            raise SeedanceVideoError("SEEDANCE_API_KEY is not configured")

        resume = resume if isinstance(resume, dict) else {}
        task_id = str(resume.get("taskId") or "").strip()
        request_id = str(resume.get("requestId") or task_id).strip()
        if not task_id:
            payload = self._build_payload(
                prompt=prompt,
                image_size=image_size,
                negative_prompt=negative_prompt,
                model=model or self.default_model,
                image=image,
                images=images,
                audio_data_url=audio_data_url,
                image_tail=image_tail,
                video_url=video_url,
                seed=seed,
                duration=duration,
                aspect_ratio=aspect_ratio,
                resolution=resolution,
                generate_audio=generate_audio,
                watermark=watermark,
                camera_fixed=camera_fixed,
            )
            created = self._request("POST", self.create_path, payload)
            task_id = self._extract_task_id(created)
            request_id = task_id
            if submitted_callback:
                submitted_callback({"taskId": task_id, "requestId": request_id})
        finished = self.wait_for_video(task_id)
        return {
            "requestId": request_id,
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
            last_payload = self._poll_status_with_retry(path=path, task_id=task_id, deadline=deadline)
            status = self._extract_status(last_payload).lower()
            if status in {"succeeded", "succeed", "success", "completed", "done"}:
                return last_payload
            if status in {"failed", "fail", "error", "cancelled", "canceled"}:
                reason = str(last_payload.get("message") or last_payload.get("reason") or "seedance video generation failed")
                raise SeedanceVideoError(reason)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(self.poll_interval_seconds, remaining))

        raise SeedanceVideoTimeoutError(
            f"seedance video generation timed out, taskId={task_id}, lastStatus={self._extract_status(last_payload)}"
        )

    def _poll_status_with_retry(self, *, path: str, task_id: str, deadline: float) -> dict[str, Any]:
        for attempt in range(1, POLL_REQUEST_ATTEMPTS + 1):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise self._poll_deadline_error(task_id)
            try:
                return self._request("GET", path, None, request_timeout=self._clip_request_timeout(remaining))
            except SeedanceVideoError as exc:
                transport_error = isinstance(exc, SeedanceVideoTimeoutError) or isinstance(
                    exc.__cause__, requests.RequestException
                )
                if not transport_error:
                    raise
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise self._poll_deadline_error(task_id) from exc
                if attempt >= POLL_REQUEST_ATTEMPTS:
                    raise
                LOGGER.warning(
                    "seedance video poll request failed; retrying existing task taskId=%s attempt=%s/%s: %s",
                    task_id,
                    attempt,
                    POLL_REQUEST_ATTEMPTS,
                    exc,
                )
                time.sleep(min(float(attempt), remaining))
                if deadline - time.monotonic() <= 0:
                    raise self._poll_deadline_error(task_id) from exc
        raise SeedanceVideoError("seedance video poll retry exhausted")

    def _clip_request_timeout(self, remaining: float) -> Urllib3Timeout:
        connect_timeout, read_timeout = self.timeout
        return Urllib3Timeout(
            total=remaining,
            connect=min(float(connect_timeout), remaining),
            read=min(float(read_timeout), remaining),
        )

    @staticmethod
    def _poll_deadline_error(task_id: str) -> SeedanceVideoTimeoutError:
        return SeedanceVideoTimeoutError(f"seedance video generation timed out while polling, taskId={task_id}")

    def _build_payload(
        self,
        *,
        prompt: str,
        image_size: str,
        negative_prompt: str,
        model: str,
        image: str,
        images: list[str] | None = None,
        audio_data_url: str = "",
        image_tail: str = "",
        video_url: str = "",
        seed: int | None = None,
        duration: str = "",
        aspect_ratio: str = "",
        resolution: str = "480p",
        generate_audio: bool | None = None,
        watermark: bool | None = None,
        camera_fixed: bool | None = None,
    ) -> dict[str, Any]:
        text = prompt.strip()
        if negative_prompt.strip():
            text = f"{text}\nNegative prompt: {negative_prompt.strip()}"
        content: list[dict[str, Any]] = [{"type": "text", "text": text}]
        resolved_images = _dedupe_texts([*(images or []), image])
        tail_image = (image_tail or "").strip()
        if tail_image:
            resolved_images.append(tail_image)
        if resolved_images:
            image_payloads = [self._image_payload_value(item) for item in resolved_images[:9]]
            if len(image_payloads) == 1:
                content.append({"type": "image_url", "image_url": {"url": image_payloads[0]}})
            elif len(image_payloads) == 2 and tail_image:
                content.append({"type": "image_url", "image_url": {"url": image_payloads[0]}, "role": "first_frame"})
                content.append({"type": "image_url", "image_url": {"url": image_payloads[1]}, "role": "last_frame"})
            else:
                for value in image_payloads:
                    content.append({"type": "image_url", "image_url": {"url": value}, "role": "reference_image"})
        if video_url.strip():
            content.append({"type": "video_url", "video_url": {"url": video_url.strip()}, "role": "reference_video"})
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
        if generate_audio is not None:
            payload["generate_audio"] = bool(generate_audio)
        if watermark is not None:
            payload["watermark"] = bool(watermark)
        if camera_fixed is not None:
            payload["camera_fixed"] = bool(camera_fixed)
        return payload

    def _image_payload_value(self, value: str) -> str:
        raw = (value or "").strip()
        if not raw:
            return raw
        if not self._should_inline_image(raw):
            return raw
        try:
            return resolve_reference_image_data_url(raw, session=self.session)
        except InputImageError as exc:
            raise SeedanceVideoError(f"seedance image must be a valid image or public URL: {exc}") from exc

    @staticmethod
    def _should_inline_image(value: str) -> bool:
        raw = (value or "").strip()
        if not raw:
            return False
        if raw.startswith("data:") or raw.startswith("/"):
            return True
        if not raw.startswith(("http://", "https://")):
            return True
        parsed = urlparse(raw)
        internal_hosts = {
            host
            for host in (
                urlparse(settings.backend_internal_base_url).hostname,
                "backend",
                "localhost",
                "127.0.0.1",
                "host.docker.internal",
            )
            if host
        }
        return parsed.hostname in internal_hosts

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

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None,
        *,
        request_timeout: Urllib3Timeout | tuple[float, float] | None = None,
    ) -> dict[str, Any]:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) if payload is not None else ""
        url = f"{self.base_url}{path}"
        headers = self._headers(method, path, body)
        try:
            response = self.session.request(
                method,
                url,
                data=body.encode("utf-8") if body else None,
                headers=headers,
                timeout=request_timeout if request_timeout is not None else self.timeout,
            )
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
        if raw == "adaptive":
            return "adaptive"
        if raw in {"", "auto", "智能", "default"} or str(image_size or "").strip().lower() == "auto":
            return ""
        if "9:16" in raw or image_size in {"480x854", "720x1280"}:
            return "9:16"
        if "1:1" in raw or image_size in {"480x480", "960x960"}:
            return "1:1"
        if raw in {"4:3", "3:4", "21:9"}:
            return raw
        return "16:9"


def _dedupe_texts(values: list[Any]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        if not isinstance(value, str):
            continue
        text = value.strip()
        if text and text not in seen:
            seen.add(text)
            result.append(text)
    return result
