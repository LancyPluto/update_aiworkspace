import re
from typing import Any

from tools.errors import ToolResultBuildError
from tools.text_publishable import bracket_section, strip_blank_join


SECTION_PATTERN = re.compile(
    r"^##\s*(活动目标与策略概览|活动节奏与阶段安排|玩法设计与资源分配|投放与转化建议|核心指标与复盘建议)\s*$",
    re.MULTILINE,
)


def parse_result_markdown(markdown_text: str) -> dict[str, Any]:
    sections = _extract_sections(markdown_text or "")
    required = (
        "活动目标与策略概览",
        "活动节奏与阶段安排",
        "玩法设计与资源分配",
        "投放与转化建议",
        "核心指标与复盘建议",
    )
    missing = [name for name in required if not sections.get(name, "").strip()]
    if missing:
        raise ToolResultBuildError(
            f"ecommerce_campaign output missing section content: {', '.join(missing)}"
        )

    return {
        "活动目标与策略概览": sections["活动目标与策略概览"].strip(),
        "活动节奏与阶段安排": sections["活动节奏与阶段安排"].strip(),
        "玩法设计与资源分配": sections["玩法设计与资源分配"].strip(),
        "投放与转化建议": sections["投放与转化建议"].strip(),
        "核心指标与复盘建议": sections["核心指标与复盘建议"].strip(),
    }


def format_publishable_text(markdown_text: str) -> str:
    sections = parse_result_markdown(markdown_text)
    order = (
        "活动目标与策略概览",
        "活动节奏与阶段安排",
        "玩法设计与资源分配",
        "投放与转化建议",
        "核心指标与复盘建议",
    )
    parts = [bracket_section(title, sections[title]) for title in order if sections.get(title)]
    return strip_blank_join(parts)


def _extract_sections(markdown_text: str) -> dict[str, str]:
    matches = list(SECTION_PATTERN.finditer(markdown_text))
    if not matches:
        raise ToolResultBuildError("ecommerce_campaign output missing required markdown sections")

    sections: dict[str, str] = {}
    for index, match in enumerate(matches):
        section_name = match.group(1)
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(markdown_text)
        sections[section_name] = markdown_text[start:end].strip()
    return sections
