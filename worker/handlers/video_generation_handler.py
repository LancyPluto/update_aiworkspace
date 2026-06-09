import json
import logging
from typing import Any

from client.backend_client import BackendClient, BackendClientError
from client.dashscope_video_client import DashScopeVideoClient, DashScopeVideoError, DashScopeVideoTimeoutError
from client.kling_video_client import KlingVideoClient, KlingVideoError, KlingVideoTimeoutError
from client.seedance_video_client import SeedanceVideoClient, SeedanceVideoError, SeedanceVideoTimeoutError
from config import resolve_kling_api_key, resolve_kling_credentials, resolve_kling_credentials_source
from handlers.generated_video_persister import GeneratedVideoPersistError, GeneratedVideoPersister
from providers import registry as provider_registry
from utils.input_image import InputImageError, resolve_reference_image_data_url


LOGGER = logging.getLogger(__name__)
TERMINAL_TASK_STATUSES = {"SUCCESS", "FAILED", "CANCELLED"}


class VideoGenerationHandler:
    def __init__(
        self,
        backend_client: BackendClient | None = None,
        seedance_client: SeedanceVideoClient | None = None,
        kling_client: KlingVideoClient | None = None,
        dashscope_client: DashScopeVideoClient | None = None,
        video_persister: GeneratedVideoPersister | None = None,
    ) -> None:
        self.backend_client = backend_client or BackendClient()
        self.seedance_client = seedance_client
        self.kling_client = kling_client
        self.dashscope_client = dashscope_client
        self.video_persister = video_persister or GeneratedVideoPersister()

    def handle(self, message: dict[str, Any]) -> dict[str, Any]:
        task_id = int(message["taskId"])
        trace_id = message.get("traceId")
        try:
            context = message.get("__executionContext") or self.backend_client.get_execution_context(task_id, trace_id=trace_id)
            trace_id = trace_id or context.get("traceId")
            status = str(context.get("status") or "").upper()
            if status in TERMINAL_TASK_STATUSES:
                LOGGER.info("skip terminal video task taskId=%s status=%s", task_id, status)
                return {"status": "SKIPPED", "taskId": task_id, "taskStatus": status, "traceId": trace_id}

            cached_video = self.video_persister.find_existing_task_video(task_id=task_id)
            if cached_video:
                content = json.dumps(
                    {
                        "provider": "cached",
                        "status": "SUCCESS",
                        "videos": [cached_video],
                    },
                    ensure_ascii=False,
                )
                self.backend_client.mark_success(
                    task_id,
                    {
                        "resourceType": "VIDEO",
                        "contentText": content,
                        "billableUnits": 1,
                    },
                    trace_id=trace_id,
                )
                LOGGER.info("video generation task %s finalized from cached media traceId=%s", task_id, trace_id or "-")
                return {"status": "SUCCESS", "taskId": task_id, "traceId": trace_id, "cached": True}

            params = context.get("params") or {}
            model_config = context.get("modelConfig") or {}
            provider = str(model_config.get("provider") or context.get("modelProviderCode") or "").lower()
            provider_registry.require_capability(provider, "VIDEO_GENERATION")
            provider_registry.require_worker_ready(provider)

            prompt = _build_prompt(params)
            if not prompt:
                raise KlingVideoError("prompt is required")

            self._mark_processing_safe(task_id, progress=12, progress_message="Video generation task started", trace_id=trace_id)
            client = self._client(provider, model_config)
            if provider == "bailian_happyhorse":
                payload = _build_happyhorse_payload(params, model_config.get("modelName"))
                result = client.generate_video(payload)
            else:
                video_request = {
                    "prompt": prompt,
                    "image_size": _resolve_image_size(params),
                    "negative_prompt": str(params.get("negativePrompt") or params.get("negative_prompt") or ""),
                    "model": model_config.get("modelName"),
                    "image": _first_text(
                        params,
                        "image",
                        "imageUrl",
                        "image_url",
                        "referenceImage",
                        "referenceImageUrl",
                        "firstFrameImage",
                        "firstFrameUrl",
                        "first_frame_image",
                        "first_frame_url",
                    ),
                    "image_tail": _first_text(params, "imageTail", "image_tail", "tailImage", "tailImageUrl", "lastFrameUrl"),
                    "seed": _optional_int(params.get("seed")),
                    "duration": str(params.get("duration") or ""),
                    "aspect_ratio": str(params.get("aspectRatio") or params.get("aspect_ratio") or ""),
                    "resolution": str(params.get("resolution") or ""),
                }
                if provider == "kling_video":
                    video_request["mode"] = str(params.get("mode") or params.get("qualityMode") or "")
                    video_request["sound"] = str(params.get("sound") or "off")
                    video_request["callback_url"] = str(params.get("callbackUrl") or params.get("callback_url") or "")
                    video_request["external_task_id"] = str(params.get("externalTaskId") or params.get("external_task_id") or "")
                result = client.generate_video(**video_request)

            self.backend_client.mark_processing(
                task_id,
                progress=90,
                progress_message="Video generated, saving result",
                trace_id=trace_id,
            )
            persisted_video = self.video_persister.persist_video_url(task_id=task_id, source_url=result["videoUrl"])
            billable_units = _resolve_billable_seconds(result.get("usage"), params) if provider == "bailian_happyhorse" else 1
            content = json.dumps(
                {
                    "provider": result.get("provider") or provider,
                    "model": result.get("model") or model_config.get("modelName"),
                    "requestId": result.get("requestId"),
                    "dashscopeTaskId": result.get("dashscopeTaskId"),
                    "status": result.get("status"),
                    "usage": result.get("usage") or {},
                    "videos": [persisted_video],
                    "sourceVideoUrl": result.get("videoUrl"),
                    "resolution": result.get("resolution"),
                },
                ensure_ascii=False,
            )
            self.backend_client.mark_success(
                task_id,
                {
                    "resourceType": "VIDEO",
                    "contentText": content,
                    "billableUnits": billable_units,
                },
                trace_id=trace_id,
            )
            LOGGER.info("video generation task %s completed traceId=%s provider=%s", task_id, trace_id or "-", provider)
            return {"status": "SUCCESS", "taskId": task_id, "traceId": trace_id, "provider": provider}
        except (KlingVideoTimeoutError, SeedanceVideoTimeoutError, DashScopeVideoTimeoutError) as exc:
            return self._mark_failed(task_id, "MODEL_TIMEOUT", str(exc), trace_id)
        except (KlingVideoError, SeedanceVideoError, DashScopeVideoError, provider_registry.ProviderRegistryError) as exc:
            return self._mark_failed(task_id, _model_call_error_code(str(exc)), str(exc), trace_id)
        except GeneratedVideoPersistError as exc:
            return self._mark_failed(task_id, "MEDIA_PERSIST_FAILED", str(exc), trace_id)
        except BackendClientError:
            LOGGER.exception("video generation handler backend error taskId=%s", task_id)
            raise
        except Exception as exc:
            return self._mark_failed(task_id, "WORKER_INTERNAL_ERROR", str(exc), trace_id)

    def _client(self, provider: str, model_config: dict[str, Any]) -> Any:
        if provider == "kling_video":
            if self.kling_client is not None:
                return self.kling_client
            access_key, secret_key = resolve_kling_credentials(model_config)
            LOGGER.info(
                "kling video credentials resolved source=%s contextSource=%s vendorAccountId=%s fingerprint=%s hasAccessKey=%s hasSecretKey=%s hasApiKey=%s",
                resolve_kling_credentials_source(model_config),
                model_config.get("credentialSource") or "",
                model_config.get("vendorAccountId") or "",
                model_config.get("credentialFingerprint") or "",
                bool(access_key),
                bool(secret_key),
                bool(resolve_kling_api_key(model_config)),
            )
            return KlingVideoClient(
                base_url=model_config.get("baseUrl"),
                api_key=resolve_kling_api_key(model_config),
                access_key=access_key,
                secret_key=secret_key,
                text_path=model_config.get("textPath"),
                image_path=model_config.get("imagePath"),
                text_result_path=model_config.get("textResultPath"),
                image_result_path=model_config.get("imageResultPath"),
                timeout_seconds=model_config.get("timeoutSeconds"),
            )
        if provider == "seedance":
            return self.seedance_client or SeedanceVideoClient()
        if provider == "bailian_happyhorse":
            return self.dashscope_client or DashScopeVideoClient(
                base_url=model_config.get("baseUrl"),
                api_key=model_config.get("apiKey"),
                timeout_seconds=model_config.get("timeoutSeconds"),
            )
        raise KlingVideoError(f"unsupported video provider: {provider or 'empty'}")

    def _mark_failed(self, task_id: int, error_code: str, error_message: str, trace_id: str | None) -> dict[str, Any]:
        LOGGER.exception("video generation task %s failed traceId=%s errorCode=%s: %s", task_id, trace_id or "-", error_code, error_message)
        self._mark_processing_safe(task_id, progress=99, progress_message="Video generation failed", trace_id=trace_id)
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
            LOGGER.warning("failed to mark video task processing taskId=%s", task_id, exc_info=True)


def _build_prompt(params: dict[str, Any]) -> str:
    prompt = _first_text(params, "prompt", "text", "description", "script", "videoTopic")
    if prompt:
        return prompt
    return ""


def _first_text(params: dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = params.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _build_happyhorse_payload(params: dict[str, Any], model_name: Any) -> dict[str, Any]:
    model = str(model_name or "happyhorse-1.0-t2v").strip()
    input_payload: dict[str, Any] = {"prompt": _build_prompt(params)}
    parameters: dict[str, Any] = {}
    media: list[dict[str, str]] = []

    first_frame = _first_text(params, "firstFrameImage", "firstFrameUrl", "first_frame_image", "first_frame_url", "imageUrl", "image")
    source_video = _first_text(params, "sourceVideo", "sourceVideoUrl", "video", "videoUrl", "inputVideo", "inputVideoUrl")
    references = _resolve_reference_image_sources(params)
    if first_frame:
        first_frame = _resolve_happyhorse_image_data_url(first_frame, "firstFrameImage")
    if references:
        references = [_resolve_happyhorse_image_data_url(url, "referenceImages") for url in references]

    if model.endswith("-i2v") and first_frame:
        media.append({"type": "first_frame", "url": first_frame})
        input_payload["img_url"] = first_frame
    elif model.endswith("-r2v"):
        media.extend({"type": "reference_image", "url": url} for url in references)
    elif "video-edit" in model:
        if source_video:
            media.append({"type": "video", "url": source_video})
            input_payload["video_url"] = source_video
        media.extend({"type": "reference_image", "url": url} for url in references)

    if media:
        input_payload["media"] = media
    if references:
        input_payload["reference_images"] = references
    if source_video:
        input_payload["source_video_url"] = source_video

    for source_key, target_key in (
        ("resolution", "resolution"),
        ("ratio", "ratio"),
        ("aspectRatio", "ratio"),
        ("aspect_ratio", "ratio"),
        ("audioSetting", "audio_setting"),
        ("audio_setting", "audio_setting"),
    ):
        value = params.get(source_key)
        if isinstance(value, str) and value.strip():
            parameters[target_key] = value.strip()
    duration = _optional_int(params.get("duration"))
    if duration is not None:
        parameters["duration"] = duration
    seed = _optional_int(params.get("seed"))
    if seed is not None:
        parameters["seed"] = seed
    if "watermark" in params:
        parameters["watermark"] = bool(params.get("watermark"))

    payload: dict[str, Any] = {"model": model, "input": input_payload}
    if parameters:
        payload["parameters"] = parameters
    return payload


def _resolve_reference_image_sources(params: dict[str, Any]) -> list[str]:
    keys = (
        "referenceImages",
        "referenceImageUrls",
        "referenceImage",
        "referenceImageUrl",
        "reference_images",
        "reference_image_urls",
        "images",
        "imageUrls",
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
    return sources


def _resolve_happyhorse_image_data_url(value: str, field_name: str) -> str:
    try:
        return resolve_reference_image_data_url(value)
    except InputImageError as exc:
        raise DashScopeVideoError(f"{field_name} must be a valid image or base64 data: {exc}") from exc


def _resolve_billable_seconds(usage: Any, params: dict[str, Any]) -> int:
    if isinstance(usage, dict):
        for key in ("output_video_duration", "duration", "video_duration", "billable_seconds"):
            units = _optional_int(usage.get(key))
            if units is not None:
                return max(1, units)
    params_duration = _optional_int(params.get("duration"))
    return max(1, params_duration or 1)


def _resolve_image_size(params: dict[str, Any]) -> str:
    explicit = params.get("imageSize") or params.get("image_size") or params.get("size")
    if isinstance(explicit, str) and explicit.strip():
        return explicit.strip()
    aspect_ratio = _normalize_aspect_ratio(params.get("aspectRatio") or params.get("aspect_ratio") or params.get("imageRatio") or "auto")
    if _is_auto_aspect_ratio(aspect_ratio):
        return "auto"
    return {
        "1:1": "960x960",
        "16:9": "1280x720",
        "9:16": "720x1280",
        "4:3": "1024x768",
        "3:4": "768x1024",
    }.get(aspect_ratio, "1280x720")


def _normalize_aspect_ratio(value: Any) -> str:
    return str(value or "").strip().replace("：", ":").replace(" ", "")


def _is_auto_aspect_ratio(value: Any) -> bool:
    normalized = _normalize_aspect_ratio(value).lower()
    return normalized in {"", "auto", "智能", "adaptive", "default"}


def _optional_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _model_call_error_code(message: str) -> str:
    normalized = message.lower()
    if (
        "risk control" in normalized
        or "content policy" in normalized
        or "safety policy" in normalized
        or "sensitive" in normalized
        or ("task_status_msg" in normalized and "failed" in normalized)
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
