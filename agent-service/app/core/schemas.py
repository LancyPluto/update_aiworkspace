import json
from typing import Any

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, field_validator


class ChatMessage(BaseModel):
    role: str
    content: str | list[dict[str, Any]]
    name: str | None = None
    toolCallId: str | None = Field(default=None, validation_alias=AliasChoices("toolCallId", "tool_call_id"))
    toolCalls: list[dict[str, Any]] | None = Field(default=None, validation_alias=AliasChoices("toolCalls", "tool_calls"))


class ToolFieldDescriptor(BaseModel):
    fieldKey: str
    fieldName: str = ""
    fieldType: str = "text"
    description: str | None = None
    options: Any | None = None
    required: bool = False
    executionRequired: bool | None = None
    userRequired: bool | None = None
    defaultValue: str | None = None
    agentFillStrategy: str | None = None
    riskLevel: str | None = None
    sortOrder: int | None = None


class ToolDescriptor(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    toolCode: str
    toolName: str = Field(default="", validation_alias=AliasChoices("toolName", "name"))
    description: str | None = None
    estimatedCreditCost: int = Field(default=0, validation_alias=AliasChoices("estimatedCreditCost", "creditCost"))
    inputSchema: dict[str, Any] = Field(default_factory=dict)
    autoCallable: bool = False
    fields: list[ToolFieldDescriptor] = Field(default_factory=list)
    hints: dict[str, Any] = Field(default_factory=dict, validation_alias=AliasChoices("hints", "agentHints"))


class AgentSkillDescriptor(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    skillCode: str = Field(validation_alias=AliasChoices("skillCode", "skill_code"))
    displayName: str = Field(default="", validation_alias=AliasChoices("displayName", "display_name"))
    description: str = ""
    toolCodes: list[str] = Field(default_factory=list, validation_alias=AliasChoices("toolCodes", "tool_codes"))
    version: int | None = None


class ToolPreference(BaseModel):
    toolCode: str
    autoCallEnabled: bool = False


class AgentFileContext(BaseModel):
    id: int
    originalFilename: str
    contentType: str | None = None
    status: str
    extractedText: str = ""
    downloadUrl: str | None = None


class AgentFileChunkContext(BaseModel):
    id: int
    fileId: int
    originalFilename: str
    chunkIndex: int
    contentText: str
    metadataJson: str | None = None
    score: int = 0


class WorkspaceMemoryItem(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: int
    title: str
    content: str
    memoryType: str = Field(validation_alias=AliasChoices("memoryType", "memory_type"))
    score: float
    reason: str | None = None
    matchedFields: list[str] = Field(default_factory=list, validation_alias=AliasChoices("matchedFields", "matched_fields"))
    userId: int | None = Field(default=None, validation_alias=AliasChoices("userId", "user_id"))
    workspaceId: int | None = Field(default=None, validation_alias=AliasChoices("workspaceId", "workspace_id"))
    sourceRunId: int | None = Field(default=None, validation_alias=AliasChoices("sourceRunId", "source_run_id"))
    sourceMessageId: int | None = Field(default=None, validation_alias=AliasChoices("sourceMessageId", "source_message_id"))
    sourceToolCallId: int | None = Field(default=None, validation_alias=AliasChoices("sourceToolCallId", "source_tool_call_id"))
    importance: int | None = None
    confidence: float | None = None
    pinned: bool = False
    tagsJson: str | None = Field(default=None, validation_alias=AliasChoices("tagsJson", "tags_json"))
    metadataJson: str | None = Field(default=None, validation_alias=AliasChoices("metadataJson", "metadata_json"))
    lastAccessedAt: str | None = Field(default=None, validation_alias=AliasChoices("lastAccessedAt", "last_accessed_at"))
    accessCount: int | None = Field(default=None, validation_alias=AliasChoices("accessCount", "access_count"))
    expiresAt: str | None = Field(default=None, validation_alias=AliasChoices("expiresAt", "expires_at"))
    status: str | None = None
    updatedAt: str | None = Field(default=None, validation_alias=AliasChoices("updatedAt", "updated_at"))


class SessionSearchItem(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    itemType: str = Field(validation_alias=AliasChoices("itemType", "item_type"))
    id: int
    runId: int | None = Field(default=None, validation_alias=AliasChoices("runId", "run_id"))
    taskId: int | None = Field(default=None, validation_alias=AliasChoices("taskId", "task_id"))
    role: str | None = None
    toolCode: str | None = Field(default=None, validation_alias=AliasChoices("toolCode", "tool_code"))
    content: str | None = None
    argumentsJson: str | None = Field(default=None, validation_alias=AliasChoices("argumentsJson", "arguments_json"))
    resultJson: str | None = Field(default=None, validation_alias=AliasChoices("resultJson", "result_json"))
    errorCode: str | None = Field(default=None, validation_alias=AliasChoices("errorCode", "error_code"))
    errorMessage: str | None = Field(default=None, validation_alias=AliasChoices("errorMessage", "error_message"))
    score: int = 0
    createdAt: str | None = Field(default=None, validation_alias=AliasChoices("createdAt", "created_at"))


class AgentModelConfig(BaseModel):
    id: int | None = None
    provider: str = "mock"
    modelName: str = "mock"
    baseUrl: str | None = None
    apiKey: str = ""
    minimaxGroupId: str | None = None
    timeoutSeconds: int = 60
    enabled: bool = True
    # Backend may send null for agentEnabled; accept it for compatibility.
    agentEnabled: bool | None = True
    capabilities: list[str] | None = None


class ContextWindow(BaseModel):
    snapshotId: int | None = None
    strategy: str | None = None
    maxHistoryMessages: int | None = None
    historyMessageCount: int = 0
    fileCount: int = 0
    fileChunkCount: int = 0
    memoryItemCount: int = 0
    estimatedInputTokens: int = 0
    snapshotJson: str | None = None


class MemorySettings(BaseModel):
    autoSaveEnabled: bool = True
    retrievalLimit: int = 6
    enabledTypes: list[str] = Field(default_factory=lambda: ["user_profile", "project_knowledge", "custom"])
    writePrompt: str | None = None
    retrievalPrompt: str | None = None
    toolLoopEnabled: bool | None = None
    consolidationEnabled: bool | None = None
    consolidationLlmEnabled: bool | None = None
    consolidationTurnInterval: int | None = None
    consolidationCharThreshold: int | None = None
    consolidationTokenThreshold: int | None = None
    consolidationRecentToolThreshold: int | None = None
    consolidationMaxContextMessages: int | None = None
    consolidationPrompt: str | None = None
    consolidationMinConfidence: float | None = None
    candidateConfidenceThreshold: float | None = None


class AgentRouterSettings(BaseModel):
    enabled: bool = True
    prompt: str | None = None
    minConfidence: float = 0.7
    fallbackToRules: bool = True
    historyTurns: int = 4
    recentToolCallLimit: int = 5


class RuntimeSettings(BaseModel):
    maxModelCalls: int | None = None
    maxToolCalls: int | None = None
    maxHistoryMessages: int | None = None
    workingMemoryTokenBudget: int | None = None
    messageTokenSoftLimit: int | None = None
    toolOutputTokenSoftLimit: int | None = None
    summaryTokenLimit: int | None = None
    toolExecutionTimeoutSeconds: int | None = None
    imageToolExecutionTimeoutSeconds: int | None = None
    videoToolExecutionTimeoutSeconds: int | None = None
    musicToolExecutionTimeoutSeconds: int | None = None
    toolPollIntervalSeconds: float | None = None
    toolStreamRelayEnabled: bool | None = None
    productToolLoopEnabled: bool | None = None
    productToolLoopMaxCalls: int | None = None
    productToolLoopFallbackToRouter: bool | None = None
    intelligenceLevel: str | None = None


class ReferenceMention(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    token: str | None = None
    refLabel: str | None = None
    assetKey: str | None = None
    fileId: int | str | None = None
    url: str
    kind: str | None = None
    name: str | None = None
    contentType: str | None = None
    previewUrl: str | None = None
    source: str | None = None


class RecentToolCallContext(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: int
    runId: int | None = None
    toolCode: str
    taskId: int | None = None
    argumentsJson: dict[str, Any] = Field(default_factory=dict)
    resultJson: dict[str, Any] = Field(default_factory=dict)
    resourceType: str | None = None
    mediaUrls: list[str] = Field(default_factory=list)
    createdAt: str | None = None


class PendingToolContext(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: int | None = None
    runId: int | None = None
    sessionId: int | None = None
    userId: int | None = None
    selectedToolCode: str | None = None
    candidateToolCodesJson: list[str] | None = None
    collectedArgumentsJson: dict[str, Any] = Field(default_factory=dict)
    missingArgumentsJson: list[str] = Field(default_factory=list)
    clarifyingQuestion: str | None = None
    confirmationRequired: bool = False
    status: str = "ACTIVE"

    @field_validator("candidateToolCodesJson", mode="before")
    @classmethod
    def _parse_candidate_tool_codes(cls, value: Any) -> list[str] | None:
        if value is None:
            return None
        if isinstance(value, list):
            return [str(item) for item in value if str(item).strip()]
        if isinstance(value, str) and value.strip():
            try:
                parsed = json.loads(value)
                if isinstance(parsed, str):
                    parsed = json.loads(parsed)
                if isinstance(parsed, list):
                    return [str(item) for item in parsed if str(item).strip()]
            except Exception:
                return None
        return None

    @field_validator("collectedArgumentsJson", mode="before")
    @classmethod
    def _parse_collected_arguments(cls, value: Any) -> dict[str, Any]:
        if isinstance(value, dict):
            return value
        if isinstance(value, str) and value.strip():
            try:
                parsed = json.loads(value)
                if isinstance(parsed, str):
                    parsed = json.loads(parsed)
                if isinstance(parsed, dict):
                    return parsed
            except Exception:
                return {}
        return {}

    @field_validator("missingArgumentsJson", mode="before")
    @classmethod
    def _parse_missing_arguments(cls, value: Any) -> list[str]:
        if isinstance(value, list):
            return [str(item) for item in value if str(item).strip()]
        if isinstance(value, str) and value.strip():
            try:
                parsed = json.loads(value)
                if isinstance(parsed, str):
                    parsed = json.loads(parsed)
                if isinstance(parsed, list):
                    return [str(item) for item in parsed if str(item).strip()]
            except Exception:
                return []
        return []


class RunContext(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    runId: int
    sessionId: int
    userId: int
    status: str | None = None
    workspaceId: int | None = Field(default=None, validation_alias=AliasChoices("workspaceId", "workspace_id"))
    message: str = Field(default="", validation_alias=AliasChoices("message", "userMessage"))
    history: list[ChatMessage] = Field(default_factory=list)
    conversationSummary: str | None = Field(
        default=None,
        validation_alias=AliasChoices("conversationSummary", "conversation_summary"),
    )
    agentFiles: list[AgentFileContext] = Field(default_factory=list)
    agentFileChunks: list[AgentFileChunkContext] = Field(default_factory=list)
    availableTools: list[ToolDescriptor] = Field(default_factory=list, validation_alias=AliasChoices("availableTools", "tools"))
    availableSkills: list[AgentSkillDescriptor] = Field(default_factory=list, validation_alias=AliasChoices("availableSkills", "skills"))
    toolPreferences: list[ToolPreference] = Field(default_factory=list)
    creditBudget: int = 0
    contextWindow: ContextWindow | None = None
    modelConfig: AgentModelConfig | None = None
    agentSystemPrompt: str | None = None
    deepAgentsSystemPrompt: str | None = None
    memorySettings: MemorySettings | None = None
    routerSettings: AgentRouterSettings | None = None
    runtimeSettings: RuntimeSettings | None = None
    recentToolCalls: list[RecentToolCallContext] = Field(default_factory=list)
    pendingToolContext: PendingToolContext | None = Field(default=None, validation_alias=AliasChoices("pendingToolContext", "pending_tool_context"))
    preferredToolCode: str | None = Field(default=None, validation_alias=AliasChoices("preferredToolCode", "preferred_tool_code"))
    referenceMentions: list[ReferenceMention] = Field(default_factory=list)
    globalFileIds: list[int | str] = Field(default_factory=list, validation_alias=AliasChoices("globalFileIds", "global_file_ids"))
    contentParts: list[dict[str, Any]] = Field(default_factory=list, validation_alias=AliasChoices("contentParts", "content_parts"))
    positionalPrompt: str | None = Field(default=None, validation_alias=AliasChoices("positionalPrompt", "positional_prompt"))


class RunEventCreate(BaseModel):
    eventType: str
    eventText: str | None = None
    eventJson: dict[str, Any] | None = None


class RunArtifactCreate(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    filename: str
    content: str
    contentType: str = Field(validation_alias=AliasChoices("contentType", "content_type"))


class RunComplete(BaseModel):
    finalAnswer: str
    intent: str
    modelProviderCode: str | None = None
    modelName: str | None = None
    consumedCredits: int
    promptTokens: int | None = None
    completionTokens: int | None = None


class RunFail(BaseModel):
    errorCode: str
    errorMessage: str
    consumedCredits: int | None = None
    promptTokens: int | None = None
    completionTokens: int | None = None


class ToolCallCreate(BaseModel):
    toolCode: str
    argumentsJson: dict[str, Any] = Field(default_factory=dict)


class ToolCallComplete(BaseModel):
    resultJson: dict[str, Any] = Field(default_factory=dict)


class ToolCallFail(BaseModel):
    errorCode: str
    errorMessage: str


class ToolCallTaskBind(BaseModel):
    taskId: int


class AgentRouteDebugTool(BaseModel):
    toolCode: str
    toolName: str = ""
    autoCallable: bool = False


class AgentRouteDebugResponse(BaseModel):
    intent: str
    confidence: float
    selectedToolCode: str | None = None
    candidateToolCodes: list[str] = Field(default_factory=list)
    clarifyingQuestion: str | None = None
    decisionSource: str
    reason: str
    requestedOutputModality: str | None = None
    visibleToolCount: int = 0
    visibleTools: list[AgentRouteDebugTool] = Field(default_factory=list)
    readiness: str = "ready"
    modelConnectivity: bool = False
    availableToolCount: int = 0
    disclosedToolCount: int = 0
    estimatedRouterPromptBytes: int = 0
    nextActions: list[str] = Field(default_factory=list)
    llmRouterEnabled: bool = True
    productToolLoopEnabled: bool = True
    toolDisclosureEnabled: bool = True


class ToolCallResponse(BaseModel):
    id: int
    runId: int | None = None
    toolCode: str
    taskId: int | None = None
    status: str | None = None
    argumentsJson: str | dict[str, Any] | None = None
    resultJson: str | dict[str, Any] | None = None
    errorCode: str | None = None
    errorMessage: str | None = None


class TaskCreate(BaseModel):
    userId: int
    toolCode: str
    params: dict[str, Any] = Field(default_factory=dict)
    clientRequestId: str | None = None


class TaskStatusResponse(BaseModel):
    taskId: int
    taskNo: str | None = None
    status: str
    progress: int | None = None
    progressMessage: str | None = None


class TaskResultResponse(BaseModel):
    resourceType: str | None = None
    contentText: str | None = None


class TaskDetailResponse(BaseModel):
    taskId: int
    taskNo: str | None = None
    userId: int | None = None
    toolCode: str | None = None
    toolName: str | None = None
    status: str
    progress: int | None = None
    progressMessage: str | None = None
    errorCode: str | None = None
    errorMessage: str | None = None
    params: dict[str, Any] | None = None
    result: TaskResultResponse | None = None
