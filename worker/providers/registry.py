"""Provider registry aligned with backend ``model-providers.yml`` (subset)."""

from __future__ import annotations

from typing import Any


PROVIDERS: dict[str, dict[str, Any]] = {
    "mock": {"capabilities": {"TEXT_GENERATION"}, "worker_ready": True},
    "local_media_mock": {"capabilities": {"IMAGE_GENERATION", "VIDEO_GENERATION"}, "worker_ready": True},
    "openai_compatible": {"capabilities": {"TEXT_GENERATION", "VISION_INPUT"}, "worker_ready": True},
    "anthropic_compatible": {"capabilities": {"TEXT_GENERATION", "VISION_INPUT"}, "worker_ready": True},
    "minimax": {"capabilities": {"TEXT_GENERATION"}, "worker_ready": True},
    "siliconflow": {"capabilities": {"IMAGE_GENERATION"}, "worker_ready": True},
    "siliconflow_images": {"capabilities": {"IMAGE_GENERATION"}, "worker_ready": True},
    "volcengine_images": {
        "capabilities": {"IMAGE_GENERATION"},
        "worker_ready": True,
        "provider_protocol": "openai_images",
    },
    "seedance": {"capabilities": {"VIDEO_GENERATION"}, "worker_ready": True},
    "infinitetalk": {"capabilities": {"VIDEO_GENERATION"}, "worker_ready": True},
    "kling_video": {"capabilities": {"VIDEO_GENERATION", "IMAGE_GENERATION"}, "worker_ready": True},
    "bailian_happyhorse": {"capabilities": {"VIDEO_GENERATION"}, "worker_ready": True},
    "ofox_openai_images": {
        "capabilities": {"IMAGE_GENERATION"},
        "worker_ready": True,
        "provider_protocol": "openai_images",
        "vendor_kind": "gateway",
        "upstream_vendor": "openai",
    },
    "openai_images_gateway": {
        "capabilities": {"IMAGE_GENERATION"},
        "worker_ready": True,
        "provider_protocol": "openai_images",
        "vendor_kind": "gateway",
        "upstream_vendor": "openai",
    },
    "agnes_chat": {
        "capabilities": {"TEXT_GENERATION", "VISION_INPUT"},
        "worker_ready": True,
        "provider_protocol": "openai_chat",
        "vendor_kind": "direct",
        "upstream_vendor": "agnes",
    },
    "agnes_images": {
        "capabilities": {"IMAGE_GENERATION"},
        "worker_ready": True,
        "provider_protocol": "openai_images",
        "vendor_kind": "direct",
        "upstream_vendor": "agnes",
    },
    "agnes_video": {
        "capabilities": {"VIDEO_GENERATION"},
        "worker_ready": True,
        "provider_protocol": "agnes_video",
        "vendor_kind": "direct",
        "upstream_vendor": "agnes",
    },
    "worker_video": {"capabilities": {"VIDEO_GENERATION"}, "worker_ready": True},
    "minimax_speech": {"capabilities": {"TEXT_TO_SPEECH"}, "worker_ready": True},
    "minimax_music": {"capabilities": {"MUSIC_GENERATION"}, "worker_ready": False},
    "suno_music": {"capabilities": {"MUSIC_GENERATION"}, "worker_ready": True},
    "siliconflow_speech": {"capabilities": {"TEXT_TO_SPEECH"}, "worker_ready": True},
    "siliconflow_asr": {"capabilities": {"SPEECH_TO_TEXT"}, "worker_ready": False},
    "dashscope_qwen_tts": {"capabilities": {"TEXT_TO_SPEECH"}, "worker_ready": True},
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


def provider_protocol(provider: str | None) -> str:
    key = normalize_provider(provider)
    meta = PROVIDERS.get(key)
    if not meta:
        return key
    return str(meta.get("provider_protocol") or key).strip().lower()


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
