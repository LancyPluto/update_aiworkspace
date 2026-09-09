"""Unified context window / truncation manager for all agent engines.

This module centralises the previously-duplicated history-windowing logic that
lived inside the graph engine and tool-call helpers.

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

from dataclasses import dataclass
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
ROLLING_SUMMARY_PROMPT = """你是会话记忆压缩器。请把旧的会话摘要和新增的旧消息合并成一份新的 Rolling Conversation Summary。

目标：
- 保留用户最初目标、长期偏好、关键约束、已确认事实、重要生成结果、当前任务脉络。
- 保留对后续续写/改图/改歌/排查有用的参数、风格、对象、名称、文件/图片引用的语义描述。
- 删除寒暄、重复确认、失败重试细节、无长期价值的中间推理。
- 不要编造没有出现过的信息。
- 如果用户问“第一句话/最初需求/前面生成了什么”，摘要必须能回答。
- <SessionState>、文件 URL、图片 URL、工具 taskId 不要在这里完整复制；只保留语义描述，具体资源由 SessionState 管理。

输出要求：
- 使用中文。
- 控制在 800-1200 tokens 内。
- 按以下结构输出纯文本，不要 Markdown 表格：

【初始目标】
...
【用户偏好与约束】
...
【关键进展】
...
【未完成/当前脉络】
...
【重要实体】
...
"""


def count_tokens(text: str, model_name: str | None = None) -> int:
    """Count tokens with tiktoken when available, falling back to the old heuristic."""
    if not text:
        return 0
    try:
        import tiktoken  # type: ignore

        if model_name:
            try:
                encoding = tiktoken.encoding_for_model(model_name)
            except Exception:
                encoding = tiktoken.get_encoding("cl100k_base")
        else:
            encoding = tiktoken.get_encoding("cl100k_base")
        return max(1, len(encoding.encode(text)))
    except Exception:
        return max(1, len(text) // 4)


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


def token_middle_truncate(
    text: str,
    token_limit: int,
    head_ratio: float = 0.6,
    *,
    model_name: str | None = None,
) -> str:
    """Middle-truncate by token budget without requiring tokenizer-specific slicing."""
    if token_limit <= 0:
        return text or ""
    if not text or count_tokens(text, model_name=model_name) <= token_limit:
        return text or ""

    # Approximate the character budget from the observed token density, then
    # shrink until the fallback text fits the requested token budget.
    tokens = count_tokens(text, model_name=model_name)
    char_limit = max(1, int(len(text) * (token_limit / max(tokens, 1))))
    candidate = middle_truncate(text, char_limit, head_ratio=head_ratio)
    while char_limit > 1 and count_tokens(candidate, model_name=model_name) > token_limit:
        char_limit = max(1, int(char_limit * 0.85))
        candidate = middle_truncate(text, char_limit, head_ratio=head_ratio)
    return candidate


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


def trim_tool_output_by_tokens(
    content: str,
    token_limit: int,
    head_ratio: float = 0.6,
    *,
    model_name: str | None = None,
) -> str:
    """Token-aware tool result truncation that preserves every URL."""
    if not content:
        return content or ""
    if count_tokens(content, model_name=model_name) <= token_limit:
        return content
    body = token_middle_truncate(content, token_limit, head_ratio=head_ratio, model_name=model_name)
    urls = URL_RE.findall(content)
    if not urls:
        return body
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
    """Compatibility wrapper for old callers; now token-aware when possible."""
    return count_tokens(text)


def estimate_messages_tokens(messages: list[ChatMessage]) -> int:
    total = 0
    for message in messages:
        total += estimate_tokens(message_text_content(message.content))
    return total


def message_text_content(content: str | list[dict] | None) -> str:
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    texts: list[str] = []
    for part in content:
        if isinstance(part, dict) and part.get("type") == "text" and isinstance(part.get("text"), str):
            texts.append(part["text"])
    return "\n".join(texts)


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


@dataclass(slots=True)
class ContextSplit:
    summary_candidates: list[ChatMessage]
    recent_messages: list[ChatMessage]


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
        working_memory_token_budget: int = 6000,
        message_token_soft_limit: int = 1200,
        tool_output_token_soft_limit: int = 500,
        summary_token_limit: int = 1200,
        head_ratio: float = 0.6,
        model_name: str | None = None,
    ) -> None:
        self.max_recent_turns = max(1, max_recent_turns)
        self.max_history_messages = max(1, max_history_messages)
        self.msg_char_limit = max(0, msg_char_limit)
        self.tool_output_char_limit = max(0, tool_output_char_limit)
        self.pruning_tool_char_limit = max(0, pruning_tool_char_limit)
        self.prune_token_budget = max(0, prune_token_budget)
        self.working_memory_token_budget = max(0, working_memory_token_budget)
        self.message_token_soft_limit = max(0, message_token_soft_limit)
        self.tool_output_token_soft_limit = max(0, tool_output_token_soft_limit)
        self.summary_token_limit = max(0, summary_token_limit)
        self.head_ratio = min(1.0, max(0.0, head_ratio))
        self.model_name = model_name

    @classmethod
    def from_settings(cls, settings: "Settings", runtime_settings: object | None = None) -> "ContextManager":
        def _runtime_int(name: str, default: int) -> int:
            value = getattr(runtime_settings, name, None) if runtime_settings is not None else None
            if value is None:
                return default
            try:
                return int(value)
            except (TypeError, ValueError):
                return default

        return cls(
            max_recent_turns=getattr(settings, "agent_context_max_recent_turns", 5),
            max_history_messages=_runtime_int(
                "maxHistoryMessages",
                getattr(settings, "agent_max_history_messages", 20),
            ),
            msg_char_limit=getattr(settings, "agent_msg_char_limit", 2000),
            tool_output_char_limit=getattr(settings, "agent_tool_output_char_limit", 800),
            pruning_tool_char_limit=getattr(settings, "agent_pruning_tool_char_limit", 400),
            prune_token_budget=getattr(settings, "agent_context_prune_token_budget", 3000),
            working_memory_token_budget=_runtime_int(
                "workingMemoryTokenBudget",
                getattr(settings, "agent_working_memory_token_budget", 6000),
            ),
            message_token_soft_limit=_runtime_int(
                "messageTokenSoftLimit",
                getattr(settings, "agent_message_token_soft_limit", 1200),
            ),
            tool_output_token_soft_limit=_runtime_int(
                "toolOutputTokenSoftLimit",
                getattr(settings, "agent_tool_output_token_soft_limit", 500),
            ),
            summary_token_limit=_runtime_int(
                "summaryTokenLimit",
                getattr(settings, "agent_summary_token_limit", 1200),
            ),
            model_name=getattr(settings, "model_name", None),
        )

    def window(self, history: list[ChatMessage] | None) -> list[ChatMessage]:
        """Keep the last ``max_recent_turns`` turns, bounded by the hard cap."""
        return self._window_by_turns(history)

    def _window_by_turns(self, history: list[ChatMessage] | None) -> list[ChatMessage]:
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

    def split_history(self, history: list[ChatMessage] | None) -> ContextSplit:
        """Split raw history into evicted summary candidates and recent working memory."""
        messages = list(history or [])
        windowed = self._window_by_turns(messages)
        recent = self._fit_token_budget(windowed, self.working_memory_token_budget)
        recent_ids = {id(message) for message in recent}
        old = [message for message in messages if id(message) not in recent_ids]
        return ContextSplit(summary_candidates=old, recent_messages=recent)

    def _fit_token_budget(self, messages: list[ChatMessage], token_budget: int) -> list[ChatMessage]:
        """Keep newest messages within token budget while preserving tool-call pair boundaries."""
        if not messages or token_budget <= 0:
            return messages
        selected: list[ChatMessage] = []
        total = 0
        idx = len(messages) - 1
        while idx >= 0:
            block_start = _align_window_start(messages, idx)
            block = messages[block_start : idx + 1]
            block_tokens = estimate_messages_tokens(block)
            if selected and total + block_tokens > token_budget:
                break
            selected = [*block, *selected]
            total += block_tokens
            idx = block_start - 1
        return selected or messages[-1:]

    def prune(self, messages: list[ChatMessage]) -> list[ChatMessage]:
        """Light session pruning: trim ``tool`` outputs only when over token budget."""
        if not messages or self.prune_token_budget <= 0:
            return messages
        if estimate_messages_tokens(messages) <= self.prune_token_budget:
            return messages

        pruned: list[ChatMessage] = []
        for message in messages:
            role = (message.role or "").lower()
            content = message_text_content(message.content)
            if role == "tool" and len(content) > self.pruning_tool_char_limit:
                pruned.append(
                    message.model_copy(
                        update={
                            "content": trim_tool_output_by_tokens(
                                content,
                                self.tool_output_token_soft_limit,
                                self.head_ratio,
                                model_name=self.model_name,
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
            content = message_text_content(message.content)
            if not content:
                continue
            shorter_limit = max(20, self.tool_output_token_soft_limit // 2)
            mutable[idx] = message.model_copy(
                update={
                    "content": trim_tool_output_by_tokens(
                        content,
                        shorter_limit,
                        self.head_ratio,
                        model_name=self.model_name,
                    )
                }
            )
        return mutable

    def compact(self, messages: list[ChatMessage]) -> list[ChatMessage]:
        """Token-aware middle truncation for each message; tool outputs keep URLs."""
        compacted: list[ChatMessage] = []
        for message in messages:
            content = message_text_content(message.content)
            role = (message.role or "").lower()
            if role == "tool":
                new_content = trim_tool_output_by_tokens(
                    content,
                    self.tool_output_token_soft_limit,
                    self.head_ratio,
                    model_name=self.model_name,
                )
            else:
                new_content = token_middle_truncate(
                    content,
                    self.message_token_soft_limit,
                    self.head_ratio,
                    model_name=self.model_name,
                )
            if new_content == content:
                compacted.append(message)
            else:
                compacted.append(message.model_copy(update={"content": new_content}))
        return compacted

    def compact_by_tokens(self, messages: list[ChatMessage]) -> list[ChatMessage]:
        return self.compact(messages)

    def build_working_memory(self, history: list[ChatMessage] | None) -> list[ChatMessage]:
        """Build short-term working memory only, without rolling summary injection."""
        split = self.split_history(history)
        return self.compact_by_tokens(self.prune(split.recent_messages))

    def format_conversation_summary(self, summary: str | None) -> ChatMessage | None:
        """Format rolling summary as an isolated system-memory block."""
        text = (summary or "").strip()
        if not text:
            return None
        clipped = token_middle_truncate(
            text,
            self.summary_token_limit,
            self.head_ratio,
            model_name=self.model_name,
        )
        return ChatMessage(
            role="system",
            content=f"<Conversation_Summary>\n{clipped}\n</Conversation_Summary>",
        )

    def build_context_messages(
        self,
        history: list[ChatMessage] | None,
        conversation_summary: str | None = None,
    ) -> list[ChatMessage]:
        """Build tiered memory messages: rolling summary first, then working memory."""
        messages: list[ChatMessage] = []
        summary = self.format_conversation_summary(conversation_summary)
        if summary is not None:
            messages.append(summary)
        messages.extend(self.build_working_memory(history))
        return messages

    def build_history(self, history: list[ChatMessage] | None) -> list[ChatMessage]:
        """Backward-compatible alias for short-term working memory."""
        return self.build_working_memory(history)

    def build_history_metrics(self, history: list[ChatMessage] | None) -> dict[str, int | bool]:
        """Token estimates for observability (window / prune / compact)."""
        raw = list(history or [])
        split = self.split_history(raw)
        windowed = split.recent_messages
        pruned = self.prune(windowed)
        compacted = self.compact(pruned)
        return {
            "historyMessages": len(raw),
            "summaryCandidateMessages": len(split.summary_candidates),
            "estimatedTokensBefore": estimate_messages_tokens(raw),
            "estimatedTokensAfterWindow": estimate_messages_tokens(windowed),
            "estimatedTokensAfterPrune": estimate_messages_tokens(pruned),
            "estimatedTokensAfter": estimate_messages_tokens(compacted),
            "pruningApplied": pruned != windowed,
        }

    @staticmethod
    def estimate_tokens(messages: list[ChatMessage]) -> int:
        return estimate_messages_tokens(messages)
