import json
import logging
from typing import Any

from client.backend_client import BackendClient, BackendClientError
from client.siliconflow_video_client import (
    SiliconFlowVideoClient,
    SiliconFlowVideoError,
    SiliconFlowVideoTimeoutError,
)
from client.kling_video_client import KlingVideoClient, KlingVideoError, KlingVideoTimeoutError
from config import resolve_kling_api_key, resolve_kling_credentials, resolve_siliconflow_api_key
from handlers.generated_image_persister import GeneratedImagePersistError, GeneratedImagePersister
from providers import registry as provider_registry
from providers.registry import ProviderRegistryError


LOGGER = logging.getLogger(__name__)
TERMINAL_TASK_STATUSES = {"SUCCESS", "FAILED", "CANCELLED"}


class ImageGenerationHandler:
    def __init__(
        self,
        backend_client: BackendClient | None = None,
        image_client: SiliconFlowVideoClient | None = None,
        image_persister: GeneratedImagePersister | None = None,
    ) -> None:
        self.backend_client = backend_client or BackendClient()
        self.image_client = image_client
        self.image_persister = image_persister or GeneratedImagePersister()

    def handle(self, message: dict[str, Any]) -> dict[str, Any]:
        task_id = int(message["taskId"])
        trace_id = message.get("traceId")
        try:
            context = message.get("__executionContext") or self.backend_client.get_execution_context(task_id, trace_id=trace_id)
            trace_id = trace_id or context.get("traceId")
            status = str(context.get("status") or "").upper()
            if status in TERMINAL_TASK_STATUSES:
                LOGGER.info("skip terminal image task taskId=%s status=%s traceId=%s", task_id, status, trace_id or "-")
                return {"status": "SKIPPED", "taskId": task_id, "taskStatus": status, "traceId": trace_id}
            params = context.get("params") or {}
            model_config = context.get("modelConfig") or {}
            self._mark_processing_safe(task_id, progress=12, progress_message="Image generation task started", trace_id=trace_id)
            provider = str(model_config.get("provider") or context.get("modelProviderCode") or "").lower()
            provider_registry.require_capability(provider, "IMAGE_GENERATION")
            provider_registry.require_worker_ready(provider)
            if provider not in {"siliconflow_images", "siliconflow", "kling_video"}:
                raise SiliconFlowVideoError(f"unsupported image provider: {provider or 'empty'}")

            prompt = _build_prompt(params, context.get("fields") or [])
            if not prompt:
                raise SiliconFlowVideoError("prompt is required")

            self.backend_client.mark_processing(
                task_id,
                progress=20,
                progress_message="图片生成任务已开始",
                trace_id=trace_id,
            )

            client = self.image_client or self._image_client(provider, model_config)
            urls = client.generate_images(
                prompt=prompt,
                model=model_config.get("modelName"),
                image_size=_resolve_image_size(params),
                batch_size=max(1, min(4, _as_int(params.get("count") or params.get("batchSize"), 1))),
                negative_prompt=str(params.get("negativePrompt") or params.get("negative_prompt") or ""),
                seed=_optional_int(params.get("seed")),
                guidance_scale=_optional_float(params.get("guidanceScale") or params.get("guidance_scale")),
                num_inference_steps=_optional_int(params.get("numInferenceSteps") or params.get("num_inference_steps")),
            )

            self.backend_client.mark_processing(
                task_id,
                progress=90,
                progress_message="图片已生成，正在保存结果",
                trace_id=trace_id,
            )
            images = self.image_persister.persist_images(task_id=task_id, urls=urls)
            content = json.dumps(
                {
                    "provider": "siliconflow",
                    "model": model_config.get("modelName"),
                    "images": images,
                },
                ensure_ascii=False,
            )
            self.backend_client.mark_success(
                task_id,
                {
                    "resourceType": "IMAGE",
                    "contentText": content,
                    "billableUnits": len(urls),
                },
                trace_id=trace_id,
            )
            LOGGER.info("image generation task %s completed traceId=%s images=%s", task_id, trace_id or "-", len(urls))
            return {"status": "SUCCESS", "taskId": task_id, "traceId": trace_id, "imageCount": len(urls)}
        except (SiliconFlowVideoTimeoutError, KlingVideoTimeoutError) as exc:
            return self._mark_failed(task_id, "MODEL_TIMEOUT", str(exc), trace_id)
        except (ProviderRegistryError, SiliconFlowVideoError, KlingVideoError) as exc:
            return self._mark_failed(task_id, "MODEL_CALL_FAILED", str(exc), trace_id)
        except GeneratedImagePersistError as exc:
            return self._mark_failed(task_id, "MEDIA_PERSIST_FAILED", str(exc), trace_id)
        except BackendClientError:
            raise
        except Exception as exc:
            return self._mark_failed(task_id, "WORKER_INTERNAL_ERROR", str(exc), trace_id)

    def _image_client(self, provider: str, model_config: dict[str, Any]) -> Any:
        if provider == "kling_video":
            access_key, secret_key = resolve_kling_credentials(model_config)
            return KlingVideoClient(
                base_url=model_config.get("baseUrl"),
                api_key=resolve_kling_api_key(model_config),
                access_key=access_key,
                secret_key=secret_key,
            )
        return SiliconFlowVideoClient(
            base_url=model_config.get("baseUrl"),
            api_key=resolve_siliconflow_api_key(model_config),
        )

    def _mark_failed(self, task_id: int, error_code: str, error_message: str, trace_id: str | None) -> dict[str, Any]:
        LOGGER.exception("image generation task %s failed traceId=%s errorCode=%s: %s", task_id, trace_id or "-", error_code, error_message)
        self._mark_processing_safe(task_id, progress=99, progress_message="Image generation failed", trace_id=trace_id)
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
            LOGGER.warning("failed to mark image task processing before state update taskId=%s", task_id, exc_info=True)


def _first_text(params: dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = params.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _build_prompt(params: dict[str, Any], fields: list[dict[str, Any]] | None = None) -> str:
    prompt = _first_text(params, "prompt", "text", "description")
    style = _first_text(params, "style")
    style_prefix = _option_prompt_prefix(fields or [], "style", style)
    if prompt and style_prefix:
        return f"{style_prefix}, {prompt}"
    if prompt and style:
        return f"{prompt}\nStyle: {style}"
    return prompt


def _option_prompt_prefix(fields: list[dict[str, Any]], field_key: str, selected_value: str) -> str:
    if not selected_value or selected_value == "__none__":
        return ""
    for field in fields:
        if str(field.get("fieldKey") or "") != field_key:
            continue
        for option in _field_options(field):
            value = str(option.get("value") or option.get("label") or "").strip()
            if value == selected_value:
                return str(option.get("promptPrefix") or "").strip()
    return ""


def _field_options(field: dict[str, Any]) -> list[dict[str, Any]]:
    options = field.get("options")
    if isinstance(options, list):
        return [_normalize_option(option) for option in options]
    options_json = field.get("optionsJson")
    if isinstance(options_json, str) and options_json.strip():
        try:
            parsed = json.loads(options_json)
            if isinstance(parsed, list):
                return [_normalize_option(option) for option in parsed]
        except ValueError:
            return []
    return []


def _normalize_option(option: Any) -> dict[str, Any]:
    if isinstance(option, str):
        return {"label": option, "value": option}
    if isinstance(option, dict):
        return option
    return {}


def _resolve_image_size(params: dict[str, Any]) -> str:
    explicit = params.get("imageSize") or params.get("image_size")
    if isinstance(explicit, str) and explicit.strip():
        return explicit.strip()
    aspect_ratio = str(params.get("aspectRatio") or params.get("aspect_ratio") or "1:1").strip()
    return {
        "1:1": "1024x1024",
        "16:9": "1280x720",
        "9:16": "720x1280",
        "4:3": "1024x768",
        "3:4": "768x1024",
    }.get(aspect_ratio, "1024x1024")


def _as_int(value: Any, fallback: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return fallback


def _optional_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    return _as_int(value, 0)


def _optional_float(value: Any) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None
