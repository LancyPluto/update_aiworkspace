import re
from typing import Any

from tools.errors import ToolResultBuildError


SECTION_PATTERN = re.compile(
    r"^##\s*(客户情况分析|跟进话术|异议处理话术|触达节奏建议|跟进记录建议|合规提醒)\s*$",
    re.MULTILINE,
)


def parse_result_markdown(markdown_text: str) -> dict[str, Any]:
    sections = _extract_sections(markdown_text or "")

    analysis = _normalize_block(sections.get("客户情况分析", ""))
    followup_script = _normalize_block(sections.get("跟进话术", ""))
    objections = _normalize_block(sections.get("异议处理话术", ""))
    cadence = _normalize_block(sections.get("触达节奏建议", ""))
    record_notes = _normalize_block(sections.get("跟进记录建议", ""))
    compliance = _normalize_block(sections.get("合规提醒", ""))

    missing = [
        section_name
        for section_name, content in [
            ("客户情况分析", analysis),
            ("跟进话术", followup_script),
            ("异议处理话术", objections),
            ("触达节奏建议", cadence),
            ("跟进记录建议", record_notes),
            ("合规提醒", compliance),
        ]
        if not content
    ]
    if missing:
        raise ToolResultBuildError(
            f"customer_followup_script output missing section content: {', '.join(missing)}"
        )

    if len(cadence.splitlines()) < 2:
        raise ToolResultBuildError(
            "customer_followup_script output 触达节奏建议 must contain at least 2 steps"
        )

    return {
        "analysis": analysis,
        "followup_script": followup_script,
        "objections": objections,
        "cadence": cadence,
        "record_notes": record_notes,
        "compliance": compliance,
    }


def _extract_sections(markdown_text: str) -> dict[str, str]:
    matches = list(SECTION_PATTERN.finditer(markdown_text))
    if not matches:
        raise ToolResultBuildError(
            "customer_followup_script output missing required markdown sections"
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
