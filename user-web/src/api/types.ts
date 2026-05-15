/**
 * 与《openapi.yml》V1 契约对齐的类型与常量。
 * 响应壳：{ code, message, data, traceId? }
 */

/** §3 V1 保留错误码 */
export type ApiErrorCode =
  | "SUCCESS"
  | "PARAM_ERROR"
  | "UNAUTHORIZED"
  | "FORBIDDEN"
  | "ADMIN_UNAUTHORIZED"
 | "ADMIN_FORBIDDEN"
  | "TOOL_NOT_FOUND"
  | "TOOL_OFFLINE"
  | "CREDIT_NOT_ENOUGH"
  | "TASK_NOT_FOUND"
  | "TASK_STATUS_INVALID"
  | "MODEL_CALL_FAILED"
  | "AGENT_SESSION_NOT_FOUND"
  | "AGENT_RUN_NOT_FOUND"
  | "AGENT_RUN_NOT_CANCELLABLE"
  | "AGENT_TOOL_NOT_AVAILABLE"
  | "AGENT_CREDIT_NOT_ENOUGH"
  | "AGENT_RATE_LIMITED"
  | "AGENT_ACTIVE_RUN_LIMIT"
  | "AGENT_RUN_BUDGET_EXCEEDED"
  | "AGENT_TOOL_CALL_LIMIT"
  | "AGENT_MODEL_CALL_LIMIT"
  | "AGENT_SECURITY_REJECTED"
  | "SYSTEM_ERROR"

export interface ApiResponse<T> {
  code: ApiErrorCode
  message: string
  data: T | null
  traceId?: string
  requestId?: string
}

/** §4 统一任务状态 */
export type TaskStatus =
  | "CREATED"
  | "QUEUED"
  | "PROCESSING"
  | "RETRYING"
  | "SUCCESS"
  | "FAILED"
  | "TIMEOUT"
  | "CANCELLED"

export type UserType = "USER" | "ADMIN"
export type UserAccountStatus = "ACTIVE" | "DISABLED"
export type ToolBizStatus = "DRAFT" | "ONLINE" | "OFFLINE"

/* ========== 分页 ========== */

export interface PageResult<T> {
  list: T[]
  total: number
  pageNo: number
  pageSize: number
  hasNext: boolean
}

/* ========== 认证相关 ========== */

/** POST /api/v1/auth/login —— 契约要求 account + password */
export interface LoginRequest {
  account: string
  password: string
}

export interface LoginResponse {
  accessToken?: string
  token?: string
  tokenType?: string
  expiresIn?: number
  user?: UserProfile
}

export type SmsCodeScene = "REGISTER" | "LOGIN"

export interface SmsCodeRequest {
  phone: string
  scene: SmsCodeScene
}

export interface SmsCodeResponse {
  expiresInSeconds: number
  cooldownSeconds: number
  debugCode?: string | null
}

export interface SmsAuthRequest {
  phone: string
  code: string
  nickname?: string
}

export interface RegisterRequest {
  username?: string
  password: string
  phone?: string
  email?: string
  nickname?: string
}

/** GET /api/v1/users/me —— UserProfile */
export interface UserProfile {
  id: number
  username: string
  nickname?: string
  userType: UserType
  phone?: string | null
  email?: string | null
  status: UserAccountStatus
}

/* ========== 工具相关 ========== */

/** GET /api/v1/tool-categories */
export interface ToolCategory {
  id: number
  categoryCode: string
  categoryName: string
  sortOrder: number
}

/** GET /api/v1/tools 列表项 */
export interface ToolSummary {
  id: number
  toolCode: string
  toolName: string
  categoryId: number
  categoryName: string
  description?: string | null
  coverUrl?: string | null
  status: ToolBizStatus
  estimatedCreditCost: number
}

/** 动态字段选项 */
export interface ToolFieldOption {
  label: string
  value: string
}

/** 动态字段定义 */
export interface ToolField {
  fieldKey: string
  fieldName: string
  fieldType: "text" | "textarea" | "select" | "number"
  placeholder?: string | null
  options?: Array<ToolFieldOption | string> | null
  required: boolean
  sortOrder: number
}

/** GET /api/v1/tools/{toolCode} —— 包含字段配置 */
export interface ToolDetail {
  id: number
  toolCode: string
  toolName: string
  categoryId: number
  categoryName: string
  description?: string | null
  coverUrl?: string | null
  status: ToolBizStatus
  estimatedCreditCost: number
  /** 动态字段列表 */
  fields: ToolField[]
}

/* ========== 任务相关 ========== */

/** POST /api/v1/tasks */
export interface CreateTaskRequest {
  toolCode: string
  params: Record<string, unknown>
  clientRequestId?: string
}

export interface CreateTaskResponse {
  taskId: number
  taskNo: string
  status: TaskStatus
}

/** GET /api/v1/tasks/{taskId}/status —— 轮询用精简状态 */
export interface TaskStatusPayload {
  taskId: number
  taskNo: string
  toolCode?: string
  status: TaskStatus
  progress?: number
  progressMessage?: string
}

/** 任务结果 */
export interface TaskResult {
  resourceType: string
  contentText: string
}

/** GET /api/v1/tasks/{taskId} —— TaskDetail */
export interface TaskDetail {
  taskId: number
  taskNo: string
  status: TaskStatus
  progress?: number
  progressMessage?: string
  userId: number
  toolCode: string
  toolName: string
  params?: Record<string, unknown>
  result?: TaskResult | null
  createdAt: string
  finishedAt?: string | null
}

/** GET /api/v1/tasks 查询参数 */
export interface ListTasksQuery {
  pageNo?: number
  pageSize?: number
  status?: TaskStatus
  toolCode?: string
}

/* ========== 算力相关 ========== */

/** GET /api/v1/credits/account —— 契约 CreditAccount */
export interface CreditAccount {
  accountId: number
  userId: number
  balance: number
  frozen: number
  available: number
  totalGranted: number
  totalConsumed: number
  status: "ACTIVE"
}

/** 算力流水记录 */
export interface CreditLog {
  id: number
  userId: number
  taskId?: number | null
  agentRunId?: number | null
  logType: "FREEZE" | "DEDUCT" | "RELEASE" | "MANUAL_ADD" | "MANUAL_DEDUCT"
  amount: number
  frozenAmount: number
  balanceBefore: number
  balanceAfter: number
  frozenBefore: number
  frozenAfter: number
  operatorType: string
  operatorId?: number | null
  reason: string
  createdAt: string
}

/* ========== Agent ========== */

export type AgentRunStatus = "CREATED" | "RUNNING" | "WAITING_USER_CONFIRMATION" | "SUCCESS" | "FAILED" | "CANCELLED" | "TIMEOUT"
export type AgentMessageRole = "USER" | "ASSISTANT" | "SYSTEM"
export type AgentRunEventType =
  | "run.started"
  | "intent.detected"
  | "tool.selected"
  | "tool.confirmation_required"
  | "tool.started"
  | "tool.task_dispatched"
  | "tool.task_progress"
  | "tool.finished"
  | "subagent.started"
  | "subagent.completed"
  | "subagent.failed"
  | "workspace_file.created"
  | "workspace_file.updated"
  | "workspace_file.read"
  | "memory.context_injected"
  | "memory.candidate_created"
  | "message.delta"
  | "message.completed"
  | "run.completed"
  | "run.failed"
  | string

export interface AgentSession {
  id: number
  title: string
  status: string
  createdAt: string
  updatedAt: string
}

export interface AgentMessage {
  id: number
  sessionId: number
  role: AgentMessageRole
  contentText: string
  contentJson?: string | null
  runId?: number | null
  createdAt: string
}

export interface CreateAgentMessageResponse {
  sessionId: number
  messageId: number
  runId: number
  runStatus: AgentRunStatus
}

export interface AgentRun {
  id: number
  sessionId: number
  userId: number
  status: AgentRunStatus
  intent?: string | null
  consumedCredits: number
  estimatedCredits: number
  createdAt: string
  updatedAt: string
}

export interface AgentRunEvent {
  id: number
  runId: number
  eventType: AgentRunEventType
  eventText?: string | null
  eventJson?: string | null
  createdAt: string
}

export interface AgentFile {
  id: number
  sessionId: number
  originalFilename: string
  contentType?: string | null
  fileSize: number
  status: "PARSING" | "READY" | "FAILED"
  extractedText?: string | null
  errorMessage?: string | null
  createdAt: string
  updatedAt: string
}

export interface AgentToolPreference {
  id: number
  toolCode: string
  autoCallEnabled: boolean
  createdAt: string
  updatedAt: string
}

export interface AgentWorkspace {
  id: number
  name: string
  workspaceType: string
  role: string
  status: string
  createdAt: string
  updatedAt: string
}

export interface AgentWorkspaceMemoryItem {
  id: number
  workspaceId: number
  userId: number
  memoryType: string
  title: string
  content: string
  sourceRunId?: number | null
  status: string
  createdAt: string
  updatedAt: string
}

export interface CreateAgentWorkspaceMemoryRequest {
  memoryType: string
  title: string
  content: string
  sourceRunId?: number | null
}

export interface UpdateAgentWorkspaceMemoryRequest {
  memoryType: string
  title: string
  content: string
}
