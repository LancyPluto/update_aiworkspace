from __future__ import annotations

from typing import Any

from volcengine_model import is_volcengine_ark_base_url, resolve_volcengine_model_name as resolve_alias


def is_volcengine_model_config(model_config: dict[str, Any]) -> bool:
    provider = str(model_config.get("provider") or "").strip().lower()
    base_url = str(model_config.get("baseUrl") or model_config.get("base_url") or "").strip()
    model_name = str(model_config.get("modelName") or model_config.get("model") or "").strip().lower()
    if provider in {"volcengine_images", "seedance"}:
        return True
    if is_volcengine_ark_base_url(base_url):
        return True
    return any(token in model_name for token in ("seedream", "seedance", "doubao-seed"))


def resolve_volcengine_task_model(params: dict[str, Any], model_config: dict[str, Any]) -> str:
    base_url = str(model_config.get("baseUrl") or model_config.get("base_url") or "").strip()
    for key in ("model", "model_name", "modelName"):
        value = params.get(key)
        if isinstance(value, str) and value.strip():
            return resolve_alias(value.strip(), base_url)
    configured = str(model_config.get("modelName") or model_config.get("model") or "").strip()
    if configured:
        return resolve_alias(configured, base_url)
    return ""
