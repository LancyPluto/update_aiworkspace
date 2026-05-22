import json
import base64
import hashlib
import hmac
import time
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import requests

from config import settings


class KlingVideoError(RuntimeError):
    pass


class KlingVideoTimeoutError(KlingVideoError):
    pass


class KlingVideoClient:
    """Kling-compatible video and image adapter.

    Defaults follow the public Kling-compatible REST shape:
    POST /v1/videos/text2video or /v1/videos/image2video, then poll the
    corresponding task query endpoint. Base URL and model are intentionally
    configurable so official or proxy gateways can share this adapter.
    """

    def __init__(
        self,
        *,
        base_url: str | None = None,
        api_key: str | None = None,
        access_key: str | None = None,
        secret_key: str | None = None,
        text_path: str | None = None,
        image_path: str | None = None,
        text_result_path: str | None = None,
        image_result_path: str | None = None,
        image_generation_path: str | None = None,
        poll_interval_seconds: float | None = None,
        timeout_seconds: int | None = None,
    ) -> None:
        self.base_url = (base_url or settings.kling_base_url).rstrip("/")
        self.api_key = api_key if api_key is not None else settings.kling_api_key
        self.access_key = access_key if access_key is not None else settings.kling_access_key
        self.secret_key = secret_key if secret_key is not None else settings.kling_secret_key
        self.text_path = text_path or settings.kling_video_text_path
        self.image_path = image_path or settings.kling_video_image_path
        self.text_result_path = text_result_path or settings.kling_video_text_result_path
        self.image_result_path = image_result_path or settings.kling_video_image_result_path
        self.image_generation_path = image_generation_path or settings.kling_image_generation_path
        self.poll_interval_seconds = poll_interval_seconds or settings.kling_poll_interval_seconds
        self.timeout_seconds = timeout_seconds or settings.kling_timeout_seconds
        self.timeout = (10, 300)
        self.session = requests.Session()
        self.max_input_image_bytes = 20 * 1024 * 1024

    def generate_video(
        self,
        *,
        prompt: str,
        image_size: str,
        negative_prompt: str = "",
        model: str | None = None,
        image: str = "",
        image_tail: str = "",
        seed: int | None = None,
        duration: str = "",
        aspect_ratio: str = "",
        resolution: str = "",
        mode: str = "",
        sound: str = "",
        callback_url: str = "",
        external_task_id: str = "",
    ) -> dict[str, Any]:
        if not self._has_auth():
            raise KlingVideoError("Kling credentials are not configured")

        payload = self._build_video_payload(
            prompt=prompt,
            image_size=image_size,
            negative_prompt=negative_prompt,
            model=model or settings.kling_video_model,
            image=image,
            image_tail=image_tail,
            seed=seed,
            duration=duration,
            aspect_ratio=aspect_ratio,
            resolution=resolution,
            mode=mode,
            sound=sound,
            callback_url=callback_url,
            external_task_id=external_task_id,
        )
        create_path = self.image_path if image.strip() else self.text_path
        created = self._request("POST", create_path, payload)
        task_id = self._extract_task_id(created)
        result_path_template = self.image_result_path if create_path == self.image_path else self.text_result_path
        finished = self.wait_for_video(task_id, result_path_template=result_path_template)
        return {
            "requestId": task_id,
            "status": self._extract_status(finished),
            "videoUrl": self._extract_video_url(finished),
            "reason": str(finished.get("reason") or finished.get("message") or ""),
            "seed": seed,
            "timings": {},
            "provider": "kling_video",
            "model": model or settings.kling_video_model,
            "resolution": resolution,
        }

    def generate_images(
        self,
        *,
        prompt: str,
        model: str | None = None,
        image_size: str = "1024x1024",
        batch_size: int = 1,
        negative_prompt: str = "",
        seed: int | None = None,
        guidance_scale: float | None = None,
        num_inference_steps: int | None = None,
    ) -> list[str]:
        if not self._has_auth():
            raise KlingVideoError("Kling credentials are not configured")

        payload: dict[str, Any] = {
            "model": model or settings.kling_image_model,
            "prompt": prompt,
            "image_size": image_size,
            "n": max(1, batch_size),
            "batch_size": max(1, batch_size),
        }
        if negative_prompt.strip():
            payload["negative_prompt"] = negative_prompt.strip()
        if seed is not None:
            payload["seed"] = seed
        if guidance_scale is not None:
            payload["guidance_scale"] = guidance_scale
        if num_inference_steps is not None:
            payload["num_inference_steps"] = num_inference_steps
        response = self._request("POST", self.image_generation_path, payload)
        return self._extract_image_urls(response)

    def wait_for_video(self, task_id: str, *, result_path_template: str | None = None) -> dict[str, Any]:
        deadline = time.monotonic() + self.timeout_seconds
        last_payload: dict[str, Any] = {}
        path = self._task_result_path(task_id, result_path_template or self.image_result_path)
        while time.monotonic() < deadline:
            last_payload = self._request("GET", path, None)
            status = self._extract_status(last_payload).lower()
            if status in {"succeeded", "succeed", "success", "completed", "done"}:
                return last_payload
            if status in {"failed", "fail", "error", "cancelled", "canceled"}:
                reason = str(last_payload.get("message") or last_payload.get("reason") or "kling video generation failed")
                raise KlingVideoError(reason)
            time.sleep(self.poll_interval_seconds)
        raise KlingVideoTimeoutError(
            f"kling video generation timed out, taskId={task_id}, lastStatus={self._extract_status(last_payload)}"
        )

    def _task_result_path(self, task_id: str, template: str) -> str:
        cleaned = str(template or "").strip() or "/v1/videos/image2video/{task_id}"
        if "{task_id}" in cleaned:
            return cleaned.replace("{task_id}", task_id)
        if "{taskId}" in cleaned:
            return cleaned.replace("{taskId}", task_id)
        return f"{cleaned.rstrip('/')}/{task_id}"

    def _build_video_payload(
        self,
        *,
        prompt: str,
        image_size: str,
        negative_prompt: str,
        model: str,
        image: str,
        image_tail: str,
        seed: int | None,
        duration: str,
        aspect_ratio: str,
        resolution: str,
        mode: str,
        sound: str,
        callback_url: str,
        external_task_id: str,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "model_name": model,
            "prompt": prompt.strip(),
        }
        duration_seconds = self._duration_seconds(duration)
        if duration_seconds is not None:
            payload["duration"] = str(duration_seconds)
        ratio = self._aspect_ratio(aspect_ratio, image_size)
        if ratio:
            payload["aspect_ratio"] = ratio
        if mode.strip():
            payload["mode"] = mode.strip()
        if negative_prompt.strip():
            payload["negative_prompt"] = negative_prompt.strip()
        if image.strip():
            payload["image"] = self._image_to_base64(image.strip())
        if image_tail.strip():
            payload["image_tail"] = self._image_to_base64(image_tail.strip())
        if resolution.strip():
            payload["resolution"] = resolution.strip()
        if sound.strip():
            payload["sound"] = sound.strip()
        if callback_url.strip():
            payload["callback_url"] = callback_url.strip()
        if external_task_id.strip():
            payload["external_task_id"] = external_task_id.strip()
        if seed is not None:
            payload["seed"] = seed
        return payload

    def _image_to_base64(self, value: str) -> str:
        raw = value.strip()
        if not raw:
            return ""
        if raw.startswith("data:"):
            header, separator, encoded = raw.partition(",")
            if separator and ";base64" in header:
                return self._validate_base64(encoded)
            raise KlingVideoError("kling image data url is not base64 encoded")
        if self._is_base64(raw):
            return raw
        local_path = self._local_media_path(raw)
        if local_path is not None:
            return self._file_to_base64(local_path)
        if raw.startswith(("http://", "https://")) or raw.startswith("/"):
            return self._download_to_base64(raw)
        possible_path = Path(raw)
        if possible_path.exists() and possible_path.is_file():
            return self._file_to_base64(possible_path)
        raise KlingVideoError("kling image must be base64, data url, URL, or readable local file")

    def _local_media_path(self, value: str) -> Path | None:
        parsed_path = value
        if value.startswith(("http://", "https://")):
            parsed_path = urlparse(value).path
        configured_base = settings.generated_media_public_base_url.rstrip("/") or "/generated"
        public_base = urlparse(configured_base).path.rstrip("/") if configured_base.startswith(("http://", "https://")) else configured_base
        public_base = public_base or "/generated"
        if not parsed_path.startswith(public_base + "/"):
            return None
        relative = parsed_path.removeprefix(public_base + "/")
        candidate = Path(settings.generated_media_dir).resolve().joinpath(relative).resolve()
        media_root = Path(settings.generated_media_dir).resolve()
        if candidate.is_file() and candidate.is_relative_to(media_root):
            return candidate
        return None

    def _file_to_base64(self, path: Path) -> str:
        try:
            data = path.read_bytes()
        except OSError as exc:
            raise KlingVideoError(f"could not read kling input image: {path}") from exc
        return self._bytes_to_base64(data)

    def _download_to_base64(self, value: str) -> str:
        url = value
        if value.startswith("/"):
            url = f"{settings.backend_internal_base_url.rstrip('/')}{value}"
        try:
            with self.session.get(url, stream=True, timeout=self.timeout) as response:
                response.raise_for_status()
                chunks: list[bytes] = []
                total = 0
                for chunk in response.iter_content(chunk_size=1024 * 256):
                    if not chunk:
                        continue
                    total += len(chunk)
                    if total > self.max_input_image_bytes:
                        raise KlingVideoError("kling input image exceeds 20MB")
                    chunks.append(chunk)
        except KlingVideoError:
            raise
        except requests.RequestException as exc:
            raise KlingVideoError(f"could not download kling input image: {value}") from exc
        return self._bytes_to_base64(b"".join(chunks))

    def _bytes_to_base64(self, data: bytes) -> str:
        if not data:
            raise KlingVideoError("kling input image is empty")
        if len(data) > self.max_input_image_bytes:
            raise KlingVideoError("kling input image exceeds 20MB")
        return base64.b64encode(data).decode("ascii")

    @staticmethod
    def _is_base64(value: str) -> bool:
        compact = "".join(value.split())
        if len(compact) < 16 or compact.startswith(("http://", "https://", "/")):
            return False
        try:
            base64.b64decode(compact, validate=True)
            return True
        except Exception:
            return False

    @staticmethod
    def _validate_base64(value: str) -> str:
        compact = "".join(value.split())
        try:
            base64.b64decode(compact, validate=True)
        except Exception as exc:
            raise KlingVideoError("kling image is not valid base64") from exc
        return compact

    def _request(self, method: str, path: str, payload: dict[str, Any] | None) -> dict[str, Any]:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) if payload is not None else ""
        try:
            response = self.session.request(
                method,
                f"{self.base_url}{path}",
                data=body.encode("utf-8") if body else None,
                headers=self._headers(),
                timeout=self.timeout,
            )
        except requests.Timeout as exc:
            raise KlingVideoTimeoutError("kling request timed out") from exc
        except requests.RequestException as exc:
            raise KlingVideoError(f"kling request failed: {exc}") from exc

        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise KlingVideoError(
                f"kling request failed: status={response.status_code}, body={response.text}"
            ) from exc

        try:
            data = response.json()
        except ValueError as exc:
            raise KlingVideoError("kling returned non-json response") from exc
        if not isinstance(data, dict):
            raise KlingVideoError("kling returned invalid response")
        return data

    def _headers(self) -> dict[str, str]:
        token = self._bearer_token()
        return {
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Authorization": f"Bearer {token}",
        }

    def _has_auth(self) -> bool:
        if self.api_key and self.api_key.strip() and not self.api_key.startswith("replace-with-"):
            return True
        return bool(
            self.access_key
            and self.access_key.strip()
            and self.secret_key
            and self.secret_key.strip()
            and not self.access_key.startswith("replace-with-")
            and not self.secret_key.startswith("replace-with-")
        )

    def _bearer_token(self) -> str:
        if self.api_key and self.api_key.strip() and not self.api_key.startswith("replace-with-"):
            return self.api_key.strip()
        return self._jwt_token()

    def _jwt_token(self) -> str:
        now = int(time.time())
        header = {"alg": "HS256", "typ": "JWT"}
        payload = {
            "iss": self.access_key.strip(),
            "exp": now + 1800,
            "nbf": now - 5,
        }
        signing_input = ".".join([
            self._base64url_json(header),
            self._base64url_json(payload),
        ])
        digest = hmac.new(
            self.secret_key.strip().encode("utf-8"),
            signing_input.encode("utf-8"),
            hashlib.sha256,
        ).digest()
        signature = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
        return f"{signing_input}.{signature}"

    @staticmethod
    def _base64url_json(payload: dict[str, Any]) -> str:
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")

    @classmethod
    def _extract_task_id(cls, payload: dict[str, Any]) -> str:
        for key in ("task_id", "taskId", "id", "request_id", "requestId"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        data = payload.get("data")
        if isinstance(data, dict):
            return cls._extract_task_id(data)
        raise KlingVideoError("kling create response missing task id")

    @classmethod
    def _extract_status(cls, payload: dict[str, Any]) -> str:
        for key in ("status", "state", "task_status", "taskStatus"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        data = payload.get("data")
        if isinstance(data, dict):
            return cls._extract_status(data)
        return "processing"

    @classmethod
    def _extract_video_url(cls, payload: dict[str, Any]) -> str:
        for value in cls._walk(payload):
            if isinstance(value, str) and cls._looks_like_video_url(value):
                return value.strip()
        raise KlingVideoError("kling response missing video url")

    @classmethod
    def _extract_image_urls(cls, payload: dict[str, Any]) -> list[str]:
        urls: list[str] = []
        for value in cls._walk(payload):
            if isinstance(value, str) and cls._looks_like_image_url(value):
                urls.append(value.strip())
        if urls:
            return list(dict.fromkeys(urls))
        raise KlingVideoError("kling image response missing image url")

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
    def _looks_like_video_url(value: str) -> bool:
        lowered = value.lower().split("?", 1)[0]
        return lowered.startswith(("http://", "https://")) and lowered.endswith((".mp4", ".mov", ".webm"))

    @staticmethod
    def _looks_like_image_url(value: str) -> bool:
        lowered = value.lower().split("?", 1)[0]
        return lowered.startswith(("http://", "https://")) and lowered.endswith((".jpg", ".jpeg", ".png", ".webp", ".gif"))

    @staticmethod
    def _duration_seconds(duration: str) -> int | None:
        digits = "".join(char for char in str(duration) if char.isdigit())
        if not digits:
            return None
        return max(int(digits), 1)

    @staticmethod
    def _aspect_ratio(aspect_ratio: str, image_size: str) -> str:
        raw = str(aspect_ratio or "")
        if "9:16" in raw or image_size in {"480x854", "720x1280", "1080x1920"}:
            return "9:16"
        if "1:1" in raw or image_size in {"480x480", "960x960", "1024x1024"}:
            return "1:1"
        return "16:9"
