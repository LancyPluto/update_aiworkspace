import re
from typing import Any

from tools.errors import ToolResultBuildError


SECTION_PATTERN = re.compile(
    r"^##\s*(选题清单|推荐优先级|创作角度|标题与标签建议|执行建议|风险提醒)\s*$",
    re.MULTILINE,
)
TOPIC_PREFIX_PATTERN = re.compile(r"^\s*\d+[\.\)、]\s*")


def parse_result_markdown(markdown_text: str) -> dict[str, Any]:
    sections = _extract_sections(markdown_text or "")

    topics = _parse_topics(sections.get("选题清单", ""))
    priority = _normalize_block(sections.get("推荐优先级", ""))
    angles = _normalize_block(sections.get("创作角度", ""))
    titles_and_tags = _normalize_block(sections.get("标题与标签建议", ""))
    execution = _normalize_block(sections.get("执行建议", ""))
    risk_note = _normalize_block(sections.get("风险提醒", ""))

    if len(topics) < 3:
        raise ToolResultBuildError("short_video_topic output must contain at least 3 topics")

    missing = [
        section_name
        for section_name, content in [
            ("推荐优先级", priority),
            ("创作角度", angles),
            ("标题与标签建议", titles_and_tags),
            ("执行建议", execution),
            ("风险提醒", risk_note),
        ]
        if not content
    ]
    if missing:
        raise ToolResultBuildError(
            f"short_video_topic output missing section content: {', '.join(missing)}"
        )

    return {
        "topics": topics,
        "priority": priority,
        "angles": angles,
        "titles_and_tags": titles_and_tags,
        "execution": execution,
        "risk_note": risk_note,
    }


def _extract_sections(markdown_text: str) -> dict[str, str]:
    matches = list(SECTION_PATTERN.finditer(markdown_text))
    if not matches:
        raise ToolResultBuildError("short_video_topic output missing required markdown sections")

    sections: dict[str, str] = {}
    for index, match in enumerate(matches):
        section_name = match.group(1)
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(markdown_text)
        sections[section_name] = markdown_text[start:end].strip()
    return sections


def _parse_topics(block: str) -> list[str]:
    topics: list[str] = []
    for raw_line in block.splitlines():
        line = TOPIC_PREFIX_PATTERN.sub("", raw_line).strip("- ").strip()
        if line:
            topics.append(line)
    return topics


def _normalize_block(block: str) -> str:
    lines = [line.strip() for line in block.splitlines() if line.strip()]
    return "\n".join(lines)
