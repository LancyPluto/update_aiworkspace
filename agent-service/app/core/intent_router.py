from enum import StrEnum

from pydantic import BaseModel

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
    reason: str


class IntentRouter:
    unsupported_keywords = ("上传", "文件", "知识库", "RAG", "向量", "多智能体", "工作流")
    vague_messages = {"帮我做一下", "帮我弄一下", "处理一下", "做一下"}

    def classify(self, context: RunContext) -> IntentResult:
        message = context.message.strip()
        if context.agentFiles:
            return IntentResult(intent=Intent.FILE_ANALYSIS, confidence=0.9, reason="ready_file_context_available")
        if "文件" in message or "上传" in message:
            return IntentResult(intent=Intent.FILE_ANALYSIS, confidence=0.75, reason="file_analysis_request")
        if any(keyword.lower() in message.lower() for keyword in self.unsupported_keywords):
            return IntentResult(intent=Intent.UNSUPPORTED, confidence=0.9, reason="phase_unsupported_capability")
        if len(message) <= 6 or message in self.vague_messages:
            return IntentResult(intent=Intent.NEEDS_CLARIFICATION, confidence=0.75, reason="request_too_vague")

        tool = ToolRegistry(context).match_by_intent(message)
        if tool is not None:
            return IntentResult(
                intent=Intent.TOOL_USE,
                confidence=0.85,
                selectedToolCode=tool.toolCode,
                reason="matched_auto_callable_tool",
            )
        return IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.6, reason="default_general_chat")
