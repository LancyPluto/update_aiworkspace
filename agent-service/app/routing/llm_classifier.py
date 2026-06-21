"""JSON LLM semantic router — deprecated; use UnifiedSemanticRouter."""

from __future__ import annotations

import json
import logging
import warnings
from typing import Any

from app.config import settings
from app.core.event_types import ROUTER_CANDIDATES, ROUTER_FALLBACK, ROUTER_SELECTED, ROUTER_STARTED
from app.core.preferred_tool_bias import (
    apply_preferred_tool_override,
    preferred_tool_code,
    resolve_preferred_tool,
    sort_tools_with_preferred,
)
from app.core.schemas import ChatMessage, RunContext, RunEventCreate
from app.routing.constants import ROUTER_PROMPT_VERSION
from app.routing.context_builder import build_routing_context, routing_context_payload
from app.routing.semantic_tool_recall import recall_tool_codes
from app.routing.state_guard import StateGuard
from app.routing.types import Intent, IntentResult
from app.runtime.router_context import (
    router_history_turns,
    router_recent_tool_call_limit,
    slice_history_by_turns,
)
from app.tools.registry import ToolRegistry, infer_output_modality, requested_output_modality

LOGGER = logging.getLogger(__name__)

DEFAULT_ROUTER_PROMPT_V2 = (
    "You are the primary semantic router for an AI tool marketplace agent (routerPromptVersion=v2). "
    "Decide whether the user needs a normal answer, a tool call, clarification, file analysis, or an unsupported path. "
    "Return only valid JSON matching this schema: "
    '{"intent":"tool_use|general_chat|needs_clarification|unsupported|file_analysis",'
    '"attachmentUsage":"none|reference_for_generation|analyze_content|unknown",'
    '"selectedToolCode":string|null,"candidateToolCodes":string[],'
    '"confidence":number,"reason":string,"arguments":object,"missingFields":string[],'
    '"followupPatch":object,"requiresConfirmation":boolean|null,"clarifyingQuestion":string|null}. '
    "Use intent value tool_use (not tool_call) when a tool should run. "
    "Use attachmentSignals and capabilityFlags together with userMessage — do not treat uploaded images as file analysis "
    "when the user wants generation/editing with the image as reference material. "
    "When capabilityFlags.fileAnalysis is false, map document/image reading requests to unsupported, not file_analysis. "
    "When capabilityFlags.rag or workflow is false, map those requests to unsupported. "
    "If preferredToolCode is present, treat it as explicit user tool selection unless unavailable or wrong modality. "
    "Prefer tools matching requestedOutputModality: image tools for photos/posters; video tools for clips; text tools for copy. "
    "Use recentToolCalls for follow-ups; return user deltas in followupPatch. "
    "When activeToolClarification is present, the user is answering a parameter request for that tool — "
    "do not switch back to an older recentToolCall (e.g. a prior image tool) unless the user explicitly changes task. "
    "Workspace memory may inform safe low-risk arguments; current user instruction always wins."
)


class LLMClassifier:
    """Deprecated JSON router — use UnifiedSemanticRouter."""

    def __init__(self, backend_client, model_client) -> None:
        warnings.warn(
            "LLMClassifier JSON router is deprecated; use UnifiedSemanticRouter",
            DeprecationWarning,
            stacklevel=2,
        )
        self.backend = backend_client
        self.model = model_client

    async def classify(
        self,
        context: RunContext,
        guard_intent: IntentResult,
        *,
        workspace_memory_context: str = "",
    ) -> IntentResult | None:
        if not self._enabled(context):
            await self._emit_fallback(context, "router_disabled", guard_intent)
            return None
        if not context.availableTools:
            await self._emit_fallback(context, "no_available_tools", guard_intent)
            return None

        candidates = self._candidate_payload(context)
        history_slice = slice_history_by_turns(context.history, router_history_turns(context))
        prompt = self._build_prompt(
            context,
            guard_intent,
            candidates,
            history_slice,
            workspace_memory_context=workspace_memory_context,
        )
        prompt_bytes = len(prompt.encode("utf-8"))
        await self._emit_started(context, guard_intent, history_slice=history_slice, prompt_bytes=prompt_bytes)
        await self._emit_candidates(context, candidates, guard_intent)

        try:
            raw = await self.model.chat([ChatMessage(role="user", content=prompt)])
            parsed = _parse_json_object(raw)
            result, validation_failure = self._validate(context, parsed)
            if result is not None:
                result = apply_preferred_tool_override(context, result)
            if result is None:
                LOGGER.warning(
                    "llm classifier rejected runId=%s failure=%s minConfidence=%.2f parsedIntent=%s parsedTool=%s raw=%s",
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
                    guard_intent,
                    raw=raw,
                    parsed=parsed,
                    validation_failure=validation_failure,
                    prompt_bytes=prompt_bytes,
                )
                return None
            await self._emit_selected(context, result, parsed)
            LOGGER.info(
                "llm classifier selected runId=%s intent=%s confidence=%.2f selectedTool=%s attachmentUsage=%s reason=%s",
                context.runId,
                result.intent.value,
                result.confidence,
                result.selectedToolCode or "-",
                result.attachmentUsage or "-",
                result.reason,
            )
            return result
        except Exception as exc:
            LOGGER.warning("llm classifier fallback runId=%s error=%s", context.runId, exc)
            await self._emit_fallback(
                context,
                "router_exception",
                guard_intent,
                error=str(exc),
                prompt_bytes=prompt_bytes,
            )
            return None

    def _enabled(self, context: RunContext) -> bool:
        if not bool(getattr(settings, "agent_llm_router_enabled", False)):
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
        return configured.strip() if configured and configured.strip() else DEFAULT_ROUTER_PROMPT_V2

    def _candidate_payload(self, context: RunContext) -> list[dict[str, Any]]:
        ranked_codes = recall_tool_codes(context)
        preferred = preferred_tool_code(context)
        if preferred and preferred not in ranked_codes:
            ranked_codes.insert(0, preferred)
        ranked = {code: index for index, code in enumerate(ranked_codes)}
        limit = max(1, int(getattr(settings, "agent_router_candidate_limit", 15)))
        registry = ToolRegistry(context)
        tools = sort_tools_with_preferred(
            context,
            sorted(registry.list_tools(), key=lambda tool: ranked.get(tool.toolCode, 999))[:limit],
        )
        return [self._compact_router_candidate(tool, ranked) for tool in tools]

    @staticmethod
    def _compact_router_candidate(tool, ranked: dict[str, int]) -> dict[str, Any]:
        description = (tool.description or "").strip().replace("\n", " ")
        if len(description) > 200:
            description = description[:200]
        schema_props = {}
        if isinstance(tool.inputSchema, dict):
            schema_props = tool.inputSchema.get("properties") or {}
        fields: list[dict[str, Any]] = []
        for field in (tool.fields or [])[:8]:
            prop = schema_props.get(field.fieldKey) if isinstance(schema_props, dict) else {}
            has_enum = bool(isinstance(prop, dict) and isinstance(prop.get("enum"), list) and prop.get("enum"))
            fields.append(
                {
                    "fieldKey": field.fieldKey,
                    "required": bool(field.required),
                    "userRequired": bool(field.userRequired),
                    "agentFillStrategy": field.agentFillStrategy,
                    "hasEnum": has_enum,
                }
            )
        return {
            "toolCode": tool.toolCode,
            "toolName": tool.toolName,
            "description": description,
            "autoCallable": tool.autoCallable,
            "agentHints": tool.hints,
            "outputModality": infer_output_modality(tool),
            "estimatedCreditCost": tool.estimatedCreditCost,
            "autoCallPolicy": "auto" if tool.autoCallable else "configured_or_confirm",
            "rank": ranked.get(tool.toolCode),
            "fields": fields,
        }

    def _build_prompt(
        self,
        context: RunContext,
        guard_intent: IntentResult,
        candidates: list[dict[str, Any]],
        history_slice: list[ChatMessage],
        *,
        workspace_memory_context: str = "",
    ) -> str:
        routing_ctx = build_routing_context(context)
        tool_limit = router_recent_tool_call_limit(context)
        preferred = resolve_preferred_tool(context)
        guidance_text, guidance_tool = _active_tool_clarification(context)
        payload = {
            "userMessage": context.message,
            "requestedOutputModality": requested_output_modality(context.message),
            "preferredToolCode": preferred.toolCode if preferred else None,
            "preferredToolName": preferred.toolName if preferred else None,
            "attachments": _attachment_payload(context),
            **routing_context_payload(routing_ctx),
            "recentHistory": [
                {"role": message.role, "content": _clip(message.content, settings.agent_router_history_clip)}
                for message in history_slice
            ],
            "activeToolClarification": {
                "assistantExcerpt": _clip(guidance_text, 800),
                "toolHint": guidance_tool or None,
            }
            if guidance_text or guidance_tool
            else None,
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
            "workspaceMemory": _clip(workspace_memory_context, 1800) if workspace_memory_context.strip() else "",
            "stateGuard": {
                "intent": guard_intent.intent.value,
                "selectedToolCode": guard_intent.selectedToolCode,
                "candidateToolCodes": guard_intent.candidateToolCodes,
                "confidence": guard_intent.confidence,
                "reason": guard_intent.reason,
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
        if confidence < self._min_confidence(context):
            return None, f"confidence_below_min:{confidence:.2f}<{self._min_confidence(context):.2f}"

        available = {tool.toolCode for tool in context.availableTools}
        selected_tool = _resolve_selected_tool_code(parsed.get("selectedToolCode"), context.availableTools)
        candidate_codes = _resolve_candidate_tool_codes(parsed.get("candidateToolCodes"), context.availableTools)

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

        attachment_usage = parsed.get("attachmentUsage")
        if attachment_usage is not None:
            attachment_usage = str(attachment_usage).strip() or None

        return IntentResult(
            intent=intent,
            confidence=confidence,
            selectedToolCode=selected_tool,
            candidateToolCodes=candidate_codes[:3],
            clarifyingQuestion=parsed.get("clarifyingQuestion") if isinstance(parsed.get("clarifyingQuestion"), str) else None,
            decisionSource="llm_classifier",
            reason=str(parsed.get("reason") or "llm_classifier"),
            arguments=parsed.get("arguments") if isinstance(parsed.get("arguments"), dict) else {},
            missingFields=[item for item in parsed.get("missingFields", []) if isinstance(item, str)]
            if isinstance(parsed.get("missingFields"), list)
            else [],
            followupPatch=parsed.get("followupPatch") if isinstance(parsed.get("followupPatch"), dict) else {},
            requiresConfirmation=parsed.get("requiresConfirmation")
            if isinstance(parsed.get("requiresConfirmation"), bool)
            else None,
            attachmentUsage=attachment_usage,
        ), None

    async def _emit_started(
        self,
        context: RunContext,
        guard_intent: IntentResult,
        *,
        history_slice: list[ChatMessage],
        prompt_bytes: int | None = None,
    ) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=ROUTER_STARTED,
                eventText="Agent router started",
                eventJson={
                    "enabled": True,
                    "routerPromptVersion": ROUTER_PROMPT_VERSION,
                    "minConfidence": self._min_confidence(context),
                    "fallbackToRules": self._fallback_to_rules(context),
                    "historyTurns": router_history_turns(context),
                    "recentToolCallLimit": router_recent_tool_call_limit(context),
                    "includedHistoryMessageCount": len(history_slice),
                    "promptBytes": prompt_bytes,
                    "estimatedRouterTokens": (prompt_bytes // 4) if prompt_bytes else None,
                    "stateGuardIntent": guard_intent.intent.value,
                    "stateGuardSelectedToolCode": guard_intent.selectedToolCode,
                    "stateGuardReason": guard_intent.reason,
                },
            ),
        )

    async def _emit_candidates(
        self,
        context: RunContext,
        candidates: list[dict[str, Any]],
        guard_intent: IntentResult,
    ) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=ROUTER_CANDIDATES,
                eventText=f"{len(candidates)} router candidates",
                eventJson={
                    "candidateToolCodes": [item["toolCode"] for item in candidates[:10]],
                    "stateGuardCandidateToolCodes": guard_intent.candidateToolCodes,
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
                    "attachmentUsage": result.attachmentUsage or parsed.get("attachmentUsage"),
                    "routerPromptVersion": ROUTER_PROMPT_VERSION,
                    "arguments": parsed.get("arguments") if isinstance(parsed.get("arguments"), dict) else {},
                    "missingFields": parsed.get("missingFields") if isinstance(parsed.get("missingFields"), list) else [],
                    "followupPatch": parsed.get("followupPatch") if isinstance(parsed.get("followupPatch"), dict) else {},
                    "requiresConfirmation": parsed.get("requiresConfirmation")
                    if isinstance(parsed.get("requiresConfirmation"), bool)
                    else None,
                    "clarifyingQuestion": result.clarifyingQuestion,
                },
            ),
        )

    async def _emit_fallback(
        self,
        context: RunContext,
        reason: str,
        guard_intent: IntentResult,
        *,
        raw: str | None = None,
        parsed: Any | None = None,
        error: str | None = None,
        validation_failure: str | None = None,
        prompt_bytes: int | None = None,
    ) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=ROUTER_FALLBACK,
                eventText=reason,
                eventJson={
                    "reason": reason,
                    "failureClass": _classify_router_failure(reason, error, validation_failure),
                    "validationFailure": validation_failure,
                    "fallbackToRules": self._fallback_to_rules(context),
                    "stateGuardIntent": guard_intent.intent.value,
                    "stateGuardSelectedToolCode": guard_intent.selectedToolCode,
                    "stateGuardCandidateToolCodes": guard_intent.candidateToolCodes,
                    "stateGuardConfidence": guard_intent.confidence,
                    "stateGuardReason": guard_intent.reason,
                    "promptBytes": prompt_bytes,
                    "estimatedRouterTokens": (prompt_bytes // 4) if prompt_bytes else None,
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
        "image_tool": "tool_use",
        "image_generation_tool": "tool_use",
        "image_editing": "tool_use",
        "image_edit": "tool_use",
        "face_swap": "tool_use",
        "video_generation": "tool_use",
        "video_tool": "tool_use",
        "video_generation_tool": "tool_use",
        "video_editing": "tool_use",
        "audio_generation": "tool_use",
        "audio_tool": "tool_use",
        "audio_generation_tool": "tool_use",
        "music_generation": "tool_use",
        "music_tool": "tool_use",
        "text_generation": "tool_use",
        "text_tool": "tool_use",
        "text_generation_tool": "tool_use",
        "chat": "general_chat",
        "general": "general_chat",
        "general_chat": "general_chat",
        "clarify": "needs_clarification",
        "needs_clarification": "needs_clarification",
        "unsupported": "unsupported",
        "file_analysis": "file_analysis",
        "analyze_file": "file_analysis",
        "analyze_attachment": "file_analysis",
    }
    return aliases.get(normalized, normalized if normalized in {item.value for item in Intent} else None)


def _active_tool_clarification(context: RunContext) -> tuple[str, str | None]:
    pending = context.pendingToolContext
    if pending is not None and pending.status == "ACTIVE":
        text = pending.clarifyingQuestion or ""
        if not text:
            for message in reversed(context.history or []):
                role = (message.role or "").strip().lower()
                if role in {"assistant", "ai"}:
                    text = message.content or ""
                    break
        return text, pending.selectedToolCode
    for message in reversed(context.history or []):
        role = (message.role or "").strip().lower()
        if role in {"assistant", "ai"}:
            return message.content or "", None
    return "", None


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
            return json.loads(text[start : end + 1])
        raise


def _safe_float(value: Any, fallback: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return fallback


def _classify_router_failure(reason: str, error: str | None, validation_failure: str | None) -> str:
    if reason == "router_exception":
        lowered = (error or "").lower()
        if "timeout" in lowered:
            return "timeout"
        if any(marker in lowered for marker in ("connection", "connect", "all connection attempts failed")):
            return "connection"
        return "exception"
    if validation_failure:
        return "validation"
    if reason in {"invalid_or_low_confidence", "router_disabled", "no_available_tools"}:
        return reason
    return "other"


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
    if lowered_type.startswith("image/") or lowered_name.endswith(
        (".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif")
    ):
        return "image"
    if lowered_type.startswith("video/") or lowered_name.endswith((".mp4", ".mov", ".webm", ".mkv")):
        return "video"
    if lowered_type.startswith("audio/") or lowered_name.endswith((".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac")):
        return "audio"
    return "file"
