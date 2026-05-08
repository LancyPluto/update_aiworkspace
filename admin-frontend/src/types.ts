export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  requestId: string | null
}

export interface PageResponse<T> {
  list: T[]
  total: number
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

export interface ToolSummary {
  id: number
  toolCode: string
  toolName: string
  categoryId: number
  categoryName?: string
  description?: string
  coverUrl?: string
  status: 'DRAFT' | 'ONLINE' | 'OFFLINE' | string
  estimatedCreditCost: number
}

export interface ToolCategory {
  id: number
  categoryCode: string
  categoryName: string
  sortOrder?: number
}

export interface UpsertToolPayload {
  toolCode?: string
  toolName: string
  categoryId: number | null
  description?: string
  coverUrl?: string
  estimatedCreditCost: number
}

export interface FieldItem {
  fieldKey: string
  fieldName: string
  fieldType: 'text' | 'textarea' | 'select' | 'number'
  placeholder?: string
  required: boolean
  optionsJson?: string
  sortOrder: number
}

export interface FieldSchema {
  id: number
  schemaVersion: string
  status: string
  items: FieldItem[]
}

export interface PromptDraft {
  promptName: string
  promptCode: string
  content: string
}

export interface AdminTask {
  taskId: number
  taskNo: string
  toolCode: string
  toolName: string
  status: string
  progress: number
  progressMessage?: string
  params?: Record<string, unknown>
  result?: TaskResult | null
  createdAt: string
  finishedAt?: string
}

export interface TaskResult {
  resourceType: string
  contentText: string
}

export interface AdminTaskDetail extends AdminTask {}

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
