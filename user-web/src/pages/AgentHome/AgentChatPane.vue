<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue"
import {
  AlertTriangle,
  Check,
  Loader2,
  Pencil,
  Pin,
  Plus,
  RefreshCw,
  Sparkles,
  Trash2,
  X,
} from "lucide-vue-next"
import { collectSessionAssets } from "@/utils/agentChatAssetRefs"
import {
  baseImageLabel,
  buildAttachmentLabelCatalog,
  buildReferenceMentionsPayload,
  displayLabelForMention,
  findReferenceMentionByAsset,
  mentionDedupeKey,
  type AgentReferenceMention,
} from "@/utils/agentReferenceMentions"
import type { ComposerContentPart } from "@/utils/agentComposerMentionEditor"
import AgentComposer from "./AgentComposer.vue"
import AgentMessageRow from "./AgentMessageRow.vue"
import AgentAvatar from "./AgentAvatar.vue"
import AgentAmbientBackground from "./AgentAmbientBackground.vue"
import RunTimeline from "./RunTimeline.vue"
import { filterUserFacingRunEvents } from "./runTimelineEvents"
import AgentToolConfirmationList from "./AgentToolConfirmationList.vue"
import AssetPreviewModal from "@/components/AssetPreviewModal.vue"
import { formatAgentRunFailure } from "@/api/errorMapping"
import { isCreditInsufficient } from "@/utils/creditInsufficient"
import {
  truncateSemanticLabel,
} from "@/utils/conversationPhases"
import { fetchAgentFilePreviewUrl, isImageAttachment, resolveAgentFileUrl, revokeAgentFilePreviewUrl } from "@/utils/agentAttachment"
import type { AgentAvatarState } from "./AgentAvatar.vue"
import { useAuthStore } from "@/store/authStore"
import {
  ApiBusinessError,
  activateAgentBranch,
  cancelAgentRun,
  confirmAgentTool,
  deleteAgentFile,
  deleteUploadAsset,
  editRegenerateAgentMessage,
  createAgentWorkspaceMemory,
  deleteAgentWorkspaceMemory,
  approveAgentWorkspaceMemoryCandidate,
  rejectAgentWorkspaceMemoryCandidate,
  pinAgentWorkspaceMemory,
  fetchAgentMessages,
  fetchAgentFiles,
  fetchRecentAgentFiles,
  fetchAgentRun,
  fetchAgentRunEvents,
  fetchAgentTools,
  fetchAgentWorkspaceMemory,
  fetchAgentWorkspaces,
  fetchTaskById,
  fetchTools,
  regenerateAgentRun,
  sendAgentMessage,
  streamAgentRunEvents,
  unpublishCommunityPost,
  updateAgentToolPreference,
  updateAgentWorkspaceMemory,
  uploadAgentFile,
  uploadToolFile,
} from "@/api"
import { formatCreditInsufficientError, isCreditInsufficientCode } from "@/api/creditErrorMessage"
import type {
  AgentFile,
  AgentMessage,
  AgentModelConfig,
  AgentRun,
  AgentRunEvent,
  AgentRunStatus,
  AgentSession,
  AgentToolPickerItem,
  AgentUrlAttachment,
  AgentWorkspace,
  AgentWorkspaceMemoryItem,
  TaskDetail,
  ToolSummary,
  UserUploadAsset,
} from "@/api/types"
import type { AssetPreviewItem, AssetPreviewRecommendation } from "@/types/assetPreview"
import { randomUUID } from "@/utils/randomUUID"
import {
  mergeAssetWithTask,
  promptFromToolEventPayload,
  resolveTaskIdFromRunEvents,
} from "@/utils/assetPreviewAdapter"
import { openCreateWithAssetRecommendation } from "@/utils/assetReplay"
import { recommendToolsForAsset as recommendAssetTools } from "@/utils/assetToolRecommendations"
import { publishAssetToCommunity, type CommunityPublishPayload } from "@/utils/publishCommunityAsset"
import { buildTaskResultBlocks, resolveAudioTracks } from "@/utils/taskResultBlocks"
import { safeDisplayName } from "@/utils/displayName"
import { useGeneratedMaterialList, useUploadHistoryList } from "@/composables/useMaterialPickerLists"
import {
  chatAssetRefByUrl,
  dragPayloadToUrlAttachment,
  type ChatAssetRef,
  type ChatAssetDragPayload,
} from "@/utils/agentChatAssetRefs"

const auth = useAuthStore()
const userDisplayName = computed(() => safeDisplayName(auth.user?.nickname) || safeDisplayName(auth.user?.username) || "我")

const props = defineProps<{
  sessionId: number
  token: string | null
  draft: string
  sessionSidebarOpen: boolean
  sessions: AgentSession[]
  modelConfigId?: number | null
  agentModels: AgentModelConfig[]
  modelsLoading: boolean
}>()

interface MessageBranchVariant {
  id: string
  messages: AgentMessage[]
  createdAt: string
}

interface MessageBranchGroup {
  anchorMessageId: number
  activeIndex: number
  variants: MessageBranchVariant[]
}

interface BranchSwitcherState {
  activeIndex: number
  total: number
}

type MinimapNodeKind = "system" | "user"

interface ConversationMinimapNode {
  id: string
  messageId: number
  kind: MinimapNodeKind
  index: number
  total: number
  title: string
  excerpt: string
  label: string
}

const emit = defineEmits<{
  "update:draft": [value: string]
  "change-model": [value: number | null]
  "toggle-session-sidebar": []
}>()

const messages = ref<AgentMessage[]>([])
const branchGroups = ref<Record<number, MessageBranchGroup>>({})
const files = ref<AgentFile[]>([])
const urlAttachments = ref<AgentUrlAttachment[]>([])
const filePreviewUrls = ref<Record<number, string>>({})
const pendingUploadPreview = ref<{ name: string; url: string } | null>(null)
const uploadedFileCache = ref<Record<number, AgentFile>>({})
const events = ref<AgentRunEvent[]>([])
const runEventsByRunId = ref<Record<number, AgentRunEvent[]>>({})
const submittedAttachmentJsonByRunId = ref<Record<number, string>>({})
const previewTools = ref<ToolSummary[]>([])
const agentTools = ref<AgentToolPickerItem[]>([])
const agentToolsLoading = ref(false)
const selectedToolCode = ref<string | null>(null)
const intelligenceLevel = ref<"standard" | "high">("standard")
const previewAsset = ref<AssetPreviewItem | null>(null)
const memoryPanelOpen = ref(false)
const memoryTab = ref<"active" | "candidate">("active")
const memoryHighlightId = ref<number | null>(null)
const memoryWorkspaces = ref<AgentWorkspace[]>([])
const memoryWorkspaceId = ref<number | null>(null)
const memoryItems = ref<AgentWorkspaceMemoryItem[]>([])
const memoryLoading = ref(false)
const memorySaving = ref(false)
const memoryDeletingId = ref<number | null>(null)
const memoryEditingId = ref<number | null>(null)
const memoryError = ref<string | null>(null)
const memoryForm = ref({
  memoryType: "user_profile",
  title: "",
  content: "",
})
const paneLoading = ref(true)
const sending = ref(false)
const uploading = ref(false)
const removingFileId = ref<number | null>(null)
const agentError = ref<string | null>(null)

const rememberTool = ref(true)
const AGENT_REFERENCE_ATTACHMENT_LIMIT = 8

type MaterialKind = "image" | "video" | "audio" | "file"

interface AgentMaterialAttachment extends AgentUrlAttachment {
  id: string
  assetId?: number
  kind: MaterialKind
  previewUrl?: string
  uploadedAt?: string
  subtitle?: string
}

const activeRunId = ref<number | null>(null)
const streamingAssistantMessageId = ref<number | null>(null)
const runConnectionStatus = ref<
  "idle" | "running" | "awaiting_confirmation" | "completed" | "failed"
>("idle")
const confirmingEventIds = ref<Set<number>>(new Set())
const confirmationError = ref<string | null>(null)
const dismissedConfirmationIds = ref<Set<number>>(new Set())
const recoveryRunId = ref<number | null>(null)

const AGENT_RUN_EVENT_CACHE_KEY = "ai_tool_market_agent_run_event_cache_v1"

type PersistedRunEventCache = {
  sessionId: number
  runEventsByRunId: Record<string, AgentRunEvent[]>
  submittedAttachmentJsonByRunId: Record<string, string>
  dismissedConfirmationIds: number[]
}

function loadPersistedRunEventCache() {
  try {
    const raw = localStorage.getItem(AGENT_RUN_EVENT_CACHE_KEY)
    if (!raw) return
    const parsed = JSON.parse(raw) as PersistedRunEventCache
    if (!parsed || parsed.sessionId !== props.sessionId) return
    const restoredEvents: Record<number, AgentRunEvent[]> = {}
    for (const [runId, list] of Object.entries(parsed.runEventsByRunId || {})) {
      const id = Number(runId)
      if (!Number.isFinite(id)) continue
      restoredEvents[id] = Array.isArray(list) ? list.slice(-120) : []
    }
    runEventsByRunId.value = restoredEvents
    const restoredJson: Record<number, string> = {}
    for (const [runId, value] of Object.entries(parsed.submittedAttachmentJsonByRunId || {})) {
      const id = Number(runId)
      if (!Number.isFinite(id) || typeof value !== "string") continue
      restoredJson[id] = value
    }
    submittedAttachmentJsonByRunId.value = restoredJson
    dismissedConfirmationIds.value = new Set((parsed.dismissedConfirmationIds || []).filter((id) => Number.isFinite(id)))
  } catch {
    // ignore corrupted cache
  }
}

function persistRunEventCache() {
  try {
    const payload: PersistedRunEventCache = {
      sessionId: props.sessionId,
      runEventsByRunId: Object.fromEntries(
        Object.entries(runEventsByRunId.value).map(([runId, list]) => [runId, (list || []).slice(-120)]),
      ),
      submittedAttachmentJsonByRunId: Object.fromEntries(
        Object.entries(submittedAttachmentJsonByRunId.value).map(([runId, value]) => [runId, value]),
      ),
      dismissedConfirmationIds: Array.from(dismissedConfirmationIds.value),
    }
    localStorage.setItem(AGENT_RUN_EVENT_CACHE_KEY, JSON.stringify(payload))
  } catch {
    // ignore quota
  }
}

function cloneBranchMessages(list: AgentMessage[]): AgentMessage[] {
  return list.map((message) => ({ ...message }))
}

function normalizeBranchGroup(group: MessageBranchGroup | undefined): MessageBranchGroup | null {
  if (!group || !Number.isFinite(group.anchorMessageId) || !Array.isArray(group.variants)) return null
  const variants = group.variants
    .filter((variant) => Array.isArray(variant.messages) && variant.messages.length > 0)
    .map((variant) => ({
      id: typeof variant.id === "string" && variant.id ? variant.id : randomUUID(),
      createdAt: typeof variant.createdAt === "string" && variant.createdAt ? variant.createdAt : new Date().toISOString(),
      messages: cloneBranchMessages(variant.messages),
    }))
  if (!variants.length) return null
  return {
    anchorMessageId: group.anchorMessageId,
    activeIndex: Math.min(Math.max(0, Number(group.activeIndex) || 0), variants.length - 1),
    variants,
  }
}

function loadPersistedMessageBranches() {
  try {
    const raw = localStorage.getItem(branchStorageKey.value)
    if (!raw) {
      branchGroups.value = {}
      return
    }
    const parsed = JSON.parse(raw) as Record<string, MessageBranchGroup>
    const restored: Record<number, MessageBranchGroup> = {}
    for (const [key, group] of Object.entries(parsed || {})) {
      const anchorId = Number(key)
      const normalized = normalizeBranchGroup(group)
      if (!Number.isFinite(anchorId) || !normalized) continue
      restored[anchorId] = { ...normalized, anchorMessageId: anchorId }
    }
    branchGroups.value = restored
  } catch {
    branchGroups.value = {}
  }
}

function persistMessageBranches() {
  try {
    localStorage.setItem(branchStorageKey.value, JSON.stringify(branchGroups.value))
  } catch {
    // ignore quota
  }
}

function branchStateForMessage(message: AgentMessage): BranchSwitcherState | null {
  const total = message.branchTotal ?? 0
  if (total <= 1) return null
  return {
    activeIndex: message.branchIndex ?? 0,
    total,
  }
}

function visiblePrefixBeforeMessage(messageId: number) {
  const index = messages.value.findIndex((item) => item.id === messageId)
  return index < 0 ? [] : messages.value.slice(0, index)
}

function variantMatchesCurrentSuffix(group: MessageBranchGroup, suffix: AgentMessage[]) {
  const first = suffix[0]
  if (!first) return false
  return group.variants.some((variant) => {
    const candidate = variant.messages[0]
    return (
      candidate?.id === first.id &&
      candidate?.contentText === first.contentText &&
      variant.messages.length === suffix.length
    )
  })
}

function captureCurrentBranchVariant(anchorMessageId: number) {
  void anchorMessageId
  return
  const anchorIndex = messages.value.findIndex((item) => item.id === anchorMessageId)
  if (anchorIndex < 0) return
  const suffix = cloneBranchMessages(messages.value.slice(anchorIndex))
  if (!suffix.length) return
  const current = branchGroups.value[anchorMessageId]
  if (current && variantMatchesCurrentSuffix(current, suffix)) return
  const group: MessageBranchGroup = current ?? {
    anchorMessageId,
    activeIndex: 0,
    variants: [],
  }
  group.variants.push({
    id: randomUUID(),
    createdAt: new Date().toISOString(),
    messages: suffix,
  })
  group.activeIndex = group.variants.length - 1
  branchGroups.value = { ...branchGroups.value, [anchorMessageId]: group }
  persistMessageBranches()
}

function beginPendingEditedBranch(anchorMessageId: number, editedMessage: AgentMessage) {
  void anchorMessageId
  void editedMessage
  return
  const current = branchGroups.value[anchorMessageId]
  const group: MessageBranchGroup = current ?? {
    anchorMessageId,
    activeIndex: 0,
    variants: [],
  }
  group.variants.push({
    id: randomUUID(),
    createdAt: new Date().toISOString(),
    messages: cloneBranchMessages([editedMessage]),
  })
  group.activeIndex = group.variants.length - 1
  branchGroups.value = { ...branchGroups.value, [anchorMessageId]: group }
  persistMessageBranches()
}

function syncActiveBranchVariantFromVisibleMessages(anchorMessageId: number) {
  void anchorMessageId
  return
  const group = branchGroups.value[anchorMessageId]
  if (!group) return
  const anchorIndex = messages.value.findIndex((item) => item.id === anchorMessageId)
  if (anchorIndex < 0) return
  group.variants[group.activeIndex] = {
    ...group.variants[group.activeIndex],
    messages: cloneBranchMessages(messages.value.slice(anchorIndex)),
  }
  branchGroups.value = { ...branchGroups.value, [anchorMessageId]: group }
  persistMessageBranches()
}

function syncActiveBranchVariantFromBaseMessages(anchorMessageId: number, baseMessages: AgentMessage[]) {
  void anchorMessageId
  void baseMessages
  return
  const group = branchGroups.value[anchorMessageId]
  if (!group) return
  const active = group.variants[group.activeIndex]
  const activeAnchor = active?.messages[0]
  const anchorIndex = baseMessages.findIndex((item) => item.id === anchorMessageId)
  const baseAnchor = anchorIndex >= 0 ? baseMessages[anchorIndex] : null
  if (!activeAnchor || !baseAnchor || activeAnchor.contentText !== baseAnchor.contentText) return
  group.variants[group.activeIndex] = {
    ...active,
    messages: cloneBranchMessages(baseMessages.slice(anchorIndex)),
  }
  branchGroups.value = { ...branchGroups.value, [anchorMessageId]: group }
  persistMessageBranches()
}

function mergeActiveBranchRunMessagesFromBase(group: MessageBranchGroup, baseMessages: AgentMessage[]) {
  void group
  void baseMessages
  return
  const active = group.variants[group.activeIndex]
  if (!active) return
  const activeRunIds = new Set(
    active.messages
      .map((message) => message.runId)
      .filter((runId): runId is number => typeof runId === "number"),
  )
  if (!activeRunIds.size) return
  const nextMessages = active.messages.map((message) => {
    if (typeof message.runId !== "number") return message
    const replacement = baseMessages.find((candidate) => candidate.runId === message.runId && candidate.role === message.role)
    return replacement ? { ...replacement } : message
  })
  for (const baseMessage of baseMessages) {
    if (typeof baseMessage.runId !== "number" || !activeRunIds.has(baseMessage.runId)) continue
    const alreadyPresent = nextMessages.some(
      (message) => message.runId === baseMessage.runId && message.role === baseMessage.role,
    )
    if (!alreadyPresent) {
      nextMessages.push({ ...baseMessage })
    }
  }
  group.variants[group.activeIndex] = {
    ...active,
    messages: cloneBranchMessages(nextMessages),
  }
  branchGroups.value = { ...branchGroups.value, [group.anchorMessageId]: group }
  persistMessageBranches()
}

function applyActiveBranchView(baseMessages: AgentMessage[]) {
  return cloneBranchMessages(baseMessages)
}

async function switchMessageBranch(anchorMessageId: number, delta: -1 | 1) {
  if (!auth.isLoggedIn) return
  const anchor = messages.value.find((message) => message.id === anchorMessageId)
  if (!anchor?.branchVariantMessageIds?.length) return
  const currentIndex = anchor.branchIndex ?? 0
  const nextIndex = Math.min(Math.max(currentIndex + delta, 0), anchor.branchVariantMessageIds.length - 1)
  const variantMessageId = anchor.branchVariantMessageIds[nextIndex]
  if (nextIndex === currentIndex || variantMessageId == null) return
  const res = await activateAgentBranch(
    props.sessionId,
    {
      anchorMessageId,
      variantMessageId,
    },
    { token: props.token },
  )
  messages.value = res.list
  await nextTick()
  scheduleNavLayoutUpdate()
}
const lastFailedRunId = ref<number | null>(null)
const showActiveRunLimitHint = ref(false)
const cancellingRun = ref(false)
const retryingRun = ref(false)
const regeneratingMessageId = ref<number | null>(null)
const editingMessageId = ref<number | null>(null)
const editingMessageDraft = ref("")
const editingRegenerating = ref(false)
const copiedMessageId = ref<number | null>(null)
const bottomRef = ref<HTMLElement | null>(null)
const messageContainerRef = ref<HTMLElement | null>(null)
const composerDockRef = ref<HTMLElement | null>(null)
const composerRef = ref<InstanceType<typeof AgentComposer> | null>(null)
const composerScrollInset = ref(210)
let composerResizeObserver: ResizeObserver | null = null
const scrollOffset = ref(0)
const stickToBottom = ref(true)
const minimapDrawerOpen = ref(false)
const hoveredMinimapNodeId = ref<string | null>(null)
const hoverCardTop = ref(160)
const activeMinimapMessageId = ref<number | null>(null)
let minimapIntersectionObserver: IntersectionObserver | null = null
const messagesKey = computed(() => `agent_messages_${props.sessionId}`)
const branchStorageKey = computed(() => `agent_message_branches_${props.sessionId}`)
const sessionAssetsForComposer = computed(() => collectSessionAssets(messages.value))
const autoMountedReference = computed<AgentReferenceMention | null>(() => {
  if (hasActiveRun.value || sending.value || editingRegenerating.value || regeneratingMessageId.value != null) return null
  if (files.value.length > 0 || urlAttachments.value.length > 0 || draftReferenceMentions.value.length > 0) return null
  if (hasTypedReferenceToken(input.value)) return null
  const latest = latestImageSessionAsset(sessionAssetsForComposer.value)
  if (!latest) return null
  const mention = sessionAssetToReferenceMention(latest)
  const key = mentionDedupeKey(mention)
  return key && key === dismissedAutoMountedReferenceKey.value ? null : mention
})
let runStreamAbort: AbortController | null = null
let runStatusWatchdog: number | null = null
let streamingAnimationTimer: number | null = null
let paneLoadEpoch = 0
const terminalEventFinalizingRunIds = new Set<number>()
const CHAT_SCROLL_KEY_PREFIX = "ai_tool_market_agent_chat_scroll_v1:"

type PersistedChatScroll = {
  scrollTop: number
  stickToBottom: boolean
  updatedAt: number
}

function scrollStorageKey(sessionId: number) {
  return `${CHAT_SCROLL_KEY_PREFIX}${sessionId}`
}

function persistChatScroll() {
  const container = messageContainerRef.value
  if (!container) return
  const payload: PersistedChatScroll = {
    scrollTop: container.scrollTop,
    stickToBottom: stickToBottom.value,
    updatedAt: Date.now(),
  }
  try {
    window.sessionStorage.setItem(scrollStorageKey(props.sessionId), JSON.stringify(payload))
  } catch {
    // ignore quota
  }
}

function loadPersistedChatScroll(sessionId: number): PersistedChatScroll | null {
  try {
    const raw = window.sessionStorage.getItem(scrollStorageKey(sessionId))
    if (!raw) return null
    const parsed = JSON.parse(raw) as PersistedChatScroll
    if (!parsed || !Number.isFinite(parsed.scrollTop)) return null
    return parsed
  } catch {
    return null
  }
}

async function withAutoScrollBehavior(fn: () => void | Promise<void>) {
  const container = messageContainerRef.value
  if (!container) {
    await fn()
    return
  }
  const previous = container.style.scrollBehavior
  container.style.scrollBehavior = "auto"
  try {
    await fn()
  } finally {
    container.style.scrollBehavior = previous
  }
}

const input = computed({
  get: () => props.draft,
  set: (val: string) => emit("update:draft", val),
})

const draftReferenceMentions = ref<AgentReferenceMention[]>([])
const dismissedAutoMountedReferenceKey = ref<string | null>(null)

const sessionAssetRefMap = computed(() => chatAssetRefByUrl(messages.value))

const confirmationEvents = computed(() =>
  events.value
    .filter((event) => event.eventType === "tool.confirmation_required")
    .filter((event) => !dismissedConfirmationIds.value.has(event.id))
    .map((event) => ({ event, payload: parseEventJson(event.eventJson) })),
)

const hasActiveRun = computed(() => {
  if (!activeRunId.value) return false
  return (
    runConnectionStatus.value === "running" ||
    runConnectionStatus.value === "awaiting_confirmation"
  )
})

const showRunRecoveryBanner = computed(
  () => recoveryRunId.value != null && showActiveRunLimitHint.value,
)
const hasStreamingAssistantContent = computed(() =>
  streamingAssistantMessageId.value != null &&
  messages.value.some((message) => message.id === streamingAssistantMessageId.value && message.contentText.length > 0),
)
const showGenerationLoading = computed(() =>
  !hasStreamingAssistantContent.value &&
  (
    sending.value ||
    editingRegenerating.value ||
    regeneratingMessageId.value != null ||
    hasActiveRun.value
  ),
)
const visibleRunTimelineEvents = computed(() => filterUserFacingRunEvents(events.value, true))
const showInlineRunTimeline = computed(
  () => hasActiveRun.value && visibleRunTimelineEvents.value.length > 0,
)
const previewRecommendations = computed<AssetPreviewRecommendation[]>(() =>
  previewAsset.value ? recommendToolsForAsset(previewAsset.value) : [],
)
const memoryTypeOptions = [
  { value: "user_profile", label: "用户画像" },
  { value: "preference", label: "稳定偏好" },
  { value: "workspace_fact", label: "项目知识" },
  { value: "tool_lesson", label: "工具经验" },
  { value: "workflow_recipe", label: "流程配方" },
  { value: "custom", label: "自定义" },
]
const groupedMemoryItems = computed(() => {
  const order = memoryTypeOptions.map((option) => option.value)
  const groups = order
    .map((type) => ({
      type,
      label: memoryTypeOptions.find((item) => item.value === type)?.label ?? type,
      items: memoryItems.value.filter((item) => item.memoryType === type),
    }))
    .filter((group) => group.items.length > 0)
  const known = new Set(order)
  const others = memoryItems.value.filter((item) => !known.has(item.memoryType))
  if (others.length > 0) {
    groups.push({ type: "other", label: "其他", items: others })
  }
  return groups
})

const ambientState = computed(() => {
  if (runConnectionStatus.value === "awaiting_confirmation") return "awaiting_confirmation" as const
  if (hasActiveRun.value || showGenerationLoading.value) return "thinking" as const
  return "idle" as const
})

const conversationMinimapNodes = computed<ConversationMinimapNode[]>(() => {
  const raw: Omit<ConversationMinimapNode, "index" | "total">[] = []
  for (const message of messages.value) {
    const isUser = message.role === "USER"
    const title = isUser ? "用户" : "系统"
    raw.push({
      id: `message-${message.id}`,
      messageId: message.id,
      kind: isUser ? "user" : "system",
      title,
      excerpt: truncateSemanticLabel(message.contentText || "空消息", 26),
      label: `${title}：${truncateSemanticLabel(message.contentText || "空消息", 22)}`,
    })
  }
  const total = raw.length
  return raw.map((node, index) => ({
    ...node,
    index: index + 1,
    total,
  }))
})

const hoveredMinimapNode = computed(() =>
  conversationMinimapNodes.value.find((node) => node.id === hoveredMinimapNodeId.value) ?? null,
)

const activeMinimapNodeId = computed(() => {
  const activeMessageId = activeMinimapMessageId.value ?? messages.value[0]?.id
  return conversationMinimapNodes.value.find((node) => node.messageId === activeMessageId)?.id ?? null
})

const showConversationMinimap = computed(() => conversationMinimapNodes.value.length > 1)

function isMessageStreaming(message: AgentMessage) {
  return message.id === streamingAssistantMessageId.value
}

function isMessageLiveStreaming(message: AgentMessage) {
  return isMessageStreaming(message) && hasActiveRun.value
}

function resolveAssistantAvatarState(message: AgentMessage): AgentAvatarState {
  if (isMessageLiveStreaming(message)) return "streaming"
  const lastAssistant = [...messages.value].reverse().find((m) => m.role === "ASSISTANT")
  if (
    lastAssistant?.id === message.id &&
    (showGenerationLoading.value || runConnectionStatus.value === "running")
  ) {
    return "thinking"
  }
  return "idle"
}

function messageTime(value?: string | null) {
  if (!value) return ""
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ""
  return date.toLocaleTimeString("zh-CN", {
    timeZone: "Asia/Shanghai",
    hour: "2-digit",
    minute: "2-digit",
  })
}

function hasTypedReferenceToken(value: string) {
  return /@(?:图|图片|视频|音频|文件)\d+/i.test(value)
}

function latestImageSessionAsset(assets: ChatAssetRef[]) {
  return [...assets].reverse().find((asset) => asset.kind === "image" && Boolean(asset.url)) ?? null
}

function sessionAssetToReferenceMention(asset: ChatAssetRef): AgentReferenceMention {
  return {
    token: baseImageLabel(asset.refLabel) || "@图1",
    refLabel: asset.refLabel,
    assetKey: asset.assetKey,
    url: asset.url,
    kind: "image",
    name: asset.name,
    contentType: asset.contentType || "image/*",
    previewUrl: resolveAgentFileUrl(asset.url),
    source: "session_asset_auto",
  }
}

function dismissAutoMountedReference(mention: AgentReferenceMention) {
  dismissedAutoMountedReferenceKey.value = mentionDedupeKey(mention)
}

function autoMountedReferenceToAttachment(mention: AgentReferenceMention): AgentUrlAttachment {
  return {
    id: mention.assetKey || mention.fileId || mention.url,
    name: mention.refLabel || mention.name || "最新图片",
    refLabel: mention.refLabel || mention.name || "最新图片",
    contentType: mention.contentType || "image/*",
    url: mention.url,
    source: "chat_reference_auto",
  }
}

function mergeAutoMountedAttachment(
  attachments: AgentUrlAttachment[],
  mention: AgentReferenceMention | null,
) {
  if (!mention?.url) return attachments
  if (attachments.some((item) => item.url === mention.url)) return attachments
  return [...attachments, autoMountedReferenceToAttachment(mention)]
}

function mergeAutoMountedMention(
  mentions: AgentReferenceMention[],
  mention: AgentReferenceMention | null,
) {
  if (!mention?.url) return mentions
  const key = mentionDedupeKey(mention)
  if (mentions.some((item) => mentionDedupeKey(item) === key)) return mentions
  return [...mentions, mention]
}

function contentPartForAutoMountedReference(mention: AgentReferenceMention): ComposerContentPart {
  const part: ComposerContentPart = {
    type: "image",
    asset_key: mention.assetKey || (mention.fileId == null ? mention.url : `file_${mention.fileId}`),
    url: mention.url,
    name: mention.refLabel || mention.name || "最新图片",
    content_type: mention.contentType || "image/*",
  }
  if (mention.fileId != null) {
    part.file_id = mention.fileId
  }
  return part
}

function mergeAutoMountedContentParts(
  parts: ComposerContentPart[],
  text: string,
  mention: AgentReferenceMention | null,
) {
  if (!mention?.url) return parts
  const next = parts.length > 0 ? [...parts] : (text ? [{ type: "text" as const, text }] : [])
  const key = mention.assetKey || mention.url
  const hasPart = next.some((part) => {
    if (part.type === "text") return false
    return part.url === mention.url || part.asset_key === key
  })
  if (!hasPart) {
    next.push(contentPartForAutoMountedReference(mention))
  }
  return next
}

function messageContentJsonForFiles(
  items: AgentFile[],
  urlItems: AgentUrlAttachment[] = [],
  messageText = "",
  explicitMentions: AgentReferenceMention[] = [],
  structured?: {
    contentParts?: unknown[]
    positionalPrompt?: string
    globalFileIds?: Array<string | number>
  },
) {
  const sessionAssets = collectSessionAssets(messages.value)
  const referenceCatalog = buildAttachmentLabelCatalog(urlItems, items, sessionAssets)
  const referenceMentions = buildReferenceMentionsPayload(
    messageText,
    urlItems,
    items,
    sessionAssets,
    explicitMentions,
  )
  if (
    items.length === 0 &&
    urlItems.length === 0 &&
    referenceMentions.length === 0 &&
    !structured?.contentParts?.length &&
    !structured?.globalFileIds?.length &&
    !structured?.positionalPrompt
  ) {
    return undefined
  }
  const payload: Record<string, unknown> = {}
  if (urlItems.length > 0 || items.length > 0) {
    payload.attachments = [
      ...urlItems.map((item, index) => ({
        id: item.id,
        name: referenceLabelForUrlAttachment(referenceCatalog, item, index),
        contentType: item.contentType,
        size: item.size,
        url: item.url,
        status: "READY",
        source: item.source || "url",
      })),
      ...items.map((file, index) => ({
        id: file.id,
        name: referenceLabelForAgentFile(referenceCatalog, file, urlItems.length + index),
        contentType: file.contentType,
        size: file.fileSize,
        url: file.downloadUrl,
        status: file.status,
        source: "agent_file",
      })),
    ]
  }
  if (referenceMentions.length > 0) {
    payload.referenceMentions = referenceMentions
  }
  if (structured?.globalFileIds?.length) {
    payload.globalFileIds = structured.globalFileIds
  }
  if (structured?.contentParts?.length) {
    payload.contentParts = structured.contentParts
  }
  if (structured?.positionalPrompt) {
    payload.positionalPrompt = structured.positionalPrompt
  }
  return JSON.stringify(payload)
}

function referenceMentionsForApi(mentions: AgentReferenceMention[]) {
  return normalizeTurnReferenceMentions(mentions).map((mention) => ({
    token: mention.token,
    refLabel: mention.refLabel,
    assetKey: mention.assetKey,
    fileId: mention.fileId,
    url: mention.url,
    kind: mention.kind,
    name: mention.name,
    contentType: mention.contentType,
    previewUrl: mention.previewUrl,
    source: mention.source,
  }))
}

function normalizeTurnReferenceMentions(mentions: AgentReferenceMention[]) {
  if (mentions.length <= 1) return mentions
  const baseCounts = new Map<string, number>()
  for (const mention of mentions) {
    const key = normalizedReferenceBaseKey(mention.token || "")
      || normalizedReferenceBaseKey(mention.refLabel || "")
      || mention.token
      || mention.refLabel
    if (!key) continue
    baseCounts.set(key, (baseCounts.get(key) ?? 0) + 1)
  }
  const needsRenumber = Array.from(baseCounts.values()).some((count) => count > 1)
  if (!needsRenumber) return mentions

  const counters: Record<string, number> = { image: 0, video: 0, audio: 0, file: 0 }
  return mentions.map((mention) => {
    const kind = normalizedMentionKind(mention)
    counters[kind] += 1
    const index = counters[kind]
    const token = displayLabelForMention({ kind }, index)
    const refLabel = referenceAttachmentLabel(index - 1, mention.refLabel || mention.name || mention.token, mention.contentType)
    return {
      ...mention,
      token,
      refLabel,
    }
  })
}

function normalizedMentionKind(mention: AgentReferenceMention): "image" | "video" | "audio" | "file" {
  if (mention.kind === "video" || mention.kind === "audio" || mention.kind === "file") return mention.kind
  if (isImageAttachment(mention.contentType, mention.name || mention.refLabel || mention.token)) return "image"
  return "file"
}

function normalizedReferenceBaseKey(label: string) {
  return baseImageLabel(label)?.replace(/^@图片/, "@图") ?? ""
}

function globalFileIdsFor(items: AgentFile[], urlItems: AgentUrlAttachment[]): Array<string | number> {
  const ids: Array<string | number> = []
  const seen = new Set<string>()
  const push = (value: string | number | undefined | null) => {
    if (value == null || value === "") return
    const key = String(value)
    if (seen.has(key)) return
    seen.add(key)
    ids.push(value)
  }
  items.forEach((item) => push(item.id))
  urlItems.forEach((item) => push(item.id ?? item.url))
  return ids
}

function runEventsForMessage(message: AgentMessage) {
  if (message.role !== "ASSISTANT" || message.runId == null) return []
  if (hasActiveRun.value && activeRunId.value === message.runId) return []
  const cachedEvents = runEventsByRunId.value[message.runId] ?? []
  if (cachedEvents.length > 0) return cachedEvents
  return activeRunId.value === message.runId ? events.value : []
}

function messageDividerTime(value?: string | null) {
  if (!value) return ""
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ""
  const now = new Date()
  const shanghaiDate = date.toLocaleDateString("zh-CN", { timeZone: "Asia/Shanghai" })
  const shanghaiToday = now.toLocaleDateString("zh-CN", { timeZone: "Asia/Shanghai" })
  const sameDay = shanghaiDate === shanghaiToday
  const datePart = sameDay
    ? "今天"
    : date.toLocaleDateString("zh-CN", {
        timeZone: "Asia/Shanghai",
        month: "2-digit",
        day: "2-digit",
      })
  return `${datePart} ${messageTime(value)}`
}

function shouldShowTimeDivider(message: AgentMessage, index: number) {
  if (index === 0) return true
  const previous = messages.value[index - 1]
  if (!previous?.createdAt || !message.createdAt) return false
  const currentTime = new Date(message.createdAt).getTime()
  const previousTime = new Date(previous.createdAt).getTime()
  if (Number.isNaN(currentTime) || Number.isNaN(previousTime)) return false
  return currentTime - previousTime > 5 * 60 * 1000
}

function changeModel(rawId: string) {
  if (!rawId) {
    emit("change-model", null)
    return
  }
  const id = Number(rawId)
  emit("change-model", Number.isFinite(id) && id > 0 ? id : null)
}

function isResumableRunStatus(status: AgentRunStatus) {
  return status === "CREATED" || status === "RUNNING" || status === "WAITING_USER_CONFIRMATION"
}

function findLatestRunIdInMessages(msgs: AgentMessage[]): number | null {
  for (let i = msgs.length - 1; i >= 0; i--) {
    const runId = msgs[i]?.runId
    if (runId != null) return runId
  }
  return null
}

function findRunIdForUserMessage(message: AgentMessage): number | null {
  if (message.runId != null) return message.runId
  const index = messages.value.findIndex((item) => item.id === message.id)
  if (index < 0) return null
  for (let i = index + 1; i < messages.value.length; i++) {
    const item = messages.value[i]
    if (item.role === "USER") break
    if (item.runId != null) return item.runId
  }
  return null
}

async function discoverActiveRunId(preferSessionId?: number | null): Promise<number | null> {
  if (!auth.isLoggedIn) return null
  const ordered = [...props.sessions]
  if (preferSessionId != null) {
    ordered.sort((a, b) => {
      if (a.id === preferSessionId) return -1
      if (b.id === preferSessionId) return 1
      return 0
    })
  }
  for (const session of ordered) {
    const res = await fetchAgentMessages(session.id, { token: props.token })
    const runId = findLatestRunIdInMessages(res.list)
    if (!runId) continue
    try {
      const run = await fetchAgentRun(runId, { token: props.token })
      if (isResumableRunStatus(run.status)) return runId
    } catch { }
  }
  return null
}

function isCurrentPaneLoad(sessionId: number, epoch: number) {
  return props.sessionId === sessionId && paneLoadEpoch === epoch
}

function resetSessionTransientState() {
  paneLoadEpoch += 1
  stopRunEventStream()
  stopRunStatusWatchdog()
  stopStreamingAnimationTimer()
  activeRunId.value = null
  streamingAssistantMessageId.value = null
  runConnectionStatus.value = "idle"
  sending.value = false
  events.value = []
  runEventsByRunId.value = {}
  submittedAttachmentJsonByRunId.value = {}
  recoveryRunId.value = null
  showActiveRunLimitHint.value = false
  confirmationError.value = null
  dismissedConfirmationIds.value = new Set()
}

async function loadPane() {
  if (!auth.isLoggedIn) return
  const sessionId = props.sessionId
  const loadEpoch = ++paneLoadEpoch
  paneLoading.value = true
  agentError.value = null
  try {
    const res = await fetchAgentMessages(sessionId, { token: props.token })
    if (!isCurrentPaneLoad(sessionId, loadEpoch)) return
    loadPersistedMessageBranches()
    messages.value = applyActiveBranchView(res.list)
    await loadFiles(sessionId, () => isCurrentPaneLoad(sessionId, loadEpoch))
    if (!isCurrentPaneLoad(sessionId, loadEpoch)) return
    await hydrateHistoricalRunEvents(() => isCurrentPaneLoad(sessionId, loadEpoch))
    if (!isCurrentPaneLoad(sessionId, loadEpoch)) return
    await resumePendingRunForSession(() => isCurrentPaneLoad(sessionId, loadEpoch))
  } finally {
    if (isCurrentPaneLoad(sessionId, loadEpoch)) {
      paneLoading.value = false
      await nextTick()
      const persisted = loadPersistedChatScroll(sessionId)
      if (persisted && messageContainerRef.value) {
        stickToBottom.value = persisted.stickToBottom
        scrollOffset.value = persisted.scrollTop
        await withAutoScrollBehavior(async () => {
          await nextTick()
          messageContainerRef.value!.scrollTop = persisted.scrollTop
        })
        scheduleNavLayoutUpdate()
      } else {
        stickToBottom.value = true
        await withAutoScrollBehavior(() => scrollBottom(true))
      }
    }
  }
}

async function hydrateHistoricalRunEvents(isCurrent: () => boolean = () => true) {
  if (!auth.isLoggedIn) return
  const runIds = [...new Set(
    messages.value
      .filter((message) => message.role === "ASSISTANT" && message.runId != null)
      .map((message) => message.runId as number),
  )]
  await Promise.all(
    runIds.map(async (runId) => {
      if (!isCurrent()) return
      const cached = runEventsByRunId.value[runId] ?? []
      if (cached.length > 0) return
      await syncRunEvents(runId)
    }),
  )
}

async function resumePendingRunForSession(isCurrent: () => boolean = () => true) {
  if (!auth.isLoggedIn) return
  const runId = findLatestRunIdInMessages(messages.value)
  if (!runId) return
  try {
    const run = await fetchAgentRun(runId, { token: props.token })
    if (!isCurrent()) return
    if (!isResumableRunStatus(run.status)) return
    activeRunId.value = runId
    recoveryRunId.value = runId
    await syncRunEvents(runId)
    if (!isCurrent()) return
    if (run.status === "WAITING_USER_CONFIRMATION") {
      runConnectionStatus.value = "awaiting_confirmation"
      await scrollBottom()
      return
    }
    runConnectionStatus.value = "running"
    void waitForRunComplete(runId)
  } catch { }
}

async function cancelRecoveryRun() {
  if (!auth.isLoggedIn || recoveryRunId.value == null || cancellingRun.value) return
  cancellingRun.value = true
  agentError.value = null
  try {
    await cancelAgentRun(recoveryRunId.value, { token: props.token })
    stopRunEventStream()
    showActiveRunLimitHint.value = false
    recoveryRunId.value = null
    activeRunId.value = null
    lastFailedRunId.value = null
    runConnectionStatus.value = "idle"
    events.value = []
    dismissedConfirmationIds.value = new Set()
    await refreshMessages()
  } catch (error) {
    applyAgentFailure(error)
  } finally {
    cancellingRun.value = false
    await scrollBottom()
  }
}

async function cancelCurrentRun() {
  if (!auth.isLoggedIn) return
  if (sending.value && !activeRunId.value) {
    sending.value = false
    agentError.value = null
    runConnectionStatus.value = "idle"
    await scrollBottom()
    return
  }
  if (activeRunId.value && !cancellingRun.value) {
    cancellingRun.value = true
    agentError.value = null
    try {
      await cancelAgentRun(activeRunId.value, { token: props.token })
      stopRunEventStream()
      activeRunId.value = null
      lastFailedRunId.value = null
      runConnectionStatus.value = "idle"
      events.value = []
      dismissedConfirmationIds.value = new Set()
      recoveryRunId.value = null
      showActiveRunLimitHint.value = false
      await refreshMessages()
    } catch (error) {
      applyAgentFailure(error)
    } finally {
      cancellingRun.value = false
      sending.value = false
      await scrollBottom()
    }
  }
}

async function hydrateFilePreviews(
  targetFiles: AgentFile[] = files.value,
  isCurrent: () => boolean = () => true,
) {
  if (!auth.isLoggedIn) return
  const next: Record<number, string> = {}
  await Promise.all(
    targetFiles.map(async (file) => {
      if (!isImageAttachment(file.contentType, file.originalFilename)) return
      const url = await fetchAgentFilePreviewUrl(file.downloadUrl, props.token)
      if (url) next[file.id] = url
    }),
  )
  if (!isCurrent()) return
  for (const file of targetFiles) {
    if (!next[file.id]) revokeAgentFilePreviewUrl(file.downloadUrl)
  }
  filePreviewUrls.value = next
}

async function loadFiles(
  sessionId: number = props.sessionId,
  isCurrent: () => boolean = () => props.sessionId === sessionId,
) {
  if (!auth.isLoggedIn) return
  const res = await fetchAgentFiles(sessionId, { token: props.token })
  if (!isCurrent()) return
  files.value = res.list
  await hydrateFilePreviews(res.list, isCurrent)
}

async function openMemoryPanel() {
  memoryPanelOpen.value = true
  if (!auth.isLoggedIn) return
  if (memoryWorkspaces.value.length === 0) {
    await loadMemoryWorkspaces()
  } else if (memoryWorkspaceId.value != null) {
    await loadMemoryItems()
  }
}

function closeMemoryPanel() {
  memoryPanelOpen.value = false
  resetMemoryForm()
}

async function loadMemoryWorkspaces() {
  if (!auth.isLoggedIn) return
  memoryLoading.value = true
  memoryError.value = null
  try {
    const res = await fetchAgentWorkspaces({ token: props.token })
    memoryWorkspaces.value = res.list
    const sessionWorkspaceId = props.sessions.find((item) => item.id === props.sessionId)?.workspaceId ?? null
    memoryWorkspaceId.value = sessionWorkspaceId ?? memoryWorkspaceId.value ?? res.list[0]?.id ?? null
    if (memoryWorkspaceId.value != null) {
      await loadMemoryItems()
    }
  } catch (error) {
    memoryError.value = formatAgentError(error)
  } finally {
    memoryLoading.value = false
  }
}

async function loadMemoryItems() {
  if (!auth.isLoggedIn || memoryWorkspaceId.value == null) return
  memoryLoading.value = true
  memoryError.value = null
  try {
    const res = await fetchAgentWorkspaceMemory(memoryWorkspaceId.value, {
      token: props.token,
      status: memoryTab.value === "candidate" ? "CANDIDATE" : undefined,
    })
    memoryItems.value = res.list
  } catch (error) {
    memoryError.value = formatAgentError(error)
  } finally {
    memoryLoading.value = false
  }
}

async function changeMemoryTab(tab: "active" | "candidate") {
  if (memoryTab.value === tab) return
  memoryTab.value = tab
  resetMemoryForm()
  await loadMemoryItems()
}

async function toggleMemoryPin(item: AgentWorkspaceMemoryItem) {
  if (!auth.isLoggedIn || memoryWorkspaceId.value == null) return
  memoryError.value = null
  try {
    const saved = await pinAgentWorkspaceMemory(
      memoryWorkspaceId.value,
      item.id,
      !item.pinned,
      { token: props.token },
    )
    memoryItems.value = memoryItems.value.map((current) => (current.id === saved.id ? saved : current))
  } catch (error) {
    memoryError.value = formatAgentError(error)
  }
}

async function approveCandidateMemory(item: AgentWorkspaceMemoryItem) {
  if (!auth.isLoggedIn || memoryWorkspaceId.value == null) return
  memoryError.value = null
  try {
    await approveAgentWorkspaceMemoryCandidate(memoryWorkspaceId.value, item.id, { token: props.token })
    memoryItems.value = memoryItems.value.filter((current) => current.id !== item.id)
  } catch (error) {
    memoryError.value = formatAgentError(error)
  }
}

async function rejectCandidateMemory(item: AgentWorkspaceMemoryItem) {
  if (!auth.isLoggedIn || memoryWorkspaceId.value == null) return
  memoryError.value = null
  try {
    await rejectAgentWorkspaceMemoryCandidate(memoryWorkspaceId.value, item.id, { token: props.token })
    memoryItems.value = memoryItems.value.filter((current) => current.id !== item.id)
  } catch (error) {
    memoryError.value = formatAgentError(error)
  }
}

async function openMemoryFromTrace(memoryId: number) {
  memoryHighlightId.value = memoryId
  await openMemoryPanel()
}

async function deleteMemoryFromTrace(memoryId: number) {
  const item = memoryItems.value.find((current) => current.id === memoryId)
  if (item) {
    await removeMemory(item)
    return
  }
  if (!auth.isLoggedIn || memoryWorkspaceId.value == null) {
    await openMemoryPanel()
    return
  }
  const confirmed = window.confirm(`删除记忆 #${memoryId}？`)
  if (!confirmed) return
  memoryDeletingId.value = memoryId
  memoryError.value = null
  try {
    await deleteAgentWorkspaceMemory(memoryWorkspaceId.value, memoryId, { token: props.token })
    memoryItems.value = memoryItems.value.filter((current) => current.id !== memoryId)
  } catch (error) {
    memoryError.value = formatAgentError(error)
  } finally {
    memoryDeletingId.value = null
  }
}

async function changeMemoryWorkspace(rawId: string) {
  const id = Number(rawId)
  memoryWorkspaceId.value = Number.isFinite(id) && id > 0 ? id : null
  resetMemoryForm()
  memoryItems.value = []
  await loadMemoryItems()
}

function resetMemoryForm() {
  memoryEditingId.value = null
  memoryForm.value = {
    memoryType: "user_profile",
    title: "",
    content: "",
  }
}

function editMemory(item: AgentWorkspaceMemoryItem) {
  memoryEditingId.value = item.id
  memoryForm.value = {
    memoryType: item.memoryType || "custom",
    title: item.title || "",
    content: item.content || "",
  }
}

async function saveMemory() {
  if (!auth.isLoggedIn || memoryWorkspaceId.value == null || memorySaving.value) return
  const title = memoryForm.value.title.trim()
  const content = memoryForm.value.content.trim()
  if (!title || !content) {
    memoryError.value = "标题和内容都不能为空。"
    return
  }
  memorySaving.value = true
  memoryError.value = null
  try {
    const body = {
      memoryType: memoryForm.value.memoryType,
      title,
      content,
    }
    const saved = memoryEditingId.value == null
      ? await createAgentWorkspaceMemory(memoryWorkspaceId.value, body, { token: props.token })
      : await updateAgentWorkspaceMemory(memoryWorkspaceId.value, memoryEditingId.value, body, { token: props.token })
    memoryItems.value = [
      saved,
      ...memoryItems.value.filter((item) => item.id !== saved.id),
    ]
    resetMemoryForm()
  } catch (error) {
    memoryError.value = formatAgentError(error)
  } finally {
    memorySaving.value = false
  }
}

async function removeMemory(item: AgentWorkspaceMemoryItem) {
  if (!auth.isLoggedIn || memoryWorkspaceId.value == null || memoryDeletingId.value != null) return
  const confirmed = window.confirm(`删除这条记忆：${item.title || item.id}？`)
  if (!confirmed) return
  memoryDeletingId.value = item.id
  memoryError.value = null
  try {
    await deleteAgentWorkspaceMemory(memoryWorkspaceId.value, item.id, { token: props.token })
    memoryItems.value = memoryItems.value.filter((current) => current.id !== item.id)
    if (memoryEditingId.value === item.id) resetMemoryForm()
  } catch (error) {
    memoryError.value = formatAgentError(error)
  } finally {
    memoryDeletingId.value = null
  }
}

function clearPendingUploadPreview() {
  const preview = pendingUploadPreview.value
  if (preview?.url.startsWith("blob:")) {
    URL.revokeObjectURL(preview.url)
  }
  pendingUploadPreview.value = null
}

function materialKind(contentType?: string | null, name?: string | null): MaterialKind {
  const type = (contentType || "").toLowerCase()
  if (type.startsWith("image/") || isImageAttachment(contentType, name)) return "image"
  if (type.startsWith("video/")) return "video"
  if (type.startsWith("audio/")) return "audio"
  return "file"
}

function normalizeUrlAttachment(item: AgentUrlAttachment): AgentMaterialAttachment | null {
  const url = item.url?.trim()
  if (!url) return null
  const name = item.name || "素材附件"
  const record = item as AgentUrlAttachment & { assetId?: number; uploadedAt?: string; subtitle?: string }
  return {
    id: String(item.id ?? `${url}-${name}`),
    assetId: record.assetId,
    sessionId: item.sessionId ?? null,
    name,
    contentType: item.contentType ?? null,
    size: item.size ?? null,
    url,
    source: item.source || "url",
    kind: materialKind(item.contentType, name),
    previewUrl: item.contentType?.startsWith("image/") || isImageAttachment(item.contentType, name)
      ? resolveAgentFileUrl(url)
      : item.contentType?.startsWith("video/")
        ? resolveAgentFileUrl(url)
        : undefined,
    uploadedAt: record.uploadedAt,
    subtitle: record.subtitle,
  }
}

function uploadAssetToMaterialAttachment(asset: UserUploadAsset): AgentMaterialAttachment | null {
  const url = asset.url?.trim()
  if (!url) return null
  const name = asset.name || asset.fileId || "上传素材"
  const kind = materialKind(asset.contentType || asset.kind, name)
  const normalized = normalizeUrlAttachment({
    id: asset.fileId || String(asset.id),
    assetId: asset.id,
    name,
    contentType: asset.contentType ?? null,
    size: asset.size ?? null,
    url,
    source: "url",
  } as AgentUrlAttachment & { assetId?: number })
  return normalized ? { ...normalized, kind, uploadedAt: asset.createdAt || undefined } : null
}

function agentFileToMaterialAttachment(file: AgentFile): AgentMaterialAttachment | null {
  if (!file.downloadUrl) return null
  const attachment = normalizeUrlAttachment({
    id: file.id,
    sessionId: file.sessionId,
    name: file.originalFilename || "素材附件",
    contentType: file.contentType,
    size: file.fileSize,
    url: file.downloadUrl,
    source: "agent_file",
  })
  return attachment ? { ...attachment, uploadedAt: file.createdAt } : null
}

const pickerUploadList = useUploadHistoryList<AgentMaterialAttachment & { uploadedAt: string }>({
  getKind: () => null,
  getToken: () => props.token,
  canRequest: () => auth.isLoggedIn,
  getUserId: () => auth.user?.id,
  toHistoryItem: (asset) => {
    const item = uploadAssetToMaterialAttachment(asset)
    if (!item) return null
    return { ...item, uploadedAt: item.uploadedAt || new Date().toISOString() }
  },
})

const generatedMaterialList = useGeneratedMaterialList<AgentMaterialAttachment>({
  getKind: () => null,
  getToken: () => props.token,
  canRequest: () => auth.isLoggedIn,
  createAssetsFromTask: (task) => createMaterialAssets(task),
})

const recentAttachments = computed(() => pickerUploadList.items.value)
const materialAssets = computed(() => generatedMaterialList.assets.value)
const materialAssetsLoading = computed(() => generatedMaterialList.loading.value)
const pickerUploadLoadingMore = computed(() => pickerUploadList.loadingMore.value)
const pickerUploadHasMore = computed(() => pickerUploadList.hasMore.value)
const materialAssetsLoadingMore = computed(() => generatedMaterialList.loadingMore.value)
const materialAssetsHasMore = computed(() => generatedMaterialList.hasMore.value)

async function loadRecentAttachments() {
  await pickerUploadList.resetAndLoad()
  if (!auth.isLoggedIn) return
  try {
    const page = await fetchRecentAgentFiles(props.sessionId, { token: props.token })
    const serverItems = page.list
      .map(agentFileToMaterialAttachment)
      .filter((item): item is AgentMaterialAttachment => Boolean(item))
    pickerUploadList.prependItems(serverItems)
  } catch {
    // Keep upload-assets list when agent session files are temporarily unavailable.
  }
}

function rememberRecentAttachment(item: AgentUrlAttachment) {
  const normalized = normalizeUrlAttachment(item)
  if (!normalized) return
  pickerUploadList.rememberItem({ ...normalized, uploadedAt: new Date().toISOString() })
}

async function removeRecentAttachment(item: AgentMaterialAttachment) {
  pickerUploadList.removeItem(item)
  if (item.assetId && auth.isLoggedIn) {
    try {
      await deleteUploadAsset(item.assetId, { token: props.token })
    } catch {
      // The local recent list is already cleaned; stale server assets can be retried on refresh.
    }
    return
  }
  const fileId = typeof item.id === "number" ? item.id : Number(item.id)
  const sessionId = item.sessionId ?? sessionIdFromAgentFileUrl(item.url)
  if (!auth.isLoggedIn || !Number.isFinite(fileId) || !sessionId) return
  try {
    await deleteAgentFile(sessionId, fileId, { token: props.token })
  } catch {
    // The local recent list is already cleaned; stale server files can be retried on refresh.
  }
}

function sessionIdFromAgentFileUrl(url?: string | null): number | null {
  const match = String(url || "").match(/\/api\/v1\/agent\/sessions\/(\d+)\/files\/\d+\/content/)
  if (!match) return null
  const value = Number(match[1])
  return Number.isFinite(value) ? value : null
}

function shortReferenceName(name?: string | null) {
  const cleaned = (name || "图片").replace(/^@[^-]+-/, "").trim() || "图片"
  return cleaned.length > 14 ? `${cleaned.slice(0, 14)}…` : cleaned
}

function referenceAttachmentLabel(index: number, name?: string | null, contentType?: string | null) {
  return isImageAttachment(contentType, name) ? `@图片${index + 1}-${shortReferenceName(name)}` : (name || "素材附件")
}

function referenceLabelForUrlAttachment(
  catalog: Map<string, AgentReferenceMention>,
  item: AgentUrlAttachment,
  index: number,
) {
  const mention = findReferenceMentionByAsset(catalog, {
    assetKey: String(item.id ?? item.url),
    fileId: item.id,
    url: item.url,
  })
  return mention?.refLabel || referenceAttachmentLabel(index, item.refLabel || item.name, item.contentType)
}

function referenceLabelForAgentFile(
  catalog: Map<string, AgentReferenceMention>,
  file: AgentFile,
  index: number,
) {
  const mention = findReferenceMentionByAsset(catalog, {
    assetKey: `agent_file:${file.id}`,
    fileId: file.id,
    url: file.downloadUrl || "",
  })
  return mention?.refLabel || referenceAttachmentLabel(index, file.originalFilename, file.contentType)
}

function selectUrlAttachment(item: AgentUrlAttachment) {
  const normalized = normalizeUrlAttachment(item)
  if (!normalized) return
  urlAttachments.value = [
    ...urlAttachments.value.filter((entry) => entry.url !== normalized.url),
    normalized,
  ].slice(0, AGENT_REFERENCE_ATTACHMENT_LIMIT)
  rememberRecentAttachment(normalized)
}

function removeUrlAttachment(item: AgentUrlAttachment) {
  urlAttachments.value = urlAttachments.value.filter((entry) => entry.url !== item.url)
}

function addReferenceAttachment(payload: ChatAssetDragPayload) {
  const normalized = dragPayloadToUrlAttachment(payload)
  if (urlAttachments.value.some((entry) => entry.url === normalized.url)) return
  urlAttachments.value = [...urlAttachments.value, normalized].slice(0, AGENT_REFERENCE_ATTACHMENT_LIMIT)
}

function formatMaterialTime(value?: string | null): string {
  if (!value) return ""
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ""
  return date.toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })
}

function createMaterialAssets(task: TaskDetail): AgentMaterialAttachment[] {
  const content = task.result?.contentText || ""
  if (!content.trim()) return []
  const blocks = buildTaskResultBlocks(content, task)
  const taskTitle = task.toolName || task.toolCode || "历史任务"
  const subtitle = `${task.taskNo || `#${task.taskId}`} · ${formatMaterialTime(task.finishedAt || task.createdAt)}`
  const assets: AgentMaterialAttachment[] = []
  for (const block of blocks) {
    if (block.type === "image") {
      block.images.forEach((image, index) => {
        assets.push({
          id: `${task.taskId}-image-${index}`,
          kind: "image",
          name: image.label || taskTitle,
          contentType: "image/*",
          url: image.url,
          previewUrl: image.url,
          source: "url",
          subtitle,
        })
      })
    } else if (block.type === "video") {
      assets.push({
        id: `${task.taskId}-video`,
        kind: "video",
        name: block.title || taskTitle,
        contentType: "video/*",
        url: block.url,
        previewUrl: block.url,
        source: "url",
        subtitle,
      })
    } else if (block.type === "audio") {
      resolveAudioTracks(block).forEach((track, index) => {
        assets.push({
          id: `${task.taskId}-audio-${index}`,
          kind: "audio",
          name: track.title || block.title || taskTitle,
          contentType: "audio/*",
          url: track.url,
          previewUrl: track.coverUrl || track.url,
          source: "url",
          subtitle,
        })
      })
    }
  }
  return assets
}

async function loadMaterialAssets() {
  await generatedMaterialList.resetAndLoad()
}

async function loadMorePickerUploads() {
  await pickerUploadList.loadMore()
}

async function loadMoreMaterialAssets() {
  await generatedMaterialList.loadMore()
}

async function uploadFiles(
  selectedFiles: File[],
  options: { autoSelect?: boolean } = {},
) {
  if (selectedFiles.length === 0 || !auth.isLoggedIn || uploading.value) return
  const autoSelect = options.autoSelect ?? false
  if (!autoSelect) {
    await uploadMaterialFiles(selectedFiles)
    return
  }
  clearPendingUploadPreview()
  const selected = selectedFiles[0]!
  if (selectedFiles.length === 1 && isImageAttachment(selected.type, selected.name)) {
    pendingUploadPreview.value = { name: selected.name, url: URL.createObjectURL(selected) }
  }
  uploading.value = true
  try {
    const availableSlots = Math.max(0, AGENT_REFERENCE_ATTACHMENT_LIMIT - files.value.length - urlAttachments.value.length)
    for (const file of selectedFiles.slice(0, availableSlots)) {
      const uploaded = await uploadAgentFile(props.sessionId, file, { token: props.token })
      uploadedFileCache.value = { ...uploadedFileCache.value, [uploaded.id]: uploaded }
      if (uploaded.downloadUrl) {
        rememberRecentAttachment({
          id: uploaded.id,
          sessionId: uploaded.sessionId,
          name: uploaded.originalFilename,
          contentType: uploaded.contentType,
          size: uploaded.fileSize,
          url: uploaded.downloadUrl,
          source: "agent_file",
        })
      }
      if (autoSelect) {
        files.value = [...files.value.filter((item) => item.id !== uploaded.id), uploaded]
        composerRef.value?.insertAgentFileChip(uploaded)
      }
    }
  } finally {
    clearPendingUploadPreview()
    uploading.value = false
  }
}

async function uploadMaterialFiles(selectedFiles: File[]) {
  clearPendingUploadPreview()
  const selected = selectedFiles[0]!
  if (selectedFiles.length === 1 && isImageAttachment(selected.type, selected.name)) {
    pendingUploadPreview.value = { name: selected.name, url: URL.createObjectURL(selected) }
  }
  uploading.value = true
  try {
    const availableSlots = Math.max(0, AGENT_REFERENCE_ATTACHMENT_LIMIT - urlAttachments.value.length)
    for (const file of selectedFiles.slice(0, availableSlots)) {
      const uploaded = await uploadToolFile(file, { token: props.token })
      const attachment = normalizeUrlAttachment({
        id: uploaded.fileId,
        assetId: uploaded.assetId,
        name: uploaded.name || file.name || uploaded.fileId,
        contentType: uploaded.contentType || file.type || null,
        size: uploaded.size ?? file.size,
        url: uploaded.url,
        source: "url",
      } as AgentUrlAttachment & { assetId?: number })
      if (!attachment) continue
      const withTime = { ...attachment, uploadedAt: new Date().toISOString() }
      rememberRecentAttachment(withTime)
      urlAttachments.value = [
        ...urlAttachments.value.filter((entry) => entry.url !== withTime.url),
        withTime,
      ].slice(0, AGENT_REFERENCE_ATTACHMENT_LIMIT)
      composerRef.value?.insertUrlAttachmentChip(withTime)
    }
  } finally {
    clearPendingUploadPreview()
    uploading.value = false
  }
}

function syncUploadedFiles(fileIds: number[]) {
  const next: AgentFile[] = []
  for (const fileId of fileIds) {
    const cached = uploadedFileCache.value[fileId] || files.value.find((item) => item.id === fileId)
    if (cached) next.push(cached)
  }
  files.value = next
}

async function handleFileSelected(event: Event) {
  const target = event.target as HTMLInputElement
  const selectedFiles = Array.from(target.files || [])
  target.value = ""
  await uploadFiles(selectedFiles)
}

function openAttachmentPreview(payload: { name: string; url: string; contentType?: string | null }) {
  const url = resolveAgentFileUrl(payload.url)
  if (!url) return
  openAssetPreview({
    id: `attachment-${payload.name}`,
    kind: "image",
    title: "图片附件",
    url,
  })
}

async function removeFile(file: AgentFile) {
  if (!auth.isLoggedIn || removingFileId.value != null) return
  removingFileId.value = file.id
  try {
    await deleteAgentFile(props.sessionId, file.id, { token: props.token })
    files.value = files.value.filter((item) => item.id !== file.id)
  } catch (error) {
    applyAgentFailure(error)
  } finally {
    removingFileId.value = null
  }
}

async function submitMessage(content = input.value) {
  const composerSnapshot = composerRef.value?.getComposerSnapshot()
  const text = (composerSnapshot?.text ?? content).trim()
  if (!text && files.value.length === 0 && urlAttachments.value.length === 0) return
  if (!auth.isLoggedIn || sending.value || editingRegenerating.value || hasActiveRun.value) return
  stickToBottom.value = true
  if (props.modelsLoading) {
    agentError.value = "模型列表仍在加载，请稍等一下再发送。"
    return
  }
  if (!props.modelConfigId) {
    agentError.value = "请先选择一个 Agent 模型。"
    return
  }
  const autoReferenceForSubmission = autoMountedReference.value
  sending.value = true
  agentError.value = null
  confirmationError.value = null
  lastFailedRunId.value = null
  runConnectionStatus.value = "running"
  const submittedParentMessageId = messages.value.at(-1)?.id ?? null
  try {
    const submittedFiles = [...files.value]
    const submittedUrlAttachments = mergeAutoMountedAttachment([...urlAttachments.value], autoReferenceForSubmission)
    const submittedReferenceCatalog = buildAttachmentLabelCatalog(
      submittedUrlAttachments,
      submittedFiles,
      collectSessionAssets(messages.value),
    )
    const submittedPreferredToolCode = selectedToolCode.value
    const submittedMentions = mergeAutoMountedMention(
      [...(composerSnapshot?.mentions ?? draftReferenceMentions.value)],
      autoReferenceForSubmission,
    )
    const submittedContentParts = mergeAutoMountedContentParts(
      composerSnapshot?.contentParts ?? [],
      text,
      autoReferenceForSubmission,
    )
    const submittedPositionalPrompt = autoReferenceForSubmission?.url
      ? submittedContentParts
        .map((part) => part.type === "text" ? part.text : `{${part.asset_key}}`)
        .join("")
      : composerSnapshot?.positionalPrompt
    const submittedGlobalFileIds = globalFileIdsFor(submittedFiles, submittedUrlAttachments)
    input.value = ""
    draftReferenceMentions.value = []
    const submittedAttachmentJson = messageContentJsonForFiles(
      submittedFiles,
      submittedUrlAttachments,
      text,
      submittedMentions,
      {
        contentParts: submittedContentParts,
        positionalPrompt: submittedPositionalPrompt,
        globalFileIds: submittedGlobalFileIds,
      },
    )
    const optimisticMessageId = Date.now()
    files.value = []
    urlAttachments.value = []
    selectedToolCode.value = null
    messages.value.push({
      id: optimisticMessageId,
      sessionId: props.sessionId,
      role: "USER",
      contentText: text,
      contentJson: submittedAttachmentJson,
      editedAt: null,
      createdAt: new Date().toISOString(),
    })
    // 发送后立即滚动到底部，避免用户输入后仍停留在当前视图。
    // 流式过程中若仍处于贴底状态，也会在 messages 更新时继续保持贴底。
    await scrollBottom(true)
    events.value = []
    const res = await sendAgentMessage(
      props.sessionId,
      {
        content: text,
        clientRequestId: randomUUID(),
        modelConfigId: props.modelConfigId ?? null,
        preferredToolCode: submittedPreferredToolCode,
        intelligenceLevel: intelligenceLevel.value,
        parentMessageId: submittedParentMessageId,
        fileIds: submittedFiles.map((item) => item.id),
        urlAttachments: submittedUrlAttachments.map((item, index) => ({
          id: item.id,
          name: referenceLabelForUrlAttachment(submittedReferenceCatalog, item, index),
          contentType: item.contentType,
          size: item.size,
          url: item.url,
          source: item.source || "url",
        })),
        referenceMentions: referenceMentionsForApi(submittedMentions),
        globalFileIds: submittedGlobalFileIds,
        contentParts: submittedContentParts,
        positionalPrompt: submittedPositionalPrompt,
      },
      { token: props.token },
    )
    activeRunId.value = res.runId
    if (submittedAttachmentJson) {
      submittedAttachmentJsonByRunId.value = {
        ...submittedAttachmentJsonByRunId.value,
        [res.runId]: submittedAttachmentJson,
      }
    }
    messages.value = messages.value.map((message) =>
      message.id === optimisticMessageId
        ? { ...message, id: res.messageId, runId: res.runId }
        : message,
    )
    await waitForRunComplete(res.runId)
  } catch (error) {
    await loadFiles()
    runConnectionStatus.value = "failed"
    activeRunId.value = null
    if (error instanceof ApiBusinessError && error.code === "AGENT_ACTIVE_RUN_LIMIT") {
      showActiveRunLimitHint.value = true
      const runId = await discoverActiveRunId(props.sessionId)
      if (runId != null) recoveryRunId.value = runId
    }
    applyAgentFailure(error)
  } finally {
    sending.value = false
    await scrollBottom()
  }
}

async function loadPreviewTools() {
  if (!auth.isLoggedIn) return
  try {
    const response = await fetchTools({ token: props.token, query: { pageNo: 1, pageSize: 120 } })
    previewTools.value = response.list
  } catch {
    previewTools.value = []
  }
}

async function loadAgentTools() {
  if (!auth.isLoggedIn || agentToolsLoading.value) return
  agentToolsLoading.value = true
  try {
    agentTools.value = await fetchAgentTools({ token: props.token })
  } catch {
    agentTools.value = []
  } finally {
    agentToolsLoading.value = false
  }
}

async function updateToolPreference(payload: {
  toolCode: string
  autoCallEnabled?: boolean
  disabled?: boolean
}) {
  if (!auth.isLoggedIn) return
  const previousTools = agentTools.value.map((tool) => ({ ...tool }))
  const previousSelectedToolCode = selectedToolCode.value
  agentError.value = null
  agentTools.value = agentTools.value.map((tool) => {
    if (tool.toolCode !== payload.toolCode) return tool
    const disabled = payload.disabled ?? Boolean(tool.disabled)
    return {
      ...tool,
      disabled,
      autoCallEnabled: disabled ? false : (payload.autoCallEnabled ?? Boolean(tool.autoCallEnabled)),
    }
  })
  if (payload.disabled === true && selectedToolCode.value === payload.toolCode) {
    selectedToolCode.value = null
  }
  try {
    const updated = await updateAgentToolPreference(payload.toolCode, payload, { token: props.token })
    agentTools.value = agentTools.value.map((tool) =>
      tool.toolCode === payload.toolCode
        ? {
            ...tool,
            autoCallEnabled: updated.autoCallEnabled,
            disabled: updated.disabled,
          }
        : tool,
    )
    if (updated.disabled && selectedToolCode.value === payload.toolCode) {
      selectedToolCode.value = null
    }
  } catch (error) {
    agentTools.value = previousTools
    selectedToolCode.value = previousSelectedToolCode
    applyAgentFailure(error)
  }
}

async function retryFailedRun() {
  if (!auth.isLoggedIn || retryingRun.value || editingRegenerating.value || regeneratingMessageId.value != null || hasActiveRun.value || lastFailedRunId.value == null) return
  if (props.modelsLoading) {
    agentError.value = "模型列表仍在加载，请稍等一下再重试。"
    return
  }
  if (!props.modelConfigId) {
    agentError.value = "请先选择一个 Agent 模型再重试。"
    return
  }
  const sourceRunId = lastFailedRunId.value
  retryingRun.value = true
  agentError.value = null
  confirmationError.value = null
  runConnectionStatus.value = "running"
  events.value = []
  try {
    const res = await regenerateAgentRun(
      sourceRunId,
      {
        clientRequestId: randomUUID(),
        modelConfigId: props.modelConfigId ?? null,
      },
      { token: props.token },
    )
    activeRunId.value = res.runId
    lastFailedRunId.value = null
    await waitForRunComplete(res.runId)
  } catch (error) {
    runConnectionStatus.value = "failed"
    applyAgentFailure(error)
  } finally {
    retryingRun.value = false
    await scrollBottom()
  }
}

async function regenerateAssistantMessage(message: AgentMessage) {
  if (!auth.isLoggedIn || message.role !== "ASSISTANT" || !message.runId) return
  if (retryingRun.value || editingRegenerating.value || regeneratingMessageId.value != null || sending.value || hasActiveRun.value) return
  if (props.modelsLoading) {
    agentError.value = "模型列表仍在加载，请稍等一下再重新生成。"
    return
  }
  if (!props.modelConfigId) {
    agentError.value = "请先选择一个 Agent 模型再重新生成。"
    return
  }

  regeneratingMessageId.value = message.id
  agentError.value = null
  confirmationError.value = null
  lastFailedRunId.value = null
  runConnectionStatus.value = "running"
  events.value = []
  try {
    const res = await regenerateAgentRun(
      message.runId,
      {
        clientRequestId: randomUUID(),
        modelConfigId: props.modelConfigId ?? null,
      },
      { token: props.token },
    )
    activeRunId.value = res.runId
    messages.value = messages.value.filter((item) => item.id < message.id)
    await waitForRunComplete(res.runId)
  } catch (error) {
    runConnectionStatus.value = "failed"
    applyAgentFailure(error)
    await refreshMessages()
  } finally {
    regeneratingMessageId.value = null
    await scrollBottom()
  }
}

async function copyMessage(message: AgentMessage) {
  const text = message.contentText?.trim()
  if (!text) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const textarea = document.createElement("textarea")
      textarea.value = text
      textarea.style.position = "fixed"
      textarea.style.opacity = "0"
      document.body.appendChild(textarea)
      textarea.focus()
      textarea.select()
      document.execCommand("copy")
      document.body.removeChild(textarea)
    }
    copiedMessageId.value = message.id
    window.setTimeout(() => {
      if (copiedMessageId.value === message.id) copiedMessageId.value = null
    }, 1200)
  } catch (error) {
    agentError.value = "复制失败，请稍后重试。"
  }
}

function startEditMessage(message: AgentMessage) {
  if (message.role !== "USER" || hasActiveRun.value || sending.value || editingRegenerating.value || regeneratingMessageId.value != null) return
  editingMessageId.value = message.id
  editingMessageDraft.value = message.contentText
  agentError.value = null
}

function cancelEditMessage() {
  editingMessageId.value = null
  editingMessageDraft.value = ""
}

async function submitEditedMessage(message: AgentMessage) {
  if (!auth.isLoggedIn || message.role !== "USER" || editingRegenerating.value || regeneratingMessageId.value != null || sending.value || hasActiveRun.value) return
  if (props.modelsLoading) {
    agentError.value = "模型列表仍在加载，请稍等一下再发送。"
    return
  }
  if (!props.modelConfigId) {
    agentError.value = "请先选择一个 Agent 模型。"
    return
  }
  const text = editingMessageDraft.value.trim()
  if (!text) {
    agentError.value = "消息内容不能为空。"
    return
  }
  if (text === message.contentText.trim()) {
    const sourceRunId = findRunIdForUserMessage(message)
    if (!sourceRunId) {
      agentError.value = "这条消息暂时没有可重跑的 Agent 运行，请稍后刷新再试。"
      return
    }
    editingRegenerating.value = true
    agentError.value = null
    confirmationError.value = null
    lastFailedRunId.value = null
    runConnectionStatus.value = "running"
    events.value = []
    editingMessageId.value = null
    editingMessageDraft.value = ""
    try {
      captureCurrentBranchVariant(message.id)
      beginPendingEditedBranch(message.id, message)
      const res = await regenerateAgentRun(
        sourceRunId,
        {
          clientRequestId: crypto.randomUUID(),
          modelConfigId: props.modelConfigId ?? null,
        },
        { token: props.token },
      )
      activeRunId.value = res.runId
      messages.value = messages.value.filter((item) => item.id <= message.id)
      await waitForRunComplete(res.runId)
      await refreshMessages()
      syncActiveBranchVariantFromVisibleMessages(message.id)
    } catch (error) {
      const group = branchGroups.value[message.id]
      if (group && group.variants.length > 1) {
        group.variants.pop()
        group.activeIndex = Math.max(0, group.variants.length - 1)
        branchGroups.value = { ...branchGroups.value, [message.id]: group }
        persistMessageBranches()
      }
      runConnectionStatus.value = "failed"
      activeRunId.value = null
      applyAgentFailure(error)
      await refreshMessages()
    } finally {
      editingRegenerating.value = false
      await scrollBottom()
    }
    return
  }

  const previousText = message.contentText
  const previousEditedAt = message.editedAt ?? null
  captureCurrentBranchVariant(message.id)
  editingRegenerating.value = true
  agentError.value = null
  confirmationError.value = null
  lastFailedRunId.value = null
  runConnectionStatus.value = "running"
  events.value = []
  message.contentText = text
  message.editedAt = new Date().toISOString()
  beginPendingEditedBranch(message.id, message)
  editingMessageId.value = null
  editingMessageDraft.value = ""
  try {
    const res = await editRegenerateAgentMessage(
      props.sessionId,
      message.id,
      {
        content: text,
        clientRequestId: randomUUID(),
        modelConfigId: props.modelConfigId ?? null,
      },
      { token: props.token },
    )
    activeRunId.value = res.runId
    messages.value = messages.value.filter((item) => item.id <= message.id)
    await waitForRunComplete(res.runId)
    await refreshMessages()
    syncActiveBranchVariantFromVisibleMessages(message.id)
  } catch (error) {
    message.contentText = previousText
    message.editedAt = previousEditedAt
    const group = branchGroups.value[message.id]
    if (group && group.variants.length > 1) {
      group.variants.pop()
      group.activeIndex = Math.max(0, group.variants.length - 1)
      branchGroups.value = { ...branchGroups.value, [message.id]: group }
      persistMessageBranches()
    }
    runConnectionStatus.value = "failed"
    activeRunId.value = null
    applyAgentFailure(error)
    await refreshMessages()
  } finally {
    editingRegenerating.value = false
    await scrollBottom()
  }
}

function formatAgentError(error: unknown) {
  if (error instanceof ApiBusinessError && error.code === "MODEL_CALL_FAILED") {
    const detail = error.message ? `后端返回：${error.message}` : "后端没有返回更多细节。"
    return `模型连接验证失败。请在管理端检查 provider、baseUrl、API Key、模型名称和 MiniMax Group ID 后重试。${detail}`
  }
  if (error instanceof ApiBusinessError && error.code === "MODEL_RISK_CONTROL_REJECTED") {
    return "您的提示词包含违禁词"
  }
  if (error instanceof ApiBusinessError && error.code === "AGENT_ACTIVE_RUN_LIMIT") {
    return "上一次 Agent 任务尚未结束，占用了运行名额。请点击下方「取消进行中的任务」后再发送；若任务仍在进行，也可等待其完成。"
  }
  if (error instanceof ApiBusinessError && error.code === "AGENT_RATE_LIMITED") {
    return "Agent 请求过于频繁，请稍后重试。"
  }
  if (error instanceof ApiBusinessError && isCreditInsufficientCode(error.code)) {
    return formatCreditInsufficientError(error)
  }
  if (error instanceof ApiBusinessError) {
    return error.message || error.code
  }
  return error instanceof Error ? error.message : "Agent 请求失败，请稍后重试"
}

function applyAgentFailure(
  error: unknown,
  options?: { errorCode?: string; errorMessage?: string },
) {
  const errorCode = options?.errorCode
  const errorMessage =
    options?.errorMessage ??
    (error instanceof ApiBusinessError ? error.message : error instanceof Error ? error.message : undefined)
  if (isCreditInsufficient(error, errorCode, errorMessage)) {
    agentError.value = "算力不足，请充值后继续"
    return
  }
  if (error instanceof ApiBusinessError) {
    agentError.value = formatAgentError(error)
    return
  }
  if (errorCode || errorMessage) {
    agentError.value = formatAgentRunFailure(errorCode, errorMessage)
    return
  }
  agentError.value = error instanceof Error ? error.message : "Agent 请求失败，请稍后重试"
}

function isTerminalRunStatus(status: AgentRunStatus) {
  return (
    status === "SUCCESS" ||
    status === "FAILED" ||
    status === "CANCELLED" ||
    status === "TIMEOUT"
  )
}

function delay(ms: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, ms))
}

function stopRunEventStream() {
  runStreamAbort?.abort()
  runStreamAbort = null
}

function stopRunStatusWatchdog() {
  if (runStatusWatchdog == null) return
  window.clearInterval(runStatusWatchdog)
  runStatusWatchdog = null
}

function stopStreamingAnimationTimer() {
  if (streamingAnimationTimer == null) return
  window.clearTimeout(streamingAnimationTimer)
  streamingAnimationTimer = null
}

function animateCompletedAssistantMessage(runId: number) {
  const assistant = messages.value.find((message) => message.role === "ASSISTANT" && message.runId === runId)
  if (!assistant?.contentText?.trim() || isStructuredMediaContent(assistant.contentText)) return
  stopStreamingAnimationTimer()
  streamingAssistantMessageId.value = assistant.id
  const duration = Math.min(Math.max(assistant.contentText.length * 4 + 240, 600), 3200)
  streamingAnimationTimer = window.setTimeout(() => {
    if (streamingAssistantMessageId.value === assistant.id) {
      streamingAssistantMessageId.value = null
    }
    streamingAnimationTimer = null
  }, duration)
}

function startRunStatusWatchdog(runId: number) {
  stopRunStatusWatchdog()
  runStatusWatchdog = window.setInterval(() => {
    if (activeRunId.value !== runId) {
      stopRunStatusWatchdog()
      return
    }
    if (runConnectionStatus.value !== "running" && runConnectionStatus.value !== "awaiting_confirmation") {
      stopRunStatusWatchdog()
      return
    }
    void reconcileRunStatus(runId)
  }, 1500)
}

async function reconcileRunStatus(runId: number) {
  try {
    const run = await fetchAgentRun(runId, { token: props.token })
    if (run.status === "WAITING_USER_CONFIRMATION") {
      await syncRunEvents(runId, { replayRenderableEvents: true })
      runConnectionStatus.value = "awaiting_confirmation"
      await scrollBottom()
      return
    }
    if (!isTerminalRunStatus(run.status)) return
    await settleTerminalRun(runId, run)
    stopRunEventStream()
  } catch {
    // Keep the live stream as the source of truth while watchdog polling is flaky.
  }
}

function streamingMessageId(runId: number) {
  return -Math.abs(runId)
}

function streamingMessageForRun(runId: number) {
  const tempId = streamingMessageId(runId)
  return messages.value.find(
    (message) => message.role === "ASSISTANT" && message.runId === runId && message.id === tempId,
  )
}

function ensureStreamingAssistantMessage(runId: number) {
  const existing = streamingMessageForRun(runId)
  if (existing) return existing
  const tempId = streamingMessageId(runId)
  stopStreamingAnimationTimer()
  const message: AgentMessage = {
    id: tempId,
    sessionId: props.sessionId,
    role: "ASSISTANT",
    contentText: "",
    runId,
    createdAt: new Date().toISOString(),
  }
  streamingAssistantMessageId.value = tempId
  messages.value = [...messages.value.filter((item) => item.id !== tempId), message]
  return message
}

function clearStreamingAssistantMessage(runId: number, options?: { preserveReadableText?: boolean }) {
  const tempId = streamingMessageId(runId)
  const tempMessage = streamingMessageForRun(runId)
  if (streamingAssistantMessageId.value === tempId) {
    streamingAssistantMessageId.value = null
  }
  if (options?.preserveReadableText && tempMessage?.contentText?.trim() && !isStructuredMediaContent(tempMessage.contentText)) {
    return
  }
  messages.value = messages.value.filter((message) => message.id !== tempId)
}

function appendStreamingAssistantDelta(runId: number, delta: string) {
  if (!delta) return
  if (isStructuredMediaContent(delta)) return
  const message = ensureStreamingAssistantMessage(runId)
  message.contentText += delta
  stickToBottom.value = true
  void scrollBottom(true)
}

function completeStreamingAssistantMessage(runId: number, content: string) {
  if (!content) return
  const message = ensureStreamingAssistantMessage(runId)
  message.contentText = content
  stickToBottom.value = true
  void scrollBottom(true)
}

async function syncRunEvents(runId: number, options?: { replayRenderableEvents?: boolean }) {
  const cachedEvents = runEventsByRunId.value[runId] ?? []
  const afterEventId = cachedEvents.length ? cachedEvents.at(-1)!.id : undefined
  const res = await fetchAgentRunEvents(runId, { token: props.token, afterEventId })
  res.list.forEach((event) => {
    if (options?.replayRenderableEvents) {
      handleStreamedRunEvent(runId, event, { fromSync: true })
    } else {
      appendRunEvent(event)
    }
  })
}

async function waitForRunComplete(runId: number) {
  runConnectionStatus.value = "running"
  stopRunEventStream()
  startRunStatusWatchdog(runId)
  const controller = new AbortController()
  runStreamAbort = controller
  const cachedEvents = runEventsByRunId.value[runId] ?? []
  const afterEventId = cachedEvents.length ? cachedEvents.at(-1)!.id : undefined
  try {
    await streamAgentRunEvents(runId, {
      token: props.token,
      signal: controller.signal,
      afterEventId,
      onEvent: (event) => handleStreamedRunEvent(runId, event),
    })
  } catch (error) {
    if (controller.signal.aborted) return
    await pollRunUntilComplete(runId)
    return
  } finally {
    if (runStreamAbort === controller) runStreamAbort = null
  }

  try {
    const run = await fetchAgentRun(runId, { token: props.token })
    if (run.status === "WAITING_USER_CONFIRMATION") {
      await syncRunEvents(runId, { replayRenderableEvents: true })
      runConnectionStatus.value = "awaiting_confirmation"
      await scrollBottom()
      return
    }
    if (isTerminalRunStatus(run.status)) {
      await settleTerminalRun(runId, run)
      return
    }
  } catch (error) {
    runConnectionStatus.value = "failed"
    applyAgentFailure(error)
    recoveryRunId.value = runId
    lastFailedRunId.value = runId
    activeRunId.value = null
    return
  }

  await pollRunUntilComplete(runId)
}

function handleStreamedRunEvent(runId: number, event: AgentRunEvent, options?: { fromSync?: boolean }) {
  const alreadySeen = events.value.some((item) => item.id === event.id)
  appendRunEvent(event)
  if (alreadySeen) return

  if (isTerminalRunEvent(event)) {
    settleRunStatus()
    clearStreamingAssistantMessage(runId, { preserveReadableText: event.eventType === "run.failed" })
    stopRunStatusWatchdog()
    if (!options?.fromSync) stopRunEventStream()
    void finalizeTerminalRunFromEvent(runId, event)
    return
  }

  if (event.eventType === "message.delta") {
    const payload = parseEventJson(event.eventJson)
    const delta = typeof payload.delta === "string" ? payload.delta : event.eventText ?? ""
    appendStreamingAssistantDelta(runId, delta)
    return
  }
  if (event.eventType === "message.completed") {
    const payload = parseEventJson(event.eventJson)
    const content = typeof payload.content === "string" ? payload.content : event.eventText ?? ""
    completeStreamingAssistantMessage(runId, content)
    return
  }
  if (event.eventType === "tool.confirmation_required") {
    runConnectionStatus.value = "awaiting_confirmation"
    stopRunEventStream()
  }
}

async function finalizeTerminalRunFromEvent(runId: number, event: AgentRunEvent) {
  if (terminalEventFinalizingRunIds.has(runId)) return
  terminalEventFinalizingRunIds.add(runId)
  try {
    const run = await fetchAgentRun(runId, { token: props.token })
    await syncRunEvents(runId, { replayRenderableEvents: true })
    settleRunStatus(run)
    if (run.status === "FAILED" || run.status === "TIMEOUT") {
      lastFailedRunId.value = run.id
    }
    await refreshMessages({ preserveStreamingRunId: run.status === "FAILED" || run.status === "TIMEOUT" ? runId : undefined })
  } catch {
    settleRunStatus()
    if (event.eventType === "run.failed") {
      lastFailedRunId.value = event.runId
    }
    await refreshMessages({ preserveStreamingRunId: event.eventType === "run.failed" ? runId : undefined })
  } finally {
    clearStreamingAssistantMessage(runId, { preserveReadableText: true })
    stopRunStatusWatchdog()
    terminalEventFinalizingRunIds.delete(runId)
    await scrollBottom()
  }
}

async function settleTerminalRun(runId: number, run: AgentRun) {
  await syncRunEvents(runId, { replayRenderableEvents: true })
  settleRunStatus(run)
  if (run.status === "FAILED" || run.status === "TIMEOUT") {
    lastFailedRunId.value = run.id
  }
  await refreshMessages({ preserveStreamingRunId: run.status === "FAILED" || run.status === "TIMEOUT" ? runId : undefined })
  clearStreamingAssistantMessage(runId, { preserveReadableText: true })
  if (run.status === "SUCCESS") {
    animateCompletedAssistantMessage(runId)
    // 任务成功完成时，触发 credits:updated 事件以更新全局算力显示
    window.dispatchEvent(new CustomEvent("credits:updated"))
  }
  stopRunStatusWatchdog()
  await scrollBottom()
}

async function pollRunUntilComplete(runId: number) {
  runConnectionStatus.value = "running"
  const POLL_INTERVAL_MS = 1200
  const MAX_WAIT_MS = 5 * 60 * 1000
  const startTime = Date.now()
  try {
    while (Date.now() - startTime < MAX_WAIT_MS) {
      const run = await fetchAgentRun(runId, { token: props.token })
      if (run.status === "WAITING_USER_CONFIRMATION") {
        await syncRunEvents(runId, { replayRenderableEvents: true })
        runConnectionStatus.value = "awaiting_confirmation"
        await scrollBottom()
        return
      }
      if (isTerminalRunStatus(run.status)) {
        await settleTerminalRun(runId, run)
        return
      }
      await delay(POLL_INTERVAL_MS)
    }
    runConnectionStatus.value = "failed"
    agentError.value = "Agent 运行超时，请稍后重试。"
    recoveryRunId.value = runId
    lastFailedRunId.value = runId
    activeRunId.value = null
  } catch (error) {
    runConnectionStatus.value = "failed"
    applyAgentFailure(error)
    recoveryRunId.value = runId
    lastFailedRunId.value = runId
    activeRunId.value = null
  }
}

async function confirmTool(eventId: number, toolCode: string, approved: boolean) {
  if (!auth.isLoggedIn || !activeRunId.value) return
  confirmationError.value = null
  const nextConfirming = new Set(confirmingEventIds.value)
  nextConfirming.add(eventId)
  confirmingEventIds.value = nextConfirming
  try {
    await confirmAgentTool(
      activeRunId.value,
      { toolCode, approved, autoCallEnabled: approved && rememberTool.value },
      { token: props.token },
    )
    const nextDismissed = new Set(dismissedConfirmationIds.value)
    nextDismissed.add(eventId)
    dismissedConfirmationIds.value = nextDismissed
    if (approved) void waitForRunComplete(activeRunId.value)
    else {
      await syncRunEvents(activeRunId.value)
      await refreshMessages()
      const run = await fetchAgentRun(activeRunId.value, { token: props.token })
      if (isTerminalRunStatus(run.status)) {
        settleRunStatus(run)
      }
    }
  } catch (error) {
    confirmationError.value = formatAgentError(error)
  } finally {
    const doneConfirming = new Set(confirmingEventIds.value)
    doneConfirming.delete(eventId)
    confirmingEventIds.value = doneConfirming
  }
}

async function refreshMessages(options?: { preserveStreamingRunId?: number }) {
  if (!auth.isLoggedIn) return
  const preserved = options?.preserveStreamingRunId != null ? streamingMessageForRun(options.preserveStreamingRunId) : undefined
  const messageRes = await fetchAgentMessages(props.sessionId, { token: props.token })
  const mergedMessages = messageRes.list.map((message) => {
    if (message.role !== "USER" || message.runId == null || message.contentJson) return message
    const cachedJson = submittedAttachmentJsonByRunId.value[message.runId]
    return cachedJson ? { ...message, contentJson: cachedJson } : message
  })
  if (
    preserved?.contentText?.trim() &&
    !isStructuredMediaContent(preserved.contentText) &&
    !mergedMessages.some((message) => message.runId === preserved.runId && message.role === "ASSISTANT")
  ) {
    messages.value = applyActiveBranchView([...mergedMessages, preserved])
    return
  }
  messages.value = applyActiveBranchView(mergedMessages)
}

function appendRunEvent(event: AgentRunEvent) {
  const cached = runEventsByRunId.value[event.runId] ?? []
  const alreadyCached = cached.some((item) => item.id === event.id)
  if (!alreadyCached) {
    runEventsByRunId.value = {
      ...runEventsByRunId.value,
      [event.runId]: [...cached, event],
    }
  }
  if (events.value.some((item) => item.id === event.id)) return
  events.value.push(event)
  if (event.eventType === "run.started") {
    agentError.value = null
    lastFailedRunId.value = null
    runConnectionStatus.value = "running"
  }
  if (event.eventType === "run.completed") {
    lastFailedRunId.value = null
  }
  if (event.eventType === "run.failed") {
    lastFailedRunId.value = event.runId
    const payload = parseEventJson(event.eventJson)
    const errorCode = typeof payload.errorCode === "string" ? payload.errorCode : ""
    const errorMessage = typeof payload.errorMessage === "string" ? payload.errorMessage : event.eventText
    if (isCreditInsufficient(null, errorCode, errorMessage)) {
      applyAgentFailure(null, { errorCode, errorMessage })
      return
    }
    if (errorCode === "AGENT_SECURITY_REJECTED") {
      agentError.value = errorMessage || "这条请求包含敏感指令，Agent 已拒绝执行。"
      return
    }
    if (errorCode === "AGENT_RUN_BUDGET_EXCEEDED") {
      agentError.value = errorMessage || "本次运行超出算力预算。"
      return
    }
    if (errorCode === "AGENT_MODEL_CALL_LIMIT" || errorCode === "AGENT_TOOL_CALL_LIMIT") {
      agentError.value = errorMessage || "已达到安全限制，系统停止执行。"
      return
    }
    if (errorCode === "AGENT_SERVICE_NOTIFY_FAILED") {
      agentError.value = errorMessage || "Agent 服务暂时不可用，请稍后重试。"
      return
    }
    if (isCreditInsufficientCode(errorCode)) {
      agentError.value = errorMessage || "可用算力不足，请前往「会员与算力」充值后再试。"
      return
    }
    if (errorCode === "MODEL_RISK_CONTROL_REJECTED") {
      agentError.value = "您的提示词包含违禁词"
      return
    }
    if (errorCode === "MODEL_CALL_FAILED") {
      agentError.value = errorMessage || "模型调用失败，请稍后重试。"
      return
    }
    agentError.value = errorMessage || "Agent 运行失败，请稍后再试。"
  }
}

function settleRunStatus(run?: AgentRun) {
  if (run) {
    runConnectionStatus.value = run.status === "FAILED" || run.status === "TIMEOUT" ? "failed" : "completed"
    if (run.status === "FAILED" || run.status === "TIMEOUT") {
      lastFailedRunId.value = run.id
    }
  } else {
    const terminalEvent = [...events.value].reverse().find(isTerminalRunEvent)
    runConnectionStatus.value = terminalEvent?.eventType === "run.failed" ? "failed" : "completed"
    if (terminalEvent?.eventType === "run.failed") {
      lastFailedRunId.value = terminalEvent.runId
    }
  }
  activeRunId.value = null
  recoveryRunId.value = null
  showActiveRunLimitHint.value = false
  stopRunStatusWatchdog()
}

function isTerminalRunEvent(event: AgentRunEvent) {
  return event.eventType === "run.completed" || event.eventType === "run.failed"
}

function parseEventJson(value?: string | null) {
  if (!value) return {} as Record<string, unknown>
  try {
    const parsed = JSON.parse(value) as unknown
    if (typeof parsed === "string") {
      const nested = JSON.parse(parsed) as unknown
      return typeof nested === "object" && nested !== null && !Array.isArray(nested) ? nested as Record<string, unknown> : {}
    }
    return typeof parsed === "object" && parsed !== null && !Array.isArray(parsed) ? parsed as Record<string, unknown> : {}
  } catch {
    return {} as Record<string, unknown>
  }
}

function isStructuredMediaContent(value: string) {
  const trimmed = value.trim()
  if (!trimmed || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) return false
  try {
    const parsed = JSON.parse(trimmed) as unknown
    return containsMediaResult(parsed)
  } catch {
    return false
  }
}

function containsMediaResult(value: unknown): boolean {
  if (!value || typeof value !== "object") return false
  if (Array.isArray(value)) return value.some(containsMediaResult)
  const record = value as Record<string, unknown>
  const resourceType = typeof record.resourceType === "string" ? record.resourceType.toUpperCase() : ""
  if (["IMAGE", "VIDEO", "AUDIO"].includes(resourceType)) return true
  for (const key of ["images", "videos", "audios", "assets", "files"]) {
    if (Array.isArray(record[key]) && record[key].length > 0) return true
  }
  if (typeof record.url === "string" && /\.(png|jpe?g|webp|gif|mp4|webm|mp3|wav)(\?|$)/i.test(record.url)) {
    return true
  }
  return Object.values(record).some(containsMediaResult)
}

function recommendToolsForAsset(asset: AssetPreviewItem): AssetPreviewRecommendation[] {
  return recommendAssetTools(asset, previewTools.value)
}

async function openAssetPreview(asset: AssetPreviewItem, message?: AgentMessage) {
  let enriched: AssetPreviewItem = { ...asset }
  const runEvents = message?.runId != null ? runEventsForMessage(message) : []
  const taskId = resolveTaskIdFromRunEvents(runEvents, asset.url)

  if (taskId && auth.isLoggedIn) {
    try {
      const task = await fetchTaskById(taskId, { token: props.token })
      enriched = mergeAssetWithTask(enriched, task, {
        defaultPromptVisible: auth.user?.promptPublicByDefault ?? false,
      })
    } catch {
      // Keep the lightweight preview when task lookup fails.
    }
  } else if (runEvents.length) {
    const finishedEvent = [...runEvents].reverse().find((event) => event.eventType === "tool.finished")
    if (finishedEvent) {
      const prompt = promptFromToolEventPayload(parseEventJson(finishedEvent.eventJson))
      if (prompt) enriched = { ...enriched, prompt }
    }
  }

  if (!enriched.createdAt && message?.createdAt) {
    enriched = { ...enriched, createdAt: message.createdAt }
  }

  previewAsset.value = {
    ...enriched,
    title: enriched.title || "生成资产",
    toolName: enriched.toolName || "Agent",
  }
}

function useAssetWithTool(tool: AssetPreviewRecommendation, asset: AssetPreviewItem) {
  openCreateWithAssetRecommendation(asset, tool)
  previewAsset.value = null
}

function openPreviewTask(asset: AssetPreviewItem) {
  if (!asset.taskId) {
    previewAsset.value = null
    return
  }
  window.location.href = `/tasks/${asset.taskId}/result`
}

async function publishPreviewAsset(asset: AssetPreviewItem, payload?: CommunityPublishPayload) {
  if (!auth.isLoggedIn || !asset.taskId) return
  try {
    const post = await publishAssetToCommunity(asset, {
      token: auth.token,
      payload,
      defaultPromptVisible: auth.user?.promptPublicByDefault ?? false,
    })
    previewAsset.value = { ...asset, communityPostId: post.id, promptVisible: post.promptVisible, title: post.title }
  } catch (err) {
    const message = err instanceof Error ? err.message : "发布失败"
    window.alert(message)
  }
}

async function unpublishPreviewAsset(asset: AssetPreviewItem) {
  if (!auth.isLoggedIn || !asset.communityPostId) return
  try {
    await unpublishCommunityPost(asset.communityPostId, { token: auth.token })
    previewAsset.value = { ...asset, communityPostId: undefined }
  } catch (err) {
    const message = err instanceof Error ? err.message : "撤回失败"
    window.alert(message)
  }
}

function adjustComposerTextareaHeight() {
  composerRef.value?.adjustComposerTextareaHeight()
}

function updateComposerScrollInset() {
  const dock = composerDockRef.value
  if (!dock) return
  composerScrollInset.value = Math.ceil(dock.getBoundingClientRect().height) + 20
}

function isNearBottom(threshold = 120) {
  const el = messageContainerRef.value
  if (!el) return true
  const inset = Math.max(threshold, composerScrollInset.value * 0.35)
  return el.scrollHeight - el.scrollTop - el.clientHeight <= inset
}

function onMessageContainerScroll() {
  const el = messageContainerRef.value
  if (!el) return
  scrollOffset.value = el.scrollTop
  stickToBottom.value = isNearBottom()
  syncActiveMinimapNodeFromViewport()
  persistChatScroll()
}

function scheduleNavLayoutUpdate() {
  void nextTick(() => {
    setupMinimapIntersectionObserver()
    syncActiveMinimapNodeFromViewport()
  })
}

function navigateToMessage(messageId: number) {
  const container = messageContainerRef.value
  if (!container) return
  const el = container.querySelector(`[data-message-id="${messageId}"]`) as HTMLElement | null
  el?.scrollIntoView({ behavior: "smooth", block: "center" })
}

function setMinimapHover(node: ConversationMinimapNode, event: Event) {
  hoveredMinimapNodeId.value = node.id
  updateMinimapHoverCardTop(event)
}

function updateMinimapHoverCardTop(event: Event) {
  if (!("clientY" in event)) return
  const viewportHeight = window.innerHeight || 720
  hoverCardTop.value = Math.min(Math.max(Number(event.clientY), 116), viewportHeight - 116)
}

function clearMinimapHover() {
  hoveredMinimapNodeId.value = null
}

function jumpToMinimapNode(node: ConversationMinimapNode) {
  activeMinimapMessageId.value = node.messageId
  navigateToMessage(node.messageId)
}

function toggleMinimapDrawer() {
  minimapDrawerOpen.value = !minimapDrawerOpen.value
}

function isMinimapNodeActive(node: ConversationMinimapNode) {
  return node.id === hoveredMinimapNodeId.value || node.id === activeMinimapNodeId.value
}

function setupMinimapIntersectionObserver() {
  minimapIntersectionObserver?.disconnect()
  minimapIntersectionObserver = null
  const container = messageContainerRef.value
  if (!container || typeof IntersectionObserver === "undefined") return

  minimapIntersectionObserver = new IntersectionObserver(
    () => syncActiveMinimapNodeFromViewport(),
    {
      root: container,
      threshold: [0, 0.01, 0.1, 0.25, 0.5, 0.75, 1],
    },
  )

  for (const message of messages.value) {
    const el = container.querySelector(`[data-message-id="${message.id}"]`) as HTMLElement | null
    if (el) minimapIntersectionObserver.observe(el)
  }
}

function syncActiveMinimapNodeFromViewport() {
  const container = messageContainerRef.value
  if (!container || messages.value.length === 0) {
    activeMinimapMessageId.value = null
    return
  }

  const rootRect = container.getBoundingClientRect()
  const visibleBottom = rootRect.bottom - Math.min(composerScrollInset.value * 0.58, rootRect.height * 0.42)
  const viewportCenter = rootRect.top + Math.max(80, (visibleBottom - rootRect.top) * 0.5)
  let closestMessageId: number | null = null
  let closestDistance = Number.POSITIVE_INFINITY

  for (const message of messages.value) {
    const el = container.querySelector(`[data-message-id="${message.id}"]`) as HTMLElement | null
    if (!el) continue
    const rect = el.getBoundingClientRect()
    const intersectsViewport = rect.bottom >= rootRect.top && rect.top <= visibleBottom
    if (!intersectsViewport) continue
    const messageCenter = rect.top + rect.height / 2
    const distance = Math.abs(messageCenter - viewportCenter)
    if (distance < closestDistance) {
      closestDistance = distance
      closestMessageId = message.id
    }
  }

  activeMinimapMessageId.value = closestMessageId ?? messages.value.at(-1)?.id ?? null
}

async function scrollBottom(force = false) {
  if (!force && !stickToBottom.value) return
  await nextTick()
  updateComposerScrollInset()
  await nextTick()
  const container = messageContainerRef.value
  if (!container) return
  const behavior = force ? "auto" : "smooth"
  if (typeof container.scrollTo === "function") {
    container.scrollTo({ top: container.scrollHeight, behavior })
  } else {
    container.scrollTop = container.scrollHeight
  }
  scheduleNavLayoutUpdate()
}

function showError(message: string) {
  agentError.value = message
}

watch(input, () => {
  void nextTick(() => {
    adjustComposerTextareaHeight()
    updateComposerScrollInset()
  })
})

watch(messages, () => {
  void nextTick(() => {
    scheduleNavLayoutUpdate()
    // 对话在流式生成时会不断更新 messages（包括同一条 assistant 消息内容增长）。
    // 若用户当前仍在贴底范围，则持续保持滚动到底部。
    if (stickToBottom.value && (sending.value || hasActiveRun.value || runConnectionStatus.value === "running")) {
      void scrollBottom()
    }
  })
}, { deep: true })

watch(
  [events, runEventsByRunId],
  () => {
    scheduleNavLayoutUpdate()
  },
  { deep: true },
)

watch(
  () => props.sessionId,
  () => {
    resetSessionTransientState()
    loadPersistedRunEventCache()
    dismissedAutoMountedReferenceKey.value = null
    stickToBottom.value = true
    scrollOffset.value = 0
    activeMinimapMessageId.value = null
    hoveredMinimapNodeId.value = null
    minimapDrawerOpen.value = false
    void loadRecentAttachments()
    void loadPane()
  },
)

watch(
  [runEventsByRunId, submittedAttachmentJsonByRunId, dismissedConfirmationIds],
  () => {
    persistRunEventCache()
  },
  { deep: true },
)

onMounted(() => {
  void loadRecentAttachments()
  loadPersistedRunEventCache()
  void loadPane()
  void loadPreviewTools()
  void loadAgentTools()
  void loadMaterialAssets()
  void nextTick(() => {
    adjustComposerTextareaHeight()
    updateComposerScrollInset()
    const dock = composerDockRef.value
    if (typeof ResizeObserver !== "undefined" && dock) {
      composerResizeObserver = new ResizeObserver(() => {
        updateComposerScrollInset()
        if (stickToBottom.value) {
          void scrollBottom(true)
        }
      })
      composerResizeObserver.observe(dock)
    }
  })
})

onUnmounted(() => {
  clearPendingUploadPreview()
  composerResizeObserver?.disconnect()
  composerResizeObserver = null
  persistChatScroll()
  stopRunEventStream()
  stopRunStatusWatchdog()
  stopStreamingAnimationTimer()
  minimapIntersectionObserver?.disconnect()
  minimapIntersectionObserver = null
})

defineExpose({
  hasActiveRun,
  showError,
})
</script>

<template>
  <div
    class="agent-chat-pane"
    :style="{ '--chat-composer-inset': `${composerScrollInset}px` }"
  >
    <AgentAmbientBackground :ambient-state="ambientState" :scroll-offset="scrollOffset" />

    <div
      v-if="showConversationMinimap"
      class="conversation-minimap"
      :class="{ 'conversation-minimap--drawer-open': minimapDrawerOpen }"
      @mouseleave="clearMinimapHover"
      @dblclick="toggleMinimapDrawer"
    >
      <button
        type="button"
        class="minimap-drawer-toggle"
        :class="{ open: minimapDrawerOpen }"
        aria-label="切换对话大纲"
        @click.stop="toggleMinimapDrawer"
      >
        «
      </button>

      <div class="minimap-pillar" role="navigation" aria-label="长对话节点缩略导航">
        <button
          v-for="node in conversationMinimapNodes"
          :key="node.id"
          type="button"
          class="minimap-segment"
          :class="[
            `minimap-segment--${node.kind}`,
            {
              'minimap-segment--active': isMinimapNodeActive(node),
              'minimap-segment--muted': hoveredMinimapNodeId && hoveredMinimapNodeId !== node.id,
            },
          ]"
          :aria-label="node.label"
          @pointerenter="setMinimapHover(node, $event)"
          @pointermove="updateMinimapHoverCardTop"
          @focus="setMinimapHover(node, $event)"
          @blur="clearMinimapHover"
          @click="jumpToMinimapNode(node)"
        />
      </div>

      <Transition name="minimap-hover-card">
        <aside
          v-if="hoveredMinimapNode"
          class="minimap-hover-card"
          :class="`minimap-hover-card--${hoveredMinimapNode.kind}`"
          :style="{ top: `${hoverCardTop}px` }"
        >
          <p>{{ hoveredMinimapNode.index }}/{{ hoveredMinimapNode.total }} 节点</p>
          <strong>{{ hoveredMinimapNode.title }}：{{ hoveredMinimapNode.excerpt }}</strong>
        </aside>
      </Transition>

      <Transition name="minimap-outline">
        <aside v-if="minimapDrawerOpen" class="minimap-outline" aria-label="树状对话大纲面板">
          <header class="minimap-outline__header">
            <div>
              <p>Conversation map</p>
              <h3>对话大纲</h3>
            </div>
            <button type="button" aria-label="收起对话大纲" @click="minimapDrawerOpen = false">×</button>
          </header>

          <ol class="minimap-outline__list">
            <li
              v-for="node in conversationMinimapNodes"
              :key="`outline-${node.id}`"
              :class="{ active: node.id === activeMinimapNodeId }"
            >
              <button type="button" @click="jumpToMinimapNode(node)">
                <span class="minimap-outline__dot" :class="`minimap-outline__dot--${node.kind}`" />
                <span>
                  <small>{{ node.index }}/{{ node.total }} · {{ node.title }}</small>
                  <strong>{{ node.excerpt }}</strong>
                </span>
              </button>
            </li>
          </ol>
        </aside>
      </Transition>
    </div>

    <div
      ref="messageContainerRef"
      class="message-container"
      @scroll.passive="onMessageContainerScroll"
    >
      <div v-if="paneLoading" class="empty-state">
        <Loader2 class="h-5 w-5 animate-spin" />
      </div>

      <div v-else-if="messages.length === 0" class="empty-state">
        <div class="empty-mark"><Sparkles class="h-6 w-6" /></div>
        <h2>想完成什么，直接告诉我</h2>
        <p>我会先分析需求，推荐合适工具，关键操作前让你确认。</p>
      </div>

      <TransitionGroup v-else name="branch-message" tag="div" class="message-list-transition">
        <div v-for="(message, index) in messages" :key="message.id" class="message-list-item">
          <div v-if="shouldShowTimeDivider(message, index)" class="time-divider">
            {{ messageDividerTime(message.createdAt) }}
          </div>
          <AgentMessageRow
            :message="message"
            :index="index"
            :run-events="runEventsForMessage(message)"
            :user-avatar-url="auth.user?.avatarUrl"
            :user-display-name="userDisplayName"
            :editing-message-id="editingMessageId"
            v-model:editing-message-draft="editingMessageDraft"
            :editing-regenerating="editingRegenerating"
            :copied-message-id="copiedMessageId"
            :regenerating-message-id="regeneratingMessageId"
            :has-active-run="hasActiveRun"
            :sending="sending"
            :models-loading="modelsLoading"
            :model-config-id="modelConfigId"
            :avatar-state="message.role === 'ASSISTANT' ? resolveAssistantAvatarState(message) : undefined"
            :is-streaming="isMessageStreaming(message)"
            :live-stream="isMessageLiveStreaming(message)"
            :asset-ref-map="sessionAssetRefMap"
            :branch-state="branchStateForMessage(message)"
            @copy="copyMessage"
            @start-edit="startEditMessage"
            @cancel-edit="cancelEditMessage"
            @submit-edit="submitEditedMessage"
            @branch-prev="(message) => switchMessageBranch(message.id, -1)"
            @branch-next="(message) => switchMessageBranch(message.id, 1)"
            @regenerate="regenerateAssistantMessage"
            @preview="(asset, message) => openAssetPreview(asset, message)"
            @reference="addReferenceAttachment"
            @open-memory-from-trace="openMemoryFromTrace"
            @delete-memory-from-trace="deleteMemoryFromTrace"
          />
        </div>

        <article
          v-if="showGenerationLoading"
          class="agent-message assistant generating-message"
          aria-live="polite"
          aria-label="Agent 正在思考"
        >
          <div class="thinking-line">
            <span>思考中</span>
            <div class="typing-dots typing-dots--under-avatar" aria-hidden="true">
              <i></i>
              <i></i>
              <i></i>
            </div>
          </div>
        </article>

        <article v-if="showInlineRunTimeline" class="agent-message assistant run-progress">
          <div class="avatar">
            <img src="https://cdn.wlcloudai.com/static/logo.svg" alt="AI" />
          </div>
          <div class="bubble">
            <RunTimeline
              :events="events"
              :inline-mode="true"
              :running="hasActiveRun"
              @open-memory="openMemoryFromTrace"
              @delete-memory="deleteMemoryFromTrace"
            />
          </div>
        </article>

        <article v-if="showRunRecoveryBanner" class="agent-recovery-card">
          <div class="card-icon warn"><AlertTriangle class="h-4 w-4" /></div>
          <div class="card-body">
            <p class="card-title">
              {{ showActiveRunLimitHint ? "上一次任务尚未结束" : "已恢复进行中的任务" }}
            </p>
            <p class="card-desc">任务 #{{ recoveryRunId }}。可等待其完成，或取消后再发送新消息。</p>
            <div class="card-actions">
              <button type="button" class="ghost-btn" :disabled="cancellingRun" @click="cancelRecoveryRun">
                <Loader2 v-if="cancellingRun" class="h-4 w-4 animate-spin" />
                <X v-else class="h-4 w-4" />
                取消进行中的任务
              </button>
            </div>
          </div>
        </article>



        <article v-if="agentError" class="agent-error-card">
          <div class="card-icon error"><AlertTriangle class="h-4 w-4" /></div>
          <div class="card-body error">
            <p class="card-title">Agent 暂时无法继续</p>
            <p class="card-desc">{{ agentError }}</p>
            <div v-if="lastFailedRunId" class="card-actions">
              <button
                type="button"
                class="primary-btn"
                :disabled="retryingRun || hasActiveRun || modelsLoading || !modelConfigId"
                @click="retryFailedRun"
              >
                <Loader2 v-if="retryingRun" class="h-4 w-4 animate-spin" />
                <RefreshCw v-else class="h-4 w-4" />
                重试
              </button>
            </div>
          </div>
        </article>

        <article v-if="confirmationError" class="agent-error-card">
          <div class="card-icon error"><AlertTriangle class="h-4 w-4" /></div>
          <div class="card-body error">
            <p class="card-title">工具确认失败</p>
            <p class="card-desc">{{ confirmationError }}</p>
          </div>
        </article>

        <AgentToolConfirmationList
          v-model:remember-tool="rememberTool"
          :items="confirmationEvents"
          :confirming-event-ids="confirmingEventIds"
          @confirm="confirmTool($event.eventId, $event.toolCode, $event.approved)"
        />

        <div
          ref="bottomRef"
          class="chat-scroll-anchor"
          :style="{ height: `${composerScrollInset}px` }"
          aria-hidden="true"
        />
      </TransitionGroup>
    </div>

    <div ref="composerDockRef" class="composer-dock">
      <button
        v-if="!paneLoading && messages.length > 0 && !stickToBottom"
        type="button"
        class="composer-scroll-to-bottom"
        aria-label="回到底部"
        title="回到底部"
        @click="stickToBottom = true; scrollBottom(true)"
      >
        ↓
      </button>
      <AgentComposer
        ref="composerRef"
        :model-config-id="modelConfigId"
        :agent-models="agentModels"
        :models-loading="modelsLoading"
        :draft="input"
        :files="files"
        :url-attachments="urlAttachments"
        :recent-attachments="recentAttachments"
        :material-assets="materialAssets"
        :material-assets-loading="materialAssetsLoading"
        :picker-upload-loading-more="pickerUploadLoadingMore"
        :picker-upload-has-more="pickerUploadHasMore"
        :material-assets-loading-more="materialAssetsLoadingMore"
        :material-assets-has-more="materialAssetsHasMore"
        :file-preview-urls="filePreviewUrls"
        :pending-upload-preview="pendingUploadPreview"
        :uploading="uploading"
        :removing-file-id="removingFileId"
        :has-active-run="hasActiveRun"
        :sending="sending"
        :editing-regenerating="editingRegenerating"
        :regenerating-message-id="regeneratingMessageId"
        :cancelling-run="cancellingRun"
        :memory-panel-open="memoryPanelOpen"
        :agent-tools="agentTools"
        :agent-tools-loading="agentToolsLoading"
        :selected-tool-code="selectedToolCode"
        :intelligence-level="intelligenceLevel"
        :session-assets="sessionAssetsForComposer"
        :reference-mentions="draftReferenceMentions"
        :auto-mounted-reference="autoMountedReference"
        @update:draft="emit('update:draft', $event)"
        @update:reference-mentions="draftReferenceMentions = $event"
        @update:selected-tool-code="selectedToolCode = $event"
        @update-tool-preference="updateToolPreference"
        @update:intelligence-level="intelligenceLevel = $event"
        @change-model="emit('change-model', $event)"
        @submit="submitMessage()"
        @cancel-run="cancelCurrentRun()"
        @file-selected="handleFileSelected"
        @files-dropped="(items, options) => uploadFiles(items, options)"
        @sync-uploaded-files="syncUploadedFiles"
        @preview-attachment="openAttachmentPreview"
        @remove-file="removeFile"
        @select-url-attachment="selectUrlAttachment"
        @remove-url-attachment="removeUrlAttachment"
        @remove-recent-attachment="removeRecentAttachment"
        @refresh-material-assets="loadMaterialAssets"
        @refresh-recent-attachments="loadRecentAttachments"
        @load-more-uploads="loadMorePickerUploads"
        @load-more-material-assets="loadMoreMaterialAssets"
        @refresh-agent-tools="loadAgentTools"
        @add-reference-attachment="addReferenceAttachment"
        @dismiss-auto-mounted-reference="dismissAutoMountedReference"
        @open-memory="openMemoryPanel"
      />
    </div>
    <AssetPreviewModal
      :asset="previewAsset"
      :recommendations="previewRecommendations"
      @close="previewAsset = null"
      @use-tool="useAssetWithTool"
      @open-task="openPreviewTask"
      @publish="publishPreviewAsset"
      @unpublish="unpublishPreviewAsset"
    />
    <Transition name="memory-drawer">
      <div v-if="memoryPanelOpen" class="memory-panel-backdrop" @click.self="closeMemoryPanel">
        <aside class="memory-panel" aria-label="Agent 长期记忆管理">
          <header class="memory-panel-header">
            <div>
              <p class="memory-panel-kicker">Agent memory</p>
              <h3>长期记忆</h3>
              <span>只保存长期有价值的偏好、习惯和项目知识。</span>
            </div>
            <button type="button" class="memory-icon-btn" aria-label="关闭记忆管理" @click="closeMemoryPanel">
              <X class="h-4 w-4" />
            </button>
          </header>

          <div class="memory-panel-controls">
            <select
              class="memory-select"
              :value="memoryWorkspaceId ?? ''"
              :disabled="memoryLoading || memoryWorkspaces.length === 0"
              @change="changeMemoryWorkspace(($event.target as HTMLSelectElement).value)"
            >
              <option v-if="memoryWorkspaces.length === 0" value="">暂无工作区</option>
              <option v-for="workspace in memoryWorkspaces" :key="workspace.id" :value="workspace.id">
                {{ workspace.name }}
              </option>
            </select>
            <button type="button" class="memory-refresh-btn" :disabled="memoryLoading" @click="loadMemoryWorkspaces">
              <Loader2 v-if="memoryLoading" class="h-4 w-4 animate-spin" />
              <RefreshCw v-else class="h-4 w-4" />
            </button>
          </div>

          <div class="memory-tabs">
            <button type="button" class="memory-tab-btn" :class="{ active: memoryTab === 'active' }" @click="changeMemoryTab('active')">
              已生效
            </button>
            <button type="button" class="memory-tab-btn" :class="{ active: memoryTab === 'candidate' }" @click="changeMemoryTab('candidate')">
              待确认
            </button>
          </div>

        <p v-if="memoryError" class="memory-error">{{ memoryError }}</p>

        <section class="memory-editor">
          <div class="memory-editor-grid">
            <select v-model="memoryForm.memoryType" class="memory-input">
              <option v-for="option in memoryTypeOptions" :key="option.value" :value="option.value">
                {{ option.label }}
              </option>
            </select>
            <input v-model="memoryForm.title" class="memory-input" placeholder="记忆标题" maxlength="160" />
          </div>
          <textarea
            v-model="memoryForm.content"
            class="memory-textarea"
            rows="4"
            placeholder="例如：用户偏好写实摄影风格，默认避免夸张动漫质感。"
          />
          <div class="memory-editor-actions">
            <button v-if="memoryEditingId != null" type="button" class="ghost-btn" :disabled="memorySaving" @click="resetMemoryForm">
              取消编辑
            </button>
            <button
              v-if="memoryTab === 'active'"
              type="button"
              class="primary-btn"
              :disabled="memorySaving || memoryWorkspaceId == null"
              @click="saveMemory"
            >
              <Loader2 v-if="memorySaving" class="h-4 w-4 animate-spin" />
              <Plus v-else-if="memoryEditingId == null" class="h-4 w-4" />
              <Check v-else class="h-4 w-4" />
              {{ memoryEditingId == null ? "添加记忆" : "保存记忆" }}
            </button>
          </div>
        </section>

        <div class="memory-list">
          <div v-if="memoryLoading" class="memory-empty">
            <Loader2 class="h-4 w-4 animate-spin" />
            正在加载记忆
          </div>
          <div v-else-if="memoryItems.length === 0" class="memory-empty">
            {{ memoryTab === "candidate" ? "暂无待确认记忆。" : "还没有长期记忆。你可以手动添加，或在对话里明确告诉 Agent “记住……”" }}
          </div>
          <section v-for="group in groupedMemoryItems" v-else :key="group.type" class="memory-group">
            <p class="memory-group-title">{{ group.label }}</p>
            <article
              v-for="item in group.items"
              :key="item.id"
              class="memory-item"
              :class="{ highlighted: memoryHighlightId === item.id }"
            >
              <div class="memory-item-main">
                <div class="memory-item-title-row">
                  <strong>#{{ item.id }} · {{ item.title || "未命名记忆" }}</strong>
                  <span v-if="item.pinned">已置顶</span>
                  <span v-else-if="item.sourceRunId">Run #{{ item.sourceRunId }}</span>
                  <span v-else>手动/历史</span>
                </div>
                <p>{{ item.content }}</p>
              </div>
              <div class="memory-item-actions">
                <template v-if="memoryTab === 'candidate'">
                  <button type="button" class="memory-text-btn" @click="approveCandidateMemory(item)">采纳</button>
                  <button type="button" class="memory-text-btn danger" @click="rejectCandidateMemory(item)">拒绝</button>
                </template>
                <template v-else>
                  <button type="button" class="memory-icon-btn" :class="{ active: item.pinned }" aria-label="置顶记忆" @click="toggleMemoryPin(item)">
                    <Pin class="h-4 w-4" />
                  </button>
                  <button type="button" class="memory-icon-btn" aria-label="编辑记忆" @click="editMemory(item)">
                    <Pencil class="h-4 w-4" />
                  </button>
                  <button type="button" class="memory-icon-btn danger" :disabled="memoryDeletingId === item.id" aria-label="删除记忆" @click="removeMemory(item)">
                    <Loader2 v-if="memoryDeletingId === item.id" class="h-4 w-4 animate-spin" />
                    <Trash2 v-else class="h-4 w-4" />
                  </button>
                </template>
              </div>
            </article>
          </section>
        </div>
        </aside>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.agent-chat-pane {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden !important;
  position: relative;
  background:
    radial-gradient(circle at 50% 102%, var(--agent-accent-glow), transparent 34%),
    transparent;
}

.agent-chat-pane::before {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(circle at 50% 44%, transparent 0 30%, rgb(255 255 255 / 0.040) 30.08% 30.20%, transparent 30.40%),
    radial-gradient(circle at 50% 44%, transparent 0 38%, rgb(255 255 255 / 0.034) 38.08% 38.20%, transparent 38.44%),
    radial-gradient(circle at 50% 44%, transparent 0 46%, rgb(255 255 255 / 0.026) 46.08% 46.20%, transparent 46.48%);
  opacity: 0.72;
  -webkit-mask-image: radial-gradient(circle at 50% 44%, rgb(0 0 0 / 0.60) 0%, #000 34%, rgb(0 0 0 / 0.36) 58%, transparent 80%);
  mask-image: radial-gradient(circle at 50% 44%, rgb(0 0 0 / 0.60) 0%, #000 34%, rgb(0 0 0 / 0.36) 58%, transparent 80%);
}

.conversation-minimap {
  position: fixed;
  top: 96px;
  right: 8px;
  bottom: 96px;
  z-index: 36;
  width: 4px;
  pointer-events: auto;
}

.minimap-pillar {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 0;
  padding: 1px 0;
  border-radius: 999px;
  background:
    linear-gradient(180deg, rgb(255 255 255 / 0.045), rgb(255 255 255 / 0.012)),
    rgb(10 12 18 / 0.32);
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.026), 0 12px 32px rgb(0 0 0 / 0.20);
  backdrop-filter: blur(12px) saturate(140%);
}

.minimap-segment {
  width: 4px;
  min-height: 3px;
  flex: 1 1 0;
  border: 0;
  border-bottom: 2px solid #14151a;
  border-radius: 999px;
  padding: 0;
  cursor: pointer;
  opacity: 0.58;
  transform-origin: right center;
  transition:
    width 0.16s ease,
    opacity 0.16s ease,
    filter 0.16s ease,
    box-shadow 0.16s ease,
    transform 0.16s ease;
}

.minimap-segment--system {
  background: #00e5ff;
  box-shadow: 0 0 8px rgb(0 229 255 / 0.18);
}

.minimap-segment--user {
  background: #3b82f6;
  box-shadow: 0 0 8px rgb(59 130 246 / 0.18);
}

.minimap-segment:last-child {
  border-bottom: 0;
}

.minimap-segment--active,
.minimap-segment:hover,
.minimap-segment:focus-visible {
  width: 8px;
  opacity: 1;
  filter: saturate(1.35) brightness(1.18);
  outline: none;
  transform: translateX(-1px);
}

.minimap-segment--system.minimap-segment--active,
.minimap-segment--system:hover,
.minimap-segment--system:focus-visible {
  box-shadow: 0 0 18px rgb(0 229 255 / 0.58), 0 0 34px rgb(0 229 255 / 0.22);
}

.minimap-segment--user.minimap-segment--active,
.minimap-segment--user:hover,
.minimap-segment--user:focus-visible {
  box-shadow: 0 0 18px rgb(59 130 246 / 0.58), 0 0 34px rgb(59 130 246 / 0.22);
}

.minimap-segment--muted {
  width: 3px;
  opacity: 0.22;
  filter: saturate(0.65);
}

.minimap-drawer-toggle {
  position: absolute;
  top: 50%;
  right: calc(100% + 2px);
  width: 20px;
  height: 44px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: rgb(255 255 255 / 0);
  box-shadow: none;
  cursor: pointer;
  transform: translateY(-50%) translateX(5px);
  backdrop-filter: none;
  opacity: 0;
  transition: opacity 0.18s ease, color 0.18s ease, transform 0.18s ease, background 0.18s ease;
}

.conversation-minimap:hover .minimap-drawer-toggle,
.minimap-drawer-toggle:focus-visible,
.minimap-drawer-toggle:hover,
.minimap-drawer-toggle.open {
  opacity: 1;
  color: rgb(255 255 255 / 0.64);
  background: rgb(18 18 22 / 0.34);
  transform: translateY(-50%) translateX(-2px);
}

.minimap-drawer-toggle:hover,
.minimap-drawer-toggle.open {
  color: #fff;
  background: rgb(18 18 22 / 0.58);
}

.minimap-hover-card {
  position: fixed;
  right: 26px;
  width: 238px;
  padding: 12px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 14px;
  background: rgb(18 18 22 / 0.80);
  color: rgb(255 255 255 / 0.78);
  box-shadow: 0 18px 56px rgb(0 0 0 / 0.42), inset 0 1px 0 rgb(255 255 255 / 0.05);
  backdrop-filter: blur(18px) saturate(145%);
  pointer-events: none;
  transform: translateY(-50%);
}

.minimap-hover-card::after {
  content: "";
  position: absolute;
  right: -5px;
  top: 50%;
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: currentColor;
  box-shadow: 0 0 14px currentColor, 0 0 28px currentColor;
  transform: translateY(-50%);
}

.minimap-hover-card--system {
  color: #00e5ff;
}

.minimap-hover-card--user {
  color: #3b82f6;
}

.minimap-hover-card p {
  margin: 0 0 6px;
  color: rgb(255 255 255 / 0.30);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.minimap-hover-card strong {
  display: block;
  overflow: hidden;
  color: rgb(255 255 255 / 0.70);
  font-size: 12px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.minimap-hover-card-enter-active,
.minimap-hover-card-leave-active {
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.minimap-hover-card-enter-from,
.minimap-hover-card-leave-to {
  opacity: 0;
  transform: translateY(-50%) translateX(8px);
}

.minimap-outline {
  position: fixed;
  top: 96px;
  right: 28px;
  bottom: 96px;
  width: min(320px, calc(100vw - 58px));
  display: flex;
  flex-direction: column;
  border: 1px solid rgb(255 255 255 / 0.09);
  border-radius: 18px;
  background:
    radial-gradient(circle at 12% 0%, var(--agent-accent-soft), transparent 34%),
    rgb(18 18 22 / 0.94);
  box-shadow: 0 24px 76px rgb(0 0 0 / 0.45), inset 0 1px 0 rgb(255 255 255 / 0.055);
  backdrop-filter: blur(22px) saturate(150%);
  overflow: hidden;
}

.minimap-outline__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 16px 12px;
  border-bottom: 1px solid rgb(255 255 255 / 0.07);
}

.minimap-outline__header p,
.minimap-outline__header h3 {
  margin: 0;
}

.minimap-outline__header p {
  color: rgb(255 255 255 / 0.38);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.minimap-outline__header h3 {
  margin-top: 4px;
  color: rgb(255 255 255 / 0.88);
  font-size: 16px;
}

.minimap-outline__header button {
  width: 30px;
  height: 30px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.045);
  color: rgb(255 255 255 / 0.62);
  cursor: pointer;
}

.minimap-outline__list {
  min-height: 0;
  margin: 0;
  padding: 12px;
  overflow-y: auto;
  list-style: none;
}

.minimap-outline__list li {
  position: relative;
}

.minimap-outline__list li::before {
  content: "";
  position: absolute;
  left: 11px;
  top: 26px;
  bottom: -10px;
  width: 1px;
  background: rgb(255 255 255 / 0.07);
}

.minimap-outline__list li:last-child::before {
  display: none;
}

.minimap-outline__list button {
  width: 100%;
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
  border: 0;
  border-radius: 12px;
  background: transparent;
  padding: 9px 10px 9px 4px;
  color: rgb(255 255 255 / 0.70);
  text-align: left;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease;
}

.minimap-outline__list button:hover,
.minimap-outline__list li.active button {
  background: rgb(255 255 255 / 0.055);
  color: #fff;
}

.minimap-outline__dot {
  position: relative;
  z-index: 1;
  width: 10px;
  height: 10px;
  margin: 6px 0 0 6px;
  border-radius: 999px;
  box-shadow: 0 0 14px currentColor;
}

.minimap-outline__dot--system {
  color: #00e5ff;
  background: #00e5ff;
}

.minimap-outline__dot--user {
  color: #3b82f6;
  background: #3b82f6;
}

.minimap-outline__list small,
.minimap-outline__list strong {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.minimap-outline__list small {
  color: rgb(255 255 255 / 0.36);
  font-size: 11px;
  line-height: 1.3;
}

.minimap-outline__list strong {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.4;
}

.minimap-outline-enter-active,
.minimap-outline-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.minimap-outline-enter-from,
.minimap-outline-leave-to {
  opacity: 0;
  transform: translateX(18px) scale(0.98);
}

.chat-scroll-anchor {
  flex-shrink: 0;
  width: 100%;
  pointer-events: none;
}

.message-list-transition {
  width: 100%;
  display: block;
  padding-top: 26px;
}

.message-list-item {
  width: 100%;
}

.branch-message-enter-active,
.branch-message-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.branch-message-enter-from,
.branch-message-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

.branch-message-move {
  transition: transform 0.2s ease;
}

.message-container {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  position: relative;
  z-index: 1;
  height: 100%;
  max-height: none;
  padding: 72px clamp(40px, 7vw, 128px) 24px;
  scroll-behavior: smooth;
  scrollbar-gutter: stable both-edges;
  scrollbar-width: thin;
  scrollbar-color: rgb(255 255 255 / 0.14) transparent;
}

.composer-dock {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 4;
  padding: 20px 0 max(36px, env(safe-area-inset-bottom));
  background: transparent;
  pointer-events: none;
}

.composer-dock::before {
  content: "";
  position: absolute;
  inset: -80px 0 0;
  z-index: -1;
  background: linear-gradient(180deg, transparent, rgb(18 21 27 / 0.16) 54%, rgb(18 21 27 / 0.34));
  pointer-events: none;
}

.composer-scroll-to-bottom {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  margin: 0 auto 14px;
  border: 1px solid rgb(255 255 255 / 0.12);
  border-radius: 999px;
  background:
    linear-gradient(180deg, rgb(255 255 255 / 0.10), rgb(255 255 255 / 0.045)),
    rgb(18 20 26 / 0.74);
  color: rgb(255 255 255 / 0.82);
  cursor: pointer;
  pointer-events: auto;
  box-shadow: 0 14px 42px rgb(0 0 0 / 0.30), inset 0 1px 0 rgb(255 255 255 / 0.08);
  backdrop-filter: blur(16px) saturate(135%);
  transition:
    transform 0.18s ease,
    border-color 0.18s ease,
    background 0.18s ease,
    color 0.18s ease,
    box-shadow 0.18s ease;
}

.composer-scroll-to-bottom:hover,
.composer-scroll-to-bottom:focus-visible {
  transform: translateY(-2px);
  border-color: var(--agent-accent-soft);
  color: #fff;
  outline: none;
  box-shadow: 0 16px 48px rgb(0 0 0 / 0.34), 0 0 24px var(--agent-accent-glow);
}

.composer-dock :deep(.composer) {
  pointer-events: auto;
}

.message-container::-webkit-scrollbar {
  width: 4px;
}

.message-container::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgb(255 255 255 / 0.10);
}

.message-container:hover::-webkit-scrollbar-thumb {
  background: rgb(255 255 255 / 0.18);
}

.composer {
  width: min(960px, calc(100% - 184px));
  margin: 0 auto;
  min-height: 198px;
  border: 1px solid rgb(255 255 255 / 0.105);
  border-radius: 24px;
  background: var(--agent-composer-bg);
  padding: 16px 20px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-shrink: 0;
  position: relative;
  z-index: 2;
  box-shadow:
    0 -24px 72px var(--agent-accent-glow, rgb(176 92 255 / 0.12)),
    0 24px 72px rgb(0 0 0 / 0.52),
    0 0 0 1px color-mix(in srgb, var(--agent-accent), transparent 86%),
    inset 0 1px 0 rgb(255 255 255 / 0.08);
  backdrop-filter: blur(24px) saturate(145%);
}

.composer-model-row {
  display: flex;
  align-items: center;
  align-self: flex-start;
  justify-content: flex-start;
  gap: 6px;
  max-width: min(250px, 100%);
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.032);
  padding: 3px 5px 3px 8px;
}

.composer-model-copy {
  display: flex;
  align-items: center;
  min-width: auto;
}

.composer-model-kicker {
  color: rgb(255 255 255 / 0.32);
  font-size: 10px;
  line-height: 1;
  white-space: nowrap;
}

.composer-model-copy small {
  display: none;
}

.composer-model-copy strong {
  display: none;
}

.composer-model-select {
  width: min(164px, 40vw);
  min-height: 26px;
  border: 0;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.14);
  color: rgb(255 255 255 / 0.72);
  padding: 0 24px 0 9px;
  outline: none;
  font-size: 11px;
}

.composer-model-select:disabled {
  opacity: 0.62;
  cursor: not-allowed;
}

.inner-file-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 120px;
  overflow-y: auto;
  padding-right: 4px;
}

.inner-file-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  background: rgb(255 255 255 / 0.045);
  border: 1px solid rgb(255 255 255 / 0.06);
  border-radius: 14px;
  font-size: 12px;
}

.inner-file-info {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
}

.inner-file-name {
  color: rgb(255 255 255 / 0.84);
  font-weight: 500;
}

.inner-file-size {
  color: rgb(255 255 255 / 0.42);
  font-size: 11px;
}

.inner-file-close {
  background: transparent;
  border: none;
  color: rgb(255 255 255 / 0.42);
  cursor: pointer;
  padding: 2px;
}

.inner-file-close:hover {
  color: #fff;
}

.input-wrap {
  position: relative;
}

.chat-input {
  width: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-size: 18px;
  line-height: 1.6;
  min-height: 48px;
  max-height: 160px;
  resize: none;
  padding: 6px 36px 6px 2px;
  color: rgb(255 255 255 / 0.88);
}

.chat-input::placeholder {
  color: rgb(255 255 255 / 0.34);
}

.chat-input.input-expand {
  min-height: 110px;
  max-height: 40vh;
}

.chat-input:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.expand-btn {
  position: absolute;
  right: 0;
  bottom: 8px;
  border: none;
  background: transparent;
  color: rgb(255 255 255 / 0.42);
  cursor: pointer;
  padding: 2px;
}

.expand-btn:hover {
  color: #fff;
}

.expand-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.toolbar-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 10px;
}

.left-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  padding: 7px 10px;
  border-radius: 999px;
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.52);
  cursor: pointer;
  transition: all 0.2s;
}

.tool-btn:hover:not(:disabled) {
  background: rgb(255 255 255 / 0.07);
  color: #fff;
}

.tool-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.send-circle-btn {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: 1px solid rgb(255 255 255 / 0.14);
  background:
    radial-gradient(circle at 28% 20%, rgb(255 255 255 / 0.42), transparent 24%),
    linear-gradient(135deg, rgb(205 132 255), rgb(176 92 255) 48%, rgb(115 72 255));
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: transform 0.18s ease, filter 0.18s ease, background 0.2s;
  box-shadow: 0 0 0 1px rgb(176 92 255 / 0.08), 0 10px 32px rgb(176 92 255 / 0.42), 0 0 70px rgb(176 92 255 / 0.22);
}

.send-circle-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  filter: brightness(1.08);
}

.send-circle-btn.stop {
  border-color: rgb(248 113 113 / 0.72);
  background: rgb(127 29 29);
  box-shadow: 0 10px 30px rgb(248 113 113 / 0.22);
}

.send-circle-btn.stop:hover {
  background: rgb(153 27 27);
}

.send-circle-btn:disabled {
  opacity: 0.42;
  cursor: not-allowed;
}

.empty-state {
  min-height: 54vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: rgb(255 255 255 / 0.48);
  transform: translateY(-36px);
}

.empty-mark {
  width: 76px;
  height: 76px;
  display: grid;
  place-items: center;
  border-radius: 24px;
  border: 1px solid color-mix(in srgb, var(--agent-accent) 32%, rgb(255 255 255 / 0.14));
  background:
    radial-gradient(circle at 34% 18%, rgb(255 255 255 / 0.22), transparent 30%),
    linear-gradient(145deg, var(--agent-accent-soft), rgb(255 255 255 / 0.055));
  color: var(--agent-accent-light);
  box-shadow: 0 24px 60px var(--agent-accent-glow), inset 0 1px 0 rgb(255 255 255 / 0.10);
  animation: breathe-soft 2.8s ease-in-out infinite;
}

.empty-state h2 {
  margin: 30px 0 10px;
  font-size: 30px;
  line-height: 1.2;
  color: #fff;
}

.empty-state p {
  margin: 0;
  color: rgb(255 255 255 / 0.48);
  font-size: 15px;
}

.agent-message {
  display: flex;
  justify-content: start;
  margin: 30px auto;
  width: min(100%, 980px);
  max-width: 980px;
  animation: message-rise 0.24s ease-out;
}

.agent-message.user {
  grid-template-columns: minmax(0, 650px) 42px;
  justify-content: end;
}

.agent-message.user .avatar {
  grid-column: 2;
  grid-row: 1;
  background: rgb(176 92 255 / 0.16);
  border-color: rgb(176 92 255 / 0.24);
  color: #fff;
}

.time-divider {
  width: fit-content;
  margin: 34px auto 12px;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.035);
  padding: 4px 11px;
  color: rgb(255 255 255 / 0.32);
  font-size: 11px;
  letter-spacing: 0;
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.045);
}

.message-main {
  position: relative;
  min-width: 0;
  width: fit-content;
  max-width: 100%;
}

.message-time {
  position: absolute;
  left: calc(100% + 12px);
  top: 4px;
  min-width: 42px;
  color: rgb(255 255 255 / 0.28);
  font-size: 11px;
  line-height: 1;
  opacity: 0;
  pointer-events: none;
  transform: translateX(-4px);
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.agent-message.user .message-time {
  right: calc(100% + 12px);
  left: auto;
  text-align: right;
  transform: translateX(4px);
}

.agent-message:hover .message-time,
.agent-message:focus-within .message-time {
  opacity: 1;
  transform: translateX(0);
}

.agent-message.user .message-main {
  grid-column: 1;
  justify-self: end;
}

.agent-message.user .bubble {
  border-color: rgb(255 255 255 / 0.09);
  background:
    radial-gradient(circle at 18% 10%, rgb(176 92 255 / 0.16), transparent 42%),
    rgb(255 255 255 / 0.055);
  color: rgb(255 255 255 / 0.91);
  border-radius: 24px 10px 24px 24px;
  box-shadow: 0 18px 48px rgb(0 0 0 / 0.20), inset 0 1px 0 rgb(255 255 255 / 0.045);
  backdrop-filter: blur(14px);
}

.message-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  min-height: 28px;
  opacity: 0;
  transition: opacity 0.16s ease;
}

.agent-message:hover .message-actions,
.agent-message:focus-within .message-actions {
  opacity: 1;
}

.message-actions--user {
  justify-content: flex-end;
}

.message-meta {
  margin-top: 2px;
  color: rgb(255 255 255 / 0.34);
  font-size: 13px;
  line-height: 1.4;
}

.message-meta--user {
  text-align: right;
}

.message-action-btn {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.045);
  color: rgb(255 255 255 / 0.52);
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease, color 0.16s ease, transform 0.16s ease;
}

.message-action-btn:hover:not(:disabled) {
  border-color: rgb(176 92 255 / 0.42);
  background: rgb(176 92 255 / 0.14);
  color: #fff;
  transform: translateY(-1px);
}

.message-action-btn.primary {
  border-color: rgb(176 92 255 / 0.48);
  background: rgb(176 92 255 / 0.22);
  color: #fff;
}

.message-action-btn:disabled {
  opacity: 0.42;
  cursor: not-allowed;
}

.message-edit-box {
  display: grid;
  gap: 10px;
  width: min(640px, 68vw);
}

.message-edit-input {
  width: 100%;
  min-height: 92px;
  max-height: 260px;
  resize: none;
  border: 1px solid rgb(255 255 255 / 0.10);
  border-radius: 16px;
  background: rgb(0 0 0 / 0.24);
  color: rgb(255 255 255 / 0.9);
  outline: none;
  padding: 10px 12px;
  font-size: 15px;
  line-height: 1.6;
}

.message-edit-input:focus {
  border-color: rgb(176 92 255 / 0.46);
  box-shadow: 0 0 0 3px rgb(176 92 255 / 0.12);
}

.message-edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.avatar,
.card-icon {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: 15px;
  border: 1px solid rgb(255 255 255 / 0.075);
  background: rgb(255 255 255 / 0.045);
  color: rgb(255 255 255 / 0.82);
  font-size: 12px;
  font-weight: 700;
}

.avatar img {
  width: 22px;
  height: 22px;
  object-fit: contain;
  filter: drop-shadow(0 0 10px rgb(176 92 255 / 0.28));
}

.bubble {
  width: fit-content;
  max-width: 100%;
  border: 1px solid rgb(255 255 255 / 0.065);
  border-radius: 10px 24px 24px 24px;
  background: rgb(255 255 255 / 0.045);
  padding: 16px 18px;
  font-size: 17px;
  line-height: 1.75;
  color: rgb(255 255 255 / 0.86);
  box-shadow: 0 18px 44px rgb(0 0 0 / 0.16), inset 0 1px 0 rgb(255 255 255 / 0.035);
  backdrop-filter: blur(10px);
}

.agent-message.assistant .bubble:has(.agent-result-renderer) {
  width: min(820px, 100%);
  padding: 10px;
  border-color: rgb(255 255 255 / 0.07);
  background:
    radial-gradient(circle at 8% 0%, var(--agent-bubble-assistant-tint), transparent 34%),
    linear-gradient(180deg, rgb(255 255 255 / 0.035), rgb(255 255 255 / 0.018));
  box-shadow:
    0 20px 70px rgb(0 0 0 / 0.28),
    inset 0 1px 0 rgb(255 255 255 / 0.04);
  backdrop-filter: blur(10px);
}

.agent-message.run-progress .bubble {
  width: min(650px, 100%);
  border-color: rgb(255 255 255 / 0.10);
  background: rgb(255 255 255 / 0.045);
}

.generating-message {
  min-height: 28px;
  animation: message-rise 0.18s ease-out;
}

.thinking-line {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: rgb(255 255 255 / 0.48);
  font-size: 14px;
}

.typing-dots {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.typing-dots i {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: rgb(210 170 255);
  animation: typing-dot 1s ease-in-out infinite;
}

.typing-dots--under-avatar i {
  width: 4px;
  height: 4px;
  background: var(--agent-accent);
}

.typing-dots i:nth-child(2) {
  animation-delay: 0.15s;
}

.typing-dots i:nth-child(3) {
  animation-delay: 0.3s;
}

.confirmation-card,
.agent-error-card {
  display: grid;
  grid-template-columns: 42px minmax(0, 820px);
  gap: 14px;
  max-width: 1040px;
  margin: 24px auto;
}

.run-status-card {
  width: fit-content;
  max-width: min(920px, calc(100% - 32px));
  min-height: 38px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: 1px solid rgb(255 255 255 / 0.10);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.055);
  color: rgb(255 255 255 / 0.54);
  font-size: 13px;
  margin: 8px auto 18px;
  padding: 8px 14px;
}

.run-status-card.running,
.run-status-card.awaiting_confirmation {
  border-color: var(--agent-accent-soft);
  animation: breathe-panel 2.4s ease-in-out infinite;
}

.run-status-card.awaiting_confirmation {
  border-color: rgb(245 158 11 / 0.48);
  background: rgb(245 158 11 / 0.10);
  color: rgb(253 230 138);
}

.run-status-card.completed {
  border-color: rgb(52 211 153 / 0.42);
  background: rgb(52 211 153 / 0.10);
  color: rgb(167 243 208);
}

.run-status-card.failed {
  border-color: rgb(248 113 113 / 0.50);
  background: rgb(248 113 113 / 0.10);
  color: rgb(254 202 202);
}

.card-icon.error {
  background: rgb(248 113 113 / 0.14);
  color: rgb(254 202 202);
}

.card-body {
  border: 1px solid rgb(255 255 255 / 0.10);
  border-radius: 22px;
  background: rgb(255 255 255 / 0.055);
  padding: 16px;
  box-shadow: 0 18px 44px rgb(0 0 0 / 0.18);
}

.card-body.error {
  border-color: rgb(248 113 113 / 0.34);
  background: rgb(248 113 113 / 0.10);
}

.card-title {
  margin: 0;
  font-weight: 700;
}

.card-desc {
  margin: 6px 0 12px;
  color: rgb(255 255 255 / 0.52);
  font-size: 13px;
}

.remember-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: rgb(255 255 255 / 0.78);
  font-size: 13px;
}

.card-actions {
  margin-top: 14px;
  display: flex;
  gap: 10px;
}

.primary-btn,
.ghost-btn {
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid rgb(255 255 255 / 0.12);
  border-radius: 999px;
  padding: 0 12px;
}

.primary-btn:disabled,
.ghost-btn:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.primary-btn {
  border-color: var(--agent-accent-soft);
  background: var(--agent-send-gradient);
  color: #fff;
  box-shadow: 0 10px 26px var(--agent-accent-glow);
}

.ghost-btn {
  background: rgb(255 255 255 / 0.06);
  color: rgb(255 255 255 / 0.76);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
}

.memory-panel-backdrop {
  position: absolute;
  inset: 0;
  z-index: 30;
  display: flex;
  justify-content: flex-end;
  background: rgb(0 0 0 / 0.16);
}

.memory-panel {
  width: min(320px, calc(100% - 16px));
  height: calc(100% - 20px);
  margin: 10px 10px 10px 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
  border: 1px solid rgb(255 255 255 / 0.12);
  border-radius: 22px;
  background:
    radial-gradient(circle at 16% 0%, var(--agent-composer-tint), transparent 38%),
    linear-gradient(180deg, rgb(32 36 46 / 0.84), rgb(14 16 22 / 0.78));
  padding: 18px;
  color: rgb(255 255 255 / 0.88);
  box-shadow:
    -18px 0 70px rgb(0 0 0 / 0.36),
    inset 1px 0 0 rgb(255 255 255 / 0.06);
  backdrop-filter: blur(24px) saturate(135%);
  overflow-y: auto;
}

.memory-drawer-enter-active,
.memory-drawer-leave-active {
  transition: opacity 0.22s ease;
}

.memory-drawer-enter-active .memory-panel,
.memory-drawer-leave-active .memory-panel {
  transition: transform 0.26s cubic-bezier(0.22, 1, 0.36, 1), opacity 0.22s ease;
}

.memory-drawer-enter-from,
.memory-drawer-leave-to {
  opacity: 0;
}

.memory-drawer-enter-from .memory-panel,
.memory-drawer-leave-to .memory-panel {
  opacity: 0;
  transform: translateX(28px);
}

.memory-panel-header,
.memory-panel-controls,
.memory-item-title-row,
.memory-item-actions,
.memory-editor-actions {
  display: flex;
  align-items: center;
}

.memory-panel-header {
  justify-content: space-between;
  gap: 16px;
}

.memory-panel-kicker {
  margin: 0 0 4px;
  color: var(--agent-accent);
  font-size: 12px;
  font-weight: 700;
  text-transform: uppercase;
}

.memory-panel-header h3 {
  margin: 0;
  font-size: 22px;
}

.memory-panel-header span {
  display: block;
  margin-top: 6px;
  color: rgb(255 255 255 / 0.52);
  font-size: 13px;
  line-height: 1.5;
}

.memory-icon-btn,
.memory-refresh-btn {
  width: 34px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  border: 1px solid rgb(255 255 255 / 0.10);
  background: rgb(255 255 255 / 0.055);
  color: rgb(255 255 255 / 0.68);
  cursor: pointer;
}

.memory-icon-btn:hover:not(:disabled),
.memory-refresh-btn:hover:not(:disabled) {
  border-color: rgb(176 92 255 / 0.42);
  color: #fff;
}

.memory-icon-btn.danger:hover:not(:disabled) {
  border-color: rgb(248 113 113 / 0.46);
  color: rgb(254 202 202);
}

.memory-panel-controls {
  gap: 8px;
}

.memory-select,
.memory-input,
.memory-textarea {
  width: 100%;
  border: 1px solid rgb(255 255 255 / 0.10);
  border-radius: 14px;
  background: rgb(0 0 0 / 0.22);
  color: rgb(255 255 255 / 0.88);
  outline: none;
}

.memory-select,
.memory-input {
  min-height: 38px;
  padding: 0 12px;
}

.memory-textarea {
  resize: vertical;
  padding: 10px 12px;
  line-height: 1.6;
}

.memory-editor {
  display: grid;
  gap: 10px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 18px;
  background: rgb(255 255 255 / 0.045);
  padding: 12px;
}

.memory-editor-grid {
  display: grid;
  grid-template-columns: 132px minmax(0, 1fr);
  gap: 8px;
}

.memory-editor-actions {
  justify-content: flex-end;
  gap: 8px;
}

.memory-list {
  display: grid;
  gap: 14px;
}

.memory-empty,
.memory-error {
  border-radius: 16px;
  padding: 14px;
  font-size: 13px;
  line-height: 1.6;
}

.memory-empty {
  display: flex;
  gap: 8px;
  align-items: center;
  border: 1px dashed rgb(255 255 255 / 0.12);
  color: rgb(255 255 255 / 0.48);
}

.memory-error {
  border: 1px solid rgb(248 113 113 / 0.32);
  background: rgb(248 113 113 / 0.10);
  color: rgb(254 202 202);
}

.memory-group {
  display: grid;
  gap: 8px;
}

.memory-group-title {
  margin: 0;
  color: rgb(255 255 255 / 0.46);
  font-size: 12px;
  font-weight: 700;
}

.memory-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 16px;
  background: rgb(255 255 255 / 0.045);
  padding: 12px;
}

.memory-item-main {
  min-width: 0;
}

.memory-item-title-row {
  justify-content: space-between;
  gap: 10px;
}

.memory-item-title-row strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.memory-item-title-row span {
  flex-shrink: 0;
  color: rgb(255 255 255 / 0.38);
  font-size: 11px;
}

.memory-item p {
  margin: 8px 0 0;
  color: rgb(255 255 255 / 0.62);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.memory-item-actions {
  gap: 6px;
  align-self: start;
}

.memory-tabs {
  display: flex;
  gap: 8px;
  margin: 0 0 12px;
}

.memory-tab-btn {
  flex: 1;
  border: 1px solid rgb(255 255 255 / 0.1);
  background: rgb(255 255 255 / 0.03);
  color: rgb(255 255 255 / 0.62);
  border-radius: 999px;
  padding: 8px 12px;
  font-size: 12px;
  cursor: pointer;
}

.memory-tab-btn.active {
  color: #fff;
  border-color: rgb(176 92 255 / 0.45);
  background: rgb(176 92 255 / 0.12);
}

.memory-item.highlighted {
  border-color: rgb(176 92 255 / 0.55);
  box-shadow: 0 0 0 1px rgb(176 92 255 / 0.18);
}

.memory-text-btn {
  border: 1px solid rgb(255 255 255 / 0.12);
  background: transparent;
  color: rgb(255 255 255 / 0.78);
  border-radius: 999px;
  padding: 6px 10px;
  font-size: 11px;
  cursor: pointer;
}

.memory-text-btn.danger {
  color: #fda29b;
  border-color: rgb(253 162 155 / 0.35);
}

.memory-icon-btn.active {
  color: #c084fc;
  border-color: rgb(192 132 252 / 0.35);
}

@media (max-width: 900px) {
  .conversation-minimap {
    display: none;
  }

  .message-container {
    padding: 36px 14px 16px;
  }
  .composer {
    width: calc(100% - 24px);
    min-height: 176px;
    border-radius: 24px;
  }
  .composer-dock {
    padding: 14px 0 max(14px, env(safe-area-inset-bottom));
  }
  .composer-scroll-to-bottom {
    width: 40px;
    height: 40px;
    margin-bottom: 10px;
  }
  .composer-model-row {
    align-items: center;
    flex-direction: row;
    max-width: 100%;
  }
  .composer-model-select {
    width: min(220px, 58vw);
  }
  .agent-message,
  .confirmation-card,
  .agent-error-card {
    grid-template-columns: 34px minmax(0, 1fr);
    gap: 10px;
    margin: 20px auto;
  }
  .agent-message.user {
    grid-template-columns: minmax(0, 1fr) 34px;
  }
  .message-actions {
    opacity: 1;
  }
  .message-time {
    display: none;
  }
  .message-edit-box {
    width: min(100%, 72vw);
  }
  .avatar,
  .card-icon {
    width: 34px;
    height: 34px;
    border-radius: 12px;
  }
  .run-status-card {
    max-width: 100%;
  }
}

@keyframes message-rise {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes breathe-soft {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgb(176 92 255 / 0.16);
    transform: translateY(0);
  }
  50% {
    box-shadow: 0 0 0 10px rgb(176 92 255 / 0);
    transform: translateY(-1px);
  }
}

@keyframes breathe-panel {
  0%,
  100% {
    box-shadow: 0 18px 56px rgb(176 92 255 / 0.10), 0 16px 40px rgb(0 0 0 / 0.34);
  }
  50% {
    box-shadow: 0 18px 68px rgb(176 92 255 / 0.22), 0 16px 40px rgb(0 0 0 / 0.34);
  }
}

@keyframes pulse-ring {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgb(176 92 255 / 0.20);
  }
  50% {
    box-shadow: 0 0 0 7px rgb(176 92 255 / 0);
  }
}

@keyframes typing-dot {
  0%,
  80%,
  100% {
    opacity: 0.35;
    transform: translateY(0);
  }
  40% {
    opacity: 1;
    transform: translateY(-3px);
  }
}
</style>
