export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  traceId?: string | null
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
  avatarUrl?: string | null
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
  toolType?: string | null
  inputModality?: string | null
  outputModality?: string | null
  toolKind?: "text" | "image" | "video" | "digitalHuman" | "audio" | "agent" | "other" | string | null
  configNote?: string | null
  /** 任务路由用，未设置时由 toolType 推导 */
  executionHandler?: string | null
  status: 'DRAFT' | 'ONLINE' | 'OFFLINE' | string
  estimatedCreditCost: number
  modelConfigId?: number | null
  modelConfigName?: string | null
  modelName?: string | null
}

export interface ToolDetail extends ToolSummary {
  fields: ToolField[]
  integration?: unknown
}

export interface UpsertToolPayload {
  toolCode?: string
  toolName: string
  categoryId: number | null
  description?: string
  coverUrl?: string
  toolType?: string
  inputModality?: string
  outputModality?: string
  configNote?: string
  estimatedCreditCost: number
  modelConfigId?: number | null
  executionHandler?: string
  templateCode?: string
}

export interface ToolCoverUploadResult {
  url: string
  filename: string
  contentType: string
  fileSize: number
}

export interface ToolPromptDraftFieldPayload {
  fieldKey: string
  fieldName: string
  fieldType: string
  placeholder?: string
  required?: boolean
}

export interface ToolPromptDraftPayload {
  modelConfigId?: number
  toolName: string
  description?: string
  toolKind?: string
  coverUrl?: string
  userInputs?: ToolPromptDraftFieldPayload[]
}

export interface ToolPromptDraftResult {
  systemPrompt: string
  toolPrompt: string
  modelConfigId?: number | null
  modelName?: string | null
  warning?: string | null
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
  toolType?: string | null
  inputModality?: string | null
  outputModality?: string | null
  status: string
  progress: number
  progressMessage?: string | null
  errorCode?: string | null
  errorMessage?: string | null
  params?: unknown
  result?: TaskResult | null
  agentSource?: {
    runId: number
    toolCallId: number
    toolCode?: string | null
  } | null
  consumedCredits?: number | null
  createdAt: string
  queuedAt?: string | null
  startedAt?: string | null
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
  taskId?: number
}

export interface AdminCommunityPost {
  id: number
  userId: number
  authorNickname?: string | null
  authorAvatarUrl?: string | null
  taskId: number
  modality: string
  coverUrl?: string | null
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
  createdAt: string
  updatedAt?: string | null
}

export interface AdminCommunityMetricPoint {
  name: string
  value: number
}

export interface AdminCommunityStats {
  postCount: number
  pendingCount: number
  hiddenCount: number
  impressionCount: number
  detailViewCount: number
  sameStyleClickCount: number
  taskCreatedCount: number
  creditSpent: number
  topTools: AdminCommunityMetricPoint[]
  topTopics: AdminCommunityMetricPoint[]
  topCreators: AdminCommunityMetricPoint[]
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
  avatarUrl?: string | null
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

export interface ModelProviderDescriptor {
  code: string
  label: string
  capabilities: string[]
  defaultBaseUrl: string
  defaultModel: string
  billingDefault: string
  providerProtocol?: string | null
  vendorKind?: string | null
  upstreamVendor?: string | null
  testStrategy: string
  workerReady: boolean
  adapterInstalled?: boolean
  adapterKey?: string | null
  metadataVersion?: string | null
  authSchemaJson?: string | null
  modelParamSchemaJson?: string | null
  description: string
}

export interface AgentModelConfig {
  id: number
  vendorAccountId?: number | null
  vendorAccountName?: string | null
  displayName?: string | null
  configCode?: string | null
  provider: string
  modelName: string
  baseUrl?: string | null
  apiKeyMasked?: string | null
  extraAuthJsonMasked?: string | null
  minimaxGroupId?: string | null
  consoleUrl?: string | null
  balanceUrl?: string | null
  docsUrl?: string | null
  timeoutSeconds: number
  connectTimeoutSeconds?: number | null
  readTimeoutSeconds?: number | null
  inputTokenPricePer1k?: number | null
  outputTokenPricePer1k?: number | null
  inputTokenPricePer1m?: number | null
  outputTokenPricePer1m?: number | null
  billingUnit?: 'TOKEN_PER_M' | 'PER_CALL' | 'IMAGE_TOKEN' | 'PER_SECOND' | string | null
  unitPrice?: number | null
  enabled: boolean
  agentEnabled?: boolean | null
  isDefault?: boolean | null
  channelCode?: string | null
  channelLabel?: string | null
  channelIconAsset?: string | null
  /** 该凭证可用于的执行能力（与 executionHandler / toolType 对齐） */
  capabilities?: string[] | null
  providerMetadataVersion?: string | null
  pricingPreview?: string | null
  effectiveCredentialsStatus?: string | null
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
  executionRequired?: boolean
  userRequired?: boolean
  defaultValue?: string | null
  agentFillStrategy?: 'infer_from_user' | 'default' | 'ask_user' | 'derive' | 'none' | string
  riskLevel?: 'LOW' | 'MEDIUM' | 'HIGH' | string
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
  executionRequired?: boolean
  userRequired?: boolean
  defaultValue?: string
  agentFillStrategy?: 'infer_from_user' | 'default' | 'ask_user' | 'derive' | 'none' | string
  riskLevel?: 'LOW' | 'MEDIUM' | 'HIGH' | string
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

export interface ModelVendorAccount {
  id: number
  vendorCode: string
  vendorLabel: string
  accountName: string
  baseUrl?: string | null
  apiKey?: string | null
  apiKeyMasked?: string | null
  extraAuthJson?: string | null
  extraAuthJsonMasked?: string | null
  consoleUrl?: string | null
  balanceUrl?: string | null
  balanceQueryMode: string
  balanceAmount?: number | null
  balanceCurrency?: string | null
  balanceStatus: string
  balanceLowThreshold?: number | null
  balanceUpdatedAt?: string | null
  balanceErrorMessage?: string | null
  healthStatus: string
  enabled: boolean
  modelCount: number
  createdAt?: string | null
  updatedAt?: string | null
}

export interface ModelVendorAccountTestResult {
  success: boolean
  message: string
  latencyMs?: number | null
  provider?: string | null
  modelName?: string | null
  account: ModelVendorAccount
}

export interface ModelVendorAccountDiscoverModelsResult {
  importedCount?: number | null
  updatedCount?: number | null
  skippedCount?: number | null
  discoveredCount?: number | null
  createdCount?: number | null
  totalCount?: number | null
  modelCount?: number | null
  imported?: number | null
  updated?: number | null
  skipped?: number | null
  discovered?: number | null
  created?: number | null
  message?: string | null
  warnings?: string[] | null
  [key: string]: unknown
}

export interface ModelVendorAccountPayload {
  vendorCode: string
  accountName: string
  baseUrl?: string
  apiKey?: string
  clearApiKey?: boolean
  extraAuthJson?: string
  clearExtraAuthJson?: boolean
  consoleUrl?: string
  balanceUrl?: string
  balanceQueryMode?: string
  balanceAmount?: number
  balanceCurrency?: string
  balanceLowThreshold?: number
  enabled?: boolean
}

export interface ModelVendorPayload {
  vendorCode: string
  vendorLabel: string
  iconAsset: string
  sortOrder?: number
  enabled: boolean
}

export interface ModelVendor {
  vendorCode: string
  vendorLabel: string
  iconAsset: string
  sortOrder: number
  enabled: boolean
}

export interface UnifiedApiModelItem {
  id: number
  vendorAccountId?: number | null
  vendorAccountName?: string | null
  displayName?: string | null
  configCode?: string | null
  provider: string
  modelName: string
  baseUrl?: string | null
  minimaxGroupId?: string | null
  consoleUrl?: string | null
  balanceUrl?: string | null
  docsUrl?: string | null
  timeoutSeconds?: number | null
  connectTimeoutSeconds?: number | null
  readTimeoutSeconds?: number | null
  capabilities?: string[] | null
  billingUnit?: string | null
  unitPrice?: number | null
  inputTokenPricePer1m?: number | null
  outputTokenPricePer1m?: number | null
  enabled: boolean
  agentEnabled?: boolean | null
  isDefault?: boolean | null
  healthStatus: string
}

export interface UnifiedApiVendorGroup {
  vendorCode: string
  label: string
  iconAsset: string
  accounts: ModelVendorAccount[]
  models: UnifiedApiModelItem[]
}

export interface UnifiedApiUnconfiguredVendor {
  vendorCode: string
  label: string
  iconAsset: string
  supportedProviders: string[]
}

export interface UnifiedApiSummary {
  vendorCount: number
  accountCount: number
  modelCount: number
  enabledModelCount: number
  lowBalanceCount: number
  unhealthyAccountCount: number
}

export interface UnifiedApiOverview {
  summary: UnifiedApiSummary
  vendors: UnifiedApiVendorGroup[]
  unconfiguredVendors: UnifiedApiUnconfiguredVendor[]
}

export interface AgentModelConfigPayload {
  vendorAccountId?: number
  displayName?: string
  configCode?: string
  provider: string
  modelName: string
  baseUrl?: string
  apiKey?: string
  clearApiKey?: boolean
  extraAuthJson?: string
  minimaxGroupId?: string
  consoleUrl?: string
  balanceUrl?: string
  docsUrl?: string
  timeoutSeconds?: number
  connectTimeoutSeconds?: number
  readTimeoutSeconds?: number
  inputTokenPricePer1k?: number
  outputTokenPricePer1k?: number
  inputTokenPricePer1m?: number
  outputTokenPricePer1m?: number
  billingUnit?: 'TOKEN_PER_M' | 'PER_CALL' | 'IMAGE_TOKEN' | 'PER_SECOND' | string
  unitPrice?: number
  enabled?: boolean
  agentEnabled?: boolean
  isDefault?: boolean
  capabilities?: string[]
}

export interface AgentModelConfigTestResult {
  success: boolean
  provider: string
  modelName: string
  latencyMs: number
  message: string
  sample: string
}

export interface ConfigBundleVendorAccount {
  vendorCode: string
  accountName: string
  accountRef?: string
  baseUrl?: string
  apiKey?: string
  extraAuthJson?: string
  secretsRedacted?: boolean
  consoleUrl?: string
  balanceUrl?: string
  balanceQueryMode?: string
  balanceAmount?: number
  balanceCurrency?: string
  balanceLowThreshold?: number
  enabled?: boolean
}

export interface ConfigBundle {
  format: "ai-tool-market-config-bundle" | string
  version: number
  exportedAt?: string
  exportedBy?: string | null
  secretsRedacted?: boolean
  settings?: Record<string, string>
  vendorAccounts?: ConfigBundleVendorAccount[]
  modelConfigs?: Array<Record<string, unknown>>
  categories?: Array<Record<string, unknown>>
  tools?: Array<Record<string, unknown>>
}

export interface ConfigBundleImportResult {
  settings: number
  vendorAccounts: number
  modelConfigs: number
  categories: number
  tools: number
  fields: number
  prompts: number
  promptVersions: number
  workflows: number
  warnings: string[]
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
  taskId?: number | null
  status: string
  argumentsJson?: string | null
  resultJson?: string | null
  errorCode?: string | null
  errorMessage?: string | null
  startedAt?: string | null
  finishedAt?: string | null
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

export interface AdminAgentFileSnapshot {
  id: number
  originalFilename?: string | null
  contentType?: string | null
  status: string
  downloadUrl?: string | null
}

export interface AdminAgentRunContextSnapshot {
  sessionId: number
  workspaceId: number
  userId: number
  userMessage: string
  agentFiles: AdminAgentFileSnapshot[]
}

export interface AdminAgentRunDetail {
  run: AgentRun
  events: AgentRunEvent[]
  toolCalls: AgentToolCall[]
  contextSnapshot?: AdminAgentRunContextSnapshot | null
  eventTruncated?: boolean | null
  totalEventCount?: number | null
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
  taskId?: number
  pageNo?: number
  pageSize?: number
}

export interface BillingModelCostPoint {
  provider: string
  modelName: string
  totalTokens: number
  costAmount: number
  chargedCredits: number
}

export interface BillingUserCostPoint {
  userId: number
  totalTokens: number
  costAmount: number
  chargedCredits: number
  usageCount: number
}

export interface BillingModalityCostPoint {
  modality: string
  totalTokens: number
  costAmount: number
  chargedCredits: number
  usageCount: number
}

export interface BillingDailyCostPoint {
  usageDate: string
  totalTokens: number
  costAmount: number
  chargedCredits: number
  usageCount: number
}

export interface BillingOverview {
  todayPromptTokens: number
  todayCompletionTokens: number
  todayTotalTokens: number
  todayCostAmount: number
  todayChargedCredits: number
  todayUsageCount: number
  modelCosts: BillingModelCostPoint[]
  userCosts: BillingUserCostPoint[]
  modalityCosts: BillingModalityCostPoint[]
  dailyCosts: BillingDailyCostPoint[]
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
  inputTokenPricePer1k: number
  outputTokenPricePer1k: number
  inputTokenPricePer1m?: number
  outputTokenPricePer1m?: number
  billingUnit?: string
  billableUnits?: number
  unitPrice?: number
  costAmount: number
  chargedCredits: number
  createdAt: string
}

// ---- Workflow types ----

export interface WorkflowNodeData extends Record<string, unknown> {
  title: string
  subtitle: string
  detail: string
  kind: string
  iconName: string
  color?: string
  config?: AgentModelConfig | null
  parameters?: Record<string, unknown>
}

export interface WorkflowNode {
  id: string
  type: string
  position: { x: number; y: number }
  data: WorkflowNodeData
  width?: number
  height?: number
  selected?: boolean
}

export interface WorkflowEdge {
  id: string
  source: string
  target: string
  sourceHandle?: string
  targetHandle?: string
  type?: string
  markerEnd?: { type: string; color?: string }
  style?: Record<string, unknown>
}

export interface WorkflowGroup {
  id: string
  title: string
  bounding: { x: number; y: number; width: number; height: number }
  color?: string
  fontSize?: number
  locked?: boolean
}

export interface WorkflowResponse {
  id: number
  toolId: number
  workflowName: string
  nodesJson: string
  edgesJson: string
  groupsJson: string | null
  configJson: string | null
  version: number
  status: string
  createdBy?: number
  updatedBy?: number
  createdAt: string
  updatedAt: string
}

export interface WorkflowVersionItem {
  id: number
  version: number
  snapshotLabel: string | null
  createdBy: number
  createdAt: string
}

export interface UpsertWorkflowPayload {
  workflowName: string
  nodesJson: string
  edgesJson: string
  groupsJson?: string
  configJson?: string
  status?: string
}
