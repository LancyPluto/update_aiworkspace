import re
from typing import Any

from tools.errors import ToolResultBuildError
from tools.text_publishable import numbered_lines, strip_blank_join


# 模型常见偏离：用一级/三级标题、标题后加冒号、加粗小节名、整段包在 ``` 围栏里。
SECTION_PATTERN = re.compile(
    r"^#{1,4}\s*\*{0,2}(标题建议|正文|标签建议|行动引导)\*{0,2}\s*[:：]?\s*$",
    re.MULTILINE,
)
TITLE_PREFIX_PATTERN = re.compile(r"^\s*\d+[\.\)、]\s*")


def _strip_outer_code_fence(text: str) -> str:
    """去掉模型在整段外包的一层 ``` / ```markdown ... ```。"""
    lines = text.split("\n")
    if len(lines) < 2:
        return text
    first = lines[0].strip()
    if not first.startswith("```"):
        return text
    last = lines[-1].strip()
    if last == "```":
        return "\n".join(lines[1:-1]).strip()
    return "\n".join(lines[1:]).strip()


def _normalize_model_markdown(markdown_text: str) -> str:
    text = markdown_text or ""
    if text.startswith("\ufeff"):
        text = text[1:]
    text = text.strip()
    if text.startswith("```"):
        text = _strip_outer_code_fence(text)
    return text


def parse_result_markdown(markdown_text: str) -> dict[str, Any]:
    sections = _extract_sections(_normalize_model_markdown(markdown_text))

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


def format_publishable_text(markdown_text: str) -> str:
    data = parse_result_markdown(markdown_text)
    parts: list[str] = []
    # 正文优先，便于先复制笔记正文再按需复制标题与标签
    parts.append(str(data["content"]).strip())
    if data["titles"]:
        parts.append(numbered_lines(data["titles"]))
    if data["hashtags"]:
        parts.append(" ".join(str(h).strip() for h in data["hashtags"] if str(h).strip()))
    parts.append(str(data["cta"]).strip())
    return strip_blank_join(parts)
