from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from app.config import settings
from app.core.event_types import SKILL_HYDRATED
from app.core.schemas import ChatMessage, RunContext, RunEventCreate
from app.runtime.session_state import format_session_state_context


@dataclass(slots=True)
class SkillHydrationResult:
    skill_code: str
    tool_code: str
    version: int | None
    content: str
    token_estimate: int


class SkillHydrationService:
    def __init__(self, backend: Any) -> None:
        self.backend = backend

    async def hydrate_for_tool(
        self,
        context: RunContext,
        tool_code: str,
        hydrated_skill_codes: set[str],
    ) -> SkillHydrationResult | None:
        if not settings.agent_skill_hydration_enabled:
            return None
        skill = _find_skill_for_tool(context, tool_code)
        if skill is None or skill.skillCode in hydrated_skill_codes:
            return None
        payload = await self.backend.get_agent_skill(skill.skillCode)
        sop = str(payload.get("sopRules") or "").strip()
        if not sop:
            return None
        examples = payload.get("examples")
        examples_text = _format_examples(examples)
        session_state = format_session_state_context(context)
        content = (
            f"<SkillHydration skill_code=\"{skill.skillCode}\" "
            f"display_name=\"{skill.displayName}\" version=\"{payload.get('version') or skill.version or ''}\">\n"
            f"{sop}\n"
        )
        if examples_text:
            content += f"\n<SkillExamples>\n{examples_text}\n</SkillExamples>\n"
        if session_state:
            content += f"\n{session_state}\n"
        content += "</SkillHydration>"
        result = SkillHydrationResult(
            skill_code=skill.skillCode,
            tool_code=tool_code,
            version=_int_or_none(payload.get("version") or skill.version),
            content=content,
            token_estimate=max(1, len(content) // 4),
        )
        hydrated_skill_codes.add(skill.skillCode)
        try:
            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=SKILL_HYDRATED,
                    eventJson={
                        "skillCode": result.skill_code,
                        "toolCode": result.tool_code,
                        "version": result.version,
                        "tokenEstimate": result.token_estimate,
                    },
                ),
            )
        except Exception:
            pass
        return result


def hydration_message(result: SkillHydrationResult) -> ChatMessage:
    return ChatMessage(role="system", content=result.content)


def _find_skill_for_tool(context: RunContext, tool_code: str):
    normalized = (tool_code or "").strip().lower()
    if not normalized:
        return None
    for skill in context.availableSkills or []:
        for pattern in skill.toolCodes or []:
            token = str(pattern or "").strip().lower()
            if token and (normalized == token or token in normalized or normalized in token):
                return skill
    return None


def _format_examples(examples: Any) -> str:
    if examples is None:
        return ""
    if isinstance(examples, str):
        return examples.strip()
    try:
        return json.dumps(examples, ensure_ascii=False, indent=2)
    except Exception:
        return str(examples)


def _int_or_none(value: Any) -> int | None:
    try:
        return int(value)
    except Exception:
        return None
