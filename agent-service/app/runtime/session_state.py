from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any

from app.core.schemas import RecentToolCallContext, ReferenceMention, RunContext


SESSION_STATE_INSTRUCTIONS = (
    "You may receive a <SessionState> block containing latest generated media and the prompt used to create it.\n\n"
    "Coreference resolution:\n"
    "- Interpret user references such as this, that, the current image, previous image, original image, "
    "or similar expressions by reading <SessionState>.\n"
    "- If the user asks to edit or adjust an existing generated image, set base_image_url to latest_generated_image.url "
    "or base_image_ref to latest_generated_image.url for the v2-lite image schema.\n"
    "- If the user @-references additional images, put those images in reference_images or references unless the user "
    "clearly says one of them is the base image.\n"
    "- Current visible attachments are isolated with turn-local aliases such as [当前参考图_1]. "
    "Use the alias, not raw @图片 labels, in references[].source_ref.\n\n"
    "Prompt inheritance:\n"
    "- For image edit/follow-up tasks, copy latest_generated_image.prompt as the base of the new prompt. "
    "This prompt is a visual-only prompt with prior routing/system rules removed.\n"
    "- Apply only the user's requested delta, such as face reference, pose change, small style adjustment, cleanup, "
    "or composition tweak.\n"
    "- Do not rewrite the whole scene, style, clothing, lighting, background, camera, layout, or genre unless the user "
    "explicitly asks for that change.\n"
    "- If the user asks for a face/reference adjustment, preserve the original prompt's environment, outfit, "
    "composition, lighting, and art direction.\n"
    "- The final prompt must be: original prompt + minimal explicit edit instruction.\n\n"
    "For edit or variation operations with the v2-lite image schema:\n"
    "- You MUST copy the chosen image prompt from <SessionState> into base_prompt verbatim.\n"
    "- Do not summarize, translate, rewrite, shorten, or beautify base_prompt.\n"
    "- Put only the user's new requested changes into modification_prompt.\n"
    "- Never put a full rewritten scene into modification_prompt."
)

MAX_GENERATED_IMAGES = 2
MAX_VISIBLE_ATTACHMENTS = 8
LATEST_PROMPT_MAX_CHARS = 3000
OLDER_PROMPT_MAX_CHARS = 1500
REFERENCE_NOTES_MAX_CHARS = 300


_PROMPT_SECTION_MARKERS = (
    r"REFERENCE ROUTING\s*:",
    r"STRICT PRESERVATION\s*:",
    r"EDIT INSTRUCTION\s*:",
)

_CHINESE_REFERENCE_RULE_PATTERN = re.compile(
    r"\s*参考图角色约束(?:（[^）]*）)?[:：].*?(?:最终输出需明确保证[:：][^。]*。?|$)",
    re.DOTALL,
)


@dataclass(frozen=True)
class LatestImageState:
    tool_call_id: int
    tool_code: str
    task_id: int | None
    image_url: str
    prompt: str
    arguments: dict[str, Any]
    created_at: str | None


@dataclass(frozen=True)
class GeneratedImageState:
    id: str
    label: str
    tool_call_id: int
    tool_code: str
    task_id: int | None
    image_url: str
    prompt: str
    arguments: dict[str, Any]
    routing_notes: str
    references: list[dict[str, str]]
    created_at: str | None


def latest_generated_image_state(calls: list[RecentToolCallContext]) -> LatestImageState | None:
    states = recent_generated_image_states(calls)
    if not states:
        return None
    latest = states[0]
    return LatestImageState(
        tool_call_id=latest.tool_call_id,
        tool_code=latest.tool_code,
        task_id=latest.task_id,
        image_url=latest.image_url,
        prompt=latest.prompt,
        arguments=latest.arguments,
        created_at=latest.created_at,
    )


def recent_generated_image_states(calls: list[RecentToolCallContext]) -> list[GeneratedImageState]:
    states: list[GeneratedImageState] = []
    for call in calls or []:
        image_url = _first_text(call.mediaUrls)
        if not image_url:
            continue
        arguments = dict(call.argumentsJson or {})
        prompt = sanitize_visual_prompt(_prompt_from_arguments(arguments) or _prompt_from_result(call.resultJson or {}))
        prompt_limit = LATEST_PROMPT_MAX_CHARS if not states else OLDER_PROMPT_MAX_CHARS
        states.append(
            GeneratedImageState(
                id=f"gen_{call.id}",
                label="latest_generated_image" if not states else "previous_generated_image",
                tool_call_id=call.id,
                tool_code=call.toolCode,
                task_id=call.taskId,
                image_url=image_url,
                prompt=_truncate_middle(prompt, prompt_limit),
                arguments=arguments,
                routing_notes=_truncate_middle(str(arguments.get("routing_notes") or ""), REFERENCE_NOTES_MAX_CHARS),
                references=_prune_references(arguments.get("references") or []),
                created_at=str(call.createdAt) if call.createdAt is not None else None,
            )
        )
        if len(states) >= MAX_GENERATED_IMAGES:
            break
    return states


def hydrate_session_state(context: RunContext) -> dict[str, Any]:
    images = recent_generated_image_states(context.recentToolCalls)
    attachments = _visible_attachment_state(context)
    if not images and not attachments:
        return {}

    state: dict[str, Any] = {
        "latest_generated_images": [_generated_image_payload(item) for item in images],
        "visible_attachments": attachments,
    }
    if images:
        latest = images[0]
        state["latest_generated_image"] = {
            "url": latest.image_url,
            "prompt": latest.prompt,
            "tool_code": latest.tool_code,
            "tool_call_id": latest.tool_call_id,
            "task_id": latest.task_id,
            "created_at": latest.created_at,
        }
    return state


def format_session_state_context(context: RunContext) -> str:
    state = hydrate_session_state(context)
    if not state:
        return ""
    lines = [
        "<SessionState>",
        "rules:",
        '  - Use this state for coreference such as "刚刚那张图", "上一张", "原图", "图1".',
        "  - For edit/variation, copy the chosen image prompt verbatim into base_prompt; it is visual-only and sanitized.",
        "  - Current visible attachments are pointers; use their alias in references[].source_ref, not raw @图片 labels.",
        "  - Resolve attachment aliases through tool arguments, not local paths.",
    ]

    images = state.get("latest_generated_images") or []
    if images:
        lines.append("latest_generated_images:")
        for item in images:
            lines.extend(
                [
                    f"  - id: {item.get('id') or ''}",
                    f"    label: {item.get('label') or ''}",
                    f"    url: {item.get('url') or ''}",
                    "    prompt: |",
                ]
            )
            for prompt_line in str(item.get("prompt") or "").splitlines() or [""]:
                lines.append(f"      {prompt_line}")
            lines.extend(
                [
                    f"    tool_code: {item.get('tool_code') or ''}",
                    f"    tool_call_id: {item.get('tool_call_id') or ''}",
                    f"    task_id: {item.get('task_id') or ''}",
                    f"    created_at: {item.get('created_at') or ''}",
                ]
            )
            routing_notes = str(item.get("routing_notes") or "").strip()
            if routing_notes:
                lines.append(f"    routing_notes: {routing_notes}")
            references = item.get("references") or []
            if references:
                lines.append("    references:")
                for ref in references:
                    lines.append(
                        "      - "
                        f"id: {ref.get('id') or ''}, "
                        f"role: {ref.get('role') or ''}, "
                        f"source_ref: {ref.get('source_ref') or ''}"
                    )

    attachments = state.get("visible_attachments") or []
    if attachments:
        lines.append("visible_attachments:")
        for item in attachments:
            lines.extend(
                [
                    f"  - alias: {item.get('alias') or ''}",
                    f"    label: {item.get('label') or ''}",
                    f"    original_label: {item.get('original_label') or ''}",
                    f"    url: {item.get('url') or ''}",
                    f"    file_id: {item.get('file_id') or ''}",
                    f"    asset_key: {item.get('asset_key') or ''}",
                    f"    content_type: {item.get('content_type') or ''}",
                ]
            )
    lines.append("</SessionState>")
    return "\n".join(lines)


def _generated_image_payload(item: GeneratedImageState) -> dict[str, Any]:
    return {
        "id": item.id,
        "label": item.label,
        "url": item.image_url,
        "prompt": item.prompt,
        "tool_code": item.tool_code,
        "tool_call_id": item.tool_call_id,
        "task_id": item.task_id,
        "created_at": item.created_at,
        "routing_notes": item.routing_notes,
        "references": item.references,
    }


def _prompt_from_arguments(arguments: dict[str, Any]) -> str:
    operation = str(arguments.get("operation") or "").strip().lower()
    if operation in {"edit", "variation"}:
        base_prompt = sanitize_visual_prompt(str(arguments.get("base_prompt") or ""))
        modification_prompt = sanitize_visual_prompt(str(arguments.get("modification_prompt") or ""))
        if base_prompt and modification_prompt:
            return f"{base_prompt}\n{modification_prompt}"
        if base_prompt or modification_prompt:
            return base_prompt or modification_prompt
    for key in ("generation_prompt", "base_prompt", "prompt", "imagePrompt", "textPrompt", "description", "userRequest"):
        value = arguments.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _prompt_from_result(result: dict[str, Any]) -> str:
    for value in _walk_values(result):
        if isinstance(value, str) and value.strip():
            key, text = value.split(":", 1) if ":" in value else ("", value)
            if key.lower().strip() == "prompt" and text.strip():
                return text.strip()
    return ""


def _first_text(items: list[str]) -> str:
    for item in items or []:
        if isinstance(item, str) and item.strip():
            return item.strip()
    return ""


def _prune_references(references: Any) -> list[dict[str, str]]:
    if not isinstance(references, list):
        return []
    pruned: list[dict[str, str]] = []
    for item in references[:8]:
        if not isinstance(item, dict):
            continue
        pruned.append(
            {
                "id": str(item.get("id") or ""),
                "role": str(item.get("role") or ""),
                "source_ref": str(item.get("source_ref") or ""),
                "notes": _truncate_middle(str(item.get("notes") or ""), REFERENCE_NOTES_MAX_CHARS),
            }
        )
    return pruned


def _visible_attachment_state(context: RunContext) -> list[dict[str, Any]]:
    mentions = list(context.referenceMentions or []) or _mentions_from_content_parts(context)
    visible: list[dict[str, Any]] = []
    seen: set[str] = set()
    for mention in mentions:
        if len(visible) >= MAX_VISIBLE_ATTACHMENTS:
            break
        label = _stable_label(mention)
        key = label or mention.url
        if not key or key in seen:
            continue
        seen.add(key)
        alias = f"[当前参考图_{len(visible) + 1}]"
        visible.append(
            {
                "alias": alias,
                "label": label,
                "original_label": label,
                "url": mention.url,
                "file_id": mention.fileId,
                "asset_key": mention.assetKey,
                "content_type": mention.contentType,
            }
        )
    return visible


def _mentions_from_content_parts(context: RunContext) -> list[ReferenceMention]:
    mentions: list[ReferenceMention] = []
    for index, part in enumerate(context.contentParts or [], start=1):
        if not isinstance(part, dict):
            continue
        part_type = str(part.get("type") or "").strip().lower()
        if part_type not in {"image", "file"}:
            continue
        url = str(part.get("url") or "").strip()
        if not url:
            continue
        name = str(part.get("name") or f"@图片{len(mentions) + 1}").strip()
        label = name if name.startswith("@") else f"@图片{len(mentions) + 1}-{name}"
        mentions.append(
            ReferenceMention(
                token=label,
                refLabel=label,
                assetKey=str(part.get("asset_key") or part.get("assetKey") or "") or None,
                fileId=part.get("file_id", part.get("fileId")),
                url=url,
                kind=part_type,
                name=name,
                contentType=str(part.get("content_type") or part.get("contentType") or "") or None,
                source="content_parts",
            )
        )
    return mentions


def _stable_label(mention: ReferenceMention) -> str:
    return (mention.refLabel or mention.token or mention.name or "").strip()


def _truncate_middle(value: str, max_length: int) -> str:
    if max_length <= 0 or len(value) <= max_length:
        return value
    marker = "[truncated]"
    keep = max(0, max_length - len(marker))
    head = keep // 2
    tail = keep - head
    return value[:head] + marker + value[len(value) - tail :]


def sanitize_visual_prompt(prompt: str) -> str:
    """Strip routing/system blocks from an image prompt while preserving visual content."""
    text = str(prompt or "").strip()
    if not text:
        return ""
    text = _CHINESE_REFERENCE_RULE_PATTERN.sub("", text)
    for marker in _PROMPT_SECTION_MARKERS:
        text = re.split(marker, text, maxsplit=1, flags=re.IGNORECASE | re.DOTALL)[0]
    text = re.sub(r"\s*最终输出需明确保证[:：][^。]*。?", "", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    text = re.sub(r"[ \t]{2,}", " ", text)
    return text.strip()


def _walk_values(value: Any):
    if isinstance(value, dict):
        for key, item in value.items():
            if key == "prompt" and isinstance(item, str):
                yield f"prompt:{item}"
            yield from _walk_values(item)
    elif isinstance(value, list):
        for item in value:
            yield from _walk_values(item)
    elif isinstance(value, str):
        text = value.strip()
        if text.startswith("{") and text.endswith("}"):
            try:
                yield from _walk_values(json.loads(text))
                return
            except Exception:
                pass
        yield value
