import re
from typing import Any

from tools.errors import ToolResultBuildError


SECTION_PATTERN = re.compile(r"^##\s*(优化标题|推荐标题|优化理由|关键词建议|使用提醒)\s*$", re.MULTILINE)
TITLE_PREFIX_PATTERN = re.compile(r"^\s*\d+[\.\)、]\s*")


def parse_result_markdown(markdown_text: str) -> dict[str, Any]:
    sections = _extract_sections(markdown_text or "")

    optimized_titles = _parse_titles(sections.get("优化标题", ""))
    recommended_title = _normalize_block(sections.get("推荐标题", ""))
    reasons = _normalize_block(sections.get("优化理由", ""))
    keywords = _parse_hashtags(sections.get("关键词建议", ""))
    usage_note = _normalize_block(sections.get("使用提醒", ""))

    if len(optimized_titles) < 3 or not recommended_title or not reasons or not keywords or not usage_note:
        raise ToolResultBuildError(
            "product_title output must contain 优化标题、推荐标题、优化理由、关键词建议、使用提醒 and non-empty content"
        )

    return {
        "optimized_titles": optimized_titles,
        "recommended_title": recommended_title,
        "keywords": keywords,
        "usage_note": usage_note,
    }


def _extract_sections(markdown_text: str) -> dict[str, str]:
    matches = list(SECTION_PATTERN.finditer(markdown_text))
    if not matches:
        raise ToolResultBuildError("product_title output missing required markdown sections")

    sections: dict[str, str] = {}
    for index, match in enumerate(matches):
        section_name = match.group(1)
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(markdown_text)
        sections[section_name] = markdown_text[start:end].strip()
    return sections


def _parse_titles(block: str) -> list[str]:
    titles: list[str] = []
    for raw_line in block.splitlines():
        line = TITLE_PREFIX_PATTERN.sub("", raw_line).strip()
        if line:
            titles.append(line)
    return titles


def _parse_hashtags(block: str) -> list[str]:
    hashtags = re.findall(r"#\S+", block)
    if hashtags:
        return hashtags
    return [line.strip("- ").strip() for line in block.splitlines() if line.strip()]


def _normalize_block(block: str) -> str:
    lines = [line.strip() for line in block.splitlines() if line.strip()]
    return "\n".join(lines)
