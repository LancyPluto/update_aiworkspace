from __future__ import annotations

from typing import Any

from app.config import settings
from app.core.schemas import AgentFileContext, RunContext, ToolDescriptor

_IMAGE_REFERENCE_ARG_KEYS = (
    "image",
    "imageUrl",
    "image_url",
    "referenceImageUrl",
    "reference_image_url",
    "initImage",
    "inputImage",
    "firstFrameImage",
    "firstFrameUrl",
    "first_frame_image",
    "first_frame_url",
)

_IMAGE_REFERENCE_ARRAY_ARG_KEYS = (
    "image",
    "images",
    "imageUrls",
    "image_urls",
    "referenceImage",
    "referenceImages",
    "referenceImageUrls",
    "reference_image_urls",
    "inputImages",
)


def is_user_explicit_attachment(file: AgentFileContext) -> bool:
    if file.id is not None and file.id < 0:
        return True
    return (file.originalFilename or "").strip().startswith("@")


def is_ready_image_file(file: AgentFileContext) -> bool:
    if file.status != "READY":
        return False
    content_type = (file.contentType or "").lower()
    filename = (file.originalFilename or "").lower()
    return content_type.startswith("image/") or filename.endswith(
        (".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif")
    ) or filename.startswith("@图片")


def _normalize_media_url(raw: str | None) -> str:
    value = (raw or "").strip()
    if not value:
        return ""
    if value.startswith(("http://", "https://", "data:")):
        return value
    base = settings.backend_internal_base_url.rstrip("/")
    path = value if value.startswith("/") else f"/{value}"
    return f"{base}{path}"


def image_download_url(file: AgentFileContext) -> str:
    return _normalize_media_url(file.downloadUrl)


def _history_media_urls(context: RunContext) -> set[str]:
    urls: set[str] = set()
    for call in context.recentToolCalls:
        for media_url in call.mediaUrls:
            normalized = _normalize_media_url(media_url)
            if normalized:
                urls.add(normalized)
    return urls


def user_selected_image_urls(context: RunContext) -> list[str]:
    urls: list[str] = []
    for file in context.agentFiles:
        if not is_user_explicit_attachment(file) or not is_ready_image_file(file):
            continue
        url = image_download_url(file)
        if url and url not in urls:
            urls.append(url)
    return urls


def ready_image_download_urls(context: RunContext) -> list[str]:
    user_urls = user_selected_image_urls(context)
    urls: list[str] = list(user_urls)
    for file in context.agentFiles:
        if not is_ready_image_file(file):
            continue
        url = image_download_url(file)
        if url and url not in urls:
            urls.append(url)
    return urls


def apply_user_selected_attachment_priority(
    context: RunContext,
    tool: ToolDescriptor,
    arguments: dict[str, Any],
) -> dict[str, Any]:
    """Router / followup 可能填入历史生成图；用户拖入 @图片 时优先采用。

    对多图字段，保留 Router 有意引用的历史生成图（如风格迁移的"第一张"），
    再补上用户本轮上传图，仅丢弃既非历史图也非用户图的陈旧 URL。
    """
    normalized = dict(arguments)
    properties = tool.inputSchema.get("properties", {})
    if not isinstance(properties, dict):
        properties = {}

    user_image_urls = user_selected_image_urls(context)
    image_urls = ready_image_download_urls(context)
    if not image_urls:
        return normalized

    history_urls = _history_media_urls(context)
    array_keys = _reference_array_arg_keys(tool, properties)
    single_keys = _reference_single_arg_keys(tool, properties)

    for key in array_keys:
        prop = properties.get(key)
        if key in properties and not _is_string_array_property(prop):
            continue
        if user_image_urls:
            normalized[key] = _merge_user_and_history_urls(
                normalized.get(key), user_image_urls, history_urls
            )
        elif not normalized.get(key):
            normalized[key] = image_urls
        return normalized

    for key in single_keys:
        if user_image_urls:
            normalized[key] = user_image_urls[0]
        elif not normalized.get(key):
            normalized[key] = image_urls[0]
    return normalized


def _merge_user_and_history_urls(
    existing: Any,
    user_image_urls: list[str],
    history_urls: set[str],
) -> list[str]:
    """保留 Router 有意引用的历史生成图，再补上用户上传图，丢弃陈旧无关 URL。"""
    merged: list[str] = []
    existing_items = existing if isinstance(existing, list) else [existing]
    for item in existing_items:
        normalized = _normalize_media_url(item if isinstance(item, str) else None)
        if normalized and normalized in history_urls and normalized not in merged:
            merged.append(normalized)
    for url in user_image_urls:
        if url not in merged:
            merged.append(url)
    return merged or list(user_image_urls)


def _reference_array_arg_keys(tool: ToolDescriptor, properties: dict[str, Any]) -> list[str]:
    keys: list[str] = []
    for key in _IMAGE_REFERENCE_ARRAY_ARG_KEYS:
        prop = properties.get(key)
        if key in properties and _is_string_array_property(prop):
            keys.append(key)
    for field in tool.fields:
        key = field.fieldKey
        if not key or key in keys:
            continue
        if field.fieldType.lower() == "multi_image" or key in _IMAGE_REFERENCE_ARRAY_ARG_KEYS:
            keys.append(key)
    return keys


def _reference_single_arg_keys(tool: ToolDescriptor, properties: dict[str, Any]) -> list[str]:
    keys: list[str] = []
    for key in _IMAGE_REFERENCE_ARG_KEYS:
        if key in properties:
            keys.append(key)
    for field in tool.fields:
        key = field.fieldKey
        if not key or key in keys:
            continue
        if field.fieldType.lower() == "image" or key in _IMAGE_REFERENCE_ARG_KEYS:
            keys.append(key)
    return keys


def _is_string_array_property(prop: Any) -> bool:
    if not isinstance(prop, dict):
        return False
    if str(prop.get("type") or "").lower() != "array":
        return False
    items = prop.get("items")
    return not isinstance(items, dict) or str(items.get("type") or "string").lower() == "string"
