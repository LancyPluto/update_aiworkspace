from __future__ import annotations

import re
from typing import Any

from app.config import settings
from app.core.schemas import RunContext
GRAPH_SYSTEM_PROMPT = (
    "You are a capable cloud agent for an AI tool marketplace. "
    "Plan and execute the user's request step by step. "
    "You can call platform AI product tools to generate images, videos, audio, or text, and you may "
    "call several tools in sequence to complete a multi-step task (for example generate an image, then "
    "use that image to generate a video). "
    "Each product tool runs with its own backend model binding; never assume your own model produces the media. "
    "When a task needs multiple steps, call update_plan first with a short todo list. "
    "When a tool fails, read the error and either fix the arguments and retry, or explain the problem. "
    "When you have everything you need, stop calling tools and write a concise final answer for the user "
    "(or call finish with the answer)."
)

_MEDIA_FIELD_HINTS = {
    "image": ("image", "img", "picture", "photo", "cover", "reference_image", "init_image", "first_frame", "last_frame"),
    "video": ("video", "clip", "movie", "footage"),
    "audio": ("audio", "voice", "sound", "music", "speech"),
}

_URL_RE = re.compile(r"https?://[^\s\"'）)】\]]+")


def format_available_tools_prompt(context: RunContext) -> str:
    available = context.availableTools or []
    if not available:
        return ""
    lines = []
    for tool in available:
        name = tool.toolName or tool.toolCode
        if tool.description:
            lines.append(f"- {name} ({tool.toolCode}): {tool.description}")
        else:
            lines.append(f"- {name} ({tool.toolCode})")
    return (
        "你可以读取并编排的平台 AI 工具如下。这些工具会使用各自后台绑定的模型配置，"
        "不要把当前 Agent 模型当作工具执行模型。\n" + "\n".join(lines)
    )


def format_file_context(context: RunContext) -> str:
    files = [f for f in (context.agentFiles or []) if (f.extractedText or "").strip()]
    if not files:
        return ""
    parts = ["以下是用户上传的附件内容（节选）："]
    for file in files[:5]:
        text = (file.extractedText or "").strip()
        if len(text) > 2000:
            text = text[:2000] + "…"
        parts.append(f"【{file.originalFilename}】\n{text}")
    return "\n\n".join(parts)


def field_media_modality(key: str, spec: dict[str, Any]) -> str | None:
    name = (key or "").lower()
    declared = str(spec.get("x-modality") or spec.get("format") or "").lower()
    for modality, hints in _MEDIA_FIELD_HINTS.items():
        if modality in declared:
            return modality
        if any(hint in name for hint in hints):
            return modality
    return None


def first_url(value: Any) -> str | None:
    if not value:
        return None
    match = _URL_RE.search(str(value))
    return match.group(0) if match else None


def chunk_text(value: str, size: int) -> list[str]:
    if not value:
        return []
    return [value[i:i + size] for i in range(0, len(value), size)]


def estimate_tokens(value: str) -> int:
    if not value:
        return 0
    return max(1, len(value) // 4)
