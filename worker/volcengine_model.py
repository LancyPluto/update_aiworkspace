"""Volcengine Ark helpers for model aliases and provider-specific endpoints."""

from __future__ import annotations

from urllib.parse import urlparse

VOLCENGINE_MODEL_ALIASES: dict[str, str] = {
    "doubao-seed-2.0-lite": "doubao-seed-2-0-lite-260215",
    "doubao-seed-2.0-pro": "doubao-seed-2-0-pro-260215",
    "doubao-seed-2.0-mini": "doubao-seed-2-0-mini-260215",
    "doubao-seed-2.0-code": "doubao-seed-2-0-code-preview-260215",
    "doubao-seedream-4.5": "doubao-seedream-4-5-251128",
    "doubao-seedream-4.0": "doubao-seedream-4.0",
    "doubao-seedream-5.0": "doubao-seedream-5-0-260128",
    "doubao-seedream-5.0-lite": "doubao-seedream-5-0-260128",
    "doubao-seedance-1-5-pro-251215": "doubao-seedance-1-5-pro-251215",
    "doubao-seedance-2-0": "doubao-seedance-2-0-260128",
    "doubao-seedance-2.0": "doubao-seedance-2-0-260128",
    "doubao-seedance-2.0-fast": "doubao-seedance-2-0-fast-260128",
    "doubao-seedance-2-0-fast": "doubao-seedance-2-0-fast-260128",
    "doubao-seedance-2.0-mini": "doubao-seedance-2-0-mini-260615",
}


def is_volcengine_ark_base_url(base_url: str) -> bool:
    host = (urlparse(base_url).hostname or "").lower()
    return host.endswith("volces.com") or host.endswith("volcengine.com")


def normalize_volcengine_openai_base_url(base_url: str) -> str:
    normalized = (base_url or "").strip().rstrip("/")
    if not normalized or not is_volcengine_ark_base_url(normalized):
        return normalized
    if normalized.endswith("/api/v3"):
        return normalized
    if normalized.endswith("/v1"):
        return normalized[:-3] + "/api/v3"
    return normalized + "/api/v3"


def resolve_volcengine_images_paths(
    base_url: str,
    endpoint_path: str,
    edit_endpoint_path: str,
) -> tuple[str, str]:
    normalized_base = (base_url or "").strip().rstrip("/")
    generation_path = endpoint_path if endpoint_path.startswith("/") else f"/{endpoint_path}"
    edit_path = edit_endpoint_path if edit_endpoint_path.startswith("/") else f"/{edit_endpoint_path}"
    if not is_volcengine_ark_base_url(normalized_base):
        return generation_path, edit_path
    # base_url 可能已经带 /api/v3 后缀（管理后台允许两种写法），此时再把 endpoint 改成
    # /api/v3/images/generations 会拼出 /api/v3/api/v3/... 导致 404。
    base_has_api_v3 = normalized_base.endswith("/api/v3")
    if generation_path == "/images/generations" and not base_has_api_v3:
        generation_path = "/api/v3/images/generations"
    if edit_path == "/images/edits" and not base_has_api_v3:
        edit_path = "/api/v3/images/edits"
    return generation_path, edit_path


def resolve_volcengine_model_name(model_name: str, base_url: str) -> str:
    if not model_name:
        return model_name
    normalized = (base_url or "").strip().lower()
    if "volces.com" not in normalized and "volcengine" not in normalized:
        return model_name
    return VOLCENGINE_MODEL_ALIASES.get(model_name.strip().lower(), model_name)
