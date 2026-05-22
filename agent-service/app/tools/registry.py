import re
from dataclasses import dataclass

from app.core.schemas import RunContext, ToolDescriptor


TOOL_KEYWORDS: dict[str, tuple[str, ...]] = {
    "xiaohongshu_copywriting": ("小红书", "种草", "笔记", "爆款笔记"),
    "moments_copywriting_generator": ("朋友圈", "微信朋友圈", "私域文案"),
    "product_title_optimizer": ("商品标题", "标题优化", "电商标题"),
    "wechat_longform_generator": ("公众号", "微信长文", "长文"),
    "social_media_comment_insights_agent": ("社交媒体评论", "小红书评论", "抖音评论", "评论分析", "用户洞察", "产品建议"),
}


@dataclass(frozen=True, slots=True)
class ToolMatch:
    tool: ToolDescriptor
    score: int
    matched_terms: tuple[str, ...]


class ToolRegistry:
    def __init__(self, context: RunContext) -> None:
        self._tools = {tool.toolCode: tool for tool in context.availableTools}

    def list_tools(self) -> list[ToolDescriptor]:
        return list(self._tools.values())

    def get(self, tool_code: str) -> ToolDescriptor | None:
        return self._tools.get(tool_code)

    def match_by_intent(self, message: str) -> ToolDescriptor | None:
        matches = self.rank_by_intent(message)
        return matches[0].tool if matches else None

    def rank_by_intent(self, message: str) -> list[ToolMatch]:
        text = message.lower()
        message_terms = _terms(message)
        matches: list[ToolMatch] = []
        for tool in self._tools.values():
            if not tool.autoCallable:
                continue
            score, matched_terms = _score_tool(text, message_terms, tool)
            if score > 0:
                matches.append(ToolMatch(tool=tool, score=score, matched_terms=tuple(sorted(matched_terms))))
        return sorted(matches, key=lambda item: (-item.score, item.tool.toolCode))


def _score_tool(text: str, message_terms: set[str], tool: ToolDescriptor) -> tuple[int, set[str]]:
    score = 0
    matched_terms: set[str] = set()
    phrases = _phrases(tool)
    for phrase in phrases:
        lowered = phrase.lower()
        if lowered and lowered in text:
            matched_terms.add(phrase)
            score += 6 if len(lowered) >= 3 else 3
    tool_terms = _terms(" ".join(phrases))
    for term in tool_terms:
        if term in message_terms:
            matched_terms.add(term)
            score += 2 if len(term) >= 3 else 1
    if tool.toolCode.lower() in text:
        matched_terms.add(tool.toolCode)
        score += 8
    return score, matched_terms


def _phrases(tool: ToolDescriptor) -> list[str]:
    phrases: list[str] = [
        tool.toolCode,
        tool.toolName,
        tool.description or "",
    ]
    phrases.extend(TOOL_KEYWORDS.get(tool.toolCode, ()))
    for value in tool.hints.values():
        if isinstance(value, str):
            phrases.append(value)
        elif isinstance(value, (list, tuple, set)):
            phrases.extend(str(item) for item in value)
    return [phrase.strip() for phrase in phrases if isinstance(phrase, str) and phrase.strip()]


def _terms(value: str) -> set[str]:
    terms: set[str] = set()
    for term in re.findall(r"[a-z0-9_]+|[\u4e00-\u9fff]{2,}", value.lower()):
        if not term:
            continue
        terms.add(term)
        if re.fullmatch(r"[\u4e00-\u9fff]{2,}", term):
            max_size = min(4, len(term))
            for size in range(2, max_size + 1):
                for index in range(0, len(term) - size + 1):
                    terms.add(term[index : index + size])
    return terms
