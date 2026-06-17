from __future__ import annotations

import json
from typing import Any

from config import settings


KLING_API_TASK_PATHS: dict[str, tuple[str, str]] = {
    "text2video": ("/v1/videos/text2video", "/v1/videos/text2video/{task_id}"),
    "image2video": ("/v1/videos/image2video", "/v1/videos/image2video/{task_id}"),
    "motion_control": ("/v1/videos/motion-control", "/v1/videos/motion-control/{task_id}"),
    "multi_image2video": ("/v1/videos/multi-image2video", "/v1/videos/multi-image2video/{task_id}"),
    "omni_video": ("/v1/videos/omni-video", "/v1/videos/omni-video/{task_id}"),
    "image_generation": ("/v1/images/generations", "/v1/images/generations/{task_id}"),
    "omni_image": ("/v1/images/omni-image", "/v1/images/omni-image/{task_id}"),
}

KLING_OMNI_IMAGE_MODELS = frozenset({"kling-image-o1", "kling-v3-omni"})

KLING_ELEMENT_PATHS: dict[str, str] = {
    "create": "/v1/general/advanced-custom-elements",
    "get": "/v1/general/advanced-custom-elements/{task_id}",
    "list": "/v1/general/advanced-custom-elements",
    "delete": "/v1/general/delete-elements",
}


def parse_extra_auth_json(raw: Any) -> dict[str, Any]:
    if isinstance(raw, dict):
        return raw
    if not isinstance(raw, str) or not raw.strip():
        return {}
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def resolve_kling_api_task(model_config: dict[str, Any]) -> str:
    extra = parse_extra_auth_json(model_config.get("extraAuthJson"))
    for key in ("apiTask", "api_task", "taskType", "task_type"):
        value = str(extra.get(key) or "").strip().lower()
        if value:
            return value.replace("-", "_")
    return ""


def resolve_kling_video_paths(model_config: dict[str, Any]) -> tuple[str, str]:
    extra = parse_extra_auth_json(model_config.get("extraAuthJson"))
    api_task = resolve_kling_api_task(model_config)
    if api_task in KLING_API_TASK_PATHS:
        return KLING_API_TASK_PATHS[api_task]
    create_path = str(extra.get("createPath") or extra.get("create_path") or "").strip()
    result_path = str(extra.get("resultPath") or extra.get("result_path") or "").strip()
    if create_path and result_path:
        return create_path, result_path
    if api_task in {"image2video", "motion_control", "multi_image2video"}:
        return settings.kling_video_image_path, settings.kling_video_image_result_path
    return settings.kling_video_text_path, settings.kling_video_text_result_path


def resolve_kling_image_api_task(model_config: dict[str, Any], params: dict[str, Any] | None = None) -> str:
    api_task = resolve_kling_api_task(model_config)
    if api_task == "omni_image":
        return api_task
    if params:
        model = resolve_kling_model_name(params, model_config)
        if model in KLING_OMNI_IMAGE_MODELS:
            return "omni_image"
    return api_task or "image_generation"


def resolve_kling_image_paths(
    model_config: dict[str, Any],
    params: dict[str, Any] | None = None,
) -> tuple[str, str]:
    extra = parse_extra_auth_json(model_config.get("extraAuthJson"))
    api_task = resolve_kling_image_api_task(model_config, params)
    if api_task in {"image_generation", "omni_image"}:
        return KLING_API_TASK_PATHS[api_task]
    create_path = str(extra.get("createPath") or extra.get("create_path") or "").strip()
    result_path = str(extra.get("resultPath") or extra.get("result_path") or "").strip()
    if create_path and result_path:
        return create_path, result_path
    return settings.kling_image_generation_path, settings.kling_image_result_path


def resolve_kling_model_name(params: dict[str, Any], model_config: dict[str, Any]) -> str:
    for key in ("model", "model_name", "modelName"):
        value = params.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return str(model_config.get("modelName") or settings.kling_video_model).strip()
