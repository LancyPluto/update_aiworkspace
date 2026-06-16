import json
import logging
from typing import Any

from client.backend_client import BackendClient, BackendClientError
from client.suno_music_client import (
    SunoGenerationResult,
    SunoMusicClient,
    SunoMusicError,
    SunoMusicTimeoutError,
)
from handlers.generated_audio_persister import GeneratedAudioPersistError, GeneratedAudioPersister
from providers import registry as provider_registry
from providers.registry import ProviderRegistryError


LOGGER = logging.getLogger(__name__)
TERMINAL_TASK_STATUSES = {"SUCCESS", "FAILED", "CANCELLED"}


class MusicGenerationHandler:
    def __init__(
        self,
        backend_client: BackendClient | None = None,
        music_client: SunoMusicClient | None = None,
        audio_persister: GeneratedAudioPersister | None = None,
    ) -> None:
        self.backend_client = backend_client or BackendClient()
        self.music_client = music_client or SunoMusicClient()
        self.audio_persister = audio_persister or GeneratedAudioPersister()

    def handle(self, message: dict[str, Any]) -> dict[str, Any]:
        task_id = int(message["taskId"])
        trace_id = message.get("traceId")
        try:
            context = message.get("__executionContext") or self.backend_client.get_execution_context(task_id, trace_id=trace_id)
            trace_id = trace_id or context.get("traceId")
            status = str(context.get("status") or "").upper()
            if status in TERMINAL_TASK_STATUSES:
                LOGGER.info("skip terminal music task taskId=%s status=%s traceId=%s", task_id, status, trace_id or "-")
                return {"status": "SKIPPED", "taskId": task_id, "taskStatus": status, "traceId": trace_id}

            params = context.get("params") or {}
            model_config = context.get("modelConfig") or {}
            provider = str(model_config.get("provider") or context.get("modelProviderCode") or "").lower()
            provider_registry.require_capability(provider, "MUSIC_GENERATION")
            provider_registry.require_worker_ready(provider)
            prompt = _resolve_prompt(params)

            self._mark_processing_safe(task_id, progress=10, progress_message="Music generation started", trace_id=trace_id)
            result = self.music_client.generate(
                model=str(model_config.get("modelName") or context.get("modelName") or ""),
                prompt=prompt,
                base_url=model_config.get("baseUrl"),
                api_key=model_config.get("apiKey"),
                params=params,
            )
            self.backend_client.mark_processing(
                task_id,
                progress=88,
                progress_message="Music generated, saving audio files",
                trace_id=trace_id,
            )
            audios = self._persist_audios(task_id, result)
            content = json.dumps(
                {
                    "type": "AUDIO",
                    "provider": provider,
                    "model": model_config.get("modelName") or context.get("modelName"),
                    "audios": audios,
                    "externalTaskId": result.task_id,
                    "metadata": result.metadata or {},
                },
                ensure_ascii=False,
            )
            self.backend_client.mark_success(
                task_id,
                {
                    "resourceType": "AUDIO",
                    "contentText": content,
                    "billableUnits": 1,
                },
                trace_id=trace_id,
            )
            LOGGER.info("music task %s completed traceId=%s externalTaskId=%s", task_id, trace_id or "-", result.task_id)
            return {"status": "SUCCESS", "taskId": task_id, "traceId": trace_id, "externalTaskId": result.task_id}
        except SunoMusicTimeoutError as exc:
            return self._mark_failed(task_id, "MODEL_TIMEOUT", str(exc), trace_id)
        except ProviderRegistryError as exc:
            return self._mark_failed(task_id, "MODEL_CALL_FAILED", str(exc), trace_id)
        except SunoMusicError as exc:
            return self._mark_failed(task_id, "MODEL_CALL_FAILED", str(exc), trace_id)
        except GeneratedAudioPersistError as exc:
            return self._mark_failed(task_id, "MEDIA_PERSIST_FAILED", str(exc), trace_id)
        except BackendClientError:
            raise
        except Exception as exc:
            return self._mark_failed(task_id, "WORKER_INTERNAL_ERROR", str(exc), trace_id)

    def _persist_audios(self, task_id: int, result: SunoGenerationResult) -> list[dict[str, Any]]:
        audios: list[dict[str, Any]] = []
        for index, track in enumerate(result.tracks[:2], start=1):
            audio = self.audio_persister.persist_audio_url(task_id=task_id, source_url=track.audio_url, index=index)
            if track.title:
                audio["title"] = track.title
            if track.duration is not None:
                audio["duration"] = track.duration
            if track.source_audio_id:
                audio["sourceAudioId"] = track.source_audio_id
            if track.image_url:
                audio["imageUrl"] = track.image_url
            if track.stream_audio_url:
                audio["streamAudioUrl"] = track.stream_audio_url
            audios.append(audio)
        if not audios:
            raise SunoMusicError("Suno provider returned empty audio list")
        return audios

    def _mark_failed(self, task_id: int, error_code: str, error_message: str, trace_id: str | None) -> dict[str, Any]:
        LOGGER.exception("music task %s failed traceId=%s errorCode=%s: %s", task_id, trace_id or "-", error_code, error_message)
        self._mark_processing_safe(task_id, progress=99, progress_message="Music generation failed", trace_id=trace_id)
        self.backend_client.mark_failed(
            task_id,
            {
                "errorCode": error_code,
                "errorMessage": error_message,
            },
            trace_id=trace_id,
        )
        return {"status": "FAILED", "taskId": task_id, "errorCode": error_code, "traceId": trace_id}

    def _mark_processing_safe(
        self,
        task_id: int,
        *,
        progress: int,
        progress_message: str,
        trace_id: str | None,
    ) -> None:
        try:
            self.backend_client.mark_processing(
                task_id,
                progress=progress,
                progress_message=progress_message,
                trace_id=trace_id,
            )
        except BackendClientError:
            LOGGER.warning("failed to mark music task processing taskId=%s", task_id, exc_info=True)


def _resolve_prompt(params: dict[str, Any]) -> str:
    for key in ("prompt", "text", "description", "lyrics", "content"):
        value = params.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""
