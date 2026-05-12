import re
from typing import Any

from tools.errors import ToolResultBuildError
from tools.text_publishable import bracket_section, strip_blank_join


SECTION_PATTERN = re.compile(
    r"^##\s*(活动总览|客群策略|活动玩法设计|执行排期|物料与话术建议|人员分工|复盘指标|风险提醒)\s*$",
    re.MULTILINE,
)


def parse_result_markdown(markdown_text: str) -> dict[str, Any]:
    sections = _extract_sections(markdown_text or "")

    overview = _normalize_block(sections.get("活动总览", ""))
    audience = _normalize_block(sections.get("客群策略", ""))
    mechanics = _normalize_block(sections.get("活动玩法设计", ""))
    schedule = _normalize_block(sections.get("执行排期", ""))
    materials = _normalize_block(sections.get("物料与话术建议", ""))
    roles = _normalize_block(sections.get("人员分工", ""))
    metrics = _normalize_block(sections.get("复盘指标", ""))
    risks = _normalize_block(sections.get("风险提醒", ""))

    missing = [
        section_name
        for section_name, content in [
            ("活动总览", overview),
            ("客群策略", audience),
            ("活动玩法设计", mechanics),
            ("执行排期", schedule),
            ("物料与话术建议", materials),
            ("人员分工", roles),
            ("复盘指标", metrics),
            ("风险提醒", risks),
        ]
        if not content
    ]
    if missing:
        raise ToolResultBuildError(
            f"store_campaign output missing section content: {', '.join(missing)}"
        )

    if len(schedule.splitlines()) < 2:
        raise ToolResultBuildError("store_campaign output 执行排期 must contain at least 2 steps")

    return {
        "overview": overview,
        "audience": audience,
        "mechanics": mechanics,
        "schedule": schedule,
        "materials": materials,
        "roles": roles,
        "metrics": metrics,
        "risks": risks,
    }


def _extract_sections(markdown_text: str) -> dict[str, str]:
    matches = list(SECTION_PATTERN.finditer(markdown_text))
    if not matches:
        raise ToolResultBuildError("store_campaign output missing required markdown sections")

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
    pairs = (
        ("活动总览", data["overview"]),
        ("客群策略", data["audience"]),
        ("活动玩法设计", data["mechanics"]),
        ("物料与话术建议", data["materials"]),
        ("执行排期", data["schedule"]),
        ("人员分工", data["roles"]),
        ("复盘指标", data["metrics"]),
        ("风险提醒", data["risks"]),
    )
    parts = [bracket_section(t, body) for t, body in pairs]
    return strip_blank_join(parts)
