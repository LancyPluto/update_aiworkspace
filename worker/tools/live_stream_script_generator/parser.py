import re
from typing import Any

from tools.errors import ToolResultBuildError


SECTION_PATTERN = re.compile(
    r"^##\s*(开场话术|产品讲解话术|互动引导话术|促单转化话术|异议处理话术|直播节奏安排|合规提醒)\s*$",
    re.MULTILINE,
)


def parse_result_markdown(markdown_text: str) -> dict[str, Any]:
    sections = _extract_sections(markdown_text or "")

    opening = _normalize_block(sections.get("开场话术", ""))
    product_pitch = _normalize_block(sections.get("产品讲解话术", ""))
    interaction = _normalize_block(sections.get("互动引导话术", ""))
    conversion = _normalize_block(sections.get("促单转化话术", ""))
    objections = _normalize_block(sections.get("异议处理话术", ""))
    schedule = _normalize_block(sections.get("直播节奏安排", ""))
    compliance = _normalize_block(sections.get("合规提醒", ""))

    missing = [
        section_name
        for section_name, content in [
            ("开场话术", opening),
            ("产品讲解话术", product_pitch),
            ("互动引导话术", interaction),
            ("促单转化话术", conversion),
            ("异议处理话术", objections),
            ("直播节奏安排", schedule),
            ("合规提醒", compliance),
        ]
        if not content
    ]
    if missing:
        raise ToolResultBuildError(
            f"live_stream_script output missing section content: {', '.join(missing)}"
        )

    if len(schedule.splitlines()) < 2:
        raise ToolResultBuildError(
            "live_stream_script output 直播节奏安排 must contain at least 2 stages"
        )

    return {
        "opening": opening,
        "product_pitch": product_pitch,
        "interaction": interaction,
        "conversion": conversion,
        "objections": objections,
        "schedule": schedule,
        "compliance": compliance,
    }


def _extract_sections(markdown_text: str) -> dict[str, str]:
    matches = list(SECTION_PATTERN.finditer(markdown_text))
    if not matches:
        raise ToolResultBuildError("live_stream_script output missing required markdown sections")

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
