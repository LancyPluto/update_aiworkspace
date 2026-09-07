from __future__ import annotations

import json
import hashlib
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
        # 没有匹配 Skill、功能关闭或本 Run 已加载过时，原 Tool Action 可以直接继续执行。
        if not settings.agent_skill_hydration_enabled:
            return None
        match = _find_skill_for_tool(context, tool_code)
        if match is None:
            return None
        skill, matched_pattern, match_type = match
        if skill.skillCode in hydrated_skill_codes:
            return None
        try:
            # 完整 SOP 保存在 Java 后端数据库中，这里通过带内部签名的 HTTP 接口按需读取。
            payload = await self.backend.get_agent_skill(skill.skillCode)
        except Exception as exc:
            await self._emit_hydration_event(
                context,
                skill_code=skill.skillCode,
                tool_code=tool_code,
                version=skill.version,
                matched_pattern=matched_pattern,
                match_type=match_type,
                hydrated=False,
                failure_reason=f"skill fetch failed: {type(exc).__name__}",
            )
            raise
        sop = str(payload.get("sopRules") or "").strip()
        if not sop:
            await self._emit_hydration_event(
                context,
                skill_code=skill.skillCode,
                tool_code=tool_code,
                version=_int_or_none(payload.get("version") or skill.version),
                matched_pattern=matched_pattern,
                match_type=match_type,
                hydrated=False,
                failure_reason="published skill has no SOP content",
            )
            return None
        examples = payload.get("examples")
        examples_text = _format_examples(examples)
        session_state = format_session_state_context(context)
        # 把数据库中的 SOP、示例和当前会话状态封装成一条 system 消息交给模型。
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
        # 同一个 Skill 在单次 Run 中最多触发一次 Action 重生成，避免重复增加模型调用。
        hydrated_skill_codes.add(skill.skillCode)
        await self._emit_hydration_event(
            context,
            skill_code=result.skill_code,
            tool_code=result.tool_code,
            version=result.version,
            matched_pattern=matched_pattern,
            match_type=match_type,
            hydrated=True,
            token_estimate=result.token_estimate,
            content_sha256=hashlib.sha256(content.encode("utf-8")).hexdigest(),
        )
        return result

    async def _emit_hydration_event(
        self,
        context: RunContext,
        *,
        skill_code: str,
        tool_code: str,
        version: int | None,
        matched_pattern: str,
        match_type: str,
        hydrated: bool,
        token_estimate: int | None = None,
        content_sha256: str | None = None,
        failure_reason: str | None = None,
    ) -> None:
        try:
            await self.backend.append_event(
                context.runId,
                RunEventCreate(
                    eventType=SKILL_HYDRATED,
                    eventJson={
                        "skillCode": skill_code,
                        "toolCode": tool_code,
                        "version": version,
                        "matchedPattern": matched_pattern,
                        "matchType": match_type,
                        "hydrated": hydrated,
                        "tokenEstimate": token_estimate,
                        "contentSha256": content_sha256,
                        "failureReason": failure_reason,
                    },
                ),
            )
        except Exception:
            pass


def hydration_message(result: SkillHydrationResult) -> ChatMessage:
    return ChatMessage(role="system", content=result.content)


def _find_skill_for_tool(context: RunContext, tool_code: str):
    normalized = (tool_code or "").strip().lower()
    if not normalized:
        return None
    # Run Context 只包含“已发布且至少匹配一个当前可见工具”的 Skill 摘要。
    for skill in context.availableSkills or []:
        for pattern in skill.toolCodes or []:
            token = str(pattern or "").strip().lower()
            if normalized == token:
                return skill, str(pattern), "EXACT"
            if token and (token in normalized or normalized in token):
                return skill, str(pattern), "CONTAINS"
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
