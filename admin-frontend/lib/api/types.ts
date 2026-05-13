export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  requestId: string | null
}

export interface PageResponse<T> {
  list: T[]
  total: number
  pageNo?: number
  pageSize?: number
  hasNext?: boolean
}

export interface AdminUser {
  id: number
  username: string
  nickname: string
  userType: 'ADMIN' | 'USER' | string
}

export interface LoginResponse {
  accessToken: string
  user: AdminUser
}

export interface ToolCategory {
  id: number
  categoryCode: string
  categoryName: string
  sortOrder?: number
  status?: string
}

export interface UpsertToolCategoryPayload {
  categoryCode: string
  categoryName: string
  sortOrder?: number
  status?: string
}

export interface ToolSummary {
  id: number
  toolCode: string
  toolName: string
  /** 后端可能为 null（未归类） */
  categoryId: number | null
  categoryName?: string | null
  description?: string | null
  coverUrl?: string | null
  status: 'DRAFT' | 'ONLINE' | 'OFFLINE' | string
  estimatedCreditCost: number
}

export interface UpsertToolPayload {
  toolCode?: string
  toolName: string
  categoryId: number | null
  description?: string
  coverUrl?: string
  estimatedCreditCost: number
}

export interface PromptRecord {
  id: number
  toolId: number
  promptCode: string
  promptName: string
  status: string
  activeVersionId?: number | null
}

export interface PromptVersionRecord {
  id: number
  promptId: number
  versionNo: string
  systemPrompt?: string | null
  userPromptTemplate: string
  outputFormat: string
  status: 'DRAFT' | 'ACTIVE' | 'INACTIVE' | string
  createdAt?: string | null
  publishedAt?: string | null
}

export interface CreatePromptPayload {
  promptCode: string
  promptName: string
}

export interface CreatePromptVersionPayload {
  versionNo: string
  systemPrompt?: string
  userPromptTemplate: string
  outputFormat?: string
}

export interface TaskResult {
  resourceType: string
  contentText: string
}

export interface TaskLog {
  id: number
  eventType: string
  fromStatus?: string | null
  toStatus?: string | null
  message?: string | null
  createdAt: string
}

export interface CreditLogItem {
  id: number
  userId?: number
  taskId?: number | null
  logType: string
  amount: number
  frozenAmount?: number
  balanceBefore: number
  balanceAfter: number
  frozenBefore?: number
  frozenAfter?: number
  operatorType?: string
  operatorId?: number | null
  reason?: string | null
  createdAt: string
}

/**
 * 与后端 TaskDetailResponse 一致（管理端任务列表项与详情主体）。
 */
export interface AdminTaskApiPayload {
  taskId: number
  taskNo: string
  userId: number
  userNickname?: string | null
  toolCode: string
  toolName: string
  status: string
  progress: number
  progressMessage?: string | null
  params?: unknown
  result?: TaskResult | null
  createdAt: string
  finishedAt?: string | null
}

/** @deprecated 请使用 AdminTaskApiPayload */
export type AdminTaskRow = AdminTaskApiPayload

export type AdminTaskDetail = AdminTaskApiPayload

/** 与后端 TaskStatusResponse 一致（重试、取消任务等） */
export interface TaskStatusPayload {
  taskId: number
  taskNo: string
  status: string
  progress: number
  progressMessage?: string | null
}

export interface AdminTaskQuery {
  status?: string
  toolCode?: string
  userId?: number
}

export interface DashboardChartPoint {
  name: string
  value: number
}

export interface DashboardOverview {
  taskTrend: DashboardChartPoint[]
  popularTools: DashboardChartPoint[]
  apiCreditConsumed: number
}

export interface CreditAccount {
  accountId: number
  userId: number
  balance: number
  frozen: number
  /** 后端 CreditAccountResponse.available */
  available?: number
  totalGranted: number
  totalConsumed: number
  status: string
}

/** 与后端 AdminUserResponse 一致 */
export interface AdminMember {
  id: number
  username: string
  phone?: string | null
  email?: string | null
  nickname: string
  userType: string
  status: string
  createdAt?: string | null
  updatedAt?: string | null
  creditAccount?: CreditAccount | null
}

export interface ManualAddCreditsPayload {
  amount: number
  reason?: string
}

/** 手动加减算力接口返回与 CreditAccountResponse 一致 */
export type ManualAddCreditsResult = CreditAccount

export interface UpdateUserStatusPayload {
  status: string
  reason?: string
}

export interface TestGenerateResult {
  output: string
}

export type AgentModelProvider = 'mock' | 'openai_compatible' | 'anthropic_compatible' | 'minimax'

export interface AgentModelConfig {
  id: number
  displayName?: string | null
  configCode?: string | null
  provider: AgentModelProvider | string
  modelName: string
  baseUrl?: string | null
  apiKeyMasked?: string | null
  minimaxGroupId?: string | null
  timeoutSeconds: number
  enabled: boolean
  isDefault?: boolean | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface ToolField {
  fieldKey: string
  fieldName: string
  fieldType: string
  placeholder?: string | null
  options?: unknown
  optionsJson?: string | null
  required?: boolean
  sortOrder?: number
}

export interface ToolFieldPayload {
  fieldKey: string
  fieldName: string
  fieldType: string
  placeholder?: string
  optionsJson?: string
  validationJson?: string
  required?: boolean
  sortOrder?: number
}

export interface FieldSchemaAdmin {
  id: number
  schemaVersion: string
  status: string
  items: ToolField[]
}

export interface UpsertFieldSchemaPayload {
  schemaVersion: string
  items: ToolFieldPayload[]
}

export interface AgentModelConfigPayload {
  displayName?: string
  configCode?: string
  provider: AgentModelProvider | string
  modelName: string
  baseUrl?: string
  apiKey?: string
  minimaxGroupId?: string
  timeoutSeconds?: number
  enabled?: boolean
  isDefault?: boolean
}

export interface AgentModelConfigTestResult {
  success: boolean
  provider: string
  modelName: string
  latencyMs: number
  message: string
  sample: string
}

export interface AdminAgentRunListItem {
  id: number
  sessionId: number
  userId: number
  status: string
  intent?: string | null
  modelProviderCode?: string | null
  modelName?: string | null
  estimatedCredits?: number | null
  consumedCredits?: number | null
  errorCode?: string | null
  errorMessage?: string | null
  eventCount?: number | null
  toolCallCount?: number | null
  startedAt?: string | null
  finishedAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AgentRunEvent {
  id: number
  runId: number
  eventType: string
  eventText?: string | null
  eventJson?: string | null
  createdAt?: string | null
}

export interface AgentToolCall {
  id: number
  runId: number
  toolCode: string
  toolName?: string | null
  status: string
  argumentsJson?: string | null
  resultJson?: string | null
  errorMessage?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AgentRun {
  id: number
  sessionId: number
  userId: number
  status: string
  intent?: string | null
  modelProviderCode?: string | null
  modelName?: string | null
  estimatedCredits?: number | null
  consumedCredits?: number | null
  errorCode?: string | null
  errorMessage?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AdminAgentRunDetail {
  run: AgentRun
  events: AgentRunEvent[]
  toolCalls: AgentToolCall[]
}

export interface AdminAgentRunStats {
  totalRuns: number
  activeRuns: number
  successRuns: number
  failedRuns: number
  cancelledRuns: number
  toolCalls: number
  totalConsumedCredits: number
}

export interface AdminAgentRunQuery {
  status?: string
  userId?: number
  pageNo?: number
  pageSize?: number
}
