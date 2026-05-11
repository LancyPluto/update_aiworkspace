/**
 * 与《00-统一接口数据库契约》对齐的类型与常量。
 * 响应壳：{ code, message, data, requestId? }
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
  | "SYSTEM_ERROR"

export interface ApiResponse<T> {
  code: ApiErrorCode
  message: string
  data: T | null
  requestId?: string
}

/** §4 统一任务状态（不使用 PENDING / RUNNING） */
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

/** GET /api/v1/users/me */
export interface UserProfile {
  id: number
  username: string
  phone?: string | null
  email?: string | null
  nickname?: string | null
  userType: UserType
  status: UserAccountStatus
}

export type ToolBizStatus = "DRAFT" | "ONLINE" | "OFFLINE"

/** GET /api/v1/tools、列表项 */
export interface ToolSummary {
  id: number
  toolCode: string
  toolName: string
  categoryId: number
  description?: string | null
  coverUrl?: string | null
  status: ToolBizStatus
  estimatedCreditCost: number
}

/** GET /api/v1/tools/{toolCode} —— 可在 ToolSummary 上扩展字段 */
export interface ToolDetail extends ToolSummary {
  /** 后端若返回 Schema/Prompt 元信息可再接字段 */
  fieldSchemaId?: number | null
  activePromptVersionId?: number | null
}

/** GET /api/v1/tool-categories */
export interface ToolCategory {
  id: number
  name: string
  sortOrder?: number
}

/** POST /api/v1/tasks */
export interface CreateTaskRequest {
  toolCode: string
  params: Record<string, unknown>
  idempotencyKey?: string
}

export interface CreateTaskResponse {
  taskId: number
  taskNo: string
}

/** GET /api/v1/tasks/{taskId}/status —— 轮询用精简载荷 */
export interface TaskStatusPayload {
  taskId: number
  taskNo: string
  status: TaskStatus
  progress?: number | null
  progressMessage?: string | null
}

/** GET /api/v1/tasks/{taskId} —— 对齐 ai_tasks 核心字段 */
export interface AiTask {
  id: number
  taskNo: string
  userId: number
  toolId: number
  fieldSchemaId?: number | null
  promptVersionId?: number | null
  status: TaskStatus
  progress?: number | null
  progressMessage?: string | null
  paramsJson?: string | null
  estimatedCreditCost?: number | null
  retryCount?: number | null
  maxRetryCount?: number | null
  errorCode?: string | null
  errorMessage?: string | null
  createdAt?: string
  queuedAt?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  updatedAt?: string | null
}

/** GET /api/v1/tasks 查询 */
export interface ListTasksQuery {
  page?: number
  pageSize?: number
  status?: TaskStatus
  toolCode?: string
}

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}

/** GET /api/v1/credits/account —— credit_accounts */
export interface CreditAccount {
  id: number
  userId: number
  balance: number
  frozen: number
  totalGranted: number
  totalConsumed: number
  status: "ACTIVE" | "DISABLED"
}

/** POST /api/v1/auth/login | register 等 —— 契约未写死字段，保留常用形态 */
export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  tokenType?: string
  expiresIn?: number
  user?: UserProfile
}

export interface RegisterRequest {
  username: string
  password: string
  email?: string
  phone?: string
}
