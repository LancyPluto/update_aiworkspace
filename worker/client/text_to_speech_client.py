from dataclasses import dataclass
import io
import logging
from pathlib import Path
import tarfile
import time
from typing import Any
from urllib.parse import urlencode

import requests

from config import settings


LOGGER = logging.getLogger(__name__)


class TextToSpeechError(RuntimeError):
    pass


class TextToSpeechTimeoutError(TextToSpeechError):
    pass


@dataclass(frozen=True)
class SpeechGenerationResult:
    audio_bytes: bytes | None = None
    audio_url: str | None = None
    content_type: str | None = None
    extension: str | None = None
    metadata: dict[str, Any] | None = None


class TextToSpeechClient:
    def __init__(self) -> None:
        self.timeout = (5, 120)
        self.max_transport_attempts = 2

    def generate(
        self,
        *,
        provider: str,
        model: str,
        text: str,
        base_url: str | None,
        api_key: str | None,
        params: dict[str, Any],
    ) -> SpeechGenerationResult:
        normalized_provider = (provider or "").strip().lower()
        if not text.strip():
            raise TextToSpeechError("text is required")
        if not api_key or api_key.startswith("replace-with-"):
            raise TextToSpeechError("TTS api key is not configured")
        if normalized_provider == "minimax_speech":
            return self._generate_minimax_speech(
                model=model,
                text=text,
                base_url=base_url or "https://api.minimaxi.com",
                api_key=api_key,
                params=params,
            )
        if normalized_provider == "siliconflow_speech":
            return self._generate_openai_speech(
                model=model,
                text=text,
                base_url=base_url or settings.siliconflow_base_url,
                api_key=api_key,
                params=params,
            )
        raise TextToSpeechError(f"unsupported TTS provider: {provider or 'empty'}")

    def _generate_minimax_speech(
        self,
        *,
        model: str,
        text: str,
        base_url: str,
        api_key: str,
        params: dict[str, Any],
    ) -> SpeechGenerationResult:
        mode = _string_param(params, "ttsMode", "minimaxMode", "mode", default="async").lower()
        if mode == "sync":
            return self._generate_minimax_speech_sync(
                model=model,
                text=text,
                base_url=base_url,
                api_key=api_key,
                params=params,
            )
        return self._generate_minimax_speech_async(
            model=model,
            text=text,
            base_url=base_url,
            api_key=api_key,
            params=params,
        )

    def _generate_minimax_speech_sync(
        self,
        *,
        model: str,
        text: str,
        base_url: str,
        api_key: str,
        params: dict[str, Any],
    ) -> SpeechGenerationResult:
        audio_format = _string_param(params, "format", "audioFormat", "responseFormat", default="mp3")
        payload: dict[str, Any] = {
            "model": model or "speech-2.8-hd",
            "text": text,
            "stream": False,
            "language_boost": _string_param(params, "languageBoost", "language_boost", default="auto"),
            "output_format": "hex",
            "voice_setting": {
                "voice_id": _string_param(
                    params,
                    "voiceId",
                    "voice_id",
                    "voice",
                    default="English_expressive_narrator",
                ),
                "speed": _float_param(params, "speed", default=1.0),
                "vol": _float_param(params, "vol", "volume", default=1.0),
                "pitch": _int_param(params, "pitch", default=0),
            },
            "audio_setting": {
                "sample_rate": _int_param(params, "sampleRate", "sample_rate", default=32000),
                "bitrate": _int_param(params, "bitrate", default=128000),
                "format": audio_format,
                "channel": _int_param(params, "channel", default=1),
            },
        }
        pronunciation_tone = params.get("pronunciationTone") or params.get("pronunciation_tone")
        if isinstance(pronunciation_tone, list) and pronunciation_tone:
            payload["pronunciation_dict"] = {"tone": [str(item) for item in pronunciation_tone if str(item).strip()]}

        endpoint = self._join_path(base_url, "/v1/t2a_v2")
        group_id = _string_param(params, "minimaxGroupId", "groupId", "GroupId", default="")
        if group_id:
            endpoint = f"{endpoint}?{urlencode({'GroupId': group_id})}"

        response = self._post_json(
            endpoint,
            api_key=api_key,
            payload=payload,
            timeout_error="minimax speech request timed out",
            request_error="minimax speech request failed",
        )
        base_resp = response.get("base_resp") or {}
        if isinstance(base_resp, dict) and int(base_resp.get("status_code") or 0) != 0:
            raise TextToSpeechError(str(base_resp.get("status_msg") or "minimax speech request failed"))
        data = response.get("data") or {}
        if not isinstance(data, dict):
            raise TextToSpeechError("minimax speech response missing data")
        audio_hex = data.get("audio")
        if not isinstance(audio_hex, str) or not audio_hex.strip():
            raise TextToSpeechError("minimax speech response missing audio")
        try:
            audio_bytes = bytes.fromhex(audio_hex.strip())
        except ValueError as exc:
            raise TextToSpeechError("minimax speech response audio is not hex encoded") from exc
        return SpeechGenerationResult(
            audio_bytes=audio_bytes,
            content_type=_content_type_for_format(audio_format),
            extension=audio_format,
            metadata={
                "traceId": response.get("trace_id"),
                "extraInfo": response.get("extra_info") or {},
            },
        )

    def _generate_minimax_speech_async(
        self,
        *,
        model: str,
        text: str,
        base_url: str,
        api_key: str,
        params: dict[str, Any],
    ) -> SpeechGenerationResult:
        audio_format = _string_param(params, "format", "audioFormat", "responseFormat", default="mp3")
        payload: dict[str, Any] = {
            "model": model or "speech-2.8-hd",
            "text": text,
            "voice_setting": {
                "voice_id": _string_param(
                    params,
                    "voiceId",
                    "voice_id",
                    "voice",
                    default="English_expressive_narrator",
                ),
                "speed": _float_param(params, "speed", default=1.0),
                "vol": _float_param(params, "vol", "volume", default=1.0),
                "pitch": _int_param(params, "pitch", default=0),
            },
            "audio_setting": {
                "audio_sample_rate": _int_param(params, "sampleRate", "sample_rate", default=32000),
                "bitrate": _int_param(params, "bitrate", default=128000),
                "format": audio_format,
                "channel": _int_param(params, "channel", default=1),
            },
        }
        language_boost = _string_param(params, "languageBoost", "language_boost", default="")
        if language_boost:
            payload["language_boost"] = language_boost
        pronunciation_tone = params.get("pronunciationTone") or params.get("pronunciation_tone")
        if isinstance(pronunciation_tone, list) and pronunciation_tone:
            payload["pronunciation_dict"] = {"tone": [str(item) for item in pronunciation_tone if str(item).strip()]}

        create_response = self._post_json(
            self._minimax_url(base_url, "/v1/t2a_async_v2", params),
            api_key=api_key,
            payload=payload,
            timeout_error="minimax async speech create timed out",
            request_error="minimax async speech create failed",
        )
        self._raise_for_minimax_base_resp(create_response, "minimax async speech create failed")
        task_id = str(create_response.get("task_id") or "").strip()
        if not task_id:
            raise TextToSpeechError("minimax async speech response missing task_id")

        poll_response = self._poll_minimax_speech_task(
            base_url=base_url,
            api_key=api_key,
            task_id=task_id,
            params=params,
        )
        file_id = str(poll_response.get("file_id") or "").strip()
        if not file_id:
            raise TextToSpeechError("minimax async speech succeeded without file_id")
        file_response = self._get_binary(
            self._minimax_url(base_url, "/v1/files/retrieve_content", params, {"file_id": file_id}),
            api_key=api_key,
            timeout_error="minimax speech file download timed out",
            request_error="minimax speech file download failed",
        )
        content_type = file_response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        audio_bytes, extracted_extension = _extract_audio_from_archive_if_needed(file_response.content, audio_format)
        return SpeechGenerationResult(
            audio_bytes=audio_bytes,
            content_type=content_type or _content_type_for_format(audio_format),
            extension=extracted_extension or audio_format,
            metadata={
                "taskId": task_id,
                "fileId": file_id,
                "usageCharacters": create_response.get("usage_characters"),
            },
        )

    def _poll_minimax_speech_task(
        self,
        *,
        base_url: str,
        api_key: str,
        task_id: str,
        params: dict[str, Any],
    ) -> dict[str, Any]:
        timeout_seconds = _int_param(params, "asyncTimeoutSeconds", "pollTimeoutSeconds", default=300)
        interval_seconds = max(1, _int_param(params, "asyncPollIntervalSeconds", "pollIntervalSeconds", default=2))
        deadline = time.monotonic() + timeout_seconds
        while True:
            response = self._get_json(
                self._minimax_url(base_url, "/v1/query/t2a_async_query_v2", params, {"task_id": task_id}),
                api_key=api_key,
                timeout_error="minimax async speech query timed out",
                request_error="minimax async speech query failed",
            )
            self._raise_for_minimax_base_resp(response, "minimax async speech query failed")
            status = str(response.get("status") or "").lower()
            if status == "success":
                return response
            if status in {"failed", "expired"}:
                raise TextToSpeechError(f"minimax async speech task {status}: task_id={task_id}")
            if time.monotonic() >= deadline:
                raise TextToSpeechTimeoutError(f"minimax async speech task timed out: task_id={task_id}")
            time.sleep(interval_seconds)

    def _generate_openai_speech(
        self,
        *,
        model: str,
        text: str,
        base_url: str,
        api_key: str,
        params: dict[str, Any],
    ) -> SpeechGenerationResult:
        audio_format = _string_param(params, "format", "audioFormat", "responseFormat", default="mp3")
        payload: dict[str, Any] = {
            "model": model or settings.siliconflow_voice_model,
            "input": text,
            "voice": _string_param(params, "voice", "voiceId", "voice_id", default="FunAudioLLM/CosyVoice2-0.5B:alex"),
            "response_format": audio_format,
        }
        speed = _optional_float_param(params, "speed")
        if speed is not None:
            payload["speed"] = speed
        try:
            response = requests.post(
                self._join_path(base_url, "/v1/audio/speech"),
                headers=self._json_headers(api_key),
                json=payload,
                timeout=self.timeout,
            )
        except requests.exceptions.Timeout as exc:
            raise TextToSpeechTimeoutError("speech request timed out") from exc
        except requests.exceptions.RequestException as exc:
            raise TextToSpeechError(f"speech request failed: {exc}") from exc

        try:
            response.raise_for_status()
        except requests.exceptions.HTTPError as exc:
            raise TextToSpeechError(
                f"speech request failed: status={response.status_code}, body={response.text}"
            ) from exc
        content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        return SpeechGenerationResult(
            audio_bytes=response.content,
            content_type=content_type or _content_type_for_format(audio_format),
            extension=audio_format,
            metadata={},
        )

    def _post_json(
        self,
        url: str,
        *,
        api_key: str,
        payload: dict[str, Any],
        timeout_error: str,
        request_error: str,
    ) -> dict[str, Any]:
        response: requests.Response | None = None
        for attempt in range(1, self.max_transport_attempts + 1):
            try:
                response = requests.post(
                    url,
                    headers=self._json_headers(api_key),
                    json=payload,
                    timeout=self.timeout,
                )
                break
            except requests.exceptions.Timeout as exc:
                raise TextToSpeechTimeoutError(f"{timeout_error}: url={_redact_url(url)}") from exc
            except requests.exceptions.RequestException as exc:
                if attempt < self.max_transport_attempts and _is_retryable_transport_error(exc):
                    LOGGER.warning(
                        "TTS provider transport error, retrying: url=%s attempt=%s/%s errorType=%s error=%s",
                        _redact_url(url),
                        attempt,
                        self.max_transport_attempts,
                        exc.__class__.__name__,
                        exc,
                    )
                    time.sleep(0.5 * attempt)
                    continue
                raise TextToSpeechError(
                    f"{request_error}: url={_redact_url(url)}, errorType={exc.__class__.__name__}, error={exc}"
                ) from exc

        if response is None:
            raise TextToSpeechError(f"{request_error}: no response from speech provider")

        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise TextToSpeechError(
                f"{request_error}: status={response.status_code}, body={response.text}"
            ) from exc

        try:
            data = response.json()
        except ValueError as exc:
            raise TextToSpeechError("speech provider returned non-json response") from exc
        if not isinstance(data, dict):
            raise TextToSpeechError("speech provider returned invalid response")
        return data

    def _get_json(
        self,
        url: str,
        *,
        api_key: str,
        timeout_error: str,
        request_error: str,
    ) -> dict[str, Any]:
        response = self._request_with_retry(
            "GET",
            url,
            api_key=api_key,
            timeout_error=timeout_error,
            request_error=request_error,
        )
        try:
            data = response.json()
        except ValueError as exc:
            raise TextToSpeechError("speech provider returned non-json response") from exc
        if not isinstance(data, dict):
            raise TextToSpeechError("speech provider returned invalid response")
        return data

    def _get_binary(
        self,
        url: str,
        *,
        api_key: str,
        timeout_error: str,
        request_error: str,
    ) -> requests.Response:
        response = self._request_with_retry(
            "GET",
            url,
            api_key=api_key,
            timeout_error=timeout_error,
            request_error=request_error,
        )
        if not response.content:
            raise TextToSpeechError("speech provider returned empty file")
        return response

    def _request_with_retry(
        self,
        method: str,
        url: str,
        *,
        api_key: str,
        timeout_error: str,
        request_error: str,
    ) -> requests.Response:
        response: requests.Response | None = None
        for attempt in range(1, self.max_transport_attempts + 1):
            try:
                response = requests.request(
                    method,
                    url,
                    headers=self._json_headers(api_key),
                    timeout=self.timeout,
                )
                break
            except requests.exceptions.Timeout as exc:
                raise TextToSpeechTimeoutError(f"{timeout_error}: url={_redact_url(url)}") from exc
            except requests.exceptions.RequestException as exc:
                if attempt < self.max_transport_attempts and _is_retryable_transport_error(exc):
                    LOGGER.warning(
                        "TTS provider transport error, retrying: url=%s attempt=%s/%s errorType=%s error=%s",
                        _redact_url(url),
                        attempt,
                        self.max_transport_attempts,
                        exc.__class__.__name__,
                        exc,
                    )
                    time.sleep(0.5 * attempt)
                    continue
                raise TextToSpeechError(
                    f"{request_error}: url={_redact_url(url)}, errorType={exc.__class__.__name__}, error={exc}"
                ) from exc

        if response is None:
            raise TextToSpeechError(f"{request_error}: no response from speech provider")
        try:
            response.raise_for_status()
        except requests.exceptions.HTTPError as exc:
            raise TextToSpeechError(
                f"{request_error}: status={response.status_code}, body={response.text}"
            ) from exc
        return response

    @staticmethod
    def _join_path(base_url: str, path: str) -> str:
        base = base_url.rstrip("/")
        if base.endswith("/v1") and path.startswith("/v1/"):
            return f"{base}{path.removeprefix('/v1')}"
        return f"{base}{path}"

    def _minimax_url(
        self,
        base_url: str,
        path: str,
        params: dict[str, Any],
        query: dict[str, str] | None = None,
    ) -> str:
        merged_query: dict[str, str] = {}
        group_id = _string_param(params, "minimaxGroupId", "groupId", "GroupId", default="")
        if group_id:
            merged_query["GroupId"] = group_id
        if query:
            merged_query.update(query)
        url = self._join_path(base_url, path)
        return f"{url}?{urlencode(merged_query)}" if merged_query else url

    @staticmethod
    def _raise_for_minimax_base_resp(response: dict[str, Any], fallback: str) -> None:
        base_resp = response.get("base_resp") or {}
        if isinstance(base_resp, dict) and int(base_resp.get("status_code") or 0) != 0:
            raise TextToSpeechError(str(base_resp.get("status_msg") or fallback))

    @staticmethod
    def _json_headers(api_key: str) -> dict[str, str]:
        return {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
            "Connection": "close",
            "User-Agent": "ai-tool-market-worker/tts",
        }


def _string_param(params: dict[str, Any], *keys: str, default: str) -> str:
    for key in keys:
        value = params.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return default


def _int_param(params: dict[str, Any], *keys: str, default: int) -> int:
    for key in keys:
        value = params.get(key)
        if value is None or value == "":
            continue
        try:
            return int(value)
        except (TypeError, ValueError):
            continue
    return default


def _float_param(params: dict[str, Any], *keys: str, default: float) -> float:
    value = _optional_float_param(params, *keys)
    return default if value is None else value


def _optional_float_param(params: dict[str, Any], *keys: str) -> float | None:
    for key in keys:
        value = params.get(key)
        if value is None or value == "":
            continue
        try:
            return float(value)
        except (TypeError, ValueError):
            continue
    return None


def _content_type_for_format(audio_format: str) -> str:
    normalized = (audio_format or "mp3").strip().lower()
    return {
        "mp3": "audio/mpeg",
        "wav": "audio/wav",
        "flac": "audio/flac",
        "ogg": "audio/ogg",
        "aac": "audio/aac",
        "webm": "audio/webm",
        "pcm": "application/octet-stream",
    }.get(normalized, "audio/mpeg")


def _is_retryable_transport_error(exc: requests.exceptions.RequestException) -> bool:
    return isinstance(exc, (requests.exceptions.ConnectionError, requests.exceptions.SSLError))


def _redact_url(url: str) -> str:
    return url.split("?", 1)[0]


def _extract_audio_from_archive_if_needed(content: bytes, fallback_extension: str) -> tuple[bytes, str | None]:
    if not content:
        return content, None
    if _looks_like_tar(content):
        try:
            with tarfile.open(fileobj=io.BytesIO(content), mode="r:*") as archive:
                for member in archive.getmembers():
                    if not member.isfile():
                        continue
                    extension = Path(member.name).suffix.lower().lstrip(".")
                    if extension not in {"mp3", "wav", "flac", "ogg", "aac", "m4a", "webm", "pcm"}:
                        continue
                    extracted = archive.extractfile(member)
                    if extracted is None:
                        continue
                    audio_bytes = extracted.read()
                    if audio_bytes:
                        return audio_bytes, extension or fallback_extension
        except tarfile.TarError:
            return content, None
    return content, None


def _looks_like_tar(content: bytes) -> bool:
    return len(content) > 512 and content[257:262] == b"ustar"
