import json
import logging
import re
import time
from typing import Any, Callable
from urllib.parse import urlparse

import requests
from urllib3.util import Timeout as Urllib3Timeout

from client.provider_error import (
    ProviderCallError,
    rejected_response_metadata,
    transport_failure_metadata,
)
from config import settings
from utils.input_image import InputImageError, resolve_reference_image_data_url
from utils.model_contract import parse_response_mapping, read_response_value, response_mapping_has
from utils.video_timeout import resolve_video_timeout_seconds
from volcengine_model import normalize_volcengine_openai_base_url


LOGGER = logging.getLogger(__name__)
POLL_REQUEST_ATTEMPTS = 3
TEXT_TO_VIDEO = "text_to_video"
FIRST_FRAME_TO_VIDEO = "first_frame_to_video"
FIRST_LAST_FRAME_TO_VIDEO = "first_last_frame_to_video"
MULTIMODAL_REFERENCE = "multimodal_reference"
SEEDANCE_GENERATION_MODES = {
    TEXT_TO_VIDEO,
    FIRST_FRAME_TO_VIDEO,
    FIRST_LAST_FRAME_TO_VIDEO,
    MULTIMODAL_REFERENCE,
}
SEEDANCE_PRIVACY_ERROR_CODE = "MODEL_005"
SEEDANCE_PRIVACY_USER_MESSAGE = "部分参考图片可能包含真人或隐私内容，未通过模型安全检查，请更换后重试"
SEEDANCE_PRIVACY_PROVIDER_ERROR_CODES = {
    "inputimagesensitivecontentdetected.privacyinformation",
}


class SeedanceVideoError(ProviderCallError):
    pass


class SeedanceVideoTimeoutError(SeedanceVideoError):
    pass


class SeedancePrivacyContentError(SeedanceVideoError):
    error_code = SEEDANCE_PRIVACY_ERROR_CODE
    user_message = SEEDANCE_PRIVACY_USER_MESSAGE


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
        model_config: dict[str, Any] | None = None,
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
        self.response_mapping = parse_response_mapping(model_config)

    @staticmethod
    def _normalize_endpoint(base_url: str, path: str) -> tuple[str, str]:
        normalized_path = path if path.startswith("/") else f"/{path}"
        normalized_base = normalize_volcengine_openai_base_url(base_url.rstrip("/"))
        # Model configs often store baseUrl with /api/v3 while worker defaults include the same prefix.
        if normalized_base.endswith("/api/v3") and normalized_path.startswith("/api/v3/"):
            normalized_path = normalized_path[len("/api/v3") :] or "/contents/generations/tasks"
        return normalized_base, normalized_path

    @classmethod
    def from_model_config(
        cls,
        model_config: dict[str, Any] | None,
        *,
        timeout_seconds: int | None = None,
    ) -> "SeedanceVideoClient":
        config = model_config or {}
        api_key = str(config.get("apiKey") or config.get("api_key") or "").strip()
        base_url = str(config.get("baseUrl") or config.get("base_url") or settings.seedance_base_url).strip()
        model_name = str(config.get("modelName") or config.get("model_name") or settings.seedance_video_model).strip()
        if not api_key:
            raise SeedanceVideoError("Seedance model snapshot missing apiKey")
        resolved_timeout = resolve_video_timeout_seconds(config)
        if timeout_seconds is not None:
            try:
                resolved_timeout = max(resolved_timeout, int(timeout_seconds))
            except (TypeError, ValueError):
                pass
        return cls(
            base_url=base_url,
            api_key=api_key,
            default_model=model_name,
            timeout_seconds=resolved_timeout,
            model_config=config,
        )

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
        video_urls: list[str] | None = None,
        audio_data_url: str = "",
        audio_urls: list[str] | None = None,
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
                audio_urls=audio_urls,
                image_tail=image_tail,
                video_url=video_url,
                video_urls=video_urls,
                seed=seed,
                duration=duration,
                aspect_ratio=aspect_ratio,
                resolution=resolution,
                generate_audio=generate_audio,
                watermark=watermark,
                camera_fixed=camera_fixed,
                mode=mode,
            )
            created = self._request("POST", self.create_path, payload)
            task_id = self._response_task_id(created)
            request_id = task_id
            if submitted_callback:
                submitted_callback({"taskId": task_id, "requestId": request_id})
        finished = self.wait_for_video(task_id)
        return {
            "requestId": request_id,
            "status": self._response_status(finished),
            "videoUrl": self._response_video_url(finished),
            "reason": str(finished.get("reason") or finished.get("message") or ""),
            "seed": seed,
            "timings": {},
            "provider": "seedance",
            "model": model or self.default_model,
            "resolution": resolution,
            "usage": self._response_usage(finished),
        }

    def wait_for_video(self, task_id: str) -> dict[str, Any]:
        deadline = time.monotonic() + self.timeout_seconds
        last_payload: dict[str, Any] = {}
        path = f"{self.create_path.rstrip('/')}/{task_id}"
        while time.monotonic() < deadline:
            last_payload = self._poll_status_with_retry(path=path, task_id=task_id, deadline=deadline)
            status = self._response_status(last_payload).lower()
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
            f"seedance video generation timed out, taskId={task_id}, lastStatus={self._response_status(last_payload)}"
        )

    def _response_task_id(self, payload: dict[str, Any]) -> str:
        if not response_mapping_has(self.response_mapping, "requestIdPath", "requestIdPaths"):
            return self._extract_task_id(payload)
        value = read_response_value(payload, self.response_mapping, "requestIdPath", "requestIdPaths")
        task_id = str(value or "").strip()
        if not task_id:
            raise SeedanceVideoError("seedance create response missing mapped task id")
        return task_id

    def _response_status(self, payload: dict[str, Any]) -> str:
        if not response_mapping_has(self.response_mapping, "statusPath", "statusPaths"):
            return self._extract_status(payload)
        value = read_response_value(payload, self.response_mapping, "statusPath", "statusPaths")
        return str(value or "processing").strip()

    def _response_video_url(self, payload: dict[str, Any]) -> str:
        if not response_mapping_has(
            self.response_mapping,
            "videoUrlPath",
            "urlPath",
            "urlPaths",
        ):
            return self._extract_video_url(payload)
        value = read_response_value(
            payload,
            self.response_mapping,
            "videoUrlPath",
            "urlPath",
            "urlPaths",
        )
        url = str(value or "").strip()
        if not url:
            raise SeedanceVideoError("seedance response missing mapped video url")
        return url

    def _response_usage(self, payload: dict[str, Any]) -> dict[str, Any]:
        value = read_response_value(
            payload,
            self.response_mapping,
            "usagePath",
            fallback_paths=("usage",),
        )
        return value if isinstance(value, dict) else {}

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
        audio_urls: list[str] | None = None,
        image_tail: str = "",
        video_url: str = "",
        video_urls: list[str] | None = None,
        seed: int | None = None,
        duration: str = "",
        aspect_ratio: str = "",
        resolution: str = "480p",
        generate_audio: bool | None = None,
        watermark: bool | None = None,
        camera_fixed: bool | None = None,
        mode: str = "",
    ) -> dict[str, Any]:
        text = prompt.strip()
        if negative_prompt.strip():
            text = f"{text}\nNegative prompt: {negative_prompt.strip()}"
        content: list[dict[str, Any]] = []
        if text:
            content.append({"type": "text", "text": text})
        resolved_images = _dedupe_texts([image, *(images or [])])
        tail_image = (image_tail or "").strip()
        resolved_videos = _dedupe_texts([video_url, *(video_urls or [])])
        resolved_audios = _dedupe_texts([audio_data_url, *(audio_urls or [])])
        generation_mode = self._resolve_generation_mode(
            mode,
            images=resolved_images,
            image_tail=tail_image,
            videos=resolved_videos,
            audios=resolved_audios,
        )
        self._validate_generation_inputs(
            generation_mode,
            model=model,
            prompt=text,
            images=resolved_images,
            image_tail=tail_image,
            videos=resolved_videos,
            audios=resolved_audios,
        )
        image_payloads = [self._image_payload_value(item) for item in resolved_images]
        if generation_mode == FIRST_FRAME_TO_VIDEO:
            content.append({"type": "image_url", "image_url": {"url": image_payloads[0]}, "role": "first_frame"})
        elif generation_mode == FIRST_LAST_FRAME_TO_VIDEO:
            content.append({"type": "image_url", "image_url": {"url": image_payloads[0]}, "role": "first_frame"})
            content.append(
                {
                    "type": "image_url",
                    "image_url": {"url": self._image_payload_value(tail_image)},
                    "role": "last_frame",
                }
            )
        elif generation_mode == MULTIMODAL_REFERENCE:
            content.extend(
                {"type": "image_url", "image_url": {"url": value}, "role": "reference_image"}
                for value in image_payloads
            )
            content.extend(
                {"type": "video_url", "video_url": {"url": value}, "role": "reference_video"}
                for value in resolved_videos
            )
            content.extend(self._audio_payload(value) for value in resolved_audios)

        payload: dict[str, Any] = {
            "model": model,
            "content": content,
            "resolution": resolution,
        }
        duration_seconds = self._duration_seconds(duration, model)
        if duration_seconds is not None:
            payload["duration"] = duration_seconds
        ratio = self._aspect_ratio(aspect_ratio, image_size)
        if ratio:
            payload["ratio"] = ratio
        if seed is not None and not self._is_seedance_2(model):
            payload["seed"] = seed
        if generate_audio is not None:
            payload["generate_audio"] = bool(generate_audio)
        if watermark is not None:
            payload["watermark"] = bool(watermark)
        if camera_fixed is not None and not self._is_seedance_2(model) and generation_mode == TEXT_TO_VIDEO:
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

    @classmethod
    def _resolve_generation_mode(
        cls,
        value: str,
        *,
        images: list[str],
        image_tail: str,
        videos: list[str],
        audios: list[str],
    ) -> str:
        raw = str(value or "").strip().lower().replace("-", "_")
        aliases = {
            "t2v": TEXT_TO_VIDEO,
            "text2video": TEXT_TO_VIDEO,
            "text_to_video": TEXT_TO_VIDEO,
            "i2v": FIRST_FRAME_TO_VIDEO,
            "image2video": FIRST_FRAME_TO_VIDEO,
            "first_frame": FIRST_FRAME_TO_VIDEO,
            "first_frame_to_video": FIRST_FRAME_TO_VIDEO,
            "first_last_frame": FIRST_LAST_FRAME_TO_VIDEO,
            "first_last_frame_to_video": FIRST_LAST_FRAME_TO_VIDEO,
            "first_end_frame_to_video": FIRST_LAST_FRAME_TO_VIDEO,
            "r2v": MULTIMODAL_REFERENCE,
            "reference": MULTIMODAL_REFERENCE,
            "omni_reference": MULTIMODAL_REFERENCE,
            "multimodal_reference": MULTIMODAL_REFERENCE,
        }
        if raw:
            resolved = aliases.get(raw, raw)
            if resolved not in SEEDANCE_GENERATION_MODES:
                raise SeedanceVideoError(f"unsupported Seedance generationMode: {value}")
            return resolved
        if image_tail:
            return FIRST_LAST_FRAME_TO_VIDEO
        if videos or audios or len(images) > 1:
            return MULTIMODAL_REFERENCE
        if images:
            return FIRST_FRAME_TO_VIDEO
        return TEXT_TO_VIDEO

    @classmethod
    def _validate_generation_inputs(
        cls,
        mode: str,
        *,
        model: str,
        prompt: str,
        images: list[str],
        image_tail: str,
        videos: list[str],
        audios: list[str],
    ) -> None:
        if mode == TEXT_TO_VIDEO:
            if not prompt:
                raise SeedanceVideoError("prompt is required for Seedance text-to-video")
            if images or image_tail or videos or audios:
                raise SeedanceVideoError("text-to-video does not accept reference media")
            return
        if mode == FIRST_FRAME_TO_VIDEO:
            if len(images) != 1 or image_tail or videos or audios:
                raise SeedanceVideoError("first-frame video requires exactly one image")
            return
        if mode == FIRST_LAST_FRAME_TO_VIDEO:
            if len(images) != 1 or not image_tail or videos or audios:
                raise SeedanceVideoError("first/last-frame video requires one first frame and one last frame")
            return
        if not cls._is_seedance_2(model):
            raise SeedanceVideoError("multimodal reference video requires a Seedance 2.0 model")
        if len(images) > 9:
            raise SeedanceVideoError("multimodal reference video accepts at most 9 images")
        if len(videos) > 3:
            raise SeedanceVideoError("multimodal reference video accepts at most 3 videos")
        if len(audios) > 3:
            raise SeedanceVideoError("multimodal reference video accepts at most 3 audio files")
        if not images and not videos:
            raise SeedanceVideoError("multimodal reference video requires at least one image or video")

    @staticmethod
    def _is_seedance_2(model: str) -> bool:
        normalized = str(model or "").strip().lower().replace(".", "-")
        return "seedance-2-0" in normalized

    @staticmethod
    def _audio_payload(audio_data_url: str) -> dict[str, Any] | None:
        raw = (audio_data_url or "").strip()
        if not raw:
            return None
        return {
            "type": "audio_url",
            "audio_url": {"url": raw},
            "role": "reference_audio",
        }

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
            raise SeedanceVideoTimeoutError(
                "seedance video request timed out",
                **transport_failure_metadata(exc),
            ) from exc
        except requests.RequestException as exc:
            raise SeedanceVideoError(
                f"seedance video request failed: {exc}",
                **transport_failure_metadata(exc),
            ) from exc

        if response.status_code >= 400:
            provider_error_code, provider_message, provider_request_id = _provider_error_details(response)
            metadata = rejected_response_metadata(response)
            if provider_error_code:
                metadata["provider_error_code"] = provider_error_code
            if provider_request_id:
                metadata["provider_request_id"] = provider_request_id
            diagnostic = _provider_error_diagnostic(
                response.status_code,
                provider_error_code,
                provider_message,
                provider_request_id,
            )
            if _is_seedance_privacy_error(provider_error_code):
                metadata["retry_scope"] = "NONE"
                raise SeedancePrivacyContentError(diagnostic, **metadata)
            raise SeedanceVideoError(diagnostic, **metadata)
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

    @classmethod
    def _duration_seconds(cls, duration: str, model: str) -> int | None:
        raw = str(duration or "").strip()
        match = re.search(r"-?\d+", raw)
        if not match:
            return None
        seconds = int(match.group(0))
        normalized_model = str(model or "").strip().lower().replace(".", "-")
        if cls._is_seedance_2(model):
            valid = seconds == -1 or 4 <= seconds <= 15
        elif "seedance-1-5" in normalized_model:
            valid = seconds == -1 or 4 <= seconds <= 12
        else:
            valid = 2 <= seconds <= 12
        if not valid:
            raise SeedanceVideoError(f"duration {seconds} is not supported by model {model}")
        return seconds

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


def _provider_error_details(response: requests.Response) -> tuple[str | None, str | None, str | None]:
    try:
        payload = response.json()
    except (TypeError, ValueError):
        payload = None
    root = payload if isinstance(payload, dict) else {}
    error = root.get("error") if isinstance(root.get("error"), dict) else {}
    provider_error_code = _first_safe_text(error, root, keys=("code", "error_code", "errorCode", "type"), limit=128)
    provider_message = _first_safe_text(error, root, keys=("message", "error_message", "errorMessage"), limit=512)
    provider_request_id = _first_safe_text(
        error,
        root,
        keys=("request_id", "requestId", "requestID"),
        limit=128,
    )
    headers = getattr(response, "headers", None)
    if not provider_request_id and hasattr(headers, "get"):
        for name in ("x-request-id", "x-tt-logid", "request-id"):
            provider_request_id = _safe_provider_text(headers.get(name), 128)
            if provider_request_id:
                break
    return provider_error_code, provider_message, provider_request_id


def _first_safe_text(*containers: dict[str, Any], keys: tuple[str, ...], limit: int) -> str | None:
    for container in containers:
        for key in keys:
            value = _safe_provider_text(container.get(key), limit)
            if value:
                return value
    return None


def _safe_provider_text(value: Any, limit: int) -> str | None:
    if not isinstance(value, (str, int, float)):
        return None
    text = re.sub(r"[\x00-\x1f\x7f]+", " ", str(value)).strip()
    return text[:limit] if text else None


def _provider_error_diagnostic(
    status_code: int,
    provider_error_code: str | None,
    provider_message: str | None,
    provider_request_id: str | None,
) -> str:
    fields = [f"status={status_code}"]
    if provider_error_code:
        fields.append(f"providerErrorCode={provider_error_code}")
    if provider_message:
        fields.append(f"providerMessage={provider_message}")
    if provider_request_id:
        fields.append(f"providerRequestId={provider_request_id}")
    return "seedance video request rejected: " + ", ".join(fields)


def _is_seedance_privacy_error(provider_error_code: str | None) -> bool:
    return str(provider_error_code or "").strip().lower() in SEEDANCE_PRIVACY_PROVIDER_ERROR_CODES
