from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from app.core.schemas import RecentToolCallContext, RunContext


SESSION_STATE_INSTRUCTIONS = (
    "You may receive a <SessionState> block containing latest generated media and the prompt used to create it.\n\n"
    "Coreference resolution:\n"
    "- Interpret user references such as this, that, the current image, previous image, original image, "
    "or similar expressions by reading <SessionState>.\n"
    "- If the user asks to edit or adjust an existing generated image, set base_image_url to latest_generated_image.url.\n"
    "- If the user @-references additional images, put those images in reference_images unless the user clearly says "
    "one of them is the base image.\n\n"
    "Prompt inheritance:\n"
    "- For image edit/follow-up tasks, copy latest_generated_image.prompt as the base of the new prompt.\n"
    "- Apply only the user's requested delta, such as face reference, pose change, small style adjustment, cleanup, "
    "or composition tweak.\n"
    "- Do not rewrite the whole scene, style, clothing, lighting, background, camera, layout, or genre unless the user "
    "explicitly asks for that change.\n"
    "- If the user asks for a face/reference adjustment, preserve the original prompt's environment, outfit, "
    "composition, lighting, and art direction.\n"
    "- The final prompt must be: original prompt + minimal explicit edit instruction."
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


def latest_generated_image_state(calls: list[RecentToolCallContext]) -> LatestImageState | None:
    for call in calls or []:
        image_url = _first_text(call.mediaUrls)
        if not image_url:
            continue
        arguments = dict(call.argumentsJson or {})
        prompt = _prompt_from_arguments(arguments)
        if not prompt:
            prompt = _prompt_from_result(call.resultJson or {})
        return LatestImageState(
            tool_call_id=call.id,
            tool_code=call.toolCode,
            task_id=call.taskId,
            image_url=image_url,
            prompt=prompt,
            arguments=arguments,
            created_at=str(call.createdAt) if call.createdAt is not None else None,
        )
    return None


def hydrate_session_state(context: RunContext) -> dict[str, Any]:
    latest = latest_generated_image_state(context.recentToolCalls)
    if latest is None:
        return {}
    return {
        "latest_generated_image": {
            "url": latest.image_url,
            "prompt": latest.prompt,
            "tool_code": latest.tool_code,
            "tool_call_id": latest.tool_call_id,
            "task_id": latest.task_id,
            "created_at": latest.created_at,
        }
    }


def format_session_state_context(context: RunContext) -> str:
    state = hydrate_session_state(context)
    if not state:
        return ""
    latest = state.get("latest_generated_image") or {}
    lines = [
        "<SessionState>",
        "latest_generated_image:",
        f"  url: {latest.get('url') or ''}",
        f"  prompt: {latest.get('prompt') or ''}",
        f"  tool_code: {latest.get('tool_code') or ''}",
        f"  tool_call_id: {latest.get('tool_call_id') or ''}",
        f"  task_id: {latest.get('task_id') or ''}",
        f"  created_at: {latest.get('created_at') or ''}",
        "</SessionState>",
    ]
    return "\n".join(lines)


def _prompt_from_arguments(arguments: dict[str, Any]) -> str:
    for key in ("prompt", "imagePrompt", "textPrompt", "description", "userRequest"):
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
