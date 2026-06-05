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
from client.openai_images_client import OpenAIImagesClient, OpenAIImagesError, OpenAIImagesTimeoutError
from config import resolve_kling_api_key, resolve_kling_credentials, resolve_siliconflow_api_key
from handlers.generated_image_persister import GeneratedImagePersistError, GeneratedImagePersister
from providers import registry as provider_registry
from providers.registry import ProviderRegistryError
from utils.input_image import InputImageError, resolve_reference_image_data_url


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
            provider_protocol = provider_registry.provider_protocol(provider)
            if provider_protocol not in {"siliconflow_images", "siliconflow", "kling_video", "openai_images"}:
                raise SiliconFlowVideoError(f"unsupported image provider: {provider or 'empty'}")

            prompt = _build_prompt(
                params,
                context.get("fields") or [],
                include_style=provider_protocol != "openai_images",
            )
            if not prompt:
                raise SiliconFlowVideoError("prompt is required")

            self.backend_client.mark_processing(
                task_id,
                progress=20,
                progress_message="图片生成任务已开始",
                trace_id=trace_id,
            )

            client = self.image_client or self._image_client(provider, model_config)
            image_request: dict[str, Any] = {
                "prompt": prompt,
                "model": model_config.get("modelName"),
                "image_size": _resolve_image_size(params),
                "batch_size": max(1, min(4, _as_int(params.get("count") or params.get("batchSize"), 1))),
                "negative_prompt": str(params.get("negativePrompt") or params.get("negative_prompt") or ""),
                "seed": _optional_int(params.get("seed")),
                "guidance_scale": _optional_float(params.get("guidanceScale") or params.get("guidance_scale")),
                "num_inference_steps": _optional_int(params.get("numInferenceSteps") or params.get("num_inference_steps")),
            }
            if provider_protocol == "kling_video":
                image_request.update(
                    {
                        "image": _first_text(
                            params,
                            "image",
                            "imageUrl",
                            "image_url",
                            "referenceImage",
                            "referenceImageUrl",
                            "reference_image_url",
                            "baseImage",
                            "baseImageUrl",
                        ),
                        "aspect_ratio": str(params.get("aspectRatio") or params.get("aspect_ratio") or ""),
                        "image_reference": _resolve_kling_image_reference(params),
                        "image_fidelity": _optional_float(params.get("imageFidelity") or params.get("image_fidelity")),
                        "human_fidelity": _optional_float(params.get("humanFidelity") or params.get("human_fidelity")),
                    }
                )
            if provider_protocol == "openai_images":
                image_request["quality"] = _first_text(params, "quality", "imageQuality", "image_quality")
                image_request["style"] = _first_text(params, "style", "imageStyle", "image_style")
                image_request["output_format"] = _first_text(params, "outputFormat", "output_format")
                image_request["response_format"] = _first_text(params, "responseFormat", "response_format")
                image_request["image_size"] = _resolve_openai_image_size(params)
                reference_image = _resolve_reference_image_source(params)
                if reference_image:
                    image_request["image"] = resolve_reference_image_data_url(
                        reference_image,
                        session=client.session if isinstance(client, OpenAIImagesClient) else None,
                    )
            LOGGER.info(
                "image generation request built taskId=%s traceId=%s provider=%s protocol=%s model=%s params=%s request=%s",
                task_id,
                trace_id or "-",
                provider or "-",
                provider_protocol,
                model_config.get("modelName") or "-",
                _json_for_log(params),
                _json_for_log(image_request),
            )
            urls = client.generate_images(**image_request)
            usage = getattr(client, "last_usage", {}) or {}

            self.backend_client.mark_processing(
                task_id,
                progress=90,
                progress_message="图片已生成，正在保存结果",
                trace_id=trace_id,
            )
            images = self.image_persister.persist_images(task_id=task_id, urls=urls)
            content = json.dumps(
                {
                    "provider": provider,
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
                    "promptTokens": _optional_int(usage.get("promptTokens")),
                    "completionTokens": _optional_int(usage.get("completionTokens")),
                },
                trace_id=trace_id,
            )
            LOGGER.info("image generation task %s completed traceId=%s images=%s", task_id, trace_id or "-", len(urls))
            return {"status": "SUCCESS", "taskId": task_id, "traceId": trace_id, "imageCount": len(urls)}
        except (SiliconFlowVideoTimeoutError, KlingVideoTimeoutError, OpenAIImagesTimeoutError) as exc:
            return self._mark_failed(task_id, "MODEL_TIMEOUT", str(exc), trace_id)
        except ProviderRegistryError as exc:
            return self._mark_failed(task_id, "MODEL_PROVIDER_UNAVAILABLE", str(exc), trace_id)
        except InputImageError as exc:
            return self._mark_failed(task_id, "INVALID_TASK_PARAMS", str(exc), trace_id)
        except (SiliconFlowVideoError, KlingVideoError, OpenAIImagesError) as exc:
            return self._mark_failed(task_id, _model_call_error_code(str(exc)), str(exc), trace_id)
        except GeneratedImagePersistError as exc:
            return self._mark_failed(task_id, "MEDIA_PERSIST_FAILED", str(exc), trace_id)
        except BackendClientError:
            raise
        except Exception as exc:
            return self._mark_failed(task_id, "WORKER_INTERNAL_ERROR", str(exc), trace_id)

    def _image_client(self, provider: str, model_config: dict[str, Any]) -> Any:
        provider_protocol = provider_registry.provider_protocol(provider)
        if provider_protocol == "kling_video":
            access_key, secret_key = resolve_kling_credentials(model_config)
            return KlingVideoClient(
                base_url=model_config.get("baseUrl"),
                api_key=resolve_kling_api_key(model_config),
                access_key=access_key,
                secret_key=secret_key,
                image_generation_path=model_config.get("imagePath") or model_config.get("endpointPath"),
                image_generation_result_path=model_config.get("imageResultPath"),
                timeout_seconds=model_config.get("timeoutSeconds"),
            )
        if provider_protocol == "openai_images":
            return OpenAIImagesClient(
                base_url=model_config.get("baseUrl"),
                api_key=model_config.get("apiKey"),
                endpoint_path=model_config.get("imagePath") or model_config.get("endpointPath"),
                timeout_seconds=model_config.get("timeoutSeconds"),
                extra_auth_json=model_config.get("extraAuthJson"),
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
                "errorMessage": _limit_text(error_message, 4000),
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


def _resolve_reference_image_source(params: dict[str, Any]) -> str:
    direct = _first_text(
        params,
        "image",
        "imageUrl",
        "image_url",
        "referenceImage",
        "referenceImageUrl",
        "reference_image_url",
        "baseImage",
        "baseImageUrl",
    )
    if direct:
        return direct
    attachments = params.get("attachments")
    if isinstance(attachments, list):
        for item in attachments:
            if isinstance(item, str) and item.strip():
                return item.strip()
    return ""


def _build_prompt(
    params: dict[str, Any],
    fields: list[dict[str, Any]] | None = None,
    *,
    include_style: bool = True,
) -> str:
    prompt = _first_text(params, "prompt", "text", "description")
    if not include_style:
        return prompt
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
    aspect_ratio = str(params.get("aspectRatio") or params.get("aspect_ratio") or params.get("imageRatio") or "16:9").strip()
    return {
        "1:1": "1024x1024",
        "16:9": "1280x720",
        "9:16": "720x1280",
        "4:3": "1024x768",
        "3:4": "768x1024",
        "3:2": "1152x768",
        "2:3": "768x1152",
        "21:9": "2560x1080",
    }.get(aspect_ratio, "1024x1024")


def _resolve_openai_image_size(params: dict[str, Any]) -> str:
    explicit = params.get("imageSize") or params.get("image_size") or params.get("size")
    if isinstance(explicit, str) and explicit.strip():
        return explicit.strip()
    aspect_ratio = str(params.get("aspectRatio") or params.get("aspect_ratio") or params.get("imageRatio") or "16:9").strip()
    return {
        "1:1": "1024x1024",
        "16:9": "1536x1024",
        "4:3": "1536x1024",
        "3:2": "1536x1024",
        "9:16": "1024x1536",
        "3:4": "1024x1536",
        "2:3": "1024x1536",
    }.get(aspect_ratio, "1024x1024")


def _resolve_kling_image_reference(params: dict[str, Any]) -> str:
    raw = _first_text(
        params,
        "imageReference",
        "image_reference",
        "referenceMode",
        "reference_mode",
        "referenceType",
        "reference_type",
    )
    if not raw:
        return ""
    normalized = raw.strip().lower()
    aliases = {
        "角色特征": "subject",
        "角色": "subject",
        "主体": "subject",
        "subject": "subject",
        "人物长相": "face",
        "人脸": "face",
        "face": "face",
        "通用垫图": "",
        "垫图": "",
        "general": "",
        "none": "",
        "__none__": "",
    }
    return aliases.get(normalized, normalized)


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


def _limit_text(value: str, max_length: int) -> str:
    if len(value) <= max_length:
        return value
    return value[: max(0, max_length - 16)] + "...[truncated]"


def _model_call_error_code(message: str) -> str:
    normalized = message.lower()
    if (
        "risk control" in normalized
        or "content policy" in normalized
        or "safety policy" in normalized
        or "sensitive" in normalized
        or "task_status_msg" in normalized and "failed" in normalized
    ):
        return "MODEL_RISK_CONTROL_REJECTED"
    if "status=401" in normalized or "status=403" in normalized:
        return "MODEL_AUTH_FAILED"
    if "invalid token" in normalized or "unauthorized" in normalized or "api key" in normalized:
        return "MODEL_AUTH_FAILED"
    if "status=429" in normalized or "rate limit" in normalized or "too many requests" in normalized:
        return "MODEL_RATE_LIMITED"
    if "timed out" in normalized or "timeout" in normalized:
        return "MODEL_TIMEOUT"
    return "MODEL_CALL_FAILED"


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
            lowered = key_text.lower()
            if any(secret in lowered for secret in ("key", "secret", "token", "authorization")):
                sanitized[key_text] = "***"
                continue
            if key_text in {"image", "image_tail"} and isinstance(nested, str) and len(nested) > 120:
                sanitized[key_text] = (
                    "<reference-image-resolved>"
                    if nested.startswith("data:image/")
                    else f"<image-bytes:{len(nested)} chars>"
                )
                continue
            sanitized[key_text] = _sanitize_for_log(nested)
        return sanitized
    if isinstance(value, list):
        return [_sanitize_for_log(item) for item in value]
    if isinstance(value, str) and len(value) > 800:
        return value[:800] + f"...<{len(value)} chars>"
    return value
