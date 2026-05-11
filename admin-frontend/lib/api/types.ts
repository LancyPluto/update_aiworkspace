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
  categoryId: number
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

export interface AdminTaskRow {
  taskId: number
  taskNo: string
  userId?: number
  userNickname?: string | null
  toolCode: string
  toolName: string
  status: string
  progress: number
  progressMessage?: string | null
  consumedCredits?: number
  errorCode?: string | null
  errorMessage?: string | null
  createdAt: string
  finishedAt?: string | null
}

export interface AdminTaskDetail extends AdminTaskRow {
  params?: Record<string, unknown> | null
  result?: TaskResult | null
  logs?: TaskLog[]
  creditLogs?: CreditLogItem[]
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
  totalGranted: number
  totalConsumed: number
  status: string
}

export interface AdminMember {
  id: number
  username: string
  nickname: string
  userType: string
  status: string
  credits?: number
  createdAt?: string
}

export interface ManualAddCreditsPayload {
  amount: number
  reason?: string
}

export interface ManualAddCreditsResult {
  accountId?: number
  userId: number
  amount?: number
  balance?: number
  frozen?: number
  totalGranted?: number
  totalConsumed?: number
  balanceBefore?: number
  balanceAfter?: number
  reason?: string | null
  createdAt?: string
}

export interface UpdateUserStatusPayload {
  status: string
  reason?: string
}

export interface TestGenerateResult {
  output: string
}
