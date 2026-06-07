from __future__ import annotations

import re
from typing import Any
from urllib.parse import urlparse

from app.core.schemas import RunContext
from app.core.user_attachment_priority import image_download_url

AGENT_FILE_PATH = re.compile(
    r"/api/v1/agent/sessions/(\d+)/files/(\d+)/content",
    re.IGNORECASE,
)


def _normalize_attachment_url(url: str) -> str:
    text = (url or "").strip()
    if not text:
        return ""
    if text.startswith(("http://", "https://")):
        parsed = urlparse(text)
        text = parsed.path or text
    if "?" in text:
        text = text.split("?", 1)[0]
    return text.rstrip("/").lower()


def _known_attachment_urls(context: RunContext) -> set[str]:
    urls: set[str] = set()
    for file in context.agentFiles:
        for candidate in (file.downloadUrl, image_download_url(file)):
            normalized = _normalize_attachment_url(candidate or "")
            if normalized:
                urls.add(normalized)
    return urls


def _positive_file_ids(context: RunContext) -> set[int]:
    return {int(file.id) for file in context.agentFiles if file.id is not None and int(file.id) > 0}


def find_agent_file_refs(value: Any) -> list[tuple[int, int, str]]:
    refs: list[tuple[int, int, str]] = []
    seen: set[tuple[int, int]] = set()

    def walk(node: Any) -> None:
        if isinstance(node, str):
            for match in AGENT_FILE_PATH.finditer(node):
                session_id = int(match.group(1))
                file_id = int(match.group(2))
                key = (session_id, file_id)
                if key not in seen:
                    seen.add(key)
                    refs.append((session_id, file_id, node))
            return
        if isinstance(node, list):
            for item in node:
                walk(item)
            return
        if isinstance(node, dict):
            for item in node.values():
                walk(item)

    walk(value)
    return refs


def validate_attachment_arguments(context: RunContext, arguments: dict[str, Any]) -> list[dict[str, Any]]:
    errors: list[dict[str, Any]] = []
    known_urls = _known_attachment_urls(context)
    positive_ids = _positive_file_ids(context)
    for session_id, file_id, raw in find_agent_file_refs(arguments):
        normalized_raw = _normalize_attachment_url(raw)
        if normalized_raw and normalized_raw in known_urls:
            continue
        if file_id in positive_ids:
            continue
        errors.append(
            {
                "sessionId": session_id,
                "fileId": file_id,
                "raw": raw,
                "availableFileIds": sorted(positive_ids),
                "availableUrls": sorted(known_urls)[:8],
            }
        )
    return errors


def format_attachment_error(errors: list[dict[str, Any]]) -> str:
    if not errors:
        return "参考图附件找不到，请重新上传"
    first = errors[0]
    session_id = first.get("sessionId")
    file_id = first.get("fileId")
    available_ids = [item for item in (first.get("availableFileIds") or []) if isinstance(item, int) and item > 0]
    available_urls = first.get("availableUrls") or []
    hint = f"参考图找不到（session#{session_id} file#{file_id}）"
    if available_ids:
        hint += f"，当前会话已登记附件 ID：{', '.join(str(item) for item in available_ids)}"
    elif available_urls:
        hint += "，当前会话仅有素材 URL 附件（无对应 fileId）"
    hint += "。请在对话里重新通过「素材」选择参考图后发送。"
    return hint
