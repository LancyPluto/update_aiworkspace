import re
from typing import Any

from tools.errors import ToolResultBuildError


SECTION_PATTERN = re.compile(
    r"^##\s*(卖点提炼|详情页主文案|分段排版建议|关键词建议|合规与发布提醒)\s*$",
    re.MULTILINE,
)


def parse_result_markdown(markdown_text: str) -> dict[str, Any]:
    sections = _extract_sections(markdown_text or "")

    selling_points = _normalize_block(sections.get("卖点提炼", ""))
    main_copy = _normalize_block(sections.get("详情页主文案", ""))
    layout = _normalize_block(sections.get("分段排版建议", ""))
    keywords = _parse_keywords_block(sections.get("关键词建议", ""))
    compliance = _normalize_block(sections.get("合规与发布提醒", ""))

    if not selling_points or len(selling_points) < 8:
        raise ToolResultBuildError(
            "product_detail_page_copywriter output: 卖点提炼 missing or too short"
        )
    if not main_copy or len(main_copy) < 40:
        raise ToolResultBuildError(
            "product_detail_page_copywriter output: 详情页主文案 missing or too short"
        )
    if not layout or len(layout) < 10:
        raise ToolResultBuildError(
            "product_detail_page_copywriter output: 分段排版建议 missing or too short"
        )
    if not keywords:
        raise ToolResultBuildError(
            "product_detail_page_copywriter output: 关键词建议 missing or empty"
        )
    if not compliance or len(compliance) < 8:
        raise ToolResultBuildError(
            "product_detail_page_copywriter output: 合规与发布提醒 missing or too short"
        )

    return {
        "selling_points": selling_points,
        "main_copy": main_copy,
        "layout": layout,
        "keywords": keywords,
        "compliance": compliance,
    }


def _extract_sections(markdown_text: str) -> dict[str, str]:
    matches = list(SECTION_PATTERN.finditer(markdown_text))
    if not matches:
        raise ToolResultBuildError(
            "product_detail_page_copywriter output missing required markdown sections"
        )

    sections: dict[str, str] = {}
    for index, match in enumerate(matches):
        section_name = match.group(1)
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(markdown_text)
        sections[section_name] = markdown_text[start:end].strip()
    return sections


def _normalize_block(block: str) -> str:
    lines = [line.strip() for line in block.splitlines() if line.strip()]
    return "\n".join(lines)


def _parse_keywords_block(block: str) -> list[str]:
    hashtags = re.findall(r"#\S+", block)
    if hashtags:
        return hashtags
    return [line.strip("- ").strip() for line in block.splitlines() if line.strip()]
