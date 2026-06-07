import json
import logging
from typing import Any

from app.config import settings
from app.core.event_types import ROUTER_CANDIDATES, ROUTER_FALLBACK, ROUTER_SELECTED, ROUTER_STARTED
from app.core.intent_router import Intent, IntentResult, IntentRouter
from app.core.preferred_tool_bias import (
    apply_preferred_tool_override,
    preferred_tool_code,
    resolve_preferred_tool,
    sort_tools_with_preferred,
)
from app.core.schemas import ChatMessage, RunContext, RunEventCreate
from app.runtime.router_context import (
    router_history_turns,
    router_recent_tool_call_limit,
    slice_history_by_turns,
)
from app.tools.registry import ToolRegistry, infer_output_modality, requested_output_modality

LOGGER = logging.getLogger(__name__)

DEFAULT_ROUTER_PROMPT = (
    "You are the primary router for an AI tool marketplace agent. "
    "Decide whether the user needs a normal answer, a tool call, clarification, or an unsupported path. "
    "Return only valid JSON matching this schema: "
    "{\"intent\":\"tool_use|general_chat|needs_clarification|unsupported\","
    "\"selectedToolCode\":string|null,\"candidateToolCodes\":string[],"
    "\"confidence\":number,\"reason\":string,\"arguments\":object,\"missingFields\":string[],"
    "\"followupPatch\":object,\"requiresConfirmation\":boolean|null,\"clarifyingQuestion\":string|null}. "
    "Use intent value tool_use (not tool_call) when a tool should run. "
    "Use the available tool metadata as source of truth. "
    "Prefer the tool that directly produces the requested output modality: image/photo/poster/cos/visual requests use image tools; "
    "video/short-video/image-to-video requests use video tools; copywriting/title/article requests use text tools. "
    "Use recentToolCalls to detect follow-up requests, inherit prior arguments, and return only the user's changes in followupPatch. "
    "Uploaded media attachments may be reference material for generation tools; do not classify them as file analysis unless the user asks to analyze/read/summarize the attachment. "
    "Only ask for missing information when it changes intent, cost, authorization, safety, or the core subject. "
    "Do not ask for low-risk defaults such as aspect ratio, count, quality, or style strength."
)


class AgentRouterService:
    """LLM-assisted router with structured events and rule fallback."""

    def __init__(
        self,
        backend_client,
        model_client,
        *,
        intent_router: IntentRouter | None = None,
    ) -> None:
        self.backend = backend_client
        self.model = model_client
        self.intent_router = intent_router or IntentRouter()

    async def classify(self, context: RunContext, rule_intent: IntentResult) -> IntentResult | None:
        if not self._enabled(context):
            await self._emit_fallback(context, "router_disabled", rule_intent)
            return None
        if not context.availableTools:
            await self._emit_fallback(context, "no_available_tools", rule_intent)
            return None

        candidates = self._candidate_payload(context)
        history_slice = slice_history_by_turns(context.history, router_history_turns(context))
        await self._emit_started(context, rule_intent, history_slice=history_slice)
        await self._emit_candidates(context, candidates, rule_intent)

        try:
            raw = await self.model.chat([ChatMessage(role="user", content=self._build_prompt(context, rule_intent, candidates, history_slice))])
            parsed = _parse_json_object(raw)
            result, validation_failure = self._validate(context, parsed)
            if result is not None:
                result = apply_preferred_tool_override(context, result)
            if result is None:
                LOGGER.warning(
                    "agent router rejected runId=%s failure=%s minConfidence=%.2f parsedIntent=%s parsedTool=%s raw=%s",
                    context.runId,
                    validation_failure or "unknown",
                    self._min_confidence(context),
                    parsed.get("intent") if isinstance(parsed, dict) else type(parsed).__name__,
                    parsed.get("selectedToolCode") if isinstance(parsed, dict) else "-",
                    _clip(raw, 300),
                )
                await self._emit_fallback(
                    context,
                    "invalid_or_low_confidence",
                    rule_intent,
                    raw=raw,
                    parsed=parsed,
                    validation_failure=validation_failure,
                )
                return None
            await self._emit_selected(context, result, parsed)
            LOGGER.info(
                "agent router selected runId=%s intent=%s confidence=%.2f selectedTool=%s candidates=%s reason=%s",
                context.runId,
                result.intent.value,
                result.confidence,
                result.selectedToolCode or "-",
                result.candidateToolCodes,
                result.reason,
            )
            return result
        except Exception as exc:
            LOGGER.warning("agent router fallback runId=%s error=%s", context.runId, exc)
            await self._emit_fallback(context, "router_exception", rule_intent, error=str(exc))
            return None

    def _enabled(self, context: RunContext) -> bool:
        runtime_enabled = bool(getattr(settings, "agent_llm_router_enabled", True))
        if not runtime_enabled:
            return False
        if context.routerSettings is None:
            return True
        return context.routerSettings.enabled

    def _min_confidence(self, context: RunContext) -> float:
        if context.routerSettings is None:
            return 0.7
        try:
            return max(0.0, min(1.0, float(context.routerSettings.minConfidence)))
        except (TypeError, ValueError):
            return 0.7

    def _fallback_to_rules(self, context: RunContext) -> bool:
        if context.routerSettings is None:
            return True
        return context.routerSettings.fallbackToRules

    def _prompt_template(self, context: RunContext) -> str:
        configured = context.routerSettings.prompt if context.routerSettings else None
        return configured.strip() if configured and configured.strip() else DEFAULT_ROUTER_PROMPT

    def _candidate_payload(self, context: RunContext) -> list[dict[str, Any]]:
        registry = ToolRegistry(context)
        ranked_codes = [candidate.tool.toolCode for candidate in registry.rank_by_intent(context.message)[:10]]
        preferred = preferred_tool_code(context)
        if preferred and preferred not in ranked_codes:
            ranked_codes.insert(0, preferred)
        ranked = {code: index for index, code in enumerate(ranked_codes)}
        tools = sort_tools_with_preferred(context, sorted(registry.list_tools(), key=lambda tool: ranked.get(tool.toolCode, 999))[:30])
        return [
            {
                "toolCode": tool.toolCode,
                "toolName": tool.toolName,
                "description": tool.description or "",
                "autoCallable": tool.autoCallable,
                "agentHints": tool.hints,
                "inputSchema": tool.inputSchema,
                "outputModality": infer_output_modality(tool),
                "estimatedCreditCost": tool.estimatedCreditCost,
                "autoCallPolicy": "auto" if tool.autoCallable else "configured_or_confirm",
                "rank": ranked.get(tool.toolCode),
                "fields": [
                    {
                        "fieldKey": field.fieldKey,
                        "fieldName": field.fieldName,
                        "description": field.description or "",
                        "required": field.required,
                        "executionRequired": field.executionRequired,
                        "userRequired": field.userRequired,
                        "defaultValue": field.defaultValue,
                        "agentFillStrategy": field.agentFillStrategy,
                        "riskLevel": field.riskLevel,
                    }
                    for field in tool.fields[:12]
                ],
            }
            for tool in tools
        ]

    def _build_prompt(
        self,
        context: RunContext,
        rule_intent: IntentResult,
        candidates: list[dict[str, Any]],
        history_slice: list[ChatMessage],
    ) -> str:
        tool_limit = router_recent_tool_call_limit(context)
        preferred = resolve_preferred_tool(context)
        payload = {
            "userMessage": context.message,
            "requestedOutputModality": requested_output_modality(context.message),
            "preferredToolCode": preferred.toolCode if preferred else None,
            "preferredToolName": preferred.toolName if preferred else None,
            "attachments": _attachment_payload(context),
            "recentHistory": [
                {
                    "role": message.role,
                    "content": _clip(message.content, 500),
                }
                for message in history_slice
            ],
            "recentToolCalls": [
                {
                    "id": call.id,
                    "toolCode": call.toolCode,
                    "taskId": call.taskId,
                    "argumentsJson": call.argumentsJson,
                    "resourceType": call.resourceType,
                    "mediaUrls": call.mediaUrls,
                    "createdAt": call.createdAt,
                }
                for call in context.recentToolCalls[:tool_limit]
            ],
            "ruleFallback": {
                "intent": rule_intent.intent.value,
                "selectedToolCode": rule_intent.selectedToolCode,
                "candidateToolCodes": rule_intent.candidateToolCodes,
                "confidence": rule_intent.confidence,
                "reason": rule_intent.reason,
            },
            "availableTools": candidates,
        }
        return f"{self._prompt_template(context)}\n\nRouting input:\n{json.dumps(payload, ensure_ascii=False)}"

    def _validate(self, context: RunContext, parsed: Any) -> tuple[IntentResult | None, str | None]:
        if not isinstance(parsed, dict):
            return None, "parsed_not_object"
        raw_intent = _normalize_router_intent(parsed.get("intent"))
        if raw_intent is None:
            return None, "invalid_intent"
        try:
            intent = Intent(raw_intent)
        except ValueError:
            return None, "invalid_intent"
        confidence = _safe_float(parsed.get("confidence"), 0)
        min_confidence = self._min_confidence(context)
        if confidence < min_confidence:
            return None, f"confidence_below_min:{confidence:.2f}<{min_confidence:.2f}"

        available = {tool.toolCode for tool in context.availableTools}
        selected_tool = _resolve_selected_tool_code(parsed.get("selectedToolCode"), context.availableTools)
        raw_candidates = parsed.get("candidateToolCodes")
        candidate_codes = _resolve_candidate_tool_codes(raw_candidates, context.availableTools)

        if intent == Intent.TOOL_USE:
            if not selected_tool and candidate_codes:
                selected_tool = candidate_codes[0]
            if not selected_tool:
                return None, "tool_use_missing_selected_tool"
            if selected_tool not in available:
                return None, f"tool_not_available:{selected_tool}"
            selected_descriptor = next((tool for tool in context.availableTools if tool.toolCode == selected_tool), None)
            requested_modality = requested_output_modality(context.message)
            selected_modality = infer_output_modality(selected_descriptor) if selected_descriptor else None
            if requested_modality and selected_modality and requested_modality != selected_modality:
                return None, f"output_modality_mismatch:{requested_modality}!={selected_modality}"
        elif selected_tool and selected_tool not in available:
            selected_tool = None

        if selected_tool and selected_tool not in candidate_codes:
            candidate_codes.insert(0, selected_tool)

        clarifying = parsed.get("clarifyingQuestion")
        reason = str(parsed.get("reason") or "llm_router")
        return IntentResult(
            intent=intent,
            confidence=confidence,
            selectedToolCode=selected_tool,
            candidateToolCodes=candidate_codes[:3],
            clarifyingQuestion=clarifying if isinstance(clarifying, str) else None,
            decisionSource="llm_router",
            reason=reason,
            arguments=parsed.get("arguments") if isinstance(parsed.get("arguments"), dict) else {},
            missingFields=[
                item for item in parsed.get("missingFields", [])
                if isinstance(item, str)
            ] if isinstance(parsed.get("missingFields"), list) else [],
            followupPatch=parsed.get("followupPatch") if isinstance(parsed.get("followupPatch"), dict) else {},
            requiresConfirmation=parsed.get("requiresConfirmation") if isinstance(parsed.get("requiresConfirmation"), bool) else None,
        ), None

    async def _emit_started(
        self,
        context: RunContext,
        rule_intent: IntentResult,
        *,
        history_slice: list[ChatMessage],
    ) -> None:
        configured_turns = router_history_turns(context)
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=ROUTER_STARTED,
                eventText="Agent router started",
                eventJson={
                    "enabled": True,
                    "minConfidence": self._min_confidence(context),
                    "fallbackToRules": self._fallback_to_rules(context),
                    "historyTurns": configured_turns,
                    "recentToolCallLimit": router_recent_tool_call_limit(context),
                    "includedHistoryMessageCount": len(history_slice),
                    "ruleIntent": rule_intent.intent.value,
                    "ruleSelectedToolCode": rule_intent.selectedToolCode,
                    "ruleReason": rule_intent.reason,
                },
            ),
        )

    async def _emit_candidates(self, context: RunContext, candidates: list[dict[str, Any]], rule_intent: IntentResult) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=ROUTER_CANDIDATES,
                eventText=f"{len(candidates)} router candidates",
                eventJson={
                    "candidateToolCodes": [item["toolCode"] for item in candidates[:10]],
                    "ruleCandidateToolCodes": rule_intent.candidateToolCodes,
                    "requestedOutputModality": requested_output_modality(context.message),
                    "tools": candidates[:10],
                },
            ),
        )

    async def _emit_selected(self, context: RunContext, result: IntentResult, parsed: dict[str, Any]) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=ROUTER_SELECTED,
                eventText=result.selectedToolCode or result.intent.value,
                eventJson={
                    "intent": result.intent.value,
                    "selectedToolCode": result.selectedToolCode,
                    "candidateToolCodes": result.candidateToolCodes,
                    "confidence": result.confidence,
                    "reason": result.reason,
                    "arguments": parsed.get("arguments") if isinstance(parsed.get("arguments"), dict) else {},
                    "missingFields": parsed.get("missingFields") if isinstance(parsed.get("missingFields"), list) else [],
                    "followupPatch": parsed.get("followupPatch") if isinstance(parsed.get("followupPatch"), dict) else {},
                    "requiresConfirmation": parsed.get("requiresConfirmation") if isinstance(parsed.get("requiresConfirmation"), bool) else None,
                    "clarifyingQuestion": result.clarifyingQuestion,
                },
            ),
        )

    async def _emit_fallback(
        self,
        context: RunContext,
        reason: str,
        rule_intent: IntentResult,
        *,
        raw: str | None = None,
        parsed: Any | None = None,
        error: str | None = None,
        validation_failure: str | None = None,
    ) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=ROUTER_FALLBACK,
                eventText=reason,
                eventJson={
                    "reason": reason,
                    "validationFailure": validation_failure,
                    "fallbackToRules": self._fallback_to_rules(context),
                    "ruleIntent": rule_intent.intent.value,
                    "ruleSelectedToolCode": rule_intent.selectedToolCode,
                    "ruleCandidateToolCodes": rule_intent.candidateToolCodes,
                    "ruleConfidence": rule_intent.confidence,
                    "ruleReason": rule_intent.reason,
                    "raw": _clip(raw, 800) if raw else None,
                    "parsed": parsed if isinstance(parsed, dict) else None,
                    "error": _clip(error, 800) if error else None,
                },
            ),
        )


def _normalize_router_intent(raw: Any) -> str | None:
    if raw is None:
        return None
    normalized = str(raw).strip().lower().replace("-", "_").replace(" ", "_")
    aliases = {
        "tool": "tool_use",
        "tooluse": "tool_use",
        "tool_use": "tool_use",
        "tool_call": "tool_use",
        "call_tool": "tool_use",
        "use_tool": "tool_use",
        "image_generation": "tool_use",
        "video_generation": "tool_use",
        "audio_generation": "tool_use",
        "music_generation": "tool_use",
        "text_generation": "tool_use",
        "chat": "general_chat",
        "general": "general_chat",
        "general_chat": "general_chat",
        "clarify": "needs_clarification",
        "needs_clarification": "needs_clarification",
        "unsupported": "unsupported",
    }
    return aliases.get(normalized, normalized if normalized in {item.value for item in Intent} else None)


def _resolve_selected_tool_code(raw: Any, tools: list) -> str | None:
    if raw is None:
        return None
    token = str(raw).strip()
    if not token:
        return None
    available = {tool.toolCode: tool for tool in tools}
    if token in available:
        return token
    lowered = token.lower()
    for tool in tools:
        code = tool.toolCode
        name = (tool.toolName or "").strip()
        if code.lower() == lowered:
            return code
        if name and (name == token or name.lower() == lowered):
            return code
        if lowered in code.lower() or (name and lowered in name.lower()):
            return code
    return None


def _resolve_candidate_tool_codes(raw: Any, tools: list) -> list[str]:
    if not isinstance(raw, list):
        return []
    available = {tool.toolCode for tool in tools}
    resolved: list[str] = []
    for item in raw:
        code = _resolve_selected_tool_code(item, tools)
        if code and code in available and code not in resolved:
            resolved.append(code)
    return resolved


def _parse_json_object(raw: str) -> Any:
    text = raw.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:].strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        start = text.find("{")
        end = text.rfind("}")
        if start >= 0 and end > start:
            return json.loads(text[start:end + 1])
        raise


def _safe_float(value: Any, fallback: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return fallback


def _clip(value: str | None, limit: int) -> str:
    if not value:
        return ""
    return value if len(value) <= limit else value[:limit] + "..."


def _attachment_payload(context: RunContext) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    for file in context.agentFiles[:8]:
        content_type = file.contentType or ""
        filename = file.originalFilename or ""
        items.append(
            {
                "id": file.id,
                "name": filename,
                "contentType": content_type,
                "status": file.status,
                "downloadUrl": file.downloadUrl,
                "kind": _attachment_kind(content_type, filename),
            }
        )
    return items


def _attachment_kind(content_type: str, filename: str) -> str:
    lowered_type = (content_type or "").lower()
    lowered_name = (filename or "").lower()
    if lowered_type.startswith("image/") or lowered_name.endswith((".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif")):
        return "image"
    if lowered_type.startswith("video/") or lowered_name.endswith((".mp4", ".mov", ".webm", ".mkv")):
        return "video"
    if lowered_type.startswith("audio/") or lowered_name.endswith((".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac")):
        return "audio"
    return "file"
