import re
from typing import Any

from tools.errors import ToolResultBuildError


TITLE_PATTERN = re.compile(r"^#\s+.+", re.MULTILINE)
SECTION_PATTERN = re.compile(r"^##\s*(导语|正文|总结|行动引导)\s*$", re.MULTILINE)
SUBSECTION_PATTERN = re.compile(r"^###\s+.+", re.MULTILINE)


def parse_result_markdown(markdown_text: str) -> dict[str, Any]:
    text = (markdown_text or "").strip()
    if not text:
        raise ToolResultBuildError("wechat_longform output is empty")

    if not TITLE_PATTERN.search(text):
        raise ToolResultBuildError("wechat_longform output missing top-level title")

    section_blocks = _extract_sections(text)
    _validate_sections(section_blocks)

    body_block = section_blocks.get("正文", "")
    if not SUBSECTION_PATTERN.search(body_block):
        raise ToolResultBuildError("wechat_longform 正文 must contain at least one ### subsection")

    return {
        "has_title": True,
        "sections": list(section_blocks.keys()),
    }


def _extract_sections(markdown_text: str) -> dict[str, str]:
    matches = list(SECTION_PATTERN.finditer(markdown_text))
    if not matches:
        raise ToolResultBuildError("wechat_longform output missing required sections")

    sections: dict[str, str] = {}
    for index, match in enumerate(matches):
        section_name = match.group(1)
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(markdown_text)
        sections[section_name] = markdown_text[start:end].strip()

    return sections


def _validate_sections(sections: dict[str, str]) -> None:
    required = ("导语", "正文", "总结", "行动引导")
    missing = [name for name in required if not sections.get(name)]
    if missing:
        raise ToolResultBuildError(f"wechat_longform output missing section content: {', '.join(missing)}")
