"""Provider registry aligned with backend ``model-providers.yml`` (subset)."""

from __future__ import annotations

from typing import Any


PROVIDERS: dict[str, dict[str, Any]] = {
    "mock": {"capabilities": {"TEXT_GENERATION"}, "worker_ready": True},
    "openai_compatible": {"capabilities": {"TEXT_GENERATION"}, "worker_ready": True},
    "anthropic_compatible": {"capabilities": {"TEXT_GENERATION"}, "worker_ready": True},
    "minimax": {"capabilities": {"TEXT_GENERATION"}, "worker_ready": True},
    "siliconflow": {"capabilities": {"IMAGE_GENERATION", "DIGITAL_HUMAN"}, "worker_ready": True},
    "siliconflow_images": {"capabilities": {"IMAGE_GENERATION", "DIGITAL_HUMAN"}, "worker_ready": True},
    "seedance": {"capabilities": {"VIDEO_GENERATION", "DIGITAL_HUMAN"}, "worker_ready": True},
    "kling_video": {"capabilities": {"VIDEO_GENERATION", "IMAGE_GENERATION"}, "worker_ready": True},
    "worker_video": {"capabilities": {"VIDEO_GENERATION"}, "worker_ready": True},
    "minimax_speech": {"capabilities": {"TEXT_TO_SPEECH"}, "worker_ready": True},
    "minimax_music": {"capabilities": {"MUSIC_GENERATION"}, "worker_ready": False},
    "siliconflow_speech": {"capabilities": {"TEXT_TO_SPEECH"}, "worker_ready": True},
}


class ProviderRegistryError(RuntimeError):
    pass


def normalize_provider(provider: str | None) -> str:
    return (provider or "").strip().lower()


def provider_capabilities(provider: str | None) -> set[str]:
    key = normalize_provider(provider)
    meta = PROVIDERS.get(key)
    if not meta:
        return set()
    return {str(c).upper() for c in meta.get("capabilities", set())}


def require_capability(provider: str | None, capability: str) -> None:
    key = normalize_provider(provider)
    cap = (capability or "").strip().upper()
    if not key:
        raise ProviderRegistryError("model provider is empty")
    meta = PROVIDERS.get(key)
    if not meta:
        raise ProviderRegistryError(f"unknown model provider: {key}")
    caps = {str(c).upper() for c in meta.get("capabilities", set())}
    if cap not in caps:
        raise ProviderRegistryError(f"provider {key} does not support capability {cap}")


def require_worker_ready(provider: str | None) -> None:
    key = normalize_provider(provider)
    meta = PROVIDERS.get(key)
    if not meta:
        raise ProviderRegistryError(f"unknown model provider: {key}")
    if not meta.get("worker_ready", False):
        raise ProviderRegistryError(f"provider {key} worker executor is not ready")
