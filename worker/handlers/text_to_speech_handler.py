import json
import logging
from typing import Any

from client.backend_client import BackendClient, BackendClientError
from handlers.error_classifier import classify_model_error
from client.text_to_speech_client import (
    SpeechGenerationResult,
    TextToSpeechClient,
    TextToSpeechError,
    TextToSpeechTimeoutError,
)
from handlers.generated_audio_persister import GeneratedAudioPersistError, GeneratedAudioPersister
from providers import registry as provider_registry
from providers.registry import ProviderRegistryError
from utils.tts_config import merge_tts_params, speech_billable_units


LOGGER = logging.getLogger(__name__)
TERMINAL_TASK_STATUSES = {"SUCCESS", "FAILED", "CANCELLED"}


class TextToSpeechHandler:
    def __init__(
        self,
        backend_client: BackendClient | None = None,
        tts_client: TextToSpeechClient | None = None,
        audio_persister: GeneratedAudioPersister | None = None,
    ) -> None:
        self.backend_client = backend_client or BackendClient()
        self.tts_client = tts_client or TextToSpeechClient()
        self.audio_persister = audio_persister or GeneratedAudioPersister()

    def handle(self, message: dict[str, Any]) -> dict[str, Any]:
        task_id = int(message["taskId"])
        trace_id = message.get("traceId")
        try:
            context = message.get("__executionContext") or self.backend_client.get_execution_context(task_id, trace_id=trace_id)
            trace_id = trace_id or context.get("traceId")
            status = str(context.get("status") or "").upper()
            if status in TERMINAL_TASK_STATUSES:
                LOGGER.info("skip terminal TTS task taskId=%s status=%s traceId=%s", task_id, status, trace_id or "-")
                return {"status": "SKIPPED", "taskId": task_id, "taskStatus": status, "traceId": trace_id}

            params = context.get("params") or {}
            model_config = context.get("modelConfig") or {}
            provider = str(model_config.get("provider") or context.get("modelProviderCode") or "").lower()
            provider_registry.require_capability(provider, "TEXT_TO_SPEECH")
            provider_registry.require_worker_ready(provider)
            text = _resolve_text(params)
            if not text:
                raise TextToSpeechError("text is required")

            self._mark_processing_safe(task_id, progress=12, progress_message="TTS task started", trace_id=trace_id)
            tts_params = merge_tts_params(model_config, params)
            result = self.tts_client.generate(
                provider=provider,
                model=str(model_config.get("modelName") or context.get("modelName") or ""),
                text=text,
                base_url=model_config.get("baseUrl"),
                api_key=model_config.get("apiKey"),
                params=tts_params,
            )

            self.backend_client.mark_processing(
                task_id,
                progress=88,
                progress_message="Audio generated, saving result",
                trace_id=trace_id,
            )
            audio = self._persist_audio(task_id, result)
            content = json.dumps(
                {
                    "provider": provider,
                    "model": model_config.get("modelName") or context.get("modelName"),
                    "audios": [audio],
                    "metadata": result.metadata or {},
                },
                ensure_ascii=False,
            )
            success_payload = {
                "resourceType": "AUDIO",
                "contentText": content,
                "billableUnits": speech_billable_units(
                    model_config,
                    text=text,
                    metadata=result.metadata,
                ),
                "providerCalled": True,
            }
            provider_request_id = _provider_request_id(result.metadata)
            if provider_request_id:
                success_payload["providerRequestId"] = provider_request_id
            self.backend_client.mark_success(
                task_id,
                success_payload,
                trace_id=trace_id,
            )
            LOGGER.info("TTS task %s completed traceId=%s", task_id, trace_id or "-")
            return {"status": "SUCCESS", "taskId": task_id, "traceId": trace_id}
        except TextToSpeechTimeoutError as exc:
            return self._mark_failed(task_id, "MODEL_TIMEOUT", str(exc), trace_id)
        except ProviderRegistryError as exc:
            return self._mark_failed(task_id, classify_model_error(str(exc)), str(exc), trace_id)
        except TextToSpeechError as exc:
            return self._mark_failed(task_id, classify_model_error(str(exc)), str(exc), trace_id)
        except GeneratedAudioPersistError as exc:
            return self._mark_failed(task_id, "MEDIA_PERSIST_FAILED", str(exc), trace_id)
        except BackendClientError:
            raise
        except Exception as exc:
            return self._mark_failed(task_id, "WORKER_INTERNAL_ERROR", str(exc), trace_id)

    def _persist_audio(self, task_id: int, result: SpeechGenerationResult) -> dict[str, str]:
        if result.audio_url:
            return self.audio_persister.persist_audio_url(
                task_id=task_id,
                source_url=result.audio_url,
                extension=result.extension,
            )
        if result.audio_bytes:
            return self.audio_persister.persist_audio_bytes(
                task_id=task_id,
                audio_bytes=result.audio_bytes,
                content_type=result.content_type,
                extension=result.extension,
            )
        raise TextToSpeechError("speech provider returned empty audio")

    def _mark_failed(self, task_id: int, error_code: str, error_message: str, trace_id: str | None) -> dict[str, Any]:
        LOGGER.exception("TTS task %s failed traceId=%s errorCode=%s: %s", task_id, trace_id or "-", error_code, error_message)
        self._mark_processing_safe(task_id, progress=99, progress_message="TTS failed", trace_id=trace_id)
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
            LOGGER.warning("failed to mark TTS task processing taskId=%s", task_id, exc_info=True)


def _resolve_text(params: dict[str, Any]) -> str:
    for key in ("text", "input", "prompt", "content"):
        value = params.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _provider_request_id(metadata: dict[str, Any] | None) -> str:
    source = metadata if isinstance(metadata, dict) else {}
    return str(
        source.get("providerRequestId")
        or source.get("provider_request_id")
        or source.get("requestId")
        or source.get("request_id")
        or ""
    ).strip()
