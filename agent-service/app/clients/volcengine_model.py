"""Map friendly Doubao Seed 2.0 names to Volcengine Ark model IDs."""

from __future__ import annotations

# Marketing / doc aliases -> Ark chat model id (see Volcengine model release notes).
VOLCENGINE_MODEL_ALIASES: dict[str, str] = {
    "doubao-seed-2.0-lite": "doubao-seed-2-0-lite-260215",
    "doubao-seed-2.0-pro": "doubao-seed-2-0-pro-260215",
    "doubao-seed-2.0-mini": "doubao-seed-2-0-mini-260215",
    "doubao-seed-2.0-code": "doubao-seed-2-0-code-preview-260215",
}


def is_volcengine_ark_base_url(base_url: str) -> bool:
    normalized = (base_url or "").strip().lower()
    return "volces.com" in normalized or "volcengine" in normalized


def resolve_volcengine_model_name(model_name: str, base_url: str) -> str:
    if not model_name or not is_volcengine_ark_base_url(base_url):
        return model_name
    return VOLCENGINE_MODEL_ALIASES.get(model_name.strip().lower(), model_name)
