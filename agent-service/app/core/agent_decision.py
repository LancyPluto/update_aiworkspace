import logging
import re
from collections.abc import Awaitable, Callable

from pydantic import BaseModel

from app.core.intent_router import Intent, IntentResult, IntentRouter
from app.core.schemas import RunContext
from app.tools.registry import ToolRegistry, requested_output_modality, tool_supports_modality

LOGGER = logging.getLogger(__name__)


class DecisionSignal(BaseModel):
    source: str
    verdict: str
    confidence: float
    reason: str


class AgentDecisionService:
    """Combines routing signals before the runtime chooses whether to chat or call tools."""

    def __init__(self, intent_router: IntentRouter | None = None) -> None:
        self.intent_router = intent_router or IntentRouter()

    async def decide(
        self,
        context: RunContext,
        *,
        hard_rule: Callable[[IntentResult], bool],
        llm_router: Callable[[RunContext, IntentResult], Awaitable[IntentResult | None]] | None = None,
    ) -> IntentResult:
        signals: list[DecisionSignal] = []

        conversation_guard = self._conversation_guard(context)
        if conversation_guard is not None:
            signals.append(_signal("conversation_guard", conversation_guard.intent.value, conversation_guard.confidence, conversation_guard.reason))
            return _with_signals(conversation_guard, signals)

        rule_intent = self.intent_router.classify(context)
        signals.append(_signal("rule_router", rule_intent.intent.value, rule_intent.confidence, rule_intent.reason))
        if hard_rule(rule_intent):
            return _with_signals(rule_intent, signals)

        if llm_router is not None:
            llm_intent = await llm_router(context, rule_intent)
            if llm_intent is not None:
                if rule_intent.isFollowUp and llm_intent.intent == Intent.TOOL_USE:
                    llm_intent.isFollowUp = True
                    if not llm_intent.inheritedFromToolCallId:
                        llm_intent.inheritedFromToolCallId = rule_intent.inheritedFromToolCallId
                signals.append(_signal("llm_router", llm_intent.intent.value, llm_intent.confidence, llm_intent.reason))
                if self._llm_overcalled_tool_for_follow_up(context, llm_intent):
                    guarded = IntentResult(
                        intent=Intent.GENERAL_CHAT,
                        confidence=0.9,
                        selectedToolCode=None,
                        candidateToolCodes=llm_intent.candidateToolCodes,
                        decisionSource="decision_layer",
                        reason="llm_tool_call_blocked_for_follow_up",
                    )
                    signals.append(_signal("conversation_guard", guarded.intent.value, guarded.confidence, guarded.reason))
                    return _with_signals(guarded, signals)
                return _with_signals(llm_intent, signals)

        schema_fallback = self._schema_tool_fallback(context, rule_intent)
        if schema_fallback is not None:
            signals.append(_signal("fallback", schema_fallback.intent.value, schema_fallback.confidence, schema_fallback.reason))
            return _with_signals(schema_fallback, signals)

        return _with_signals(rule_intent, signals)

    def _schema_tool_fallback(self, context: RunContext, rule_intent: IntentResult) -> IntentResult | None:
        requested_modality = requested_output_modality(context.message or "")
        if not requested_modality:
            return None
        registry = ToolRegistry(context)
        candidates = registry.rank_by_intent(context.message or "")
        if not candidates:
            return None
        modality_candidates = [
            candidate for candidate in candidates
            if tool_supports_modality(candidate.tool, requested_modality)
        ]
        if not modality_candidates:
            return None
        if requested_modality == "image":
            pure_image_candidates = [
                candidate for candidate in modality_candidates
                if not tool_supports_modality(candidate.tool, "video")
            ]
            modality_candidates = pure_image_candidates or modality_candidates
        top = modality_candidates[0]
        if top.score < 7:
            return None
        return IntentResult(
            intent=Intent.TOOL_USE,
            confidence=max(0.78, rule_intent.confidence),
            selectedToolCode=top.tool.toolCode,
            candidateToolCodes=[candidate.tool.toolCode for candidate in modality_candidates[:3]],
            decisionSource="decision_layer",
            reason=f"schema_{requested_modality}_tool_fallback",
        )

    def _conversation_guard(self, context: RunContext) -> IntentResult | None:
        message = context.message or ""
        if _looks_like_explicit_chat_intent(message):
            return IntentResult(
                intent=Intent.GENERAL_CHAT,
                confidence=0.95,
                selectedToolCode=None,
                candidateToolCodes=[],
                decisionSource="decision_layer",
                reason="explicit_chat_intent",
            )
        if _looks_like_session_recap_question(message):
            return IntentResult(
                intent=Intent.GENERAL_CHAT,
                confidence=0.92,
                selectedToolCode=None,
                candidateToolCodes=[],
                decisionSource="decision_layer",
                reason="session_recap_question",
            )
        if _looks_like_tool_failure_question(message):
            return IntentResult(
                intent=Intent.GENERAL_CHAT,
                confidence=0.9,
                selectedToolCode=None,
                candidateToolCodes=[],
                decisionSource="decision_layer",
                reason="tool_failure_question",
            )
        if _looks_like_follow_up_or_meta_question(message):
            return IntentResult(
                intent=Intent.GENERAL_CHAT,
                confidence=0.93,
                selectedToolCode=None,
                candidateToolCodes=[],
                decisionSource="decision_layer",
                reason="follow_up_or_meta_question",
            )
        return None

    def _llm_overcalled_tool_for_follow_up(self, context: RunContext, llm_intent: IntentResult) -> bool:
        return llm_intent.intent == Intent.TOOL_USE and (
            _looks_like_explicit_chat_intent(context.message or "")
            or _looks_like_follow_up_or_meta_question(context.message or "")
        )


def _looks_like_explicit_chat_intent(message: str) -> bool:
    compact = re.sub(r"\s+", "", message)
    if not compact:
        return False
    if requested_output_modality(compact):
        return False
    return _contains_any(
        compact,
        (
            "跟我聊天",
            "陪我聊天",
            "和我聊天",
            "聊聊天",
            "闲聊",
            "随便聊",
            "正常聊",
            "我们聊会",
            "我们正常聊会",
            "跟我聊会",
            "陪我聊会",
            "只聊天",
            "不要调用工具",
            "别调用工具",
        ),
    )


def _looks_like_follow_up_or_meta_question(message: str) -> bool:
    compact = re.sub(r"\s+", "", message)
    if not compact:
        return False
    has_previous_reference = _contains_any(
        compact,
        (
            "刚才",
            "刚刚",
            "之前",
            "上一轮",
            "上次",
            "上面",
            "这张图",
            "这张图片",
            "这张照片",
            "那张图",
            "那张照片",
            "刚才那张",
            "上面那张",
        ),
    )
    asks_about_context = _contains_any(
        compact,
        (
            "为什么",
            "怎么想",
            "怎么看",
            "觉得",
            "像不像",
            "评价",
            "分析",
            "猜猜",
            "用什么",
            "哪个工具",
            "怎么生成",
            "怎么优化",
            "继续改",
            "可以改",
        ),
    )
    if has_previous_reference and asks_about_context:
        return True
    if "你猜猜" in compact and _contains_any(compact, ("为什么", "干嘛", "目的")):
        return True
    if _contains_any(compact, ("我为什么让你", "为什么让你", "为什么叫你", "为什么要你")) and _contains_any(
        compact,
        ("生成", "做", "画", "图片", "照片", "视频", "文案"),
    ):
        return True
    if compact.startswith(("你觉得", "你认为")) and _contains_any(compact, ("这张", "刚才", "之前", "上面")):
        return True
    return False


def _looks_like_session_recap_question(message: str) -> bool:
    compact = re.sub(r"\s+", "", message)
    return _contains_any(compact, ("总结一下刚才", "总结刚才", "刚才的对话", "刚刚的对话")) and _contains_any(
        compact,
        ("总结", "回顾", "做了什么", "完成了什么", "结果"),
    )


def _looks_like_tool_failure_question(message: str) -> bool:
    compact = re.sub(r"\s+", "", message)
    return _contains_any(compact, ("失败", "报错", "异常", "原因")) and _contains_any(
        compact,
        ("是什么原因", "为什么", "一般", "怎么回事", "如何解决"),
    )


def _contains_any(value: str, needles: tuple[str, ...]) -> bool:
    return any(needle in value for needle in needles)


def _signal(source: str, verdict: str, confidence: float, reason: str) -> DecisionSignal:
    return DecisionSignal(source=source, verdict=verdict, confidence=confidence, reason=reason)


def _with_signals(intent: IntentResult, signals: list[DecisionSignal]) -> IntentResult:
    intent.signals = [signal.model_dump() for signal in signals]
    LOGGER.info(
        "agent decision selected intent=%s confidence=%.2f selectedTool=%s reason=%s signals=%s",
        intent.intent.value,
        intent.confidence,
        intent.selectedToolCode or "-",
        intent.reason,
        intent.signals,
    )
    return intent
