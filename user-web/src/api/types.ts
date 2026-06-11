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
  | "MODEL_RISK_CONTROL_REJECTED"
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
  | "PPT_PROJECT_NOT_FOUND"
  | "PPT_STEP_DISABLED"
  | "PPT_ENGINE_ERROR"
  | "PPT_TASK_FAILED"
  | "PPT_EXPORT_FAILED"
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
  | "AWAITING_USER"
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

export interface UserUploadAsset {
  id: number
  fileId: string
  kind: "image" | "video" | "audio" | "file" | string
  name: string
  contentType?: string | null
  size?: number | null
  url: string
  createdAt?: string | null
  updatedAt?: string | null
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

export type SmsCodeScene = "REGISTER" | "LOGIN" | "LOGIN_OR_REGISTER" | "RESET_PASSWORD"

export interface SmsCodeRequest {
  phone: string
  scene: SmsCodeScene
  captchaVerifyParam?: string | null
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
  password?: string
}

export interface ResetPasswordRequest {
  phone: string
  code: string
  password: string
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
  avatarUrl?: string | null
  bio?: string | null
  autoPublishAssets?: boolean
  promptPublicByDefault?: boolean
  userType: UserType
  phone?: string | null
  email?: string | null
  status: UserAccountStatus
}

export interface UpdateUserProfileRequest {
  nickname?: string
  avatarUrl?: string | null
}

export interface CommunitySettingsRequest {
  bio?: string | null
  autoPublishAssets?: boolean
  promptPublicByDefault?: boolean
}

export interface UserAvatarUploadResponse {
  avatarUrl: string
  user: UserProfile
}

export interface PublicUserProfile {
  id: number
  username: string
  nickname?: string | null
  avatarUrl?: string | null
  bio?: string | null
  postCount: number
  likeCount: number
  favoriteCount: number
  sameStyleCount?: number
  featuredCount?: number
}

export interface CommunityPost {
  id: number
  userId: number
  authorNickname?: string | null
  authorAvatarUrl?: string | null
  taskId: number
  modality: string
  coverUrl?: string | null
  mediaUrl?: string | null
  mediaUrls?: string[]
  title: string
  description?: string | null
  promptVisible: boolean
  prompt?: string | null
  promptPreview?: string | null
  toolCode?: string | null
  toolName?: string | null
  status: string
  featured?: boolean
  pinned?: boolean
  topic?: string | null
  tags?: string[]
  sameStyleCount?: number
  auditStatus?: string | null
  auditReason?: string | null
  viewCount: number
  detailClickCount?: number
  shareCount?: number
  qualityScore?: number
  likeCount: number
  favoriteCount: number
  liked?: boolean
  favorited?: boolean
  createdAt: string
  updatedAt?: string | null
}

export interface CommunityCreator {
  profile: PublicUserProfile
  sameStyleCount: number
  featuredCount: number
  featuredPosts: CommunityPost[]
  recentPosts: CommunityPost[]
}

export interface CommunityCollection {
  id: number
  name: string
  defaultCollection: boolean
  itemCount: number
  createdAt: string
  updatedAt?: string | null
  items: CommunityPost[]
}

export interface CommunityTopic {
  name: string
  postCount: number
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
  toolType?: string | null
  inputModality?: string | null
  outputModality?: string | null
  toolKind?: "text" | "image" | "video" | "digitalHuman" | "audio" | "agent" | "other" | string | null
  configNote?: string | null
  status: ToolBizStatus
  estimatedCreditCost: number
  modelConfigId?: number | null
  modelConfigName?: string | null
  modelName?: string | null
  executionHandler?: string | null
  frontendStyle?: ToolFrontendStyle | null
}

/** 动态字段选项 */
export interface ToolFieldOption {
  label: string
  value: string
  promptPrefix?: string
}

/** 动态字段定义 */
export interface ToolField {
  fieldKey: string
  fieldName: string
  fieldType:
    | "text"
    | "textarea"
    | "select"
    | "number"
    | "radio"
    | "aspect_ratio"
    | "checkbox"
    | "slider"
    | "image"
    | "multi_image"
    | "file"
    | "image_upload"
    | "video_upload"
    | "audio_upload"
  placeholder?: string | null
  options?: Array<ToolFieldOption | string> | null
  optionsJson?: string | null
  required: boolean
  executionRequired?: boolean
  userRequired?: boolean
  defaultValue?: string | null
  agentFillStrategy?: string | null
  riskLevel?: string | null
  sortOrder: number
}

export interface ToolFrontendStyle {
  primaryColor?: string | null
  welcomeMessage?: string | null
  mediaDisplayMode?: "icon" | "effect" | "comparison" | string | null
  modelIconUrl?: string | null
  comparisonOriginalUrl?: string | null
  comparisonEffectUrl?: string | null
  heroTitle?: string | null
  heroSubtitle?: string | null
  demoThumbnails?: string[] | null
  useCases?: string[] | null
  steps?: string[] | null
  recommendedToolCodes?: string[] | null
  beforeVideoUrl?: string | null
  afterVideoUrl?: string | null
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
  toolType?: string | null
  inputModality?: string | null
  outputModality?: string | null
  toolKind?: "text" | "image" | "video" | "digitalHuman" | "audio" | "agent" | "other" | string | null
  configNote?: string | null
  status: ToolBizStatus
  estimatedCreditCost: number
  modelConfigId?: number | null
  modelConfigName?: string | null
  modelName?: string | null
  executionHandler?: string | null
  frontendStyle?: ToolFrontendStyle | null
  /** 动态字段列表 */
  fields: ToolField[]
  /** 平台化集成（PPT 工作台等） */
  integration?: import("./pptApi").ToolIntegrationView | null
  /** 兼容字段，优先读 integration.extension */
  workflow?: import("./pptApi").PptWorkflow | null
}

/* ========== 模型选项 ========== */

export interface ImageSizeOption {
  label: string
  value: string
}

export interface ImageGenerationParameters {
  sizes?: ImageSizeOption[] | null
  defaultSize?: string | null
  counts?: number[] | null
  defaultCount?: number | null
  qualities?: ImageSizeOption[] | null
  defaultQuality?: string | null
}

export interface ModelOptionItem {
  id?: number | null
  modelConfigId?: number | null
  configCode?: string | null
  displayName?: string | null
  name?: string | null
  modelConfigName?: string | null
  modelName?: string | null
  description?: string | null
  toolCode?: string | null
  toolName?: string | null
  provider?: string | null
  providerName?: string | null
  vendorCode?: string | null
  vendorName?: string | null
  iconUrl?: string | null
  modelIconUrl?: string | null
  estimatedCreditCost?: number | null
  badges?: string[] | null
  capabilities?: string[] | null
  imageParameters?: ImageGenerationParameters | null
  isDefault?: boolean | null
  enabled?: boolean | null
}

export interface ModelOptionGroup {
  vendorCode?: string | null
  vendorName?: string | null
  provider?: string | null
  providerName?: string | null
  iconUrl?: string | null
  sortOrder?: number | null
  models: ModelOptionItem[]
}

export interface ModelOptionsResponse {
  mode?: string | null
  groups: ModelOptionGroup[]
}

/* ========== 任务相关 ========== */

/** POST /api/v1/tasks */
export interface CreateTaskRequest {
  toolCode: string
  params: Record<string, unknown>
  modelConfigId?: number | null
  clientRequestId?: string
  sourcePostId?: number
}

export interface CreateTaskResponse {
  taskId: number
  taskNo: string
  status: TaskStatus
}

export interface RegenerateTaskRequest {
  params: Record<string, unknown>
  clientRequestId?: string
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
  errorCode?: string | null
  errorMessage?: string | null
  userId: number
  toolCode: string
  toolName: string
  modelConfigId?: number | null
  modelConfigName?: string | null
  modelName?: string | null
  toolType?: string
  inputModality?: string
  outputModality?: string
  params?: Record<string, unknown>
  result?: TaskResult | null
  communityPostId?: number | null
  communityPromptVisible?: boolean | null
  createdAt: string
  queuedAt?: string | null
  startedAt?: string | null
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
  logType: "FREEZE" | "DEDUCT" | "RELEASE" | "RECHARGE" | "MANUAL_ADD" | "MANUAL_DEDUCT"
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

export interface BillingUsageLog {
  id: number
  sourceType: string
  sourceId: number
  taskNo?: string | null
  inputModality?: string | null
  outputModality?: string | null
  userId: number
  modelConfigId?: number | null
  provider?: string | null
  modelName?: string | null
  promptTokens: number
  completionTokens: number
  totalTokens: number
  billingUnit?: string | null
  billableUnits?: number | null
  unitPrice?: number | null
  costAmount: number
  chargedCredits: number
  createdAt: string
}

export interface CreditInsufficientDetail {
  availableCredits: number
  requiredCredits: number
  toolCode?: string | null
}

export interface RechargePackage {
  id: number
  packageCode: string
  packageName: string
  credits: number
  priceAmount: number
  currency: string
  validityDays: number
  benefits: string[]
  recommended: boolean
}

export type RechargeOrderStatus = "WAITING_PAYMENT" | "PAID" | "CREDITED" | "CLOSED" | "FAILED"

export interface RechargeOrder {
  id: number
  orderNo: string
  packageId: number
  credits: number
  priceAmount: number
  currency: string
  paymentChannel: string
  status: RechargeOrderStatus
  statusReason?: string | null
  payUrl?: string | null
  qrCodeUrl?: string | null
  paidAt?: string | null
  creditedAt?: string | null
  closedAt?: string | null
  expiresAt: string
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
  | "memory.context_frozen"
  | "memory.candidate_created"
  | "memory.saved"
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
  status?: "ACTIVE" | "SUPERSEDED" | string
  editedAt?: string | null
  createdAt: string
}

export interface AgentUrlAttachment {
  id?: string | number
  sessionId?: number | null
  name: string
  contentType?: string | null
  size?: number | null
  url: string
  refLabel?: string | null
  source?: "url" | "chat_reference" | "agent_file" | string
}

export interface AgentToolPickerItem {
  toolCode: string
  toolName: string
  description?: string | null
  outputModality?: string | null
  coverUrl?: string | null
  estimatedCreditCost?: number | null
}

export interface CreateAgentMessageResponse {
  sessionId: number
  messageId: number
  runId: number
  runStatus: AgentRunStatus
}

export interface AgentModelConfig {
  id: number
  displayName?: string | null
  configCode?: string | null
  provider: string
  modelName: string
  baseUrl?: string | null
  apiKeyMasked?: string | null
  extraAuthJsonMasked?: string | null
  enabled: boolean
  agentEnabled?: boolean | null
  isDefault?: boolean | null
  capabilities?: string[] | null
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
  downloadUrl?: string | null
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
