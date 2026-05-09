import re
from typing import Any

from tools.errors import ToolResultBuildError


SECTION_PATTERN = re.compile(r"^##\s*(标题建议|正文|标签建议|行动引导)\s*$", re.MULTILINE)
TITLE_PREFIX_PATTERN = re.compile(r"^\s*\d+[\.\)、]\s*")


def parse_result_markdown(markdown_text: str) -> dict[str, Any]:
    sections = _extract_sections(markdown_text)

    titles = _parse_titles(sections.get("标题建议", ""))
    content = _normalize_block(sections.get("正文", ""))
    hashtags = _parse_hashtags(sections.get("标签建议", ""))
    cta = _normalize_block(sections.get("行动引导", ""))

    if not titles or not content or not hashtags or not cta:
        raise ToolResultBuildError(
            "xiaohongshu output must contain 标题建议、正文、标签建议、行动引导 and non-empty content"
        )

    return {
        "titles": titles,
        "content": content,
        "hashtags": hashtags,
        "cta": cta,
    }


def _extract_sections(markdown_text: str) -> dict[str, str]:
    matches = list(SECTION_PATTERN.finditer(markdown_text))
    if not matches:
        raise ToolResultBuildError("xiaohongshu output missing required markdown sections")

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
    hashtags: list[str] = []
    for raw_line in block.splitlines():
        hashtags.extend(re.findall(r"#\S+", raw_line))
    return hashtags


def _normalize_block(block: str) -> str:
    lines = [line.strip() for line in block.splitlines() if line.strip()]
    return "\n".join(lines)
