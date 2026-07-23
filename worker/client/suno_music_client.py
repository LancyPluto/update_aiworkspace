from __future__ import annotations

import base64
from dataclasses import dataclass
import logging
import math
import mimetypes
import time
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlparse

import requests

from config import settings
from utils.outbound_http import OutboundRequestsClient
from utils.model_contract import parse_response_mapping, read_response_value, response_mapping_has


LOGGER = logging.getLogger(__name__)
NON_CUSTOM_PROMPT_LIMIT = 500
V5_5_MIN_DURATION_SECONDS = 10
V5_5_MAX_DURATION_SECONDS = 360

SUNO_GENERATION_PATHS = {
    "generate": "/api/v1/generate",
    "upload_cover": "/api/v1/generate/upload-cover",
    "extend": "/api/v1/generate/extend",
    "upload_extend": "/api/v1/generate/upload-extend",
    "add_vocals": "/api/v1/generate/add-vocals",
    "add_instrumental": "/api/v1/generate/add-instrumental",
    "replace_section": "/api/v1/generate/replace-section",
}


class SunoMusicError(RuntimeError):
    pass


class SunoMusicInputError(SunoMusicError):
    pass


class SunoMusicTimeoutError(SunoMusicError):
    pass


class SunoMusicTransportError(SunoMusicError):
    pass


class SunoMusicSubmissionUnknownError(SunoMusicTimeoutError):
    """The create request may have reached Suno, but its response was not received."""


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
        self.poll_timeout = (5, 15)
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
        extra_auth_json: str | None = None,
        model_config: dict[str, Any] | None = None,
        callback_url: str | None = None,
        resume_task_id: str | None = None,
        submitted_callback: Callable[[str], None] | None = None,
        callback_result_loader: Callable[[], dict[str, Any] | None] | None = None,
    ) -> SunoGenerationResult:
        http = OutboundRequestsClient.from_model_config(model_config, extra_auth_json=extra_auth_json)
        try:
            return self._generate_with_http(
                model=model,
                prompt=prompt,
                base_url=base_url,
                api_key=api_key,
                params=params,
                http=http,
                callback_url=callback_url,
                resume_task_id=resume_task_id,
                submitted_callback=submitted_callback,
                callback_result_loader=callback_result_loader,
                response_mapping=parse_response_mapping(model_config),
            )
        finally:
            http.close()

    def _generate_with_http(
        self,
        *,
        model: str,
        prompt: str,
        base_url: str | None,
        api_key: str | None,
        params: dict[str, Any],
        http: OutboundRequestsClient,
        callback_url: str | None,
        resume_task_id: str | None,
        submitted_callback: Callable[[str], None] | None,
        callback_result_loader: Callable[[], dict[str, Any] | None] | None,
        response_mapping: dict[str, Any],
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
        prompt_text = prompt.strip()
        generation_mode = _resolve_generation_mode(params)

        callback_result = _generation_result_from_callback(_load_callback(callback_result_loader))
        if callback_result is not None:
            return callback_result

        effective_params = dict(params)
        if callback_url:
            effective_params["callBackUrl"] = callback_url
        payload = self._build_payload(
            model=model,
            prompt=prompt_text,
            params=effective_params,
            api_key=resolved_api_key,
            http=http,
        )
        LOGGER.info(
            "Suno %s request model=%s customMode=%s upload=%s",
            generation_mode.replace("_", "-"),
            payload.get("model"),
            payload.get("customMode"),
            bool(payload.get("uploadUrl")),
        )
        task_id = (resume_task_id or "").strip()
        if not task_id:
            try:
                task_id = self._create_task(
                    root_url=root_url,
                    headers=headers,
                    payload=payload,
                    path=SUNO_GENERATION_PATHS[generation_mode],
                    http=http,
                    response_mapping=response_mapping,
                )
            except SunoMusicSubmissionUnknownError as exc:
                recovered = self._wait_for_callback(callback_result_loader)
                if recovered is not None:
                    return recovered
                raise SunoMusicSubmissionUnknownError(
                    "Suno create response timed out; delivery is unknown and no callback arrived before the provider deadline"
                ) from exc
            if submitted_callback:
                submitted_callback(task_id)
        tracks, detail = self._poll_task(
            root_url=root_url,
            headers=headers,
            task_id=task_id,
            http=http,
            response_mapping=response_mapping,
            callback_result_loader=callback_result_loader,
        )
        return SunoGenerationResult(
            task_id=task_id,
            tracks=tracks,
            metadata={
                "generationMode": generation_mode,
                "record": detail,
                "payload": _safe_payload(payload),
            },
        )

    def _wait_for_callback(
        self,
        callback_result_loader: Callable[[], dict[str, Any] | None] | None,
    ) -> SunoGenerationResult | None:
        if callback_result_loader is None:
            return None
        deadline = time.monotonic() + max(self.timeout_seconds, 30)
        while time.monotonic() < deadline:
            recovered = _generation_result_from_callback(_load_callback(callback_result_loader))
            if recovered is not None:
                return recovered
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(self.poll_interval_seconds, remaining))
        return None

    def _create_task(
        self,
        *,
        root_url: str,
        headers: dict[str, str],
        payload: dict[str, Any],
        path: str,
        http: OutboundRequestsClient,
        response_mapping: dict[str, Any] | None = None,
    ) -> str:
        url = f"{root_url}{path}"
        response = self._request_with_retry("POST", url, http=http, headers=headers, json=payload)
        data = _json_response(response)
        if response_mapping_has(response_mapping, "requestIdPath", "requestIdPaths"):
            task_id = str(
                read_response_value(data, response_mapping, "requestIdPath", "requestIdPaths")
                or ""
            ).strip()
        else:
            task_id = _string_from_path(data, "data.taskId", "taskId", "id")
        if not task_id:
            raise SunoMusicError(f"Suno {path.rsplit('/', 1)[-1]} response missing taskId")
        return task_id

    def _poll_task(
        self,
        *,
        root_url: str,
        headers: dict[str, str],
        task_id: str,
        http: OutboundRequestsClient,
        response_mapping: dict[str, Any] | None = None,
        callback_result_loader: Callable[[], dict[str, Any] | None] | None = None,
    ) -> tuple[list[SunoTrack], dict[str, Any]]:
        deadline = time.monotonic() + max(self.timeout_seconds, 30)
        url = f"{root_url}/api/v1/generate/record-info"
        last_status = ""
        while True:
            recovered = _generation_result_from_callback(_load_callback(callback_result_loader))
            if recovered is not None:
                if recovered.task_id != task_id:
                    raise SunoMusicError("Suno callback task id does not match submitted task id")
                return recovered.tracks, {"status": "SUCCESS", "callback": recovered.metadata}
            try:
                response = self._request_with_retry("GET", url, http=http, headers=headers, params={"taskId": task_id})
            except SunoMusicTransportError as exc:
                if time.monotonic() >= deadline:
                    raise SunoMusicTimeoutError(
                        f"Suno task timed out after polling transport failures: taskId={task_id}"
                    ) from exc
                LOGGER.warning("Suno poll transport failed; keeping existing task taskId=%s: %s", task_id, exc)
                time.sleep(min(self.poll_interval_seconds, max(0.0, deadline - time.monotonic())))
                continue
            data = _json_response(response)
            detail = data.get("data") if isinstance(data.get("data"), dict) else data
            if response_mapping_has(response_mapping, "statusPath", "statusPaths"):
                status = str(
                    read_response_value(data, response_mapping, "statusPath", "statusPaths")
                    or ""
                ).upper()
                if not status:
                    raise SunoMusicError(f"Suno task {task_id} response missing mapped status")
            else:
                status = str(detail.get("status") or data.get("status") or "").upper()
            last_status = status or last_status
            if status == "SUCCESS":
                tracks = (
                    _extract_tracks(data, response_mapping)
                    if response_mapping_has(response_mapping, "itemsPath")
                    else _extract_tracks(detail)
                )
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

    def _request_with_retry(self, method: str, url: str, *, http: OutboundRequestsClient, **kwargs: Any) -> requests.Response:
        last_exc: Exception | None = None
        for attempt in range(3):
            try:
                request_timeout = self.poll_timeout if method.upper() == "GET" else self.timeout
                response = http.request(method, url, timeout=request_timeout, **kwargs)
                if method.upper() == "GET" and response.status_code in {405, 430, 500, 502, 503, 504} and attempt < 2:
                    time.sleep(1.5 * (attempt + 1))
                    continue
                _raise_for_status(response)
                return response
            except requests.ConnectTimeout as exc:
                last_exc = exc
                if attempt < 2:
                    time.sleep(1.5 * (attempt + 1))
                    continue
                if method.upper() == "POST":
                    raise SunoMusicTransportError(f"Suno create connection timed out before delivery: {exc}") from exc
                raise SunoMusicTransportError(f"Suno poll connection timed out: {exc}") from exc
            except requests.ReadTimeout as exc:
                if method.upper() == "POST":
                    raise SunoMusicSubmissionUnknownError(f"Suno create response timed out: {exc}") from exc
                last_exc = exc
                if attempt < 2:
                    time.sleep(1.5 * (attempt + 1))
                    continue
                raise SunoMusicTransportError(f"Suno poll response timed out: {exc}") from exc
            except requests.HTTPError as exc:
                raise SunoMusicError(f"Suno request rejected: {exc}") from exc
            except requests.RequestException as exc:
                last_exc = exc
                if method.upper() == "POST":
                    raise SunoMusicSubmissionUnknownError(
                        f"Suno create delivery state is unknown after transport failure: {exc}"
                    ) from exc
                if attempt < 2:
                    time.sleep(1.5 * (attempt + 1))
                    continue
                raise SunoMusicTransportError(f"Suno poll request failed: {exc}") from exc
        raise SunoMusicTransportError(f"Suno request failed: {last_exc}")

    def _build_payload(
        self,
        *,
        model: str,
        prompt: str,
        params: dict[str, Any],
        api_key: str,
        http: OutboundRequestsClient | None = None,
    ) -> dict[str, Any]:
        generation_mode = _resolve_generation_mode(params)
        resolved_model = _normalize_suno_model(
            _string_param(
                params,
                "model",
                "sunoModel",
                default=model or settings.suno_music_model or "V5",
            )
        )
        if generation_mode in {"generate", "upload_cover"}:
            return _build_generate_payload(
                generation_mode=generation_mode,
                model=resolved_model,
                prompt=prompt,
                params=params,
                api_key=api_key,
                http=http,
            )
        if generation_mode in {"extend", "upload_extend"}:
            return _build_extend_payload(
                generation_mode=generation_mode,
                model=resolved_model,
                prompt=prompt,
                params=params,
                api_key=api_key,
                http=http,
            )
        if generation_mode == "add_vocals":
            return _build_add_vocals_payload(
                model=resolved_model,
                prompt=prompt,
                params=params,
                api_key=api_key,
                http=http,
            )
        if generation_mode == "add_instrumental":
            return _build_add_instrumental_payload(
                model=resolved_model,
                params=params,
                api_key=api_key,
                http=http,
            )
        return _build_replace_section_payload(
            model=resolved_model,
            prompt=prompt,
            params=params,
            api_key=api_key,
            http=http,
        )


def _build_generate_payload(
    *,
    generation_mode: str,
    model: str,
    prompt: str,
    params: dict[str, Any],
    api_key: str,
    http: OutboundRequestsClient | None,
) -> dict[str, Any]:
    custom_mode = _bool_param(params, "customMode", "custom_mode", default=False)
    instrumental = _bool_param(params, "instrumental", "makeInstrumental", "isInstrumental", default=False)
    prompt_text = prompt.strip()
    if custom_mode:
        style = _required_text(params, "style", "tags", "genre", label="style")
        title = _required_text(params, "title", "songTitle", label="title")
        if not instrumental and not prompt_text:
            raise SunoMusicInputError(
                f"Suno {generation_mode.replace('_', '-')} custom mode requires lyrics when instrumental is false"
            )
        if len(prompt_text) > 5000:
            raise SunoMusicInputError("Suno V5_5 custom mode prompt must be 5000 characters or fewer")
    else:
        style = ""
        title = ""
        if not prompt_text:
            raise SunoMusicInputError(f"Suno {generation_mode.replace('_', '-')} simple mode requires prompt")
        if len(prompt_text) > NON_CUSTOM_PROMPT_LIMIT:
            raise SunoMusicInputError(
                "Suno non-custom mode prompt must be 500 characters or fewer; "
                "use customMode=true for long lyrics or detailed song structure."
            )

    payload: dict[str, Any] = {
        "customMode": custom_mode,
        "instrumental": instrumental,
        "model": model,
        "callBackUrl": _resolve_callback_url(params),
    }
    if prompt_text:
        payload["prompt"] = prompt_text
    upload_source = ""
    if generation_mode == "upload_cover":
        upload_source = _required_text(
            params,
            "referenceAudio",
            "reference_audio",
            "referenceAudioUrl",
            "uploadUrl",
            "upload_url",
            label="reference audio",
        )
    if custom_mode:
        payload["style"] = style
        payload["title"] = title
        if generation_mode == "generate":
            duration = _resolve_v5_5_duration(params, model)
            if duration is not None:
                payload["duration"] = duration
        _add_generation_controls(payload, params, include_persona=True)
    if upload_source:
        payload["uploadUrl"] = _ensure_suno_upload_url(
            upload_source,
            api_key=api_key,
            http=http,
        )
    return payload


def _build_extend_payload(
    *,
    generation_mode: str,
    model: str,
    prompt: str,
    params: dict[str, Any],
    api_key: str,
    http: OutboundRequestsClient | None,
) -> dict[str, Any]:
    default_param_flag = _bool_param(params, "defaultParamFlag", "default_param_flag", default=False)
    payload: dict[str, Any] = {
        "defaultParamFlag": default_param_flag,
        "model": model,
        "callBackUrl": _resolve_callback_url(params),
    }
    upload_source = ""
    if generation_mode == "extend":
        payload["audioId"] = _required_text(
            params,
            "audioId",
            "extendAudioId",
            "audio_id",
            label="audioId",
        )
    else:
        upload_source = _required_text(
            params,
            "referenceAudio",
            "reference_audio",
            "referenceAudioUrl",
            "uploadUrl",
            "upload_url",
            label="reference audio",
        )

    prompt_text = prompt.strip()
    instrumental = False
    if generation_mode == "upload_extend":
        instrumental = _bool_param(
            params,
            "instrumental",
            "makeInstrumental",
            "isInstrumental",
            default=False,
        )
        payload["instrumental"] = instrumental

    if default_param_flag:
        if generation_mode == "extend" or not instrumental:
            prompt_text = _required_prompt(
                prompt_text,
                f"{generation_mode.replace('_', '-')} custom parameters",
            )
        payload.update(
            {
                "style": _required_text(params, "style", "genre", label="style"),
                "title": _required_text(params, "title", "songTitle", label="title"),
            }
        )
        if prompt_text:
            payload["prompt"] = prompt_text
        if generation_mode == "extend":
            payload["continueAt"] = _required_positive_number(
                params,
                "continueAt",
                "continue_at",
                label="continueAt",
            )
        elif _string_param(params, "continueAt", "continue_at", default=""):
            payload["continueAt"] = _required_positive_number(
                params,
                "continueAt",
                "continue_at",
                label="continueAt",
            )
        _add_generation_controls(payload, params, include_persona=True)
    elif generation_mode == "upload_extend":
        payload["prompt"] = _required_prompt(prompt_text, "upload-extend default parameters")

    if upload_source:
        payload["uploadUrl"] = _ensure_suno_upload_url(
            upload_source,
            api_key=api_key,
            http=http,
        )
    return payload


def _build_add_vocals_payload(
    *,
    model: str,
    prompt: str,
    params: dict[str, Any],
    api_key: str,
    http: OutboundRequestsClient | None,
) -> dict[str, Any]:
    _require_mode_model("add-vocals", model, {"V4_5PLUS", "V5", "V5_5"})
    upload_source = _required_upload_source(params)
    prompt_text = _required_prompt(prompt, "add-vocals")
    title = _required_text(params, "title", "songTitle", label="title")
    negative_tags = _required_text(params, "negativeTags", "negative_tags", label="negativeTags")
    style = _required_text(params, "style", "genre", label="style")
    payload: dict[str, Any] = {
        "prompt": prompt_text,
        "title": title,
        "negativeTags": negative_tags,
        "style": style,
        "model": model,
        "callBackUrl": _resolve_callback_url(params),
    }
    _add_generation_controls(payload, params, include_persona=False, include_negative_tags=False)
    payload["uploadUrl"] = _ensure_suno_upload_url(upload_source, api_key=api_key, http=http)
    return payload


def _build_add_instrumental_payload(
    *,
    model: str,
    params: dict[str, Any],
    api_key: str,
    http: OutboundRequestsClient | None,
) -> dict[str, Any]:
    _require_mode_model("add-instrumental", model, {"V4_5PLUS", "V5", "V5_5"})
    upload_source = _required_upload_source(params)
    title = _required_text(params, "title", "songTitle", label="title")
    negative_tags = _required_text(params, "negativeTags", "negative_tags", label="negativeTags")
    tags = _required_text(params, "tags", label="tags")
    payload: dict[str, Any] = {
        "title": title,
        "negativeTags": negative_tags,
        "tags": tags,
        "model": model,
        "callBackUrl": _resolve_callback_url(params),
    }
    _add_generation_controls(payload, params, include_persona=False, include_negative_tags=False)
    payload["uploadUrl"] = _ensure_suno_upload_url(upload_source, api_key=api_key, http=http)
    return payload


def _build_replace_section_payload(
    *,
    model: str,
    prompt: str,
    params: dict[str, Any],
    api_key: str,
    http: OutboundRequestsClient | None,
) -> dict[str, Any]:
    start = _required_non_negative_number(params, "infillStartS", "infill_start_s", label="infillStartS")
    end = _required_non_negative_number(params, "infillEndS", "infill_end_s", label="infillEndS")
    interval = end - start
    if start >= end or interval < 6 or interval > 60:
        raise SunoMusicInputError("Suno replace-section interval must be between 6 and 60 seconds")

    payload: dict[str, Any] = {
        "prompt": _required_prompt(prompt, "replace-section"),
        "tags": _required_text(params, "tags", label="tags"),
        "title": _required_text(params, "title", "songTitle", label="title"),
        "infillStartS": start,
        "infillEndS": end,
        "fullLyrics": _required_text(params, "fullLyrics", "full_lyrics", label="fullLyrics"),
        "callBackUrl": _resolve_callback_url(params),
    }
    negative_tags = _string_param(params, "negativeTags", "negative_tags", default="")
    if negative_tags:
        payload["negativeTags"] = negative_tags

    task_id = _string_param(params, "taskId", "sourceTaskId", "task_id", default="")
    audio_id = _string_param(params, "audioId", "audio_id", default="")
    upload_source = _string_param(
        params,
        "referenceAudio",
        "replaceAudio",
        "reference_audio",
        "uploadUrl",
        "upload_url",
        default="",
    )
    replace_source = _string_param(params, "replaceSource", "replace_source", default="").lower()
    replace_source = replace_source.replace("-", "_")
    if not replace_source:
        if upload_source and not task_id and not audio_id:
            replace_source = "uploaded_audio"
        elif not upload_source and (task_id or audio_id):
            replace_source = "existing_audio"
        elif upload_source or task_id or audio_id:
            raise SunoMusicInputError(
                "Suno replace-section source is ambiguous; choose existing_audio or uploaded_audio"
            )
    if replace_source == "existing_audio":
        if upload_source:
            raise SunoMusicInputError("Suno replace-section existing_audio cannot include uploaded audio")
        payload["taskId"] = task_id or _missing_required("taskId")
        payload["audioId"] = audio_id or _missing_required("audioId")
    elif replace_source == "uploaded_audio":
        if task_id or audio_id:
            raise SunoMusicInputError("Suno replace-section uploaded_audio cannot include taskId or audioId")
        if not upload_source:
            _missing_required("reference audio")
        payload["uploadUrl"] = _ensure_suno_upload_url(upload_source, api_key=api_key, http=http)
        payload["model"] = model
    else:
        raise SunoMusicInputError(
            "Suno replace-section replaceSource must be existing_audio or uploaded_audio"
        )
    return payload


def _resolve_generation_mode(params: dict[str, Any]) -> str:
    raw = _string_param(
        params,
        "generationMode",
        "generation_mode",
        "generationType",
        "generation_type",
        "musicTaskType",
        default="",
    ).lower()
    if not raw:
        raw = "upload_cover" if _resolve_upload_source(params) else "generate"
    normalized = raw.replace("-", "_")
    aliases = {
        "cover": "upload_cover",
        "upload": "upload_cover",
        "addvocal": "add_vocals",
        "addvocals": "add_vocals",
        "addinstrumental": "add_instrumental",
        "replace": "replace_section",
    }
    normalized = aliases.get(normalized, normalized)
    if normalized not in SUNO_GENERATION_PATHS:
        raise SunoMusicInputError(f"unsupported Suno generationMode: {raw}")
    return normalized


def _load_callback(
    callback_result_loader: Callable[[], dict[str, Any] | None] | None,
) -> dict[str, Any] | None:
    if callback_result_loader is None:
        return None
    try:
        value = callback_result_loader()
    except Exception:
        LOGGER.warning("Could not read persisted Suno callback; polling provider will continue", exc_info=True)
        return None
    return value if isinstance(value, dict) and value else None


def _generation_result_from_callback(event: dict[str, Any] | None) -> SunoGenerationResult | None:
    if not event:
        return None
    payload = event.get("payload") if isinstance(event.get("payload"), dict) else event
    if not isinstance(payload, dict):
        return None
    data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
    callback_type = str(
        data.get("callbackType") or data.get("callback_type") or event.get("callbackType") or ""
    ).strip().lower()
    try:
        code = int(payload.get("code"))
    except (TypeError, ValueError):
        code = int(event.get("providerStatusCode") or 500)
    task_id = str(
        data.get("task_id") or data.get("taskId") or event.get("providerTaskId") or ""
    ).strip()
    if code != 200 or callback_type == "error":
        raise SunoMusicError(str(payload.get("msg") or "Suno callback reported generation failure"))
    if callback_type != "complete":
        return None
    tracks = _extract_tracks(data)
    if not task_id or not tracks:
        raise SunoMusicError("Suno completion callback is missing task id or audio tracks")
    return SunoGenerationResult(
        task_id=task_id,
        tracks=tracks,
        metadata={"callback": payload, "callbackEventId": event.get("eventId")},
    )


def _resolve_generation_type(params: dict[str, Any]) -> str:
    """Compatibility alias for older callers and tests."""
    return _resolve_generation_mode(params)


def _required_upload_source(params: dict[str, Any]) -> str:
    return _required_text(
        params,
        "referenceAudio",
        "reference_audio",
        "referenceAudioUrl",
        "uploadUrl",
        "upload_url",
        label="reference audio",
    )


def _required_prompt(prompt: str, mode_label: str) -> str:
    value = (prompt or "").strip()
    if not value:
        raise SunoMusicInputError(f"Suno {mode_label} requires prompt")
    return value


def _required_text(params: dict[str, Any], *keys: str, label: str) -> str:
    value = _string_param(params, *keys, default="")
    if not value:
        _missing_required(label)
    return value


def _missing_required(label: str) -> Any:
    raise SunoMusicInputError(f"Suno request requires {label}")


def _required_positive_number(params: dict[str, Any], *keys: str, label: str) -> float:
    value = _required_number(params, *keys, label=label)
    if value <= 0:
        raise SunoMusicInputError(f"Suno {label} must be greater than 0")
    return value


def _required_non_negative_number(params: dict[str, Any], *keys: str, label: str) -> float:
    value = _required_number(params, *keys, label=label)
    if value < 0:
        raise SunoMusicInputError(f"Suno {label} must be 0 or greater")
    return value


def _required_number(params: dict[str, Any], *keys: str, label: str) -> float:
    raw: Any = None
    for key in keys:
        if key in params and params.get(key) is not None and str(params.get(key)).strip():
            raw = params.get(key)
            break
    if raw is None:
        _missing_required(label)
    if isinstance(raw, bool):
        raise SunoMusicInputError(f"Suno {label} must be a number")
    try:
        value = float(raw)
    except (TypeError, ValueError) as exc:
        raise SunoMusicInputError(f"Suno {label} must be a number") from exc
    if not math.isfinite(value):
        raise SunoMusicInputError(f"Suno {label} must be a finite number")
    return value


def _add_generation_controls(
    payload: dict[str, Any],
    params: dict[str, Any],
    *,
    include_persona: bool,
    include_negative_tags: bool = True,
) -> None:
    if include_negative_tags:
        negative_tags = _string_param(params, "negativeTags", "negative_tags", default="")
        if negative_tags:
            payload["negativeTags"] = negative_tags
    vocal_gender = _normalize_vocal_gender(
        _string_param(params, "vocalGender", "vocal_gender", default="")
    )
    if vocal_gender:
        payload["vocalGender"] = vocal_gender
    if include_persona:
        persona_id = _string_param(params, "personaId", "persona_id", default="")
        persona_model = _string_param(params, "personaModel", "persona_model", default="")
        if persona_id:
            payload["personaId"] = persona_id
            if persona_model:
                payload["personaModel"] = persona_model
    for key in ("styleWeight", "weirdnessConstraint", "audioWeight"):
        number = _number_param(params, key)
        if number is None:
            continue
        if number < 0 or number > 1:
            raise SunoMusicInputError(f"Suno {key} must be between 0 and 1")
        payload[key] = number


def _require_mode_model(mode_label: str, model: str, supported_models: set[str]) -> None:
    if model not in supported_models:
        supported = ", ".join(sorted(supported_models))
        raise SunoMusicInputError(f"Suno {mode_label} supports only these models: {supported}")


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


def _ensure_suno_upload_url(source: str, *, api_key: str, http: OutboundRequestsClient | None = None) -> str:
    normalized = (source or "").strip()
    if not normalized:
        return ""
    if normalized.startswith("http://") or normalized.startswith("https://"):
        if not _should_reupload_to_suno(normalized):
            return normalized
    audio_bytes, content_type = _read_audio_bytes(normalized, http=http)
    extension = _guess_audio_extension(normalized, content_type)
    file_name = f"reference{extension}"
    return _upload_base64_to_suno(api_key=api_key, audio_bytes=audio_bytes, content_type=content_type, file_name=file_name, http=http)


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


def _read_audio_bytes(source: str, *, http: OutboundRequestsClient | None = None) -> tuple[bytes, str | None]:
    if source.startswith("data:"):
        header, separator, encoded = source.partition(",")
        if not separator or ";base64" not in header:
            raise SunoMusicError("reference audio data url is invalid")
        content_type = header.removeprefix("data:").split(";", 1)[0].strip().lower() or None
        try:
            return base64.b64decode(encoded), content_type
        except ValueError as exc:
            raise SunoMusicError("reference audio data url decode failed") from exc

    local_path = _local_generated_media_path(source)
    if local_path is not None:
        return _read_local_audio_bytes(local_path)

    fetch_url = source
    if source.startswith("/"):
        backend = (settings.backend_internal_base_url or "").rstrip("/")
        if not backend:
            raise SunoMusicError("reference audio relative URL requires BACKEND_INTERNAL_BASE_URL")
        fetch_url = f"{backend}{source}"
    else:
        fetch_url = _rewrite_backend_generated_url(source)

    try:
        if http is None or _is_local_or_internal_url(fetch_url):
            response = requests.get(fetch_url, timeout=(10, 120))
        else:
            response = http.get(fetch_url, timeout=(10, 120))
        response.raise_for_status()
    except requests.RequestException as exc:
        raise SunoMusicError(f"download reference audio failed: {exc}") from exc
    content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() or None
    if not response.content:
        raise SunoMusicError("reference audio file is empty")
    return response.content, content_type


def _local_generated_media_path(value: str) -> Path | None:
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


def _read_local_audio_bytes(path: Path) -> tuple[bytes, str | None]:
    try:
        data = path.read_bytes()
    except OSError as exc:
        raise SunoMusicError(f"could not read reference audio: {path}") from exc
    if not data:
        raise SunoMusicError("reference audio file is empty")
    content_type, _ = mimetypes.guess_type(path.name)
    return data, content_type


def _rewrite_backend_generated_url(value: str) -> str:
    parsed = urlparse(value)
    if parsed.scheme not in {"http", "https"}:
        return value
    if parsed.hostname != "backend":
        return value
    backend = (settings.backend_internal_base_url or "").rstrip("/")
    if not backend:
        return value
    path = parsed.path or ""
    query = f"?{parsed.query}" if parsed.query else ""
    return f"{backend}{path}{query}"


def _is_local_or_internal_url(value: str) -> bool:
    parsed = urlparse(value)
    hostname = (parsed.hostname or "").lower()
    return hostname in {"", "localhost", "127.0.0.1", "::1", "0.0.0.0", "backend", "host.docker.internal"}


def _guess_audio_extension(source: str, content_type: str | None) -> str:
    if content_type:
        extension = mimetypes.guess_extension(content_type) or ""
        if extension == ".mpeg":
            return ".mp3"
        if extension:
            return extension
    suffix = Path(urlparse(source).path).suffix.lower()
    return suffix if suffix else ".mp3"


def _upload_base64_to_suno(
    *,
    api_key: str,
    audio_bytes: bytes,
    content_type: str | None,
    file_name: str,
    http: OutboundRequestsClient | None = None,
) -> str:
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
        if http is None:
            response = requests.post(url, headers=headers, json=payload, timeout=(10, 180))
        else:
            response = http.post(url, headers=headers, json=payload, timeout=(10, 180))
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


def _resolve_v5_5_duration(params: dict[str, Any], model: str) -> int | None:
    if model != "V5_5":
        return None
    value = params.get("duration")
    if value is None or (isinstance(value, str) and not value.strip()):
        return None
    if isinstance(value, bool):
        raise SunoMusicInputError("Suno V5_5 custom mode duration must be an integer from 10 to 360 seconds")
    try:
        numeric = float(value)
    except (TypeError, ValueError) as exc:
        raise SunoMusicInputError(
            "Suno V5_5 custom mode duration must be an integer from 10 to 360 seconds"
        ) from exc
    if not math.isfinite(numeric) or not numeric.is_integer():
        raise SunoMusicInputError("Suno V5_5 custom mode duration must be an integer from 10 to 360 seconds")
    duration = int(numeric)
    if not V5_5_MIN_DURATION_SECONDS <= duration <= V5_5_MAX_DURATION_SECONDS:
        raise SunoMusicInputError("Suno V5_5 custom mode duration must be an integer from 10 to 360 seconds")
    return duration


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


def _extract_tracks(
    detail: dict[str, Any],
    response_mapping: dict[str, Any] | None = None,
) -> list[SunoTrack]:
    mapping = response_mapping or {}
    if response_mapping_has(mapping, "itemsPath"):
        mapped_items = read_response_value(detail, mapping, "itemsPath")
        raw_tracks = mapped_items if isinstance(mapped_items, list) else []
    else:
        raw_tracks = _extract_track_items(detail)
    tracks: list[SunoTrack] = []
    for item in raw_tracks:
        if not isinstance(item, dict):
            continue
        if response_mapping_has(mapping, "urlPath"):
            audio_url = str(read_response_value(item, mapping, "urlPath") or "").strip() or None
        else:
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
