"""Structured @ reference resolution — no 图1/图2 NLP."""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from app.config import settings
from app.core.schemas import AgentFileContext, ReferenceMention, RunContext
from app.runtime.session_state import latest_generated_image_state

_AT_TOKEN_PATTERN = re.compile(r"@[^\s@]+")


@dataclass(frozen=True, slots=True)
class ReferencePlan:
    has_explicit_references: bool
    ordered_urls: tuple[str, ...]
    mentions: tuple[ReferenceMention, ...]


def build_reference_plan(context: RunContext) -> ReferencePlan:
    mentions = list(context.referenceMentions or [])
    if not mentions:
        mentions = _mentions_from_message_tokens(context)
    if not mentions:
        mentions = _mentions_from_content_parts(context)
    urls: list[str] = []
    for mention in mentions:
        normalized = _normalize_media_url(mention.url)
        if normalized and normalized not in urls:
            urls.append(normalized)
    return ReferencePlan(
        has_explicit_references=bool(urls),
        ordered_urls=tuple(urls),
        mentions=tuple(mentions),
    )


def reference_mentions_payload(plan: ReferencePlan) -> list[dict]:
    return [
        {
            "alias": current_attachment_alias(index),
            "token": mention.token or mention.refLabel,
            "refLabel": mention.refLabel,
            "originalLabel": llm_token_for_mention(mention),
            "llmLabel": llm_token_for_mention(mention),
            "url": mention.url,
            "kind": mention.kind,
            "source": mention.source,
            "assetKey": mention.assetKey,
            "fileId": mention.fileId,
            "name": mention.name,
            "contentType": mention.contentType,
        }
        for index, mention in enumerate(plan.mentions, start=1)
    ]


def current_attachment_alias(index: int) -> str:
    return f"[当前参考图_{index}]"


def llm_token_for_mention(mention: ReferenceMention) -> str:
    """Usable attachment label for LLM — not the UI display token."""
    ref = (mention.refLabel or "").strip()
    if not ref:
        return (mention.token or "").strip()
    nested = re.match(r"^(@图片\d+)-@图片\d+-(.+)$", ref)
    if nested:
        return f"{nested.group(1)}-{nested.group(2)}"
    return ref


def reference_readiness_hint(context: RunContext) -> str:
    """Lightweight fallback when @ references must be visible in agent chat context."""
    plan = build_reference_plan(context)
    if not plan.mentions and not context.contentParts:
        return ""
    lines = [
        "The user explicitly @-referenced media below. These assets are already available on the server.",
        "For image generation or editing, call the image tool directly; do not ask the user to re-upload.",
        "Ref labels are server-resolved attachment names, not inaccessible local file paths.",
        "These are additional references, not necessarily the base image; decide field placement via tool schema.",
    ]
    if plan.mentions:
        for index, mention in enumerate(plan.mentions, start=1):
            label = llm_token_for_mention(mention)
            url = _normalize_media_url(mention.url)
            file_id = str(mention.fileId or "").strip()
            asset_key = (mention.assetKey or "").strip()
            details = []
            if url:
                details.append(f"url={url}")
            if file_id:
                details.append(f"file_id={file_id}")
            if asset_key:
                details.append(f"asset_key={asset_key}")
            if label and details:
                lines.append(f"  {index}. {label} -> {', '.join(details)}")
    ordered = ordered_content_parts_for_llm(context)
    if ordered:
        lines.append("Ordered multimodal user prompt parts:")
        lines.extend(f"  {line}" for line in ordered)
    readable = readable_positional_prompt(context)
    if readable:
        lines.append(f"Readable positional prompt: {readable}")
    return "\n".join(lines)


def user_message_for_llm(context: RunContext) -> str:
    """Replace UI display @ tokens with attachment ref labels for LLM consumption."""
    message = readable_positional_prompt(context) or context.message or ""
    plan = build_reference_plan(context)
    if not plan.mentions:
        return message
    replacements: list[tuple[str, str]] = []
    for mention in plan.mentions:
        display = (mention.token or "").strip()
        llm_token = llm_token_for_mention(mention)
        if display and llm_token and display != llm_token:
            replacements.append((display, llm_token))
    if not replacements:
        return message
    replacements.sort(key=lambda item: len(item[0]), reverse=True)
    resolved = message
    for display, llm_token in replacements:
        resolved = resolved.replace(display, llm_token)
    return resolved


def build_user_message_content(context: RunContext) -> str | list[dict[str, Any]]:
    """Build the final user content sent to the Agent model.

    Text-only models keep the current string payload. Vision-enabled Agent models
    receive OpenAI-compatible multimodal content with current-turn referenced images.
    """
    text = user_message_for_llm(context)
    if not model_supports_vision_input(context):
        return text
    image_urls = referenced_image_urls_for_vision(context)
    if not image_urls:
        return text
    parts: list[dict[str, Any]] = [{"type": "text", "text": text}]
    parts.extend({"type": "image_url", "image_url": {"url": url}} for url in image_urls)
    return parts


def model_supports_vision_input(context: RunContext) -> bool:
    config = context.modelConfig
    if config is None:
        return False
    return any((capability or "").strip().upper() == "VISION_INPUT" for capability in config.capabilities or [])


def referenced_image_urls_for_vision(context: RunContext) -> list[str]:
    plan = build_reference_plan(context)
    urls: list[str] = []
    for mention in plan.mentions:
        if not _is_visual_mention(mention):
            continue
        normalized = _normalize_media_url(mention.url)
        if normalized and normalized not in urls:
            urls.append(normalized)
    return urls


def resolve_media_argument_pointers(context: RunContext, arguments: dict) -> dict:
    """Resolve structured media pointer labels in tool args to concrete URLs."""
    if not isinstance(arguments, dict):
        return arguments
    lookup = _mention_lookup(context)
    normalized = dict(arguments)
    for key, value in list(normalized.items()):
        if not _is_media_argument_key(key):
            continue
        normalized[key] = _resolve_media_argument_value(context, value, lookup)
    return normalized


def readable_positional_prompt(context: RunContext) -> str:
    """Render positional prompt placeholders/content parts into stable attachment labels."""
    prompt = (context.positionalPrompt or "").strip()
    if prompt:
        resolved = _replace_reference_placeholders(prompt, context)
        if resolved and resolved != prompt:
            return resolved
        if "{" not in prompt and "}" not in prompt:
            return prompt
    return _content_parts_to_text(context)


def ordered_content_parts_for_llm(context: RunContext) -> list[str]:
    parts = context.contentParts or []
    if not parts:
        return []
    lines: list[str] = []
    mention_lookup = _mention_lookup(context)
    image_index = 0
    for index, part in enumerate(parts, start=1):
        if not isinstance(part, dict):
            continue
        part_type = str(part.get("type") or "").strip().lower()
        if part_type == "text":
            text = str(part.get("text") or "")
            if text:
                lines.append(f"{index}. text: {text}")
            continue
        if part_type not in {"image", "file"}:
            continue
        image_index += 1
        mention = _mention_for_part(part, mention_lookup)
        label = llm_token_for_mention(mention) if mention is not None else str(part.get("name") or f"{part_type}{image_index}")
        url = _normalize_media_url(str(part.get("url") or (mention.url if mention is not None else "")))
        file_id = str(part.get("file_id") or part.get("fileId") or (mention.fileId if mention is not None else "") or "").strip()
        asset_key = str(part.get("asset_key") or part.get("assetKey") or (mention.assetKey if mention is not None else "") or "").strip()
        details = [f"label={label}"]
        if url:
            details.append(f"url={url}")
        if file_id:
            details.append(f"file_id={file_id}")
        if asset_key:
            details.append(f"asset_key={asset_key}")
        lines.append(f"{index}. {part_type}: {', '.join(details)}")
    return lines


def _resolve_media_argument_value(context: RunContext, value, lookup: dict[str, ReferenceMention]):
    if isinstance(value, list):
        return [_resolve_media_argument_value(context, item, lookup) for item in value]
    if isinstance(value, dict):
        resolved = dict(value)
        for key, nested in list(resolved.items()):
            if _is_media_argument_key(str(key)):
                resolved[key] = _resolve_media_argument_value(context, nested, lookup)
            elif isinstance(nested, (dict, list)):
                resolved[key] = _resolve_media_argument_value(context, nested, lookup)
        return resolved
    if not isinstance(value, str):
        return value
    text = value.strip()
    if not text:
        return value
    if text.startswith(("http://", "https://", "data:")) or text.startswith("/") or _looks_like_base64_image(text):
        return value
    mention = lookup.get(text) or _fuzzy_label_match(text, lookup)
    if mention is not None:
        resolved = _normalize_media_url(mention.url)
        return resolved or value
    latest = latest_generated_image_state(context.recentToolCalls)
    if latest is not None and text in {"latest_generated_image", "latest_generated_image.url", "{latest_generated_image.url}"}:
        return latest.image_url
    return value


def _is_media_argument_key(key: str) -> bool:
    lower = (key or "").strip().lower()
    return lower in {
        "image",
        "images",
        "imageurl",
        "imageurls",
        "image_url",
        "image_urls",
        "sourceimage",
        "sourceimages",
        "sourceimageurl",
        "sourceimageurls",
        "source_image",
        "source_images",
        "source_image_url",
        "source_image_urls",
        "referenceimage",
        "referenceimages",
        "referenceimageurl",
        "referenceimageurls",
        "reference_image",
        "reference_images",
        "reference_image_url",
        "reference_image_urls",
        "inputimage",
        "inputimages",
        "input_image",
        "input_images",
        "baseimage",
        "baseimages",
        "baseimageurl",
        "baseimageurls",
        "base_image",
        "base_images",
        "base_image_url",
        "base_image_urls",
        "base_image_ref",
        "base_image_refs",
        "initimage",
        "init_image",
        "firstframeimage",
        "firstframeurl",
        "first_frame_image",
        "first_frame_url",
        "source_ref",
        "source_refs",
        "references",
    }


def _looks_like_base64_image(text: str) -> bool:
    if len(text) < 64:
        return False
    return bool(re.fullmatch(r"[A-Za-z0-9+/=\s]+", text[:128]))


def _mentions_from_message_tokens(context: RunContext) -> list[ReferenceMention]:
    tokens = extract_at_tokens(context.message or "")
    if not tokens:
        return []
    catalog = _label_catalog(context)
    mentions: list[ReferenceMention] = []
    for token in tokens:
        entry = catalog.get(token) or _fuzzy_label_match(token, catalog)
        if entry is None:
            continue
        mentions.append(entry)
    return mentions


def _mentions_from_content_parts(context: RunContext) -> list[ReferenceMention]:
    mentions: list[ReferenceMention] = []
    for index, part in enumerate(context.contentParts or [], start=1):
        if not isinstance(part, dict):
            continue
        part_type = str(part.get("type") or "").strip().lower()
        if part_type not in {"image", "file"}:
            continue
        url = _normalize_media_url(str(part.get("url") or ""))
        if not url:
            continue
        asset_key = str(part.get("asset_key") or part.get("assetKey") or "").strip()
        file_id = part.get("file_id", part.get("fileId"))
        name = str(part.get("name") or f"{part_type}{index}").strip()
        label = name if name.startswith("@") else f"@图片{len(mentions) + 1}-{name}"
        mentions.append(
            ReferenceMention(
                token=label,
                refLabel=label,
                assetKey=asset_key or None,
                fileId=file_id,
                url=url,
                kind=part_type,
                name=name,
                contentType=str(part.get("content_type") or part.get("contentType") or "") or None,
                source="content_parts",
            )
        )
    return mentions


def extract_at_tokens(message: str) -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []
    for match in _AT_TOKEN_PATTERN.finditer(message or ""):
        token = match.group(0).strip()
        if token and token not in seen:
            seen.add(token)
            ordered.append(token)
    return ordered


def _label_catalog(context: RunContext) -> dict[str, ReferenceMention]:
    catalog: dict[str, ReferenceMention] = {}
    for mention in context.referenceMentions or []:
        label = (mention.refLabel or mention.token or "").strip()
        if label:
            catalog[label] = mention
        token = (mention.token or "").strip()
        if token and token not in catalog:
            catalog[token] = mention

    for file in context.agentFiles or []:
        if not _is_ready_image_file(file):
            continue
        label = (file.originalFilename or "").strip()
        url = _normalize_media_url(file.downloadUrl)
        if not label or not url:
            continue
        mention = ReferenceMention(
            token=label,
            refLabel=label,
            url=url,
            kind="image",
            source="agent_file" if file.id is not None and file.id > 0 else "current_turn",
        )
        catalog.setdefault(label, mention)
        base = _base_image_label(label)
        if base and base not in catalog:
            catalog[base] = mention
    return catalog


def _mention_lookup(context: RunContext) -> dict[str, ReferenceMention]:
    lookup: dict[str, ReferenceMention] = {}
    for index, mention in enumerate(build_reference_plan(context).mentions, start=1):
        for key in (
            current_attachment_alias(index),
            mention.assetKey,
            str(mention.fileId or "") if mention.fileId is not None else "",
            _normalize_media_url(mention.url),
            mention.url,
            mention.token,
            mention.refLabel,
            llm_token_for_mention(mention),
            mention.name,
        ):
            normalized = (key or "").strip()
            if normalized and normalized not in lookup:
                lookup[normalized] = mention
    return lookup


def _mention_for_part(part: dict, lookup: dict[str, ReferenceMention]) -> ReferenceMention | None:
    for key in (
        str(part.get("asset_key") or part.get("assetKey") or "").strip(),
        str(part.get("file_id") or part.get("fileId") or "").strip(),
        _normalize_media_url(str(part.get("url") or "")),
        str(part.get("url") or "").strip(),
        str(part.get("name") or "").strip(),
    ):
        if key and key in lookup:
            return lookup[key]
    return None


def _replace_reference_placeholders(prompt: str, context: RunContext) -> str:
    lookup = _mention_lookup(context)
    if not lookup:
        return prompt

    def replace(match: re.Match[str]) -> str:
        key = match.group(1).strip()
        mention = lookup.get(key)
        return llm_token_for_mention(mention) if mention is not None else match.group(0)

    return re.sub(r"\{([^{}]+)\}", replace, prompt)


def _content_parts_to_text(context: RunContext) -> str:
    parts = context.contentParts or []
    if not parts:
        return ""
    lookup = _mention_lookup(context)
    rendered: list[str] = []
    image_index = 0
    for part in parts:
        if not isinstance(part, dict):
            continue
        part_type = str(part.get("type") or "").strip().lower()
        if part_type == "text":
            rendered.append(str(part.get("text") or ""))
            continue
        if part_type not in {"image", "file"}:
            continue
        image_index += 1
        mention = _mention_for_part(part, lookup)
        if mention is not None:
            rendered.append(llm_token_for_mention(mention))
        else:
            rendered.append(str(part.get("name") or f"@图片{image_index}"))
    return "".join(rendered).strip()


def _fuzzy_label_match(token: str, catalog: dict[str, ReferenceMention]) -> ReferenceMention | None:
    base = _base_image_label(token)
    if base and base in catalog:
        return catalog[base]
    for label, mention in catalog.items():
        label_base = _base_image_label(label)
        if label.startswith(token) or (label_base and token.startswith(label_base)):
            return mention
    return None


def _base_image_label(label: str) -> str | None:
    text = (label or "").strip()
    if not text.startswith("@图片"):
        return None
    match = re.match(r"(@图片\d+)", text)
    return match.group(1) if match else None


def _is_ready_image_file(file: AgentFileContext) -> bool:
    if file.status != "READY":
        return False
    content_type = (file.contentType or "").lower()
    filename = (file.originalFilename or "").lower()
    return content_type.startswith("image/") or filename.endswith(
        (".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif")
    ) or filename.startswith("@图片")


def _is_visual_mention(mention: ReferenceMention) -> bool:
    kind = (mention.kind or "").strip().lower()
    content_type = (mention.contentType or "").strip().lower()
    url = (mention.url or "").strip().lower()
    name = (mention.name or "").strip().lower()
    if kind == "image" or content_type.startswith("image/") or url.startswith("data:image/"):
        return True
    return url.endswith((".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif")) or name.endswith(
        (".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif")
    )


def _normalize_media_url(raw: str | None) -> str:
    value = (raw or "").strip()
    if not value:
        return ""
    if value.startswith(("http://", "https://", "data:")):
        return value
    base = settings.backend_internal_base_url.rstrip("/")
    path = value if value.startswith("/") else f"/{value}"
    return f"{base}{path}"
