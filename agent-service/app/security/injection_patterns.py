"""Shared prompt-injection and data-exfiltration pattern checks."""

from __future__ import annotations

import re
import unicodedata

_INVISIBLE_CONTROL_CHARS = tuple(
    chr(code)
    for code in (
        *range(0x200B, 0x2010),
        0x202A,
        0x202B,
        0x202C,
        0x202D,
        0x202E,
        0x2060,
    )
)

_PROMPT_INJECTION_PATTERNS = (
    "ignore previous instructions",
    "ignore all previous instructions",
    "ignore all previous",
    "forget all your rules",
    "forget all",
    "you are now",
    "developer message",
    "reveal your instructions",
    "print your prompt",
    "jailbreak",
    "dan mode",
    "忽略之前",
    "忽略先前",
    "忽略上面",
    "忽略以上",
    "忽略全部",
    "忽略所有指令",
    "忘记所有",
    "忘记之前",
    "你被系统",
    "系统提示词",
    "开发者模式",
    "越狱",
    "绕过安全",
    "绕过限制",
    "泄露提示词",
    "输出提示词",
    "打印提示词",
)

_DATA_EXFILTRATION_PATTERNS = (
    "all user data",
    "all users data",
    "dump all users",
    "export all users",
    "send me all user",
    "every user",
    "other users",
    "other user",
    "cross user",
    "cross-user",
    "admin tool",
    "hidden tool",
    "internal token",
    "api key",
    "secret key",
    "system prompt",
    "modify balance",
    "change credits",
    "把所有用户",
    "全部用户数据",
    "所有用户数据",
    "导出所有用户",
    "泄露用户",
    "其他用户的数据",
    "别的用户",
    "跨用户",
    "管理员工具",
    "隐藏工具",
    "内部令牌",
    "修改余额",
    "改积分",
    "jwt",
)

_SECRET_PATTERNS = (
    r"(?i)\bapi[_-]?key\b\s*[:=]\s*['\"]?[A-Za-z0-9_\-]{16,}",
    r"(?i)\b(secret|token|password)\b\s*[:=]\s*['\"]?[A-Za-z0-9_\-./+=]{12,}",
    r"(?i)\bsk-[A-Za-z0-9]{20,}",
    r"(?i)\bAKIA[0-9A-Z]{16}\b",
)


def normalize_for_inspection(message: str) -> str:
    text = unicodedata.normalize("NFKC", message or "")
    for char in _INVISIBLE_CONTROL_CHARS:
        text = text.replace(char, "")
    return re.sub(r"\s+", " ", text).strip().lower()


def contains_invisible_control_chars(message: str) -> bool:
    return any(char in (message or "") for char in _INVISIBLE_CONTROL_CHARS)


def looks_like_prompt_injection(message: str) -> bool:
    normalized = normalize_for_inspection(message)
    if not normalized:
        return False
    if contains_invisible_control_chars(message):
        return True
    return any(pattern in normalized for pattern in _PROMPT_INJECTION_PATTERNS)


def looks_like_data_exfiltration(message: str) -> bool:
    normalized = normalize_for_inspection(message)
    if not normalized:
        return False
    return any(pattern in normalized for pattern in _DATA_EXFILTRATION_PATTERNS)


def looks_like_secret_exfiltration(message: str) -> bool:
    text = message or ""
    return any(re.search(pattern, text) for pattern in _SECRET_PATTERNS)


def classify_unsafe_message(message: str) -> str | None:
    if looks_like_prompt_injection(message):
        return "prompt_injection"
    if looks_like_data_exfiltration(message):
        return "data_exfiltration"
    if looks_like_secret_exfiltration(message):
        return "secret_exfiltration"
    return None
