from typing import Any

from typing import Any

from pydantic import AliasChoices, BaseModel, ConfigDict, Field


class ChatMessage(BaseModel):
    role: str
    content: str


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
    hints: dict[str, Any] = Field(default_factory=dict)


class ToolPreference(BaseModel):
    toolCode: str
    autoCallEnabled: bool = False


class AgentFileContext(BaseModel):
    id: int
    originalFilename: str
    contentType: str | None = None
    status: str
    extractedText: str = ""


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
    workspaceId: int | None = Field(default=None, validation_alias=AliasChoices("workspaceId", "workspace_id"))
    sourceRunId: int | None = Field(default=None, validation_alias=AliasChoices("sourceRunId", "source_run_id"))
    status: str | None = None
    updatedAt: str | None = Field(default=None, validation_alias=AliasChoices("updatedAt", "updated_at"))


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


class RunContext(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    runId: int
    sessionId: int
    userId: int
    status: str | None = None
    workspaceId: int | None = Field(default=None, validation_alias=AliasChoices("workspaceId", "workspace_id"))
    message: str = Field(default="", validation_alias=AliasChoices("message", "userMessage"))
    history: list[ChatMessage] = Field(default_factory=list)
    agentFiles: list[AgentFileContext] = Field(default_factory=list)
    agentFileChunks: list[AgentFileChunkContext] = Field(default_factory=list)
    availableTools: list[ToolDescriptor] = Field(default_factory=list, validation_alias=AliasChoices("availableTools", "tools"))
    toolPreferences: list[ToolPreference] = Field(default_factory=list)
    creditBudget: int = 0
    contextWindow: ContextWindow | None = None
    modelConfig: AgentModelConfig | None = None
    agentSystemPrompt: str | None = None
    deepAgentsSystemPrompt: str | None = None
    pendingToolContext: PendingToolContext | None = Field(default=None, validation_alias=AliasChoices("pendingToolContext", "pending_tool_context"))


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
