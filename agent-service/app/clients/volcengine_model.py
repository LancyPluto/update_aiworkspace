"""Volcengine Ark helpers for model aliases and provider-specific endpoints."""

from __future__ import annotations

from urllib.parse import urlparse

# Marketing / doc aliases -> Ark chat model id (see Volcengine model release notes).
VOLCENGINE_MODEL_ALIASES: dict[str, str] = {
    "doubao-seed-2.0-lite": "doubao-seed-2-0-lite-260215",
    "doubao-seed-2.0-pro": "doubao-seed-2-0-pro-260215",
    "doubao-seed-2.0-mini": "doubao-seed-2-0-mini-260215",
    "doubao-seed-2.0-code": "doubao-seed-2-0-code-preview-260215",
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


def resolve_volcengine_model_name(model_name: str, base_url: str) -> str:
    if not model_name or not is_volcengine_ark_base_url(base_url):
        return model_name
    return VOLCENGINE_MODEL_ALIASES.get(model_name.strip().lower(), model_name)
