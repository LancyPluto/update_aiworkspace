"""Unified context window / truncation manager for all agent engines.

This module centralises the previously-duplicated history-windowing logic that
lived inside ``agent_graph.engine``, ``deep_agents_engine`` and ``tool_call_loop``.

Goals (Phase 1 of the context-cost refactor):

* **Sliding window** - keep only the last ``max_recent_turns`` conversation
  turns (a turn starts at a ``user`` message), bounded by a hard message cap.
* **Session pruning** - lightly trim ``tool`` role messages when the in-memory
  context exceeds a token budget (OpenClaw-style; does not rewrite stored history).
* **Per-message middle truncation** - a single oversized message (long tool
  JSON / HTML / code) is collapsed to ``head + ...[truncated]... + tail`` so the
  most informative beginning and ending survive.
* **Tool-output trimming that preserves URLs** - product tool results carry
  media URLs that downstream steps may need; truncation must never drop them.

Pipeline: ``window`` -> ``prune`` -> ``compact``.
"""

from __future__ import annotations

import re
from typing import TYPE_CHECKING

from app.core.schemas import ChatMessage

if TYPE_CHECKING:  # pragma: no cover - typing only.
    from app.config import Settings

# Matches http(s) URLs, stopping at whitespace and common CJK / bracket
# punctuation so trailing characters are not swallowed into the URL.
URL_RE = re.compile(r"https?://[^\s\"'）)】\]]+")

_TRUNCATION_NOTE = "\n...[已截断 {removed} 字]...\n"
_RETAINED_LINKS_PREFIX = "\n[保留链接] "


def middle_truncate(text: str, limit: int, head_ratio: float = 0.6) -> str:
    """Collapse the middle of an oversized string, keeping head and tail.

    ``limit`` is the maximum number of characters to keep from the original
    text (the truncation marker is added on top). ``head_ratio`` controls how
    much of the budget goes to the beginning vs the end.
    """
    if limit <= 0:
        return text or ""
    if not text or len(text) <= limit:
        return text or ""
    head = max(0, int(limit * head_ratio))
    tail = max(0, limit - head)
    removed = len(text) - limit
    note = _TRUNCATION_NOTE.format(removed=removed)
    if tail == 0:
        return f"{text[:head]}{note}"
    return f"{text[:head]}{note}{text[-tail:]}"


def trim_tool_output(content: str, limit: int, head_ratio: float = 0.6) -> str:
    """Truncate a tool result while guaranteeing every URL survives.

    Media URLs (image/video/audio results) are extracted first, the body is
    middle-truncated, and any URL that the truncation dropped is appended back
    so chaining / citation never breaks.
    """
    if not content:
        return content or ""
    if len(content) <= limit:
        return content
    body = middle_truncate(content, limit, head_ratio=head_ratio)
    urls = URL_RE.findall(content)
    if not urls:
        return body
    # De-duplicate while preserving order, keep only URLs the body lost.
    seen: set[str] = set()
    dropped: list[str] = []
    for url in urls:
        if url in seen:
            continue
        seen.add(url)
        if url not in body:
            dropped.append(url)
    if not dropped:
        return body
    return body + _RETAINED_LINKS_PREFIX + " ".join(dropped)


def estimate_tokens(text: str) -> int:
    """Rough token estimate (~4 chars/token), matching the existing heuristic."""
    if not text:
        return 0
    return max(1, len(text) // 4)


def estimate_messages_tokens(messages: list[ChatMessage]) -> int:
    total = 0
    for message in messages:
        total += estimate_tokens(message.content or "")
    return total


def _align_window_start(messages: list[ChatMessage], start: int) -> int:
    """Keep assistant tool-call blocks paired with their tool results (OpenClaw-style)."""
    while start > 0:
        role = (messages[start - 1].role or "").lower()
        if role == "tool":
            start -= 1
            continue
        if role in {"assistant", "ai"} and messages[start - 1].toolCalls:
            start -= 1
            continue
        break
    return start


class ContextManager:
    """Sliding-window + pruning + truncation policy shared by every agent engine."""

    def __init__(
        self,
        *,
        max_recent_turns: int = 5,
        max_history_messages: int = 20,
        msg_char_limit: int = 2000,
        tool_output_char_limit: int = 800,
        pruning_tool_char_limit: int = 400,
        prune_token_budget: int = 3000,
        head_ratio: float = 0.6,
    ) -> None:
        self.max_recent_turns = max(1, max_recent_turns)
        self.max_history_messages = max(1, max_history_messages)
        self.msg_char_limit = max(0, msg_char_limit)
        self.tool_output_char_limit = max(0, tool_output_char_limit)
        self.pruning_tool_char_limit = max(0, pruning_tool_char_limit)
        self.prune_token_budget = max(0, prune_token_budget)
        self.head_ratio = min(1.0, max(0.0, head_ratio))

    @classmethod
    def from_settings(cls, settings: "Settings") -> "ContextManager":
        return cls(
            max_recent_turns=getattr(settings, "agent_context_max_recent_turns", 5),
            max_history_messages=getattr(settings, "agent_max_history_messages", 20),
            msg_char_limit=getattr(settings, "agent_msg_char_limit", 2000),
            tool_output_char_limit=getattr(settings, "agent_tool_output_char_limit", 800),
            pruning_tool_char_limit=getattr(settings, "agent_pruning_tool_char_limit", 400),
            prune_token_budget=getattr(settings, "agent_context_prune_token_budget", 3000),
        )

    def window(self, history: list[ChatMessage] | None) -> list[ChatMessage]:
        """Keep the last ``max_recent_turns`` turns, bounded by the hard cap."""
        if not history:
            return []
        messages = list(history)
        user_indices = [i for i, m in enumerate(messages) if (m.role or "").lower() == "user"]
        if len(user_indices) > self.max_recent_turns:
            start = user_indices[-self.max_recent_turns]
            start = _align_window_start(messages, start)
            messages = messages[start:]
        if len(messages) > self.max_history_messages:
            messages = messages[-self.max_history_messages:]
        return messages

    def prune(self, messages: list[ChatMessage]) -> list[ChatMessage]:
        """Light session pruning: trim ``tool`` outputs only when over token budget."""
        if not messages or self.prune_token_budget <= 0:
            return messages
        if estimate_messages_tokens(messages) <= self.prune_token_budget:
            return messages

        pruned: list[ChatMessage] = []
        for message in messages:
            role = (message.role or "").lower()
            content = message.content or ""
            if role == "tool" and len(content) > self.pruning_tool_char_limit:
                pruned.append(
                    message.model_copy(
                        update={
                            "content": trim_tool_output(
                                content,
                                self.pruning_tool_char_limit,
                                self.head_ratio,
                            )
                        }
                    )
                )
            else:
                pruned.append(message)

        if estimate_messages_tokens(pruned) <= self.prune_token_budget:
            return pruned

        mutable = list(pruned)
        for idx, message in enumerate(mutable):
            if estimate_messages_tokens(mutable) <= self.prune_token_budget:
                break
            if (message.role or "").lower() != "tool":
                continue
            content = message.content or ""
            if not content:
                continue
            shorter_limit = max(80, self.pruning_tool_char_limit // 2)
            mutable[idx] = message.model_copy(
                update={"content": trim_tool_output(content, shorter_limit, self.head_ratio)}
            )
        return mutable

    def compact(self, messages: list[ChatMessage]) -> list[ChatMessage]:
        """Middle-truncate each message; tool outputs keep their URLs."""
        compacted: list[ChatMessage] = []
        for message in messages:
            content = message.content or ""
            role = (message.role or "").lower()
            if role == "tool":
                new_content = trim_tool_output(content, self.tool_output_char_limit, self.head_ratio)
            else:
                new_content = middle_truncate(content, self.msg_char_limit, self.head_ratio)
            if new_content == content:
                compacted.append(message)
            else:
                compacted.append(message.model_copy(update={"content": new_content}))
        return compacted

    def build_history(self, history: list[ChatMessage] | None) -> list[ChatMessage]:
        """Apply sliding window, session pruning, then per-message truncation."""
        return self.compact(self.prune(self.window(history)))

    def build_history_metrics(self, history: list[ChatMessage] | None) -> dict[str, int | bool]:
        """Token estimates for observability (window / prune / compact)."""
        raw = list(history or [])
        windowed = self.window(raw)
        pruned = self.prune(windowed)
        compacted = self.compact(pruned)
        return {
            "historyMessages": len(raw),
            "estimatedTokensBefore": estimate_messages_tokens(raw),
            "estimatedTokensAfterWindow": estimate_messages_tokens(windowed),
            "estimatedTokensAfterPrune": estimate_messages_tokens(pruned),
            "estimatedTokensAfter": estimate_messages_tokens(compacted),
            "pruningApplied": pruned != windowed,
        }

    @staticmethod
    def estimate_tokens(messages: list[ChatMessage]) -> int:
        return estimate_messages_tokens(messages)
