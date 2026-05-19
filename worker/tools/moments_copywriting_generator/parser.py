import re
from typing import Any

from tools.errors import ToolResultBuildError


SECTION_PATTERN = re.compile(r"^##\s*(文案正文|表情建议|话题标签|行动引导)\s*$", re.MULTILINE)
LABEL_PATTERN = re.compile(r"^\s*(?:\*\*)?(文案正文|表情建议|话题标签|行动引导)(?:\*\*)?\s*[:：]\s*", re.MULTILINE)
HASHTAG_PATTERN = re.compile(r"#\S+")


def parse_result_markdown(markdown_text: str) -> dict[str, Any]:
    markdown_text = (markdown_text or "").strip()
    sections = _extract_sections(markdown_text)

    content = sections.get("文案正文", "").strip()
    emoji = sections.get("表情建议", "").strip()
    hashtags = HASHTAG_PATTERN.findall(sections.get("话题标签", ""))
    cta = sections.get("行动引导", "").strip()

    if not content:
        raise ToolResultBuildError(
            "moments output must contain non-empty copy content"
        )
    if not hashtags:
        hashtags = HASHTAG_PATTERN.findall(markdown_text)

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
        label_sections = _extract_label_sections(markdown_text)
        if label_sections:
            return label_sections
        return _fallback_plain_text_sections(markdown_text)

    sections: dict[str, str] = {}
    for index, match in enumerate(matches):
        section_name = match.group(1)
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(markdown_text)
        sections[section_name] = markdown_text[start:end].strip()

    return sections


def _extract_label_sections(markdown_text: str) -> dict[str, str]:
    matches = list(LABEL_PATTERN.finditer(markdown_text))
    sections: dict[str, str] = {}
    for index, match in enumerate(matches):
        section_name = match.group(1)
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(markdown_text)
        sections[section_name] = markdown_text[start:end].strip()
    return sections


def _fallback_plain_text_sections(markdown_text: str) -> dict[str, str]:
    normalized = markdown_text.strip()
    if not normalized:
        raise ToolResultBuildError("moments output missing copy content")

    hashtags = HASHTAG_PATTERN.findall(normalized)
    lines = [line.strip() for line in normalized.splitlines() if line.strip()]
    emoji_lines = [line for line in lines if _looks_like_emoji_line(line)]

    content_lines: list[str] = []
    for line in lines:
        if line in emoji_lines:
            continue
        if HASHTAG_PATTERN.fullmatch(line.replace(" ", "")):
            continue
        content_lines.append(line)

    content = "\n".join(content_lines).strip() or normalized
    return {
        "文案正文": content,
        "表情建议": "".join(emoji_lines),
        "话题标签": " ".join(hashtags),
        "行动引导": "",
    }


def _looks_like_emoji_line(line: str) -> bool:
    compact = line.replace(" ", "")
    if not compact or len(compact) > 24:
        return False
    return not any(char.isalnum() or "\u4e00" <= char <= "\u9fff" for char in compact)
