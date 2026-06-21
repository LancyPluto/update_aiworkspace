"""Non-routing helpers shared by engines and preferred-tool bias (not intent classifiers)."""

from __future__ import annotations

import re


def looks_like_session_recap_question(message: str) -> bool:
    compact = re.sub(r"\s+", "", message or "")
    if (
        ("刚刚" in compact or "刚才" in compact or "上次" in compact or "这张图" in compact or "这张图片" in compact)
        and ("用什么" in compact or "什么生成" in compact or "哪个工具" in compact or "怎么生成" in compact)
    ):
        return True
    needles = (
        "你之前帮我",
        "你刚刚帮我",
        "你刚才帮我",
        "你刚刚用什么",
        "你刚才用什么",
        "刚刚用什么生成",
        "刚才用什么生成",
        "刚刚是什么生成",
        "刚才是什么生成",
        "刚刚用哪个工具",
        "刚才用哪个工具",
        "你帮我做了什么",
        "你帮我完成了",
        "完成了什么",
        "完成了那些",
        "完成了哪些",
        "做了什么",
        "干了什么",
        "刚才做了什么",
        "上一轮",
        "之前做了什么",
        "帮我完成了什么",
        "帮我做了哪些",
    )
    return any(needle in message for needle in needles)


def is_short_chat_message(message: str) -> bool:
    lowered = (message or "").strip().lower()
    if not lowered:
        return True
    if len(lowered) <= 12:
        short_patterns = {"你是谁", "你能做什么", "你能帮我做什么", "你有什么工具", "有什么工具", "你好", "hi", "hello", "在吗"}
        if lowered in short_patterns:
            return True
    return False


def looks_like_tool_request(message: str) -> bool:
    if looks_like_session_recap_question(message):
        return False
    stripped = (message or "").strip()
    if not stripped or is_short_chat_message(stripped):
        return False
    return len(stripped) >= 4
