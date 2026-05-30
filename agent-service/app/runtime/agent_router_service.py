import json
import logging
from typing import Any

from app.config import settings
from app.core.event_types import ROUTER_CANDIDATES, ROUTER_FALLBACK, ROUTER_SELECTED, ROUTER_STARTED
from app.core.intent_router import Intent, IntentResult, IntentRouter
from app.core.schemas import ChatMessage, RunContext, RunEventCreate
from app.tools.registry import ToolRegistry, requested_output_modality

LOGGER = logging.getLogger(__name__)

DEFAULT_ROUTER_PROMPT = (
    "You are the primary router for an AI tool marketplace agent. "
    "Decide whether the user needs a normal answer, a tool call, clarification, or an unsupported path. "
    "Return only valid JSON matching this schema: "
    "{\"intent\":\"tool_use|general_chat|needs_clarification|unsupported\","
    "\"selectedToolCode\":string|null,\"candidateToolCodes\":string[],"
    "\"confidence\":number,\"reason\":string,\"arguments\":object,\"missingFields\":string[],"
    "\"clarifyingQuestion\":string|null}. "
    "Use the available tool metadata as source of truth. "
    "Prefer the tool that directly produces the requested output modality: image/photo/poster/cos/visual requests use image tools; "
    "video/short-video/image-to-video requests use video tools; copywriting/title/article requests use text tools. "
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
        await self._emit_started(context, rule_intent)
        await self._emit_candidates(context, candidates, rule_intent)

        try:
            raw = await self.model.chat([ChatMessage(role="user", content=self._build_prompt(context, rule_intent, candidates))])
            parsed = _parse_json_object(raw)
            result = self._validate(context, parsed)
            if result is None:
                await self._emit_fallback(context, "invalid_or_low_confidence", rule_intent, raw=raw, parsed=parsed)
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
        ranked = {code: index for index, code in enumerate(ranked_codes)}
        tools = sorted(registry.list_tools(), key=lambda tool: ranked.get(tool.toolCode, 999))[:30]
        return [
            {
                "toolCode": tool.toolCode,
                "toolName": tool.toolName,
                "description": tool.description or "",
                "autoCallable": tool.autoCallable,
                "agentHints": tool.hints,
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

    def _build_prompt(self, context: RunContext, rule_intent: IntentResult, candidates: list[dict[str, Any]]) -> str:
        payload = {
            "userMessage": context.message,
            "requestedOutputModality": requested_output_modality(context.message),
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

    def _validate(self, context: RunContext, parsed: Any) -> IntentResult | None:
        if not isinstance(parsed, dict):
            return None
        raw_intent = str(parsed.get("intent") or "").strip()
        try:
            intent = Intent(raw_intent)
        except ValueError:
            return None
        confidence = _safe_float(parsed.get("confidence"), 0)
        if confidence < self._min_confidence(context):
            return None

        available = {tool.toolCode for tool in context.availableTools}
        selected_tool = parsed.get("selectedToolCode")
        selected_tool = selected_tool.strip() if isinstance(selected_tool, str) and selected_tool.strip() else None
        if intent == Intent.TOOL_USE:
            if not selected_tool or selected_tool not in available:
                return None
        elif selected_tool and selected_tool not in available:
            selected_tool = None

        raw_candidates = parsed.get("candidateToolCodes")
        candidate_codes = [
            code for code in raw_candidates
            if isinstance(code, str) and code in available
        ] if isinstance(raw_candidates, list) else []
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
        )

    async def _emit_started(self, context: RunContext, rule_intent: IntentResult) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=ROUTER_STARTED,
                eventText="Agent router started",
                eventJson={
                    "enabled": True,
                    "minConfidence": self._min_confidence(context),
                    "fallbackToRules": self._fallback_to_rules(context),
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
    ) -> None:
        await self.backend.append_event(
            context.runId,
            RunEventCreate(
                eventType=ROUTER_FALLBACK,
                eventText=reason,
                eventJson={
                    "reason": reason,
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
