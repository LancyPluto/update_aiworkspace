from enum import StrEnum

from pydantic import BaseModel, Field

from app.core.schemas import RunContext
from app.tools.registry import ToolRegistry


class Intent(StrEnum):
    GENERAL_CHAT = "general_chat"
    TOOL_USE = "tool_use"
    NEEDS_CLARIFICATION = "needs_clarification"
    UNSUPPORTED = "unsupported"
    RAG = "rag"
    FILE_ANALYSIS = "file_analysis"
    WORKFLOW = "workflow"
    SECURITY_REJECTED = "security_rejected"


class IntentResult(BaseModel):
    intent: Intent
    confidence: float
    selectedToolCode: str | None = None
    candidateToolCodes: list[str] = Field(default_factory=list)
    clarifyingQuestion: str | None = None
    decisionSource: str = "rules"
    reason: str


class IntentRouter:
    unsupported_keywords = ("上传", "文件", "知识库", "RAG", "向量", "多智能体", "工作流")
    vague_messages = {"帮我做一下", "帮我弄一下", "处理一下", "做一下"}
    short_chat_messages = {"你是谁", "你能做什么", "你好", "hi", "hello", "在吗"}
    tool_action_keywords = ("写", "生成", "优化", "改写", "润色", "做", "帮我", "输出", "文案", "标题", "笔记", "朋友圈", "公众号")

    def classify(self, context: RunContext) -> IntentResult:
        message = context.message.strip()
        message_lower = message.lower()
        if context.agentFiles:
            return IntentResult(intent=Intent.FILE_ANALYSIS, confidence=0.9, reason="ready_file_context_available")
        if "文件" in message or "上传" in message:
            return IntentResult(intent=Intent.FILE_ANALYSIS, confidence=0.75, reason="file_analysis_request")
        if any(keyword.lower() in message_lower for keyword in self.unsupported_keywords):
            return IntentResult(intent=Intent.UNSUPPORTED, confidence=0.9, reason="phase_unsupported_capability")
        if not message:
            return IntentResult(intent=Intent.NEEDS_CLARIFICATION, confidence=0.8, reason="empty_request")
        if message_lower in self.short_chat_messages:
            return IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.95, reason="short_general_chat")

        registry = ToolRegistry(context)
        candidates = registry.rank_by_intent(message)
        if candidates:
            top = candidates[0]
            second_score = candidates[1].score if len(candidates) > 1 else 0
            candidate_codes = [candidate.tool.toolCode for candidate in candidates[:3]]
            if top.score >= 10 and (len(candidates) == 1 or top.score - second_score >= 3):
                return IntentResult(
                    intent=Intent.TOOL_USE,
                    confidence=0.9,
                    selectedToolCode=top.tool.toolCode,
                    candidateToolCodes=candidate_codes,
                    reason="high_confidence_tool_match",
                )
            if len(candidates) > 1 and top.score >= 4 and top.score - second_score <= 2 and self._looks_like_tool_request(message):
                return IntentResult(
                    intent=Intent.NEEDS_CLARIFICATION,
                    confidence=0.7,
                    candidateToolCodes=candidate_codes,
                    reason="ambiguous_tool_candidates",
                )
            if top.score >= 4 and self._looks_like_tool_request(message):
                return IntentResult(
                    intent=Intent.TOOL_USE,
                    confidence=0.75,
                    selectedToolCode=top.tool.toolCode,
                    candidateToolCodes=candidate_codes,
                    reason="scored_tool_match",
                )
            if self._looks_like_tool_request(message):
                return IntentResult(
                    intent=Intent.NEEDS_CLARIFICATION,
                    confidence=0.65,
                    candidateToolCodes=candidate_codes,
                    reason="weak_tool_signal",
                )

        if len(message) <= 6 or message in self.vague_messages:
            return IntentResult(intent=Intent.NEEDS_CLARIFICATION, confidence=0.75, reason="request_too_vague")
        return IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.6, reason="default_general_chat")

    def _looks_like_tool_request(self, message: str) -> bool:
        lowered = message.lower()
        return any(keyword in lowered for keyword in self.tool_action_keywords)
