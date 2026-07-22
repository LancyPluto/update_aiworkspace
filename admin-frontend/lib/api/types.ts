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
  /** 工具绑定模型必须同时具备的全部能力。 */
  requiredModelCapabilities?: string[] | null
  status: 'DRAFT' | 'ONLINE' | 'OFFLINE' | string
  /** 工作流工具运行方式与对外可用性，由发布接口原子更新。 */
  executionMode?: 'DIRECT' | 'WORKFLOW' | string | null
  billingMode?: string | null
  agentSurfaceEnabled?: boolean | null
  workflowExecutionEnabled?: boolean | null
  workflowConfigured?: boolean | null
  publishedWorkflowVersionId?: number | null
  workflowUsable?: boolean | null
  /** 兼容后端过渡期的同义字段。 */
  workflowAvailable?: boolean | null
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
  requiredModelCapabilities?: string[]
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
  reportPendingCount: number
  impressionCount: number
  detailViewCount: number
  sameStyleClickCount: number
  taskCreatedCount: number
  creditSpent: number
  topTools: AdminCommunityMetricPoint[]
  topTopics: AdminCommunityMetricPoint[]
  topCreators: AdminCommunityMetricPoint[]
}

export interface AdminCommunityReport {
  id: number
  postId: number
  postTitle?: string | null
  postCoverUrl?: string | null
  postStatus?: string | null
  reporterUserId: number
  reason?: string | null
  status: string
  adminNote?: string | null
  reviewedAt?: string | null
  createdAt?: string | null
}

export interface DashboardChartPoint {
  name: string
  value: number
}

export interface DashboardBusinessTrendPoint {
  name: string
  rechargeRevenueAmount: number
  usageRevenueAmount: number
  vendorCostAmount: number
  grossProfitAmount: number
  taskTotal: number
}

export interface DashboardToolContributionPoint {
  toolName: string
  taskTotal: number
  successTaskTotal: number
  failedTaskTotal: number
  usageRevenueAmount: number
  vendorCostAmount: number
  grossProfitAmount: number
  successRate: number
}

export interface DashboardOverview {
  rangeStartDate: string
  rangeEndDate: string
  taskTrend: DashboardChartPoint[]
  popularTools: DashboardChartPoint[]
  apiCreditConsumed: number
  rechargeRevenueAmount: number
  usageRevenueAmount: number
  vendorCostAmount: number
  grossProfitAmount: number
  grossMarginRate: number
  taskTotal: number
  successTaskTotal: number
  failedTaskTotal: number
  processingTaskTotal: number
  successRate: number
  newUserCount: number
  totalUserCount: number
  onlineToolCount: number
  draftToolCount: number
  businessTrend: DashboardBusinessTrendPoint[]
  toolContributions: DashboardToolContributionPoint[]
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

export interface ManualCreditAdjustmentPayload {
  amount: number
  reason?: string
}

export interface ManualAddCreditsPayload extends ManualCreditAdjustmentPayload {
  operationId: string
}

export interface AdminIssuedGiftCard {
  id: number
  cardCode: string
  packageName?: string | null
  credits: number
  status: string
  cardTheme?: string | null
  cardType?: string | null
  requiredMemberTier?: string | null
  createdAt: string
  giftedFromUserId?: number | null
  giftedAt?: string | null
  redeemedAt?: string | null
}

export interface ManualAddCreditsResult {
  userId: number
  operationId: string
  operatorId: number
  amount: number
  balanceBefore: number
  balanceAfter: number
  reason: string
  giftCard: AdminIssuedGiftCard
  createdAt: string
}

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
  routingPoolId?: number | null
  routingPoolName?: string | null
  displayName?: string | null
  configCode?: string | null
  provider: string
  modelName: string
  baseUrl?: string | null
  endpointPath?: string | null
  apiKeyMasked?: string | null
  extraAuthJsonMasked?: string | null
  executionTask?: string | null
  executionOptionsJsonMasked?: string | null
  routePreview?: ModelRoutePreview | null
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
  billingUnit?: 'TOKEN_PER_M' | 'PER_CALL' | 'IMAGE_TOKEN' | 'PER_SECOND' | 'PER_CHARACTER' | string | null
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
  proxyMode?: string | null
  proxyUrl?: string | null
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

export interface AgentSkillBundle {
  id?: number | null
  skillCode: string
  displayName: string
  description: string
  toolCodes: string[]
  sopRules: string
  whenToUse?: string | null
  whenNotToUse?: string | null
  fieldPolicy?: unknown
  examples?: unknown
  status: string
  version?: number | null
  publishedAt?: string | null
  updatedAt?: string | null
}

export interface AgentSkillBundlePayload {
  displayName: string
  description: string
  toolCodes: string[]
  sopRules: string
  whenToUse?: string
  whenNotToUse?: string
  fieldPolicy?: unknown
  examples?: unknown
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
  endpointPath?: string | null
  apiKey?: string | null
  apiKeyMasked?: string | null
  extraAuthJson?: string | null
  extraAuthJsonMasked?: string | null
  consoleUrl?: string | null
  balanceUrl?: string | null
  consoleCookieMasked?: string | null
  consoleCookieStatus?: string | null
  balanceQueryMode: string
  balanceAmount?: number | null
  balanceCurrency?: string | null
  balanceStatus: string
  balanceLowThreshold?: number | null
  balanceUpdatedAt?: string | null
  balanceErrorMessage?: string | null
  healthStatus: string
  healthMessage?: string | null
  healthCheckedAt?: string | null
  enabled: boolean
  modelCount: number
  loadBalanceEnabled?: boolean | null
  loadBalanceWeight?: number | null
  routingPoolId?: number | null
  routingPoolName?: string | null
  inFlightCount?: number | null
  circuitState?: string | null
  circuitOpenUntil?: string | null
  routingExclusionReason?: string | null
  proxyMode?: string | null
  proxyUrl?: string | null
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
  consoleCookie?: string
  clearConsoleCookie?: boolean
  balanceQueryMode?: string
  balanceAmount?: number
  balanceCurrency?: string
  balanceLowThreshold?: number
  enabled?: boolean
  proxyMode?: string
  proxyUrl?: string
}

export interface ModelVendorAccountRoutingPayload {
  loadBalanceEnabled: boolean
  loadBalanceWeight: number
  routingPoolName: string | null
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
  routingPoolId?: number | null
  routingPoolName?: string | null
  displayName?: string | null
  configCode?: string | null
  provider: string
  modelName: string
  baseUrl?: string | null
  endpointPath?: string | null
  minimaxGroupId?: string | null
  consoleUrl?: string | null
  balanceUrl?: string | null
  docsUrl?: string | null
  executionTask?: string | null
  executionOptionsJsonMasked?: string | null
  routePreview?: ModelRoutePreview | null
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
  routingExclusionReason?: string | null
  healthStatus: string
}

export interface UnifiedApiVendorGroup {
  vendorCode: string
  label: string
  iconAsset: string
  supportedProviders: string[]
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
  unhealthyModelCount?: number
  warningAccountCount?: number
}

export interface UnifiedApiOverview {
  summary: UnifiedApiSummary
  vendors: UnifiedApiVendorGroup[]
  unconfiguredVendors: UnifiedApiUnconfiguredVendor[]
}

export interface AgentModelConfigPayload {
  vendorAccountId?: number | null
  routingPoolId?: number | null
  displayName?: string
  configCode?: string
  provider: string
  modelName: string
  baseUrl?: string
  apiKey?: string
  clearApiKey?: boolean
  extraAuthJson?: string
  executionTask?: string
  executionOptionsJson?: string
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
  billingUnit?: 'TOKEN_PER_M' | 'PER_CALL' | 'IMAGE_TOKEN' | 'PER_SECOND' | 'PER_CHARACTER' | string
  unitPrice?: number
  enabled?: boolean
  agentEnabled?: boolean
  isDefault?: boolean
  capabilities?: string[]
  proxyMode?: string
  proxyUrl?: string
}

export interface ModelRoutePreview {
  createPath: string
  resultPath: string
  source: "executionTask" | "executionOptionsJson" | "legacyExtraAuthJson" | "default"
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
  exportScope?: "FULL" | "SELECTED_TOOLS" | string | null
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
  snapshotId?: number | null
  sessionId: number
  workspaceId?: number | null
  userId: number
  userMessage: string
  agentFiles: AdminAgentFileSnapshot[]
  strategy?: string | null
  historyMessageCount?: number | null
  fileChunkCount?: number | null
  memoryItemCount?: number | null
  estimatedInputTokens?: number | null
  payloadSha256?: string | null
  snapshot?: Record<string, unknown> | null
}

export type AgentAuditCategory =
  | "CONTEXT_INCOMPLETE"
  | "TOOL_NOT_VISIBLE"
  | "DISCLOSURE_MISS"
  | "SKILL_MISSING"
  | "TOOL_SELECTION_MISMATCH"
  | "ARGUMENT_SCHEMA_ERROR"
  | "EXECUTION_FAILURE"
  | "UNDETERMINED"

export interface AgentAuditEvidenceEvent {
  id: number
  eventType: string
  payload?: Record<string, unknown> | null
  createdAt?: string | null
}

export interface AgentRunAuditReview {
  id?: number | null
  runId?: number | null
  expectedToolCode?: string | null
  finalCategory?: AgentAuditCategory | null
  reviewNote?: string | null
  reviewedBy?: number | null
  updatedAt?: string | null
}

export interface AgentRunAudit {
  runId: number
  contextSnapshotId?: number | null
  inputSnapshot?: Record<string, unknown> | null
  inputSnapshotExpired: boolean
  diagnosis: { category: AgentAuditCategory; summary: string; evidence: string[] }
  review?: AgentRunAuditReview | null
  modelRequestCount: number
  disclosureEvents: AgentAuditEvidenceEvent[]
  skillEvents: AgentAuditEvidenceEvent[]
}

export interface AgentModelRequestSnapshot {
  id: number
  runId: number
  requestSequence: number
  requestStage: string
  iterationNo?: number | null
  modelProviderCode?: string | null
  modelName?: string | null
  messageCount: number
  toolCount: number
  estimatedInputTokens: number
  skillCodes: string[]
  payload?: Record<string, unknown> | null
  payloadSha256: string
  payloadExpiresAt?: string | null
  payloadExpired: boolean
  createdAt?: string | null
}

export interface AgentSkillCoverage {
  skillCode: string
  displayName: string
  status: string
  version?: number | null
  toolCodes: string[]
  recentHydrationCount: number
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
  rangeStartDate?: string | null
  rangeEndDate?: string | null
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
  draftRevision: number
  publishedVersionId: number | null
  executionEnabled: boolean
  hasUnpublishedChanges: boolean
  createdBy?: number
  updatedBy?: number
  createdAt: string
  updatedAt: string
}

export interface WorkflowVersionItem {
  id: number
  version: number
  dslHash: string | null
  sourceDraftRevision: number | null
  snapshotLabel: string | null
  publishedBy: number | null
  publishedAt: string | null
}

export interface UpsertWorkflowPayload {
  workflowName: string
  nodesJson: string
  edgesJson: string
  groupsJson?: string
  configJson?: string
  status?: string
  expectedDraftRevision: number
}

export interface WorkflowValidationResult {
  valid: boolean
  errors: string[]
}
