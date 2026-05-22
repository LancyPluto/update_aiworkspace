import re

from tools.errors import ToolResultBuildError


REQUIRED_SECTIONS = (
    "检索范围与可用性说明",
    "评论信号摘要",
    "用户期望与购买动机",
    "高频痛点与顾虑",
    "平台差异洞察",
    "产品与运营建议",
    "内容选题与话术方向",
    "待验证问题清单",
    "来源与样本记录",
)


def format_publishable_text(markdown_text: str) -> str:
    normalized = (markdown_text or "").strip()
    _validate_required_sections(normalized)
    return normalized


def _validate_required_sections(markdown_text: str) -> None:
    missing = [
        section
        for section in REQUIRED_SECTIONS
        if not re.search(rf"^##\s*\d*\.?\s*{re.escape(section)}\s*$", markdown_text, re.MULTILINE)
    ]
    if missing:
        raise ToolResultBuildError(
            f"social media comment insights output missing section content: {', '.join(missing)}"
        )
