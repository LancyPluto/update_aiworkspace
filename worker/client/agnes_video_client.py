import base64
import json
import logging
import time
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlparse

import requests
from urllib3.util import Timeout as Urllib3Timeout

from config import settings


LOGGER = logging.getLogger(__name__)
SUCCESS_STATUSES = {"succeeded", "succeed", "success", "completed", "done", "finish", "finished"}
FAILED_STATUSES = {"failed", "fail", "failure", "error", "cancelled", "canceled", "timeout", "timed_out"}
POLL_REQUEST_ATTEMPTS = 3


class AgnesVideoError(RuntimeError):
    pass


class AgnesVideoTimeoutError(AgnesVideoError):
    pass


class AgnesVideoClient:
    """Agnes async video adapter.

    Agnes creates video jobs at /v1/videos and recommends polling /agnesapi
    with video_id and model_name. The endpoint paths stay configurable because
    admin model configs store provider-specific defaults in extraAuthJson.
    """

    def __init__(
        self,
        *,
        base_url: str | None = None,
        api_key: str | None = None,
        create_endpoint_path: str | None = None,
        result_endpoint_path: str | None = None,
        result_query_mode: str | None = None,
        poll_interval_seconds: float | None = None,
        timeout_seconds: int | None = None,
        extra_auth_json: str | None = None,
    ) -> None:
        self.extra_auth = self._parse_json(extra_auth_json)
        self.base_url = self._normalize_base_url(base_url or "https://apihub.agnes-ai.com")
        self.api_key = (api_key or "").strip()
        self.create_endpoint_path = str(
            create_endpoint_path or self.extra_auth.get("createEndpointPath") or "/v1/videos"
        )
        self.result_endpoint_path = str(
            result_endpoint_path or self.extra_auth.get("resultEndpointPath") or "/agnesapi"
        )
        self.result_query_mode = str(
            result_query_mode or self.extra_auth.get("resultQueryMode") or "videoIdQuery"
        )
        self.poll_interval_seconds = _as_float(
            poll_interval_seconds if poll_interval_seconds is not None else self.extra_auth.get("pollIntervalSeconds"),
            5.0,
        )
        self.timeout_seconds = max(
            1,
            _as_int(self.extra_auth.get("timeoutSeconds") or timeout_seconds, 900),
        )
        self.request_timeout = (
            max(1.0, _as_float(self.extra_auth.get("connectTimeoutSeconds"), 10.0)),
            max(60.0, _as_float(self.extra_auth.get("readTimeoutSeconds"), 300.0)),
        )
        self.max_input_image_bytes = max(1, _as_int(self.extra_auth.get("maxInputImageBytes"), 20 * 1024 * 1024))
        self.session = requests.Session()

    def generate_video(
        self,
        *,
        prompt: str,
        image_size: str,
        negative_prompt: str = "",
        model: str | None = None,
        image: str = "",
        image_tail: str = "",
        images: list[str] | None = None,
        seed: int | None = None,
        duration: str = "",
        aspect_ratio: str = "",
        resolution: str = "",
        mode: str = "",
        progress_callback: Callable[[int], None] | None = None,
        resume: dict[str, Any] | None = None,
        submitted_callback: Callable[[dict[str, Any]], None] | None = None,
        **_: Any,
    ) -> dict[str, Any]:
        if not self.base_url:
            raise AgnesVideoError("Agnes video baseUrl is not configured")
        if not self.api_key or self.api_key.startswith("replace-with-"):
            raise AgnesVideoError("Agnes video API key is not configured")
        if not model:
            raise AgnesVideoError("Agnes video modelName is required")
        if not prompt.strip():
            raise AgnesVideoError("Agnes video prompt is required")

        resume = resume if isinstance(resume, dict) else {}
        resumed_task_id = str(resume.get("taskId") or "").strip()
        if resumed_task_id:
            task_id = resumed_task_id
            video_id = str(resume.get("videoId") or task_id).strip()
            request_id = str(resume.get("requestId") or task_id).strip()
            finished = self.wait_for_video(
                task_id=task_id,
                video_id=video_id,
                model=model,
                progress_callback=progress_callback,
            )
        else:
            payload = self._build_payload(
                prompt=prompt,
                image_size=image_size,
                negative_prompt=negative_prompt,
                model=model,
                image=image,
                image_tail=image_tail,
                images=images,
                seed=seed,
                duration=duration,
                aspect_ratio=aspect_ratio,
                resolution=resolution,
                mode=mode,
            )
            LOGGER.info(
                "agnes video create path=%s model=%s size=%sx%s hasImage=%s",
                self.create_endpoint_path,
                payload.get("model"),
                payload.get("width"),
                payload.get("height"),
                bool(payload.get("image") or payload.get("extra_body")),
            )
            created = self._request("POST", self.create_endpoint_path, json_payload=payload)
            task_id = self._extract_task_id(created)
            request_id = task_id
            video_id = self._extract_video_id(created) or task_id
            if submitted_callback:
                submitted_callback({"taskId": task_id, "videoId": video_id, "requestId": request_id})
            if self._extract_video_url_or_empty(created):
                finished = created
            else:
                finished = self.wait_for_video(
                    task_id=task_id,
                    video_id=video_id,
                    model=model,
                    progress_callback=progress_callback,
                )
        return {
            "requestId": request_id,
            "status": self._extract_status(finished),
            "videoUrl": self._extract_video_url(finished),
            "reason": str(finished.get("reason") or finished.get("message") or ""),
            "seed": seed,
            "timings": {},
            "provider": "agnes_video",
            "model": model,
            "resolution": resolution or image_size,
        }

    def wait_for_video(
        self,
        *,
        task_id: str,
        video_id: str,
        model: str,
        progress_callback: Callable[[int], None] | None = None,
    ) -> dict[str, Any]:
        deadline = time.monotonic() + self.timeout_seconds
        last_payload: dict[str, Any] = {}
        last_progress: int | None = None
        while time.monotonic() < deadline:
            last_payload = self._request_result_with_retry(
                task_id=task_id,
                video_id=video_id,
                model=model,
                deadline=deadline,
            )
            if self._extract_video_url_or_empty(last_payload):
                return last_payload
            progress = self._extract_progress_percent(last_payload)
            if progress is not None and progress != last_progress:
                last_progress = progress
                if progress_callback:
                    progress_callback(progress)
            status = self._extract_status(last_payload).lower()
            if status in SUCCESS_STATUSES:
                raise AgnesVideoError(
                    self._describe_response_problem(
                        "Agnes video response reached terminal success but no video url",
                        last_payload,
                    )
                )
            if status in FAILED_STATUSES:
                raise AgnesVideoError(
                    self._describe_response_problem("Agnes video generation failed", last_payload)
                )
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(self.poll_interval_seconds, remaining))
        raise AgnesVideoTimeoutError(
            self._describe_response_problem(
                f"Agnes video generation timed out, taskId={task_id}, videoId={video_id}, "
                f"lastStatus={self._extract_status(last_payload)}",
                last_payload,
            )
        )

    def _request_result_with_retry(
        self,
        *,
        task_id: str,
        video_id: str,
        model: str,
        deadline: float,
    ) -> dict[str, Any]:
        for attempt in range(1, POLL_REQUEST_ATTEMPTS + 1):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise self._poll_deadline_error(task_id)
            try:
                return self._request_result(
                    task_id=task_id,
                    video_id=video_id,
                    model=model,
                    request_timeout=self._clip_request_timeout(remaining),
                )
            except AgnesVideoError as exc:
                transport_error = isinstance(exc, AgnesVideoTimeoutError) or isinstance(
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
                    "Agnes video poll request failed; retrying existing task taskId=%s attempt=%s/%s: %s",
                    task_id,
                    attempt,
                    POLL_REQUEST_ATTEMPTS,
                    exc,
                )
                time.sleep(min(float(attempt), remaining))
                if deadline - time.monotonic() <= 0:
                    raise self._poll_deadline_error(task_id) from exc
        raise AgnesVideoError("Agnes video poll retry exhausted")

    def _request_result(
        self,
        *,
        task_id: str,
        video_id: str,
        model: str,
        request_timeout: Urllib3Timeout | tuple[float, float] | None = None,
    ) -> dict[str, Any]:
        mode = _normalized_option(self.result_query_mode)
        if mode in {"videoidquery", "agnesapi"}:
            return self._request(
                "GET",
                self.result_endpoint_path,
                params={"video_id": video_id, "model_name": model},
                request_timeout=request_timeout,
            )
        return self._request(
            "GET",
            self._task_result_path(task_id),
            params=None,
            request_timeout=request_timeout,
        )

    def _clip_request_timeout(self, remaining: float) -> Urllib3Timeout:
        connect_timeout, read_timeout = self.request_timeout
        return Urllib3Timeout(
            total=remaining,
            connect=min(float(connect_timeout), remaining),
            read=min(float(read_timeout), remaining),
        )

    @staticmethod
    def _poll_deadline_error(task_id: str) -> AgnesVideoTimeoutError:
        return AgnesVideoTimeoutError(f"Agnes video generation timed out while polling, taskId={task_id}")

    def _build_payload(
        self,
        *,
        prompt: str,
        image_size: str,
        negative_prompt: str,
        model: str,
        image: str,
        image_tail: str,
        images: list[str] | None,
        seed: int | None,
        duration: str,
        aspect_ratio: str,
        resolution: str,
        mode: str,
    ) -> dict[str, Any]:
        width, height = _parse_size(image_size, aspect_ratio=aspect_ratio)
        frame_rate = max(1, _as_int(self.extra_auth.get("defaultFrameRate"), 24))
        num_frames = self._num_frames(duration=duration, frame_rate=frame_rate)
        payload: dict[str, Any] = {
            "model": model,
            "prompt": prompt.strip(),
            "height": height,
            "width": width,
            "num_frames": num_frames,
            "frame_rate": frame_rate,
        }
        if negative_prompt.strip():
            payload["negative_prompt"] = negative_prompt.strip()
        if seed is not None:
            payload["seed"] = seed
        if resolution.strip():
            payload["resolution"] = resolution.strip()

        raw_images = [value for value in (images or []) if isinstance(value, str) and value.strip()]
        if not raw_images:
            raw_images = [value for value in (image, image_tail) if value and value.strip()]
        model_images = [self._image_to_model_input(value.strip()) for value in raw_images]
        mode = mode.strip()
        if len(model_images) > 1:
            extra_body: dict[str, Any] = {"image": model_images}
            if mode:
                extra_body["mode"] = mode
            payload["extra_body"] = extra_body
        elif model_images:
            payload["image"] = model_images[0]
        elif mode:
            payload["extra_body"] = {"mode": mode}
        return payload

    def _image_to_model_input(self, value: str) -> str:
        raw = value.strip()
        if not raw:
            return ""
        if raw.startswith("data:"):
            header, separator, encoded = raw.partition(",")
            if separator and ";base64" in header:
                return self._validate_base64(encoded)
            raise AgnesVideoError("Agnes video image data URL must be base64 encoded")
        if self._is_base64(raw):
            return self._validate_base64(raw)
        local_path = self._local_media_path(raw)
        if local_path is not None:
            return self._file_to_base64(local_path)
        if raw.startswith(("http://", "https://")):
            if _normalized_option(self.extra_auth.get("imageInputMode")) in {"base64", "rawbase64"}:
                return self._download_to_base64(raw)
            return raw
        if raw.startswith("/"):
            return self._download_to_base64(raw)
        possible_path = Path(raw)
        if possible_path.exists() and possible_path.is_file():
            return self._file_to_base64(possible_path)
        raise AgnesVideoError("Agnes video image must be base64, data URL, URL, or readable local file")

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
        media_root = Path(settings.generated_media_dir).resolve()
        candidate = media_root.joinpath(relative).resolve()
        if candidate.is_file() and candidate.is_relative_to(media_root):
            return candidate
        return None

    def _file_to_base64(self, path: Path) -> str:
        try:
            data = path.read_bytes()
        except OSError as exc:
            raise AgnesVideoError(f"could not read Agnes video input image: {path}") from exc
        return self._bytes_to_base64(data)

    def _download_to_base64(self, value: str) -> str:
        url = value
        if value.startswith("/"):
            url = f"{settings.backend_internal_base_url.rstrip('/')}{value}"
        try:
            with requests.get(url, stream=True, timeout=self.request_timeout, headers={"Accept": "image/*"}) as response:
                response.raise_for_status()
                chunks: list[bytes] = []
                total = 0
                for chunk in response.iter_content(chunk_size=1024 * 256):
                    if not chunk:
                        continue
                    total += len(chunk)
                    if total > self.max_input_image_bytes:
                        raise AgnesVideoError("Agnes video input image exceeds 20MB")
                    chunks.append(chunk)
        except AgnesVideoError:
            raise
        except requests.RequestException as exc:
            raise AgnesVideoError(f"could not download Agnes video input image: {value}") from exc
        return self._bytes_to_base64(b"".join(chunks))

    def _bytes_to_base64(self, data: bytes) -> str:
        if not data:
            raise AgnesVideoError("Agnes video input image is empty")
        if len(data) > self.max_input_image_bytes:
            raise AgnesVideoError("Agnes video input image exceeds 20MB")
        return base64.b64encode(data).decode("ascii")

    @staticmethod
    def _is_base64(value: str) -> bool:
        compact = "".join(value.split())
        if len(compact) < 16 or compact.startswith(("http://", "https://", "/")):
            return False
        try:
            base64.b64decode(compact, validate=True)
        except Exception:
            return False
        return True

    @staticmethod
    def _validate_base64(value: str) -> str:
        compact = "".join(value.split())
        try:
            base64.b64decode(compact, validate=True)
        except Exception as exc:
            raise AgnesVideoError("Agnes video image base64 is invalid") from exc
        return compact

    def _num_frames(self, *, duration: str, frame_rate: int) -> int:
        configured = _as_int(self.extra_auth.get("defaultNumFrames"), 0)
        seconds = _duration_seconds(duration)
        if seconds is not None:
            return max(1, min(441, seconds * frame_rate + 1))
        return configured or 121

    def _request(
        self,
        method: str,
        path: str,
        *,
        json_payload: dict[str, Any] | None = None,
        params: dict[str, Any] | None = None,
        request_timeout: Urllib3Timeout | tuple[float, float] | None = None,
    ) -> dict[str, Any]:
        url = f"{self.base_url}{_ensure_leading_slash(path)}"
        try:
            response = self.session.request(
                method,
                url,
                json=json_payload,
                params=params,
                headers=self._headers(),
                timeout=request_timeout if request_timeout is not None else self.request_timeout,
            )
        except requests.Timeout as exc:
            raise AgnesVideoTimeoutError("Agnes video request timed out") from exc
        except requests.RequestException as exc:
            raise AgnesVideoError(f"Agnes video request failed: {exc}") from exc

        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise AgnesVideoError(
                f"Agnes video request failed: status={response.status_code}, body={response.text}"
            ) from exc

        try:
            data = response.json()
        except ValueError as exc:
            raise AgnesVideoError("Agnes video returned non-json response") from exc
        if not isinstance(data, dict):
            raise AgnesVideoError("Agnes video returned invalid response")
        return data

    def _headers(self) -> dict[str, str]:
        return {
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Authorization": f"Bearer {self.api_key}",
        }

    def _task_result_path(self, task_id: str) -> str:
        path = self.result_endpoint_path.strip() or "/v1/videos/{task_id}"
        if "{task_id}" in path:
            return path.replace("{task_id}", task_id)
        if "{taskId}" in path:
            return path.replace("{taskId}", task_id)
        return f"{path.rstrip('/')}/{task_id}"

    @classmethod
    def _extract_task_id(cls, payload: dict[str, Any]) -> str:
        for key in ("task_id", "taskId", "id", "request_id", "requestId"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        data = payload.get("data")
        if isinstance(data, dict):
            return cls._extract_task_id(data)
        raise AgnesVideoError("Agnes video create response missing task id")

    @classmethod
    def _extract_video_id(cls, payload: dict[str, Any]) -> str:
        for key in ("video_id", "videoId"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        data = payload.get("data")
        if isinstance(data, dict):
            return cls._extract_video_id(data)
        return ""

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
    def _extract_progress_percent(cls, payload: dict[str, Any]) -> int | None:
        return cls._find_progress_percent(payload)

    @classmethod
    def _find_progress_percent(cls, value: Any, parent_key: str = "") -> int | None:
        progress_keys = {
            "progress",
            "percent",
            "percentage",
            "progress_percent",
            "progresspercentage",
            "progressrate",
            "completion",
        }
        if isinstance(value, dict):
            for key, nested in value.items():
                normalized_key = str(key).replace("-", "_").lower()
                if normalized_key in progress_keys:
                    parsed = cls._parse_progress_value(nested)
                    if parsed is not None:
                        return parsed
                found = cls._find_progress_percent(nested, normalized_key)
                if found is not None:
                    return found
            return None
        if isinstance(value, list):
            for item in value:
                found = cls._find_progress_percent(item, parent_key)
                if found is not None:
                    return found
        return None

    @staticmethod
    def _parse_progress_value(value: Any) -> int | None:
        if isinstance(value, bool) or value is None:
            return None
        if isinstance(value, (int, float)):
            numeric = float(value)
        elif isinstance(value, str):
            text = value.strip().replace("%", "")
            if not text:
                return None
            try:
                numeric = float(text)
            except ValueError:
                return None
        else:
            return None
        if 0 < numeric <= 1:
            numeric *= 100
        if numeric < 0:
            return None
        return max(0, min(100, int(round(numeric))))

    @classmethod
    def _extract_video_url(cls, payload: dict[str, Any]) -> str:
        url = cls._extract_video_url_or_empty(payload)
        if url:
            return url
        raise AgnesVideoError(cls._describe_response_problem("Agnes video response missing video url", payload))

    @classmethod
    def _extract_video_url_or_empty(cls, payload: dict[str, Any]) -> str:
        for value in cls._collect_video_urls(payload):
            return value
        return ""

    @classmethod
    def _collect_video_urls(cls, value: Any, parent_key: str = "") -> list[str]:
        urls: list[str] = []
        if isinstance(value, dict):
            for key, nested in value.items():
                urls.extend(cls._collect_video_urls(nested, str(key)))
            return urls
        if isinstance(value, list):
            for item in value:
                urls.extend(cls._collect_video_urls(item, parent_key))
            return urls
        if not isinstance(value, str):
            return urls
        candidate = value.strip()
        if cls._looks_like_video_url(candidate):
            return [candidate]
        if cls._looks_like_video_url_field(parent_key, candidate):
            return [candidate]
        return urls

    @staticmethod
    def _looks_like_video_url(value: str) -> bool:
        lowered = value.lower().split("?", 1)[0]
        return lowered.startswith(("http://", "https://")) and lowered.endswith((".mp4", ".mov", ".webm", ".m3u8"))

    @staticmethod
    def _looks_like_video_url_field(key: str, value: str) -> bool:
        lowered_key = key.replace("-", "_").lower()
        lowered_value = value.lower().split("?", 1)[0]
        if not lowered_value.startswith(("http://", "https://")):
            return False
        if lowered_value.endswith((".jpg", ".jpeg", ".png", ".webp", ".gif")):
            return False
        return lowered_key in {
            "url",
            "video",
            "video_url",
            "videourl",
            "result_url",
            "resulturl",
            "download_url",
            "downloadurl",
            "remixed_from_video_id",
        }

    @classmethod
    def _describe_response_problem(cls, prefix: str, payload: dict[str, Any]) -> str:
        payload_text = _json_for_log(payload)
        if len(payload_text) > 1600:
            payload_text = payload_text[:1600] + "...<truncated>"
        return f"{prefix}; payload={payload_text}"

    @staticmethod
    def _parse_json(value: str | None) -> dict[str, Any]:
        if not value or not value.strip():
            return {}
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError:
            return {}
        return parsed if isinstance(parsed, dict) else {}

    @staticmethod
    def _normalize_base_url(value: str) -> str:
        base_url = value.strip().rstrip("/")
        if base_url.endswith("/v1"):
            return base_url[:-3]
        return base_url


def _parse_size(value: str, *, aspect_ratio: str = "") -> tuple[int, int]:
    raw = str(value or "").lower().strip()
    if "x" in raw:
        left, _, right = raw.partition("x")
        try:
            width = max(1, int(left.strip()))
            height = max(1, int(right.strip()))
            return width, height
        except ValueError:
            pass
    ratio = str(aspect_ratio or raw or "16:9").strip()
    return {
        "1:1": (960, 960),
        "16:9": (1280, 720),
        "9:16": (720, 1280),
        "4:3": (1024, 768),
        "3:4": (768, 1024),
        "3:2": (1152, 768),
        "2:3": (768, 1152),
    }.get(ratio, (1280, 720))


def _duration_seconds(duration: str) -> int | None:
    digits = "".join(char for char in str(duration) if char.isdigit())
    if not digits:
        return None
    return max(1, int(digits))


def _ensure_leading_slash(value: str) -> str:
    return value if value.startswith("/") else f"/{value}"


def _as_int(value: Any, fallback: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return fallback


def _as_float(value: Any, fallback: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return fallback


def _normalized_option(value: Any) -> str:
    return str(value or "").strip().lower().replace("_", "").replace("-", "")


def _json_for_log(value: Any) -> str:
    try:
        return json.dumps(_sanitize_for_log(value), ensure_ascii=False, separators=(",", ":"))[:4000]
    except Exception:
        return "<unserializable>"


def _sanitize_for_log(value: Any) -> Any:
    if isinstance(value, dict):
        sanitized: dict[str, Any] = {}
        for key, nested in value.items():
            key_text = str(key)
            if any(secret in key_text.lower() for secret in ("key", "secret", "token", "authorization")):
                sanitized[key_text] = "***"
            else:
                sanitized[key_text] = _sanitize_for_log(nested)
        return sanitized
    if isinstance(value, list):
        return [_sanitize_for_log(item) for item in value]
    if isinstance(value, str) and len(value) > 800:
        return value[:800] + f"...<{len(value)} chars>"
    return value
