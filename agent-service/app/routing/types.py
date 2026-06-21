from enum import StrEnum

from pydantic import BaseModel, Field


class Intent(StrEnum):
    GENERAL_CHAT = "general_chat"
    TOOL_USE = "tool_use"
    NEEDS_CLARIFICATION = "needs_clarification"
    UNSUPPORTED = "unsupported"
    RAG = "rag"
    FILE_ANALYSIS = "file_analysis"
    WORKFLOW = "workflow"
    SECURITY_REJECTED = "security_rejected"


class AttachmentUsage(StrEnum):
    NONE = "none"
    REFERENCE_FOR_GENERATION = "reference_for_generation"
    ANALYZE_CONTENT = "analyze_content"
    UNKNOWN = "unknown"


class IntentResult(BaseModel):
    intent: Intent
    confidence: float
    selectedToolCode: str | None = None
    candidateToolCodes: list[str] = Field(default_factory=list)
    clarifyingQuestion: str | None = None
    decisionSource: str = "rules"
    reason: str
    signals: list[dict] = Field(default_factory=list)
    arguments: dict = Field(default_factory=dict)
    missingFields: list[str] = Field(default_factory=list)
    isFollowUp: bool = False
    inheritedFromToolCallId: int | None = None
    followupPatch: dict = Field(default_factory=dict)
    requiresConfirmation: bool | None = None
    attachmentUsage: str | None = None
