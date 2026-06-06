from __future__ import annotations

import base64
from dataclasses import dataclass
import logging
import mimetypes
import time
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import requests

from config import settings


LOGGER = logging.getLogger(__name__)


class SunoMusicError(RuntimeError):
    pass


class SunoMusicTimeoutError(SunoMusicError):
    pass


@dataclass(frozen=True)
class SunoTrack:
    audio_url: str
    title: str | None = None
    duration: float | None = None
    source_audio_id: str | None = None
    image_url: str | None = None
    stream_audio_url: str | None = None
    metadata: dict[str, Any] | None = None


@dataclass(frozen=True)
class SunoGenerationResult:
    task_id: str
    tracks: list[SunoTrack]
    metadata: dict[str, Any] | None = None


class SunoMusicClient:
    def __init__(self) -> None:
        self.timeout = (5, 60)
        self.poll_interval_seconds = settings.suno_poll_interval_seconds
        self.timeout_seconds = settings.suno_timeout_seconds

    def generate(
        self,
        *,
        model: str,
        prompt: str,
        base_url: str | None,
        api_key: str | None,
        params: dict[str, Any],
    ) -> SunoGenerationResult:
        resolved_api_key = _resolve_api_key(api_key)
        if not resolved_api_key:
            raise SunoMusicError("Suno api key is not configured")

        root_url = (base_url or settings.suno_base_url or "https://api.sunoapi.org").rstrip("/")
        headers = {
            "Authorization": f"Bearer {resolved_api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": "ai-tool-market-worker/suno-music",
        }
        generation_type = _resolve_generation_type(params)
        upload_source = _resolve_upload_source(params)
        custom_mode = _bool_param(params, "customMode", "custom_mode", default=False)
        instrumental = _bool_param(params, "instrumental", "makeInstrumental", "isInstrumental", default=False)
        prompt_text = prompt.strip()

        if generation_type == "upload_cover" or upload_source:
            if not upload_source:
                raise SunoMusicError("upload cover requires reference audio URL or file")
            if not custom_mode and not prompt_text:
                raise SunoMusicError("upload cover simple mode requires prompt")
            if custom_mode and not instrumental and not prompt_text:
                raise SunoMusicError("upload cover advanced mode requires lyrics when instrumental is false")
        elif not prompt_text:
            raise SunoMusicError("music prompt is required")

        payload = self._build_payload(
            model=model,
            prompt=prompt_text,
            params=params,
            api_key=resolved_api_key,
        )
        upload_url = payload.get("uploadUrl")
        mode_label = "upload-cover" if upload_url else "generate"
        LOGGER.info(
            "Suno %s request model=%s customMode=%s upload=%s",
            mode_label,
            payload.get("model"),
            payload.get("customMode"),
            bool(upload_url),
        )
        task_id = self._create_task(root_url=root_url, headers=headers, payload=payload, upload_cover=bool(upload_url))
        tracks, detail = self._poll_task(root_url=root_url, headers=headers, task_id=task_id)
        return SunoGenerationResult(task_id=task_id, tracks=tracks, metadata={"record": detail, "payload": _safe_payload(payload)})

    def _create_task(
        self,
        *,
        root_url: str,
        headers: dict[str, str],
        payload: dict[str, Any],
        upload_cover: bool = False,
    ) -> str:
        path = "/api/v1/generate/upload-cover" if upload_cover else "/api/v1/generate"
        url = f"{root_url}{path}"
        response = self._request_with_retry("POST", url, headers=headers, json=payload)
        data = _json_response(response)
        task_id = _string_from_path(data, "data.taskId", "taskId", "id")
        if not task_id:
            raise SunoMusicError("Suno generate response missing taskId")
        return task_id

    def _poll_task(self, *, root_url: str, headers: dict[str, str], task_id: str) -> tuple[list[SunoTrack], dict[str, Any]]:
        deadline = time.monotonic() + max(self.timeout_seconds, 30)
        url = f"{root_url}/api/v1/generate/record-info"
        last_status = ""
        while True:
            response = self._request_with_retry("GET", url, headers=headers, params={"taskId": task_id})
            data = _json_response(response)
            detail = data.get("data") if isinstance(data.get("data"), dict) else data
            status = str(detail.get("status") or data.get("status") or "").upper()
            last_status = status or last_status
            if status == "SUCCESS":
                tracks = _extract_tracks(detail)
                if not tracks:
                    LOGGER.warning(
                        "Suno task %s SUCCESS but no tracks parsed; record keys=%s response keys=%s",
                        task_id,
                        sorted(detail.keys()),
                        sorted((detail.get("response") or {}).keys()) if isinstance(detail.get("response"), dict) else [],
                    )
                    raise SunoMusicError(f"Suno task {task_id} succeeded without audioUrl")
                return tracks, detail
            if status in _FAILURE_STATUSES:
                message = (
                    detail.get("errorMessage")
                    or detail.get("failReason")
                    or data.get("message")
                    or data.get("msg")
                    or f"Suno task {task_id} failed"
                )
                raise SunoMusicError(str(message))
            if time.monotonic() >= deadline:
                raise SunoMusicTimeoutError(f"Suno task timed out: taskId={task_id} status={last_status or 'UNKNOWN'}")
            time.sleep(self.poll_interval_seconds)

    def _request_with_retry(self, method: str, url: str, **kwargs: Any) -> requests.Response:
        last_exc: Exception | None = None
        for attempt in range(3):
            try:
                response = requests.request(method, url, timeout=self.timeout, **kwargs)
                if response.status_code in {405, 430, 500, 502, 503, 504} and attempt < 2:
                    time.sleep(1.5 * (attempt + 1))
                    continue
                _raise_for_status(response)
                return response
            except requests.RequestException as exc:
                last_exc = exc
                if attempt < 2:
                    time.sleep(1.5 * (attempt + 1))
                    continue
                raise SunoMusicError(f"Suno request failed: {exc}") from exc
        raise SunoMusicError(f"Suno request failed: {last_exc}")

    def _build_payload(
        self,
        *,
        model: str,
        prompt: str,
        params: dict[str, Any],
        api_key: str,
    ) -> dict[str, Any]:
        custom_mode = _bool_param(params, "customMode", "custom_mode", default=False)
        instrumental = _bool_param(params, "instrumental", "makeInstrumental", "isInstrumental", default=False)
        resolved_model = _normalize_suno_model(_string_param(params, "model", "sunoModel", default=model or settings.suno_music_model or "V5"))
        generation_type = _resolve_generation_type(params)
        upload_source = _resolve_upload_source(params)
        is_upload_cover = generation_type == "upload_cover" or bool(upload_source)
        upload_url = ""
        if is_upload_cover:
            upload_url = _ensure_suno_upload_url(upload_source, api_key=api_key)

        payload: dict[str, Any] = {
            "customMode": custom_mode,
            "instrumental": instrumental,
            "model": resolved_model,
            "callBackUrl": _resolve_callback_url(params),
        }
        if prompt.strip():
            payload["prompt"] = prompt.strip()
        if upload_url:
            payload["uploadUrl"] = upload_url

        if not custom_mode:
            return payload

        title = _string_param(params, "title", "songTitle", default="")
        style = _string_param(params, "style", "tags", "genre", default="")
        negative_tags = _string_param(params, "negativeTags", "negative_tags", default="")
        vocal_gender = _normalize_vocal_gender(_string_param(params, "vocalGender", "vocal_gender", default=""))
        persona_id = _string_param(params, "personaId", "persona_id", default="")
        persona_model = _string_param(params, "personaModel", "persona_model", default="")
        if title:
            payload["title"] = title
        if style:
            payload["style"] = style
        if negative_tags:
            payload["negativeTags"] = negative_tags
        if vocal_gender:
            payload["vocalGender"] = vocal_gender
        if persona_id:
            payload["personaId"] = persona_id
        if persona_model:
            payload["personaModel"] = persona_model
        for key in ("styleWeight", "weirdnessConstraint", "audioWeight"):
            number = _number_param(params, key)
            if number is not None:
                payload[key] = number
        return {key: value for key, value in payload.items() if value != ""}


def _resolve_generation_type(params: dict[str, Any]) -> str:
    raw = _string_param(params, "generationType", "generation_type", "musicTaskType", default="generate").lower()
    if raw in {"upload_cover", "upload-cover", "cover", "upload"}:
        return "upload_cover"
    return "generate"


def _resolve_upload_source(params: dict[str, Any]) -> str:
    return _string_param(
        params,
        "uploadUrl",
        "upload_url",
        "referenceAudio",
        "reference_audio",
        "referenceAudioUrl",
        default="",
    )


def _ensure_suno_upload_url(source: str, *, api_key: str) -> str:
    normalized = (source or "").strip()
    if not normalized:
        return ""
    if normalized.startswith("http://") or normalized.startswith("https://"):
        if not _should_reupload_to_suno(normalized):
            return normalized
    audio_bytes, content_type = _read_audio_bytes(normalized)
    extension = _guess_audio_extension(normalized, content_type)
    file_name = f"reference{extension}"
    return _upload_base64_to_suno(api_key=api_key, audio_bytes=audio_bytes, content_type=content_type, file_name=file_name)


def _should_reupload_to_suno(url: str) -> bool:
    lowered = url.lower()
    if lowered.startswith("data:"):
        return True
    if any(token in lowered for token in ("localhost", "127.0.0.1", "0.0.0.0")):
        return True
    if "tempfile." in lowered or "redpandaai.co" in lowered:
        return False
    parsed = urlparse(url)
    if parsed.path.startswith("/generated/"):
        return True
    return False


def _read_audio_bytes(source: str) -> tuple[bytes, str | None]:
    if source.startswith("data:"):
        header, separator, encoded = source.partition(",")
        if not separator or ";base64" not in header:
            raise SunoMusicError("reference audio data url is invalid")
        content_type = header.removeprefix("data:").split(";", 1)[0].strip().lower() or None
        try:
            return base64.b64decode(encoded), content_type
        except ValueError as exc:
            raise SunoMusicError("reference audio data url decode failed") from exc

    fetch_url = source
    if source.startswith("/"):
        backend = (settings.backend_internal_base_url or "").rstrip("/")
        if not backend:
            raise SunoMusicError("reference audio relative URL requires BACKEND_INTERNAL_BASE_URL")
        fetch_url = f"{backend}{source}"

    try:
        response = requests.get(fetch_url, timeout=(10, 120))
        response.raise_for_status()
    except requests.RequestException as exc:
        raise SunoMusicError(f"download reference audio failed: {exc}") from exc
    content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() or None
    if not response.content:
        raise SunoMusicError("reference audio file is empty")
    return response.content, content_type


def _guess_audio_extension(source: str, content_type: str | None) -> str:
    if content_type:
        extension = mimetypes.guess_extension(content_type) or ""
        if extension == ".mpeg":
            return ".mp3"
        if extension:
            return extension
    suffix = Path(urlparse(source).path).suffix.lower()
    return suffix if suffix else ".mp3"


def _upload_base64_to_suno(*, api_key: str, audio_bytes: bytes, content_type: str | None, file_name: str) -> str:
    root_url = (settings.suno_file_upload_base_url or "https://sunoapiorg.redpandaai.co").rstrip("/")
    url = f"{root_url}/api/file-base64-upload"
    mime = content_type or "audio/mpeg"
    encoded = base64.b64encode(audio_bytes).decode("ascii")
    payload = {
        "base64Data": f"data:{mime};base64,{encoded}",
        "uploadPath": "aidesu/audio",
        "fileName": file_name,
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "Accept": "application/json",
        "User-Agent": "ai-tool-market-worker/suno-upload",
    }
    try:
        response = requests.post(url, headers=headers, json=payload, timeout=(10, 180))
        response.raise_for_status()
    except requests.RequestException as exc:
        raise SunoMusicError(f"Suno file upload failed: {exc}") from exc
    data = _json_response(response, allow_file_upload=True)
    download_url = _string_from_path(data, "data.downloadUrl", "downloadUrl")
    if not download_url:
        raise SunoMusicError("Suno file upload response missing downloadUrl")
    return download_url


def _resolve_callback_url(params: dict[str, Any]) -> str:
    configured = _string_param(params, "callBackUrl", "callbackUrl", default="")
    if configured:
        return configured
    fallback = (settings.suno_callback_url or "").strip()
    if fallback:
        return fallback
    # Suno API requires callBackUrl even when the worker polls record-info instead.
    return "https://example.com/suno-callback"


def _normalize_suno_model(model: str) -> str:
    normalized = (model or "").strip().upper()
    if not normalized:
        return settings.suno_music_model or "V5"
    aliases = {
        "V5.5": "V5_5",
        "CHIRP-V5": "V5",
    }
    return aliases.get(normalized, normalized)


def _resolve_api_key(configured: str | None) -> str:
    value = (configured or "").strip()
    if value and not value.startswith("replace-with-"):
        return value
    return (settings.suno_api_key or "").strip()


def _raise_for_status(response: requests.Response) -> None:
    if response.status_code < 400:
        return
    message = response.text[:500] if response.text else response.reason
    if response.status_code == 413:
        raise SunoMusicError(f"Suno request validation failed: {message}")
    if response.status_code == 429:
        raise SunoMusicError(f"Suno quota or rate limit reached: {message}")
    response.raise_for_status()


def _json_response(response: requests.Response, *, allow_file_upload: bool = False) -> dict[str, Any]:
    try:
        data = response.json()
    except ValueError as exc:
        raise SunoMusicError("Suno returned non-json response") from exc
    if not isinstance(data, dict):
        raise SunoMusicError("Suno returned invalid response")
    code = data.get("code")
    success = data.get("success")
    if allow_file_upload:
        if success is False or code not in (None, 200, 0, "200", "0"):
            raise SunoMusicError(str(data.get("message") or data.get("msg") or f"Suno upload error: {code}"))
        return data
    if code not in (None, 200, 0, "200", "0"):
        raise SunoMusicError(str(data.get("message") or data.get("msg") or f"Suno api error: {code}"))
    return data


_FAILURE_STATUSES = {
    "FAILED",
    "ERROR",
    "CANCELLED",
    "CREATE_TASK_FAILED",
    "GENERATE_AUDIO_FAILED",
    "CALLBACK_EXCEPTION",
    "SENSITIVE_WORD_ERROR",
}


def _extract_tracks(detail: dict[str, Any]) -> list[SunoTrack]:
    raw_tracks = _extract_track_items(detail)
    tracks: list[SunoTrack] = []
    for item in raw_tracks:
        if not isinstance(item, dict):
            continue
        audio_url = _string_value(
            item,
            "audioUrl",
            "audio_url",
            "sourceAudioUrl",
            "streamAudioUrl",
            "stream_audio_url",
        )
        if not audio_url:
            continue
        tracks.append(
            SunoTrack(
                audio_url=audio_url,
                title=_string_value(item, "title", "name"),
                duration=_number_value(item, "duration"),
                source_audio_id=_string_value(item, "id", "audioId", "sourceAudioId"),
                image_url=_string_value(item, "imageUrl", "image_url", "source_image_url"),
                stream_audio_url=_string_value(item, "streamAudioUrl", "stream_audio_url"),
                metadata=item,
            )
        )
    return tracks


def _extract_track_items(detail: dict[str, Any]) -> list[Any]:
    response = detail.get("response")
    if isinstance(response, dict):
        for key in ("sunoData", "audioData", "data"):
            raw = response.get(key)
            if isinstance(raw, list):
                return raw
    for key in ("sunoData", "audioData", "data"):
        raw = detail.get(key)
        if isinstance(raw, list):
            return raw
    return []


def _string_param(params: dict[str, Any], *keys: str, default: str = "") -> str:
    for key in keys:
        value = params.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return default


def _bool_param(params: dict[str, Any], *keys: str, default: bool = False) -> bool:
    for key in keys:
        value = params.get(key)
        if isinstance(value, bool):
            return value
        if value is not None and str(value).strip():
            return str(value).strip().lower() in {"1", "true", "yes", "on", "是", "纯音乐"}
    return default


def _normalize_vocal_gender(value: str) -> str:
    normalized = (value or "").strip().lower()
    if normalized in {"m", "male", "man", "男", "男声"}:
        return "m"
    if normalized in {"f", "female", "woman", "女", "女声"}:
        return "f"
    return ""


def _number_param(params: dict[str, Any], key: str) -> float | None:
    value = params.get(key)
    if value is None or not str(value).strip():
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _string_value(item: dict[str, Any], *keys: str) -> str | None:
    for key in keys:
        value = item.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return None


def _number_value(item: dict[str, Any], key: str) -> float | None:
    value = item.get(key)
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _string_from_path(data: dict[str, Any], *paths: str) -> str:
    for path in paths:
        current: Any = data
        for part in path.split("."):
            if not isinstance(current, dict):
                current = None
                break
            current = current.get(part)
        if current is not None and str(current).strip():
            return str(current).strip()
    return ""


def _safe_payload(payload: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in payload.items() if "key" not in key.lower() and "token" not in key.lower()}
