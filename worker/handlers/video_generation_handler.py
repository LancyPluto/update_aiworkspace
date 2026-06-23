import json
import logging
from typing import Any

from client.agnes_video_client import AgnesVideoClient, AgnesVideoError, AgnesVideoTimeoutError
from client.backend_client import BackendClient, BackendClientError
from client.dashscope_video_client import DashScopeVideoClient, DashScopeVideoError, DashScopeVideoTimeoutError
from client.kling_video_client import KlingVideoClient, KlingVideoError, KlingVideoTimeoutError
from client.seedance_video_client import SeedanceVideoClient, SeedanceVideoError, SeedanceVideoTimeoutError
from config import resolve_kling_api_key, resolve_kling_credentials, resolve_kling_credentials_source
from handlers.error_classifier import classify_model_error
from handlers.generated_video_persister import GeneratedVideoPersistError, GeneratedVideoPersister
from providers import registry as provider_registry
from utils.input_image import InputImageError, resolve_reference_image_data_url
from utils.kling_config import resolve_kling_api_task, resolve_kling_model_name, resolve_kling_video_paths
from utils.volcengine_config import resolve_volcengine_task_model


LOGGER = logging.getLogger(__name__)
TERMINAL_TASK_STATUSES = {"SUCCESS", "FAILED", "CANCELLED"}


class VideoGenerationHandler:
    def __init__(
        self,
        backend_client: BackendClient | None = None,
        seedance_client: SeedanceVideoClient | None = None,
        kling_client: KlingVideoClient | None = None,
        agnes_client: AgnesVideoClient | None = None,
        dashscope_client: DashScopeVideoClient | None = None,
        video_persister: GeneratedVideoPersister | None = None,
    ) -> None:
        self.backend_client = backend_client or BackendClient()
        self.seedance_client = seedance_client
        self.kling_client = kling_client
        self.agnes_client = agnes_client
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
            provider_protocol = provider_registry.provider_protocol(provider)
            provider_progress = {"value": 12}

            prompt = _build_prompt(params)
            api_task = resolve_kling_api_task(model_config) if provider_protocol == "kling_video" else ""
            if not prompt and api_task not in {"motion_control"}:
                raise KlingVideoError("prompt is required")

            self._mark_processing_safe(task_id, progress=12, progress_message="Video generation task started", trace_id=trace_id)
            client = self._client(provider, model_config)
            if provider == "bailian_happyhorse":
                payload = _build_happyhorse_payload(params, model_config.get("modelName"))
                result = client.generate_video(payload)
            else:
                if provider_protocol == "kling_video":
                    resolved_model = resolve_kling_model_name(params, model_config)
                elif provider == "seedance":
                    resolved_model = resolve_volcengine_task_model(params, model_config)
                else:
                    resolved_model = str(model_config.get("modelName") or "")
                video_request = _build_video_request(params, resolved_model, provider_protocol)
                if provider_protocol == "kling_video":
                    create_path, result_path = resolve_kling_video_paths(model_config)
                    LOGGER.info(
                        "kling video route resolved provider=%s model=%s capabilities=%s executionTask=%s createPath=%s resultPath=%s traceId=%s",
                        provider,
                        resolved_model,
                        model_config.get("capabilities") or [],
                        model_config.get("executionTask") or model_config.get("execution_task") or "",
                        create_path,
                        result_path,
                        trace_id or "-",
                    )
                    video_request.update(
                        {
                            "sound": str(params.get("sound") or "off"),
                            "callback_url": str(params.get("callbackUrl") or params.get("callback_url") or ""),
                            "external_task_id": str(params.get("externalTaskId") or params.get("external_task_id") or ""),
                            "create_path": create_path,
                            "result_path_template": result_path,
                            "video_url": _first_text(params, "videoUrl", "video_url", "sourceVideo", "sourceVideoUrl"),
                            "character_orientation": _first_text(
                                params,
                                "characterOrientation",
                                "character_orientation",
                            ),
                            "static_mask": _first_text(params, "staticMask", "static_mask"),
                            "dynamic_masks": params.get("dynamicMasks") or params.get("dynamic_masks"),
                            "image_list": params.get("imageList") or params.get("image_list"),
                            "video_list": params.get("videoList") or params.get("video_list"),
                            "element_list": params.get("elementList") or params.get("element_list"),
                            "multi_shot": str(params.get("multiShot") or params.get("multi_shot") or "false"),
                            "shot_type": str(params.get("shotType") or params.get("shot_type") or ""),
                            "multi_prompt": params.get("multiPrompt") or params.get("multi_prompt"),
                            "cfg_scale": _optional_float(params.get("cfgScale") or params.get("cfg_scale")),
                            "keep_original_sound": _first_text(
                                params,
                                "keepOriginalSound",
                                "keep_original_sound",
                            ),
                            "api_task": api_task,
                        }
                    )
                if provider_protocol == "agnes_video":
                    video_request["progress_callback"] = lambda progress: self._mark_provider_progress(
                        task_id,
                        progress,
                        provider_progress,
                        trace_id,
                    )
                result = client.generate_video(**video_request)

            self.backend_client.mark_processing(
                task_id,
                progress=max(90, min(99, provider_progress["value"])),
                progress_message="Video generated, saving result",
                trace_id=trace_id,
            )
            persisted_video = self.video_persister.persist_video_url(task_id=task_id, source_url=result["videoUrl"])
            billable_units = (
                _resolve_billable_seconds(result.get("usage"), params)
                if provider in {"bailian_happyhorse", "kling_video"}
                else 1
            )
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
        except (KlingVideoTimeoutError, SeedanceVideoTimeoutError, AgnesVideoTimeoutError, DashScopeVideoTimeoutError) as exc:
            return self._mark_failed(task_id, "MODEL_TIMEOUT", str(exc), trace_id)
        except InputImageError as exc:
            return self._mark_failed(task_id, "INVALID_TASK_PARAMS", str(exc), trace_id)
        except (KlingVideoError, SeedanceVideoError, AgnesVideoError, DashScopeVideoError, provider_registry.ProviderRegistryError) as exc:
            return self._mark_failed(task_id, classify_model_error(str(exc)), str(exc), trace_id)
        except GeneratedVideoPersistError as exc:
            return self._mark_failed(task_id, "MEDIA_PERSIST_FAILED", str(exc), trace_id)
        except BackendClientError:
            LOGGER.exception("video generation handler backend error taskId=%s", task_id)
            raise
        except Exception as exc:
            return self._mark_failed(task_id, "WORKER_INTERNAL_ERROR", str(exc), trace_id)

    def _client(self, provider: str, model_config: dict[str, Any]) -> Any:
        provider_protocol = provider_registry.provider_protocol(provider)
        if provider_protocol == "kling_video":
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
            create_path, result_path = resolve_kling_video_paths(model_config)
            LOGGER.info(
                "kling video client paths provider=%s model=%s executionTask=%s createPath=%s resultPath=%s",
                provider,
                model_config.get("modelName") or "",
                model_config.get("executionTask") or model_config.get("execution_task") or "",
                create_path,
                result_path,
            )
            return KlingVideoClient(
                base_url=model_config.get("baseUrl"),
                api_key=resolve_kling_api_key(model_config),
                access_key=access_key,
                secret_key=secret_key,
                text_path=model_config.get("textPath") or create_path,
                image_path=model_config.get("imagePath") or create_path,
                text_result_path=model_config.get("textResultPath") or result_path,
                image_result_path=model_config.get("imageResultPath") or result_path,
                timeout_seconds=model_config.get("timeoutSeconds"),
            )
        if provider_protocol == "agnes_video":
            return self.agnes_client or AgnesVideoClient(
                base_url=model_config.get("baseUrl"),
                api_key=model_config.get("apiKey"),
                timeout_seconds=model_config.get("timeoutSeconds"),
                extra_auth_json=model_config.get("extraAuthJson"),
            )
        if provider_protocol == "seedance":
            return self.seedance_client or SeedanceVideoClient.from_model_config(model_config)
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

    def _mark_provider_progress(
        self,
        task_id: int,
        progress: int,
        progress_state: dict[str, int],
        trace_id: str | None,
    ) -> None:
        normalized = max(1, min(99, int(progress)))
        if normalized <= progress_state["value"]:
            return
        progress_state["value"] = normalized
        self._mark_processing_safe(
            task_id,
            progress=normalized,
            progress_message=f"实时进度：{normalized}%",
            trace_id=trace_id,
        )


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


def _build_video_request(params: dict[str, Any], model: str, provider_protocol: str) -> dict[str, Any]:
    request = {
        "prompt": _build_prompt(params),
        "image_size": _resolve_image_size(params),
        "negative_prompt": str(params.get("negativePrompt") or params.get("negative_prompt") or ""),
        "model": model,
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
        "seed": _optional_int(params.get("seed")),
        "duration": str(params.get("duration") or ""),
        "aspect_ratio": str(params.get("aspectRatio") or params.get("aspect_ratio") or ""),
        "resolution": str(params.get("resolution") or ""),
    }
    if provider_protocol == "seedance":
        reference_images = _resolve_reference_image_sources(params)
        if reference_images:
            request["images"] = reference_images
        image_tail = _first_text(
            params,
            "lastFrameImage",
            "lastFrameUrl",
            "tailImage",
            "tailImageUrl",
            "imageTail",
            "image_tail",
        )
        if image_tail:
            request["image_tail"] = image_tail
        video_url = _first_text(
            params,
            "sourceVideoUrl",
            "sourceVideo",
            "videoUrl",
            "video_url",
            "referenceVideoUrl",
        )
        if video_url:
            request["video_url"] = video_url
        audio_data_url = _first_text(
            params,
            "audioUrl",
            "audioDataUrl",
            "audio_url",
            "referenceAudioUrl",
        )
        if audio_data_url:
            request["audio_data_url"] = audio_data_url
        if "generateAudio" in params or "generate_audio" in params:
            request["generate_audio"] = _resolve_bool_param(
                params.get("generateAudio") if "generateAudio" in params else params.get("generate_audio"),
                default=False,
            )
        if "watermark" in params:
            request["watermark"] = _resolve_bool_param(params.get("watermark"), default=False)
        if "cameraFixed" in params or "camera_fixed" in params:
            request["camera_fixed"] = _resolve_bool_param(
                params.get("cameraFixed") if "cameraFixed" in params else params.get("camera_fixed"),
                default=False,
            )
    if provider_protocol in {"kling_video", "agnes_video"}:
        request["image_tail"] = _first_text(params, "imageTail", "image_tail", "tailImage", "tailImageUrl", "lastFrameUrl")
        request["mode"] = str(params.get("mode") or params.get("qualityMode") or "")
    return request


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

    is_video_edit = "video-edit" in model

    for source_key, target_key in (
        ("resolution", "resolution"),
        ("ratio", "ratio"),
        ("aspectRatio", "ratio"),
        ("aspect_ratio", "ratio"),
    ):
        value = params.get(source_key)
        if isinstance(value, str) and value.strip():
            if is_video_edit and target_key == "ratio":
                continue
            parameters[target_key] = value.strip()

    audio_setting = _normalize_happyhorse_audio_setting(
        params.get("audioSetting") or params.get("audio_setting"),
        model,
    )
    if audio_setting:
        parameters["audio_setting"] = audio_setting

    if not is_video_edit:
        duration = _optional_int(params.get("duration"))
        if duration is not None:
            parameters["duration"] = duration
    seed = _optional_int(params.get("seed"))
    if seed is not None:
        parameters["seed"] = seed
    if "watermark" in params:
        parameters["watermark"] = _resolve_bool_param(params.get("watermark"), default=False)

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


def _normalize_happyhorse_audio_setting(value: Any, model: str) -> str | None:
    if not isinstance(value, str) or not value.strip():
        return None
    normalized = value.strip().lower()
    if "video-edit" not in model:
        return None
    if normalized in {"origin", "keep", "keep_original", "preserve", "original"}:
        return "origin"
    if normalized == "auto":
        return "auto"
    return None


def _resolve_bool_param(value: Any, default: bool = False) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in {"true", "1", "yes", "on"}:
            return True
        if lowered in {"false", "0", "no", "off", ""}:
            return False
    if isinstance(value, (int, float)):
        return bool(value)
    return default


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
    return str(value or "").strip().replace("\uff1a", ":").replace(" ", "")


def _is_auto_aspect_ratio(value: Any) -> bool:
    normalized = _normalize_aspect_ratio(value).lower()
    return normalized in {"", "auto", "\u81ea\u52a8", "adaptive", "default"}


def _optional_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _optional_float(value: Any) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


