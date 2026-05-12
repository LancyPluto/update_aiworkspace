import re
from typing import Any

from tools.errors import ToolResultBuildError


SECTION_PATTERN = re.compile(
    r"^##\s*(开场钩子|分镜脚本|口播文案|拍摄与剪辑建议|标题与标签建议|合规提醒)\s*$",
    re.MULTILINE,
)


def parse_result_markdown(markdown_text: str) -> dict[str, Any]:
    sections = _extract_sections(markdown_text or "")

    hook = _normalize_block(sections.get("开场钩子", ""))
    storyboard = _normalize_block(sections.get("分镜脚本", ""))
    voiceover = _normalize_block(sections.get("口播文案", ""))
    shooting_notes = _normalize_block(sections.get("拍摄与剪辑建议", ""))
    titles_and_tags = _normalize_block(sections.get("标题与标签建议", ""))
    compliance = _normalize_block(sections.get("合规提醒", ""))

    missing = [
        section_name
        for section_name, content in [
            ("开场钩子", hook),
            ("分镜脚本", storyboard),
            ("口播文案", voiceover),
            ("拍摄与剪辑建议", shooting_notes),
            ("标题与标签建议", titles_and_tags),
            ("合规提醒", compliance),
        ]
        if not content
    ]
    if missing:
        raise ToolResultBuildError(
            f"short_video_script output missing section content: {', '.join(missing)}"
        )

    if len(storyboard.splitlines()) < 2:
        raise ToolResultBuildError(
            "short_video_script output 分镜脚本 must contain at least 2 shots"
        )

    return {
        "hook": hook,
        "storyboard": storyboard,
        "voiceover": voiceover,
        "shooting_notes": shooting_notes,
        "titles_and_tags": titles_and_tags,
        "compliance": compliance,
    }


def _extract_sections(markdown_text: str) -> dict[str, str]:
    matches = list(SECTION_PATTERN.finditer(markdown_text))
    if not matches:
        raise ToolResultBuildError("short_video_script output missing required markdown sections")

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
