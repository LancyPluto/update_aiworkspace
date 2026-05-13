import re
from typing import Any

from tools.errors import ToolResultBuildError
from tools.text_publishable import bracket_section, strip_blank_join


SECTION_PATTERN = re.compile(
    r"^##\s*(异议判断|回应策略|推荐话术|追问引导|替代表达|后续动作建议|合规提醒)\s*$",
    re.MULTILINE,
)


def parse_result_markdown(markdown_text: str) -> dict[str, Any]:
    sections = _extract_sections(markdown_text or "")

    diagnosis = _normalize_block(sections.get("异议判断", ""))
    strategy = _normalize_block(sections.get("回应策略", ""))
    script = _normalize_block(sections.get("推荐话术", ""))
    questions = _normalize_block(sections.get("追问引导", ""))
    alternatives = _normalize_block(sections.get("替代表达", ""))
    next_steps = _normalize_block(sections.get("后续动作建议", ""))
    compliance = _normalize_block(sections.get("合规提醒", ""))

    missing = [
        section_name
        for section_name, content in [
            ("异议判断", diagnosis),
            ("回应策略", strategy),
            ("推荐话术", script),
            ("追问引导", questions),
            ("替代表达", alternatives),
            ("后续动作建议", next_steps),
            ("合规提醒", compliance),
        ]
        if not content
    ]
    if missing:
        raise ToolResultBuildError(
            f"objection_handling_script output missing section content: {', '.join(missing)}"
        )

    if len(next_steps.splitlines()) < 2:
        raise ToolResultBuildError(
            "objection_handling_script output 后续动作建议 must contain at least 2 steps"
        )

    return {
        "diagnosis": diagnosis,
        "strategy": strategy,
        "script": script,
        "questions": questions,
        "alternatives": alternatives,
        "next_steps": next_steps,
        "compliance": compliance,
    }


def _extract_sections(markdown_text: str) -> dict[str, str]:
    matches = list(SECTION_PATTERN.finditer(markdown_text))
    if not matches:
        raise ToolResultBuildError(
            "objection_handling_script output missing required markdown sections"
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


def format_publishable_text(markdown_text: str) -> str:
    data = parse_result_markdown(markdown_text)
    # 推荐话术、替代表达优先，便于直接复制发送
    pairs = (
        ("推荐话术", data["script"]),
        ("替代表达", data["alternatives"]),
        ("追问引导", data["questions"]),
        ("回应策略", data["strategy"]),
        ("异议判断", data["diagnosis"]),
        ("后续动作建议", data["next_steps"]),
        ("合规提醒", data["compliance"]),
    )
    parts = [bracket_section(t, body) for t, body in pairs]
    return strip_blank_join(parts)
