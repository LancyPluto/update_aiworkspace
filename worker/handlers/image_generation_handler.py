import json
import logging
import threading
import time
from typing import Any
from urllib.parse import urlparse

from client.backend_client import BackendClient, BackendClientError
from client.siliconflow_video_client import (
    SiliconFlowVideoClient,
    SiliconFlowVideoError,
    SiliconFlowVideoTimeoutError,
)
from client.kling_video_client import KlingVideoClient, KlingVideoError, KlingVideoTimeoutError
from client.openai_images_client import OpenAIImagesClient, OpenAIImagesError, OpenAIImagesTimeoutError
from config import (
    resolve_kling_api_key,
    resolve_kling_credentials,
    resolve_kling_credentials_source,
    resolve_siliconflow_api_key,
    settings,
)
from handlers.generated_image_persister import GeneratedImagePersistError, GeneratedImagePersister
from prompt.renderer import PromptRenderError, render_prompt
from providers import registry as provider_registry
from providers.registry import ProviderRegistryError
from utils.input_image import InputImageError, resolve_reference_image_data_url
from utils.kling_config import (
    resolve_kling_image_api_task,
    resolve_kling_image_paths,
    resolve_kling_model_name,
)


LOGGER = logging.getLogger(__name__)
TERMINAL_TASK_STATUSES = {"SUCCESS", "FAILED", "CANCELLED"}


class ImageProgressTicker:
    def __init__(
        self,
        report_progress,
        *,
        current_progress: int = 1,
        interval_seconds: float = 1.0,
        max_progress: int = 99,
    ) -> None:
        self.report_progress = report_progress
        self.current_progress = max(0, min(max_progress, int(current_progress)))
        self.interval_seconds = max(0.001, float(interval_seconds))
        self.max_progress = max(1, min(99, int(max_progress)))
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> "ImageProgressTicker":
        if self._thread is not None:
            return self
        self._thread = threading.Thread(target=self._run, name="image-progress-ticker", daemon=True)
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=0.2)

    def report(self, progress: int) -> None:
        normalized = max(1, min(self.max_progress, int(progress)))
        if normalized <= self.current_progress:
            return
        self.current_progress = normalized
        self.report_progress(normalized)

    def finish_smoothly(self, *, target_progress: int | None = None, interval_seconds: float = 0.08) -> None:
        target = self.max_progress if target_progress is None else max(1, min(self.max_progress, int(target_progress)))
        delay = max(0.0, float(interval_seconds))
        while self.current_progress < target:
            self.report(self.current_progress + 1)
            if delay > 0 and self.current_progress < target:
                time.sleep(delay)

    def _run(self) -> None:
        while not self._stop.wait(self.interval_seconds):
            if self.current_progress >= self.max_progress:
                continue
            self.report(self.current_progress + 1)


class ImageGenerationHandler:
    def __init__(
        self,
        backend_client: BackendClient | None = None,
        image_client: SiliconFlowVideoClient | None = None,
        image_persister: GeneratedImagePersister | None = None,
        progress_interval_seconds: float = 1.0,
        final_progress_interval_seconds: float = 0.08,
    ) -> None:
        self.backend_client = backend_client or BackendClient()
        self.image_client = image_client
        self.image_persister = image_persister or GeneratedImagePersister()
        self.progress_interval_seconds = progress_interval_seconds
        self.final_progress_interval_seconds = final_progress_interval_seconds

    def handle(self, message: dict[str, Any]) -> dict[str, Any]:
        task_id = int(message["taskId"])
        trace_id = message.get("traceId")
        progress_ticker: ImageProgressTicker | None = None
        try:
            context = message.get("__executionContext") or self.backend_client.get_execution_context(task_id, trace_id=trace_id)
            trace_id = trace_id or context.get("traceId")
            status = str(context.get("status") or "").upper()
            if status in TERMINAL_TASK_STATUSES:
                LOGGER.info("skip terminal image task taskId=%s status=%s traceId=%s", task_id, status, trace_id or "-")
                return {"status": "SKIPPED", "taskId": task_id, "taskStatus": status, "traceId": trace_id}
            params = context.get("params") or {}
            model_config = context.get("modelConfig") or {}
            self._mark_processing_safe(task_id, progress=1, progress_message="实时进度：1%", trace_id=trace_id)
            provider = str(model_config.get("provider") or context.get("modelProviderCode") or "").lower()
            provider_registry.require_capability(provider, "IMAGE_GENERATION")
            provider_registry.require_worker_ready(provider)
            provider_protocol = provider_registry.provider_protocol(provider)
            if provider_protocol not in {"siliconflow_images", "siliconflow", "kling_video", "openai_images"}:
                raise SiliconFlowVideoError(f"unsupported image provider: {provider or 'empty'}")

            prompt = _resolve_prompt(
                context,
                params,
                include_style=provider_protocol != "openai_images",
            )
            if not prompt:
                raise SiliconFlowVideoError("prompt is required")

            client = self.image_client or self._image_client(provider, model_config, params)
            resolved_model = (
                resolve_kling_model_name(params, model_config)
                if provider_protocol == "kling_video"
                else model_config.get("modelName")
            )
            api_task = resolve_kling_image_api_task(model_config, params) if provider_protocol == "kling_video" else ""
            max_batch = 9 if api_task == "omni_image" else 4
            image_request: dict[str, Any] = {
                "prompt": prompt,
                "model": resolved_model,
                "image_size": _resolve_image_size(params),
                "batch_size": max(1, min(max_batch, _as_int(params.get("count") or params.get("batchSize"), 1))),
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
                        "resolution": str(params.get("resolution") or ""),
                    }
                )
                if api_task == "omni_image":
                    image_request.update(
                        {
                            "image_list": params.get("imageList") or params.get("image_list"),
                            "result_type": str(params.get("resultType") or params.get("result_type") or ""),
                        }
                    )
            if provider_protocol == "openai_images":
                image_request["quality"] = _first_text(params, "quality", "imageQuality", "image_quality")
                image_request["style"] = _first_text(params, "style", "imageStyle", "image_style")
                image_request["output_format"] = _first_text(params, "outputFormat", "output_format")
                image_request["response_format"] = _first_text(params, "responseFormat", "response_format")
                image_request["image_size"] = _resolve_openai_image_size(params, model_config)
                reference_images = _resolve_reference_image_sources(params)
                if reference_images:
                    backend_base_url = getattr(self.backend_client, "base_url", settings.backend_internal_base_url)
                    if isinstance(client, OpenAIImagesClient) and client._uses_json_image_array_input():
                        session = client.session
                        resolved_images = [
                            _resolve_json_reference_image(reference_image, backend_base_url, session=session)
                            for reference_image in reference_images
                        ]
                    else:
                        session = client.session if isinstance(client, OpenAIImagesClient) else None
                        resolved_images = [
                            resolve_reference_image_data_url(reference_image, session=session)
                            for reference_image in reference_images
                        ]
                    image_request["image"] = resolved_images if len(resolved_images) > 1 else resolved_images[0]
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
            progress_ticker = self._image_progress_ticker(task_id, trace_id).start()
            urls = client.generate_images(**image_request)
            usage = getattr(client, "last_usage", {}) or {}

            progress_ticker.stop()
            progress_ticker.finish_smoothly(interval_seconds=self.final_progress_interval_seconds)
            progress_ticker = None
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
        except PromptRenderError as exc:
            return self._mark_failed(task_id, "INVALID_TASK_PARAMS", str(exc), trace_id)
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
        finally:
            if progress_ticker is not None:
                progress_ticker.stop()

    def _image_client(
        self,
        provider: str,
        model_config: dict[str, Any],
        params: dict[str, Any] | None = None,
    ) -> Any:
        provider_protocol = provider_registry.provider_protocol(provider)
        if provider_protocol == "kling_video":
            access_key, secret_key = resolve_kling_credentials(model_config)
            LOGGER.info(
                "kling image credentials resolved source=%s contextSource=%s vendorAccountId=%s fingerprint=%s hasAccessKey=%s hasSecretKey=%s hasApiKey=%s",
                resolve_kling_credentials_source(model_config),
                model_config.get("credentialSource") or "",
                model_config.get("vendorAccountId") or "",
                model_config.get("credentialFingerprint") or "",
                bool(access_key),
                bool(secret_key),
                bool(resolve_kling_api_key(model_config)),
            )
            create_path, result_path = resolve_kling_image_paths(model_config, params or {})
            return KlingVideoClient(
                base_url=model_config.get("baseUrl"),
                api_key=resolve_kling_api_key(model_config),
                access_key=access_key,
                secret_key=secret_key,
                image_generation_path=model_config.get("imagePath") or model_config.get("endpointPath") or create_path,
                image_generation_result_path=model_config.get("imageResultPath") or result_path,
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

    def _image_progress_ticker(self, task_id: int, trace_id: str | None) -> ImageProgressTicker:
        return ImageProgressTicker(
            lambda progress: self._mark_processing_safe(
                task_id,
                progress=progress,
                progress_message=f"实时进度：{progress}%",
                trace_id=trace_id,
            ),
            current_progress=1,
            interval_seconds=self.progress_interval_seconds,
            max_progress=99,
        )


def _first_text(params: dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = params.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _to_backend_absolute_url(value: str, backend_base_url: str) -> str:
    text = (value or "").strip()
    if not text or text.startswith(("http://", "https://", "data:image/")):
        return text
    if text.startswith("/"):
        return backend_base_url.rstrip("/") + text
    return text


def _resolve_json_reference_image(value: str, backend_base_url: str, *, session: Any | None = None) -> str:
    text = (value or "").strip()
    if not text:
        return text
    if _should_inline_reference_image(text, backend_base_url):
        return resolve_reference_image_data_url(text, session=session)
    return _to_backend_absolute_url(text, backend_base_url)


def _should_inline_reference_image(value: str, backend_base_url: str) -> bool:
    text = (value or "").strip()
    if not text:
        return False
    if text.startswith("data:") or text.startswith("/"):
        return True
    if not text.startswith(("http://", "https://")):
        return True
    parsed = urlparse(text)
    internal_hosts = {
        host
        for host in (
            urlparse(settings.backend_internal_base_url).hostname,
            urlparse(backend_base_url).hostname,
            "backend",
            "localhost",
            "127.0.0.1",
            "host.docker.internal",
        )
        if host
    }
    if parsed.hostname in internal_hosts:
        return True
    public_base = settings.generated_media_public_base_url.rstrip("/") or "/generated"
    public_path = urlparse(public_base).path.rstrip("/") if public_base.startswith(("http://", "https://")) else public_base
    public_path = public_path or "/generated"
    return parsed.path.startswith(public_path + "/")


def _resolve_reference_image_sources(params: dict[str, Any]) -> list[str]:
    keys = (
        "image",
        "images",
        "imageUrl",
        "imageUrls",
        "image_url",
        "image_urls",
        "sourceImage",
        "sourceImages",
        "sourceImageUrl",
        "sourceImageUrls",
        "source_image",
        "source_images",
        "source_image_url",
        "source_image_urls",
        "referenceImage",
        "referenceImages",
        "referenceImageUrl",
        "referenceImageUrls",
        "reference_image_url",
        "reference_image_urls",
        "inputImage",
        "inputImages",
        "baseImage",
        "baseImages",
        "baseImageUrl",
        "baseImageUrls",
    )
    sources: list[str] = []
    seen: set[str] = set()

    def add(value: Any) -> None:
        if isinstance(value, str):
            text = value.strip()
            if text and text not in seen:
                seen.add(text)
                sources.append(text)

    for key in keys:
        value = params.get(key)
        if isinstance(value, list):
            for item in value:
                add(item)
        else:
            add(value)

    attachments = params.get("attachments")
    if isinstance(attachments, list):
        for item in attachments:
            add(item)
    return sources


def _resolve_prompt(
    context: dict[str, Any],
    params: dict[str, Any],
    *,
    include_style: bool = True,
) -> str:
    user_prompt_template = context.get("userPromptTemplate")
    if isinstance(user_prompt_template, str) and user_prompt_template.strip():
        return render_prompt(user_prompt_template, params).strip()
    return _build_prompt(params, context.get("fields") or [], include_style=include_style)


def _build_prompt(
    params: dict[str, Any],
    fields: list[dict[str, Any]] | None = None,
    *,
    include_style: bool = True,
) -> str:
    prompt = _first_text(params, "prompt", "text", "description")
    if not prompt:
        prompt = _default_image_prompt(params)
    if not include_style:
        return prompt
    style = _first_text(params, "style")
    style_prefix = _option_prompt_prefix(fields or [], "style", style)
    if prompt and style_prefix:
        return f"{style_prefix}, {prompt}"
    if prompt and style:
        return f"{prompt}\nStyle: {style}"
    return prompt


def _default_image_prompt(params: dict[str, Any]) -> str:
    if not _resolve_reference_image_sources(params):
        return ""
    direction = _first_text(params, "expansionDirection", "expansion_direction", "direction")
    aspect_ratio = _first_text(params, "aspectRatio", "aspect_ratio", "imageRatio", "image_ratio")
    strength = _first_text(params, "strength", "expandStrength", "expand_strength")
    parts = [
        "Extend the uploaded image canvas outward while preserving the original subject, lighting, perspective, and style."
    ]
    if direction:
        parts.append(f"Direction: {direction}.")
    if aspect_ratio:
        parts.append(f"Target aspect ratio: {aspect_ratio}.")
    if strength:
        parts.append(f"Expansion strength: {strength}.")
    return " ".join(parts)


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


def _resolve_openai_image_size(params: dict[str, Any], model_config: dict[str, Any] | None = None) -> str:
    allowed_sizes = _openai_allowed_image_sizes(model_config or {})
    explicit = params.get("imageSize") or params.get("image_size") or params.get("size")
    if isinstance(explicit, str) and explicit.strip():
        requested = _openai_image_size_from_ratio_or_size(explicit.strip())
        if requested == "auto":
            return _preferred_allowed_image_size("auto", allowed_sizes) if allowed_sizes else requested
        if allowed_sizes and requested not in allowed_sizes:
            return _closest_allowed_image_size(requested, allowed_sizes)
        return requested
    aspect_ratio = _normalize_aspect_ratio(params.get("aspectRatio") or params.get("aspect_ratio") or params.get("imageRatio") or "auto")
    if allowed_sizes:
        return _preferred_allowed_image_size(aspect_ratio, allowed_sizes)
    return _openai_image_size_from_ratio_or_size(aspect_ratio)


def _openai_image_size_from_ratio_or_size(value: Any) -> str:
    aspect_ratio = _normalize_aspect_ratio(value)
    if _is_auto_aspect_ratio(aspect_ratio):
        return "auto"
    if "x" in aspect_ratio.lower():
        return aspect_ratio.lower()
    return {
        "1:1": "1024x1024",
        "16:9": "1536x1024",
        "4:3": "1536x1024",
        "3:2": "1536x1024",
        "9:16": "1024x1536",
        "3:4": "1024x1536",
        "2:3": "1024x1536",
    }.get(aspect_ratio, "1024x1024")


GPT_IMAGE_2_4K_ALLOWED_SIZES = [
    "2560x2560",
    "2880x2880",
    "3072x1728",
    "3840x2160",
    "1728x3072",
    "2160x3840",
    "2560x1920",
    "3072x2304",
    "1920x2560",
    "2304x3072",
    "2880x1920",
    "3072x2048",
    "3456x2304",
    "1920x2880",
    "2048x3072",
    "2304x3456",
    "3072x1536",
    "3840x1920",
    "1536x3072",
    "1920x3840",
    "3840x1280",
    "1280x3840",
]


SEEDREAM_5_0_ALLOWED_SIZES = [
    # All entries are >= 3,686,400 px (Volcengine Seedream 5.0 hard minimum).
    "2048x2048",
    "2560x1440",
    "1440x2560",
    "2304x1728",
    "1728x2304",
    "2400x1600",
    "1600x2400",
]


def _openai_allowed_image_sizes(model_config: dict[str, Any]) -> list[str]:
    extra_auth = _parse_json_object(model_config.get("extraAuthJson"))
    for key in ("allowedSizes", "allowedImageSizes", "imageSizes"):
        value = extra_auth.get(key)
        if isinstance(value, list):
            sizes = [str(item).strip() for item in value if isinstance(item, str) and "x" in item]
            if sizes:
                return sizes
    model_name = str(model_config.get("modelName") or model_config.get("model") or "").strip().lower()
    if model_name == "gpt-image-2-4k":
        return GPT_IMAGE_2_4K_ALLOWED_SIZES
    # Seedream 5.0 / 5.0-lite hard-require image area >= 3,686,400 px.
    # Worker default 1024x1024 / 1536x1024 violates that constraint, so fall back
    # to a vetted size list when the model name matches the seedream-5-0 family.
    if "seedream-5-0" in model_name:
        return SEEDREAM_5_0_ALLOWED_SIZES
    return []


def _preferred_allowed_image_size(aspect_ratio: str, allowed_sizes: list[str]) -> str:
    normalized = _normalized_ratio(aspect_ratio)
    preferred_by_ratio = {
        "": "3072x2048",
        "auto": "3072x2048",
        "1:1": "2560x2560",
        "16:9": "3072x1728",
        "9:16": "1728x3072",
        "4:3": "3072x2304",
        "3:4": "2304x3072",
        "3:2": "3072x2048",
        "2:3": "2048x3072",
        "2:1": "3072x1536",
        "1:2": "1536x3072",
        "3:1": "3840x1280",
        "1:3": "1280x3840",
    }
    preferred = preferred_by_ratio.get(normalized)
    if preferred in allowed_sizes:
        return preferred
    return _closest_allowed_image_size(normalized or "3:2", allowed_sizes)


def _closest_allowed_image_size(requested: str, allowed_sizes: list[str]) -> str:
    requested_ratio = _ratio_value(requested)
    if requested_ratio is None:
        return allowed_sizes[0]
    for ratio, preferred in (
        (1.0, "2560x2560"),
        (16 / 9, "3072x1728"),
        (9 / 16, "1728x3072"),
        (4 / 3, "3072x2304"),
        (3 / 4, "2304x3072"),
        (3 / 2, "3072x2048"),
        (2 / 3, "2048x3072"),
        (2.0, "3072x1536"),
        (0.5, "1536x3072"),
        (3.0, "3840x1280"),
        (1 / 3, "1280x3840"),
    ):
        if abs(requested_ratio - ratio) < 0.01 and preferred in allowed_sizes:
            return preferred
    parsed = [(size, _ratio_value(size), _image_area(size)) for size in allowed_sizes]
    candidates = [(size, ratio, area) for size, ratio, area in parsed if ratio is not None]
    if not candidates:
        return allowed_sizes[0]
    return min(candidates, key=lambda item: (abs(item[1] - requested_ratio), -item[2]))[0]


def _ratio_value(value: str) -> float | None:
    raw = str(value or "").strip().lower()
    if ":" in raw:
        left, _, right = raw.partition(":")
    elif "x" in raw:
        left, _, right = raw.partition("x")
    else:
        return None
    try:
        width = float(left.strip())
        height = float(right.strip())
    except ValueError:
        return None
    if width <= 0 or height <= 0:
        return None
    return width / height


def _image_area(value: str) -> int:
    raw = str(value or "").strip().lower()
    if "x" not in raw:
        return 0
    left, _, right = raw.partition("x")
    try:
        return max(1, int(left.strip())) * max(1, int(right.strip()))
    except ValueError:
        return 0


def _normalized_ratio(value: str) -> str:
    return str(value or "").strip().lower().replace(" ", "")


def _normalize_aspect_ratio(value: Any) -> str:
    return str(value or "").strip().replace("\uff1a", ":").replace(" ", "")


def _is_auto_aspect_ratio(value: Any) -> bool:
    normalized = _normalize_aspect_ratio(value).lower()
    return normalized in {"", "auto", "\u81ea\u52a8", "adaptive", "default"}


def _parse_json_object(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if not isinstance(value, str) or not value.strip():
        return {}
    try:
        parsed = json.loads(value)
    except ValueError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


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
    if "account balance not enough" in normalized or "balance not enough" in normalized or "insufficient balance" in normalized or '"code":1102' in normalized:
        return "MODEL_CREDIT_INSUFFICIENT"
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
