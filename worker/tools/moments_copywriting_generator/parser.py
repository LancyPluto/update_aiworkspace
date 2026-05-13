import re
from typing import Any

from tools.errors import ToolResultBuildError


SECTION_PATTERN = re.compile(r"^##\s*(文案正文|表情建议|话题标签|行动引导)\s*$", re.MULTILINE)


def parse_result_markdown(markdown_text: str) -> dict[str, Any]:
    sections = _extract_sections(markdown_text or "")

    content = sections.get("文案正文", "").strip()
    emoji = sections.get("表情建议", "").strip()
    hashtags = re.findall(r"#\S+", sections.get("话题标签", ""))
    cta = sections.get("行动引导", "").strip()

    if not content or not emoji or not hashtags or not cta:
        raise ToolResultBuildError(
            "moments output must contain 文案正文、表情建议、话题标签、行动引导 and non-empty content"
        )

    return {
        "content": content,
        "emoji": emoji,
        "hashtags": hashtags,
        "cta": cta,
    }


def format_publishable_text(markdown_text: str) -> str:
    """将模型结构化输出转成可直接粘贴发布的朋友圈文案。"""
    return _assemble_publishable_from_parsed(parse_result_markdown(markdown_text))


def _assemble_publishable_from_parsed(parsed_result: dict[str, Any]) -> str:
    content = str(parsed_result.get("content") or "").strip()
    emoji = str(parsed_result.get("emoji") or "").strip()
    hashtags = parsed_result.get("hashtags") or []
    cta = str(parsed_result.get("cta") or "").strip()

    blocks: list[str] = [content]
    if emoji:
        blocks.append(emoji)
    if hashtags:
        blocks.append(" ".join(str(tag).strip() for tag in hashtags if str(tag).strip()))
    if cta:
        blocks.append(cta)

    return "\n\n".join(block for block in blocks if block)


def _extract_sections(markdown_text: str) -> dict[str, str]:
    matches = list(SECTION_PATTERN.finditer(markdown_text))
    if not matches:
        raise ToolResultBuildError("moments output missing required markdown sections")

    sections: dict[str, str] = {}
    for index, match in enumerate(matches):
        section_name = match.group(1)
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(markdown_text)
        sections[section_name] = markdown_text[start:end].strip()

    return sections
