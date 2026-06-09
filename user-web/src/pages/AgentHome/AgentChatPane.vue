<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue"
import {
  AlertTriangle,
  Check,
  Loader2,
  Pencil,
  Plus,
  RefreshCw,
  Sparkles,
  Trash2,
  X,
} from "lucide-vue-next"
import AgentComposer from "./AgentComposer.vue"
import AgentMessageRow from "./AgentMessageRow.vue"
import AgentAvatar from "./AgentAvatar.vue"
import AgentAmbientBackground from "./AgentAmbientBackground.vue"
import ConversationScrollNav from "./ConversationScrollNav.vue"
import ConversationPhaseTimeline from "./ConversationPhaseTimeline.vue"
import RunTimeline from "./RunTimeline.vue"
import { filterUserFacingRunEvents } from "./runTimelineEvents"
import AgentToolConfirmationList from "./AgentToolConfirmationList.vue"
import AssetPreviewModal from "@/components/AssetPreviewModal.vue"
import CreditRechargeModal from "@/components/CreditRechargeModal.vue"
import { formatAgentRunFailure } from "@/api/errorMapping"
import { isCreditInsufficient } from "@/utils/creditInsufficient"
import {
  buildConversationPhases,
  buildScrollNavNodes,
} from "@/utils/conversationPhases"
import { fetchAgentFilePreviewUrl, isImageAttachment, resolveAgentFileUrl, revokeAgentFilePreviewUrl } from "@/utils/agentAttachment"
import type { AgentAvatarState } from "./AgentAvatar.vue"
import { useAuthStore } from "@/store/authStore"
import {
  ApiBusinessError,
  cancelAgentRun,
  confirmAgentTool,
  deleteAgentFile,
  deleteUploadAsset,
  editRegenerateAgentMessage,
  createAgentWorkspaceMemory,
  deleteAgentWorkspaceMemory,
  fetchAgentMessages,
  fetchAgentFiles,
  fetchRecentAgentFiles,
  fetchAgentRun,
  fetchAgentRunEvents,
  fetchAgentTools,
  fetchAgentWorkspaceMemory,
  fetchAgentWorkspaces,
  fetchTaskById,
  fetchTasks,
  fetchTools,
  fetchUploadAssets,
  publishCommunityPost,
  regenerateAgentRun,
  sendAgentMessage,
  streamAgentRunEvents,
  unpublishCommunityPost,
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
import { openDashboardWithAsset } from "@/utils/assetReplay"
import { buildTaskResultBlocks, resolveAudioTracks } from "@/utils/taskResultBlocks"
import {
  chatAssetRefByUrl,
  dragPayloadToUrlAttachment,
  type ChatAssetDragPayload,
} from "@/utils/agentChatAssetRefs"

const auth = useAuthStore()

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

const emit = defineEmits<{
  "update:draft": [value: string]
  "change-model": [value: number | null]
  "toggle-session-sidebar": []
}>()

const messages = ref<AgentMessage[]>([])
const files = ref<AgentFile[]>([])
const urlAttachments = ref<AgentUrlAttachment[]>([])
const filePreviewUrls = ref<Record<number, string>>({})
const pendingUploadPreview = ref<{ name: string; url: string } | null>(null)
const recentAttachments = ref<AgentMaterialAttachment[]>([])
const materialAssets = ref<AgentMaterialAttachment[]>([])
const materialAssetsLoading = ref(false)
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
const creditModalOpen = ref(false)
const rememberTool = ref(true)
const AGENT_REFERENCE_ATTACHMENT_LIMIT = 8
const SHARED_UPLOAD_HISTORY_LIMIT = 60

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
const navLayoutTick = ref(0)
const messagesKey = computed(() => `agent_messages_${props.sessionId}`)
let runStreamAbort: AbortController | null = null
let runStatusWatchdog: number | null = null
let streamingAnimationTimer: number | null = null
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

const sessionAssetRefMap = computed(() => chatAssetRefByUrl(messages.value))

const suggestions = [
  "帮我写一篇小红书种草笔记",
  "帮我优化一个电商商品标题",
  "给朋友圈生成一段新品文案",
  "我想做公众号长文，先推荐工具",
]

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
    runConnectionStatus.value === "running" ||
    runConnectionStatus.value === "awaiting_confirmation"
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
  { value: "user_profile", label: "用户偏好" },
  { value: "project_knowledge", label: "项目知识" },
  { value: "custom", label: "自定义" },
]
const groupedMemoryItems = computed(() => {
  const order = ["user_profile", "project_knowledge", "custom"]
  return order
    .map((type) => ({
      type,
      label: memoryTypeOptions.find((item) => item.value === type)?.label ?? type,
      items: memoryItems.value.filter((item) => item.memoryType === type),
    }))
    .filter((group) => group.items.length > 0)
})

const ambientState = computed(() => {
  if (runConnectionStatus.value === "awaiting_confirmation") return "awaiting_confirmation" as const
  if (hasActiveRun.value || showGenerationLoading.value) return "thinking" as const
  return "idle" as const
})

const conversationPhases = computed(() =>
  buildConversationPhases(messages.value, events.value),
)

const scrollNavNodes = computed(() => {
  void navLayoutTick.value
  const container = messageContainerRef.value
  if (!container) return []
  const scrollHeight = container.scrollHeight
  const offsets = new Map<number, number>()
  for (const message of messages.value) {
    const el = container.querySelector(`[data-message-id="${message.id}"]`) as HTMLElement | null
    if (el) offsets.set(message.id, el.offsetTop)
  }
  return buildScrollNavNodes(messages.value, events.value, scrollHeight, offsets)
})

function resolveAssistantAvatarState(message: AgentMessage): AgentAvatarState {
  if (message.id === streamingAssistantMessageId.value && hasActiveRun.value) return "streaming"
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

function messageContentJsonForFiles(items: AgentFile[], urlItems: AgentUrlAttachment[] = []) {
  if (items.length === 0 && urlItems.length === 0) return undefined
  return JSON.stringify({
    attachments: [
      ...urlItems.map((item, index) => ({
        id: item.id,
        name: referenceAttachmentLabel(index, item.refLabel || item.name, item.contentType),
        contentType: item.contentType,
        size: item.size,
        url: item.url,
        status: "READY",
        source: "url",
      })),
      ...items.map((file, index) => ({
        id: file.id,
        name: referenceAttachmentLabel(urlItems.length + index, file.originalFilename, file.contentType),
        contentType: file.contentType,
        size: file.fileSize,
        url: file.downloadUrl,
        status: file.status,
        source: "agent_file",
      })),
    ],
  })
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
  if (!props.token) return null
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

async function loadPane() {
  if (!props.token) return
  paneLoading.value = true
  agentError.value = null
  try {
    const res = await fetchAgentMessages(props.sessionId, { token: props.token })
    messages.value = res.list
    await loadFiles()
    await hydrateHistoricalRunEvents()
    await resumePendingRunForSession()
  } finally {
    paneLoading.value = false
    await nextTick()
    const persisted = loadPersistedChatScroll(props.sessionId)
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

async function hydrateHistoricalRunEvents() {
  if (!props.token) return
  const runIds = [...new Set(
    messages.value
      .filter((message) => message.role === "ASSISTANT" && message.runId != null)
      .map((message) => message.runId as number),
  )]
  await Promise.all(
    runIds.map(async (runId) => {
      const cached = runEventsByRunId.value[runId] ?? []
      if (cached.length > 0) return
      await syncRunEvents(runId)
    }),
  )
}

async function resumePendingRunForSession() {
  if (!props.token) return
  const runId = findLatestRunIdInMessages(messages.value)
  if (!runId) return
  try {
    const run = await fetchAgentRun(runId, { token: props.token })
    if (!isResumableRunStatus(run.status)) return
    activeRunId.value = runId
    recoveryRunId.value = runId
    await syncRunEvents(runId)
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
  if (!props.token || recoveryRunId.value == null || cancellingRun.value) return
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
  if (!props.token) return
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

async function hydrateFilePreviews() {
  if (!props.token) return
  const next: Record<number, string> = {}
  await Promise.all(
    files.value.map(async (file) => {
      if (!isImageAttachment(file.contentType, file.originalFilename)) return
      const url = await fetchAgentFilePreviewUrl(file.downloadUrl, props.token)
      if (url) next[file.id] = url
    }),
  )
  for (const file of files.value) {
    if (!next[file.id]) revokeAgentFilePreviewUrl(file.downloadUrl)
  }
  filePreviewUrls.value = next
}

async function loadFiles() {
  if (!props.token) return
  const res = await fetchAgentFiles(props.sessionId, { token: props.token })
  files.value = res.list
  await hydrateFilePreviews()
}

async function openMemoryPanel() {
  memoryPanelOpen.value = true
  if (!props.token) return
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
  if (!props.token) return
  memoryLoading.value = true
  memoryError.value = null
  try {
    const res = await fetchAgentWorkspaces({ token: props.token })
    memoryWorkspaces.value = res.list
    memoryWorkspaceId.value = memoryWorkspaceId.value ?? res.list[0]?.id ?? null
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
  if (!props.token || memoryWorkspaceId.value == null) return
  memoryLoading.value = true
  memoryError.value = null
  try {
    const res = await fetchAgentWorkspaceMemory(memoryWorkspaceId.value, { token: props.token })
    memoryItems.value = res.list
  } catch (error) {
    memoryError.value = formatAgentError(error)
  } finally {
    memoryLoading.value = false
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
  if (!props.token || memoryWorkspaceId.value == null || memorySaving.value) return
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
  if (!props.token || memoryWorkspaceId.value == null || memoryDeletingId.value != null) return
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

function recentAttachmentStorageKey() {
  return `agent:recent-attachments:${auth.user?.id ?? "anon"}`
}

function sharedUploadHistoryStorageKey(kind: MaterialKind): string {
  const userId = auth.user?.id ?? "guest"
  return `ai_tool_market_upload_history:${userId}:${kind}`
}

function materialKind(contentType?: string | null, name?: string | null): MaterialKind {
  const type = (contentType || "").toLowerCase()
  if (type.startsWith("image/") || isImageAttachment(contentType, name)) return "image"
  if (type.startsWith("video/")) return "video"
  if (type.startsWith("audio/")) return "audio"
  return "file"
}

function readSharedUploadHistory(kind: MaterialKind): AgentMaterialAttachment[] {
  if (typeof window === "undefined") return []
  try {
    const raw = window.localStorage.getItem(sharedUploadHistoryStorageKey(kind))
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed
      .map((item) => normalizeUrlAttachment(item as AgentUrlAttachment))
      .filter((item): item is AgentMaterialAttachment => Boolean(item))
      .map((item) => ({ ...item, kind }))
      .slice(0, SHARED_UPLOAD_HISTORY_LIMIT)
  } catch {
    return []
  }
}

function writeSharedUploadHistory(kind: MaterialKind, items: AgentMaterialAttachment[]) {
  if (typeof window === "undefined") return
  window.localStorage.setItem(sharedUploadHistoryStorageKey(kind), JSON.stringify(items.slice(0, SHARED_UPLOAD_HISTORY_LIMIT)))
}

function rememberSharedUploadHistory(item: AgentMaterialAttachment) {
  const kind = item.kind || materialKind(item.contentType, item.name)
  const existing = readSharedUploadHistory(kind).filter((entry) => entry.url !== item.url)
  writeSharedUploadHistory(kind, [{ ...item, kind }, ...existing])
}

function readAllSharedUploadHistory() {
  return (["image", "video", "audio", "file"] as MaterialKind[]).flatMap((kind) => readSharedUploadHistory(kind))
}

function dedupeRecentAttachments(items: AgentMaterialAttachment[]) {
  const seen = new Set<string>()
  const result: AgentMaterialAttachment[] = []
  for (const item of items) {
    const key = item.url || item.id
    if (!key || seen.has(key)) continue
    seen.add(key)
    result.push(item)
  }
  return result
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
    previewUrl: item.contentType?.startsWith("image/") || isImageAttachment(item.contentType, name) ? resolveAgentFileUrl(url) : undefined,
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
  return normalizeUrlAttachment({
    id: file.id,
    sessionId: file.sessionId,
    name: file.originalFilename || "素材附件",
    contentType: file.contentType,
    size: file.fileSize,
    url: file.downloadUrl,
    source: "agent_file",
  })
}

function readRecentAttachments() {
  const sharedItems = readAllSharedUploadHistory()
  try {
    const parsed = JSON.parse(localStorage.getItem(recentAttachmentStorageKey()) || "[]") as unknown
    const items = Array.isArray(parsed) ? parsed : []
    const localItems = items
      .map((item) => normalizeUrlAttachment(item as AgentUrlAttachment))
      .filter((item): item is AgentMaterialAttachment => Boolean(item))
    recentAttachments.value = dedupeRecentAttachments([...sharedItems, ...localItems]).slice(0, 20)
  } catch {
    recentAttachments.value = sharedItems.slice(0, 20)
  }
}

async function loadRecentAttachments() {
  readRecentAttachments()
  if (!props.token) return
  try {
    const uploadAssetsPage = await fetchUploadAssets({ token: props.token, pageSize: SHARED_UPLOAD_HISTORY_LIMIT })
    const uploadAssetItems = uploadAssetsPage.list
      .map(uploadAssetToMaterialAttachment)
      .filter((item): item is AgentMaterialAttachment => Boolean(item))
    for (const item of uploadAssetItems) {
      rememberSharedUploadHistory(item)
    }
    const page = await fetchRecentAgentFiles(props.sessionId, { token: props.token })
    const serverItems = page.list
      .map(agentFileToMaterialAttachment)
      .filter((item): item is AgentMaterialAttachment => Boolean(item))
    writeRecentAttachments(dedupeRecentAttachments([...uploadAssetItems, ...readAllSharedUploadHistory(), ...serverItems]))
  } catch {
    // Keep localStorage fallback when the server-side material list is temporarily unavailable.
  }
}

function writeRecentAttachments(items: AgentMaterialAttachment[]) {
  recentAttachments.value = items.slice(0, 20)
  localStorage.setItem(recentAttachmentStorageKey(), JSON.stringify(recentAttachments.value))
}

function rememberRecentAttachment(item: AgentUrlAttachment) {
  const normalized = normalizeUrlAttachment(item)
  if (!normalized) return
  const withTime = { ...normalized, uploadedAt: new Date().toISOString() }
  writeRecentAttachments([
    withTime,
    ...recentAttachments.value.filter((entry) => entry.url !== withTime.url && entry.id !== withTime.id),
  ])
}

async function removeRecentAttachment(item: AgentMaterialAttachment) {
  writeRecentAttachments(recentAttachments.value.filter((entry) => entry.id !== item.id && entry.url !== item.url))
  const kind = item.kind || materialKind(item.contentType, item.name)
  writeSharedUploadHistory(kind, readSharedUploadHistory(kind).filter((entry) => entry.id !== item.id && entry.url !== item.url))
  if (item.assetId && props.token) {
    try {
      await deleteUploadAsset(item.assetId, { token: props.token })
    } catch {
      // The local recent list is already cleaned; stale server assets can be retried on refresh.
    }
    return
  }
  const fileId = typeof item.id === "number" ? item.id : Number(item.id)
  const sessionId = item.sessionId ?? sessionIdFromAgentFileUrl(item.url)
  if (!props.token || !Number.isFinite(fileId) || !sessionId) return
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
  if (!props.token || materialAssetsLoading.value) return
  materialAssetsLoading.value = true
  try {
    const page = await fetchTasks({ token: props.token, query: { pageNo: 1, pageSize: 80, status: "SUCCESS" } })
    const seen = new Set<string>()
    materialAssets.value = page.list.flatMap(createMaterialAssets).filter((asset) => {
      const key = `${asset.kind}:${asset.url}`
      if (seen.has(key)) return false
      seen.add(key)
      return true
    }).slice(0, 60)
  } catch {
    materialAssets.value = []
  } finally {
    materialAssetsLoading.value = false
  }
}

async function uploadFiles(
  selectedFiles: File[],
  options: { autoSelect?: boolean } = {},
) {
  if (selectedFiles.length === 0 || !props.token || uploading.value) return
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
      rememberSharedUploadHistory(withTime)
      rememberRecentAttachment(withTime)
      urlAttachments.value = [
        ...urlAttachments.value.filter((entry) => entry.url !== withTime.url),
        withTime,
      ].slice(0, AGENT_REFERENCE_ATTACHMENT_LIMIT)
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
  if (!props.token || removingFileId.value != null) return
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
  const text = content.trim()
  if (!text && files.value.length === 0 && urlAttachments.value.length === 0) return
  if (!props.token || sending.value || editingRegenerating.value || hasActiveRun.value) return
  stickToBottom.value = true
  if (props.modelsLoading) {
    agentError.value = "模型列表仍在加载，请稍等一下再发送。"
    return
  }
  if (!props.modelConfigId) {
    agentError.value = "请先选择一个 Agent 模型。"
    return
  }
  sending.value = true
  agentError.value = null
  confirmationError.value = null
  lastFailedRunId.value = null
  runConnectionStatus.value = "running"
  try {
    input.value = ""
    const submittedFiles = [...files.value]
    const submittedUrlAttachments = [...urlAttachments.value]
    const submittedPreferredToolCode = selectedToolCode.value
    const submittedAttachmentJson = messageContentJsonForFiles(submittedFiles, submittedUrlAttachments)
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
        fileIds: submittedFiles.map((item) => item.id),
        urlAttachments: submittedUrlAttachments.map((item, index) => ({
          id: item.id,
          name: referenceAttachmentLabel(index, item.refLabel || item.name, item.contentType),
          contentType: item.contentType,
          size: item.size,
          url: item.url,
          source: item.source || "url",
        })),
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
  if (!props.token) return
  try {
    const response = await fetchTools({ token: props.token, query: { pageNo: 1, pageSize: 120 } })
    previewTools.value = response.list
  } catch {
    previewTools.value = []
  }
}

async function loadAgentTools() {
  if (!props.token || agentToolsLoading.value) return
  agentToolsLoading.value = true
  try {
    agentTools.value = await fetchAgentTools({ token: props.token })
  } catch {
    agentTools.value = []
  } finally {
    agentToolsLoading.value = false
  }
}

async function retryFailedRun() {
  if (!props.token || retryingRun.value || editingRegenerating.value || regeneratingMessageId.value != null || hasActiveRun.value || lastFailedRunId.value == null) return
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
  if (!props.token || message.role !== "ASSISTANT" || !message.runId) return
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
  if (!props.token || message.role !== "USER" || editingRegenerating.value || regeneratingMessageId.value != null || sending.value || hasActiveRun.value) return
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
    } catch (error) {
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
  editingRegenerating.value = true
  agentError.value = null
  confirmationError.value = null
  lastFailedRunId.value = null
  runConnectionStatus.value = "running"
  events.value = []
  message.contentText = text
  message.editedAt = new Date().toISOString()
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
  } catch (error) {
    message.contentText = previousText
    message.editedAt = previousEditedAt
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
    return "第三方模型平台的内容风控未通过，本次没有扣除生成结果。请换一种更安全、明确的描述后重试。"
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
    agentError.value = null
    creditModalOpen.value = true
    return
  }
  if (error instanceof ApiBusinessError) {
    applyAgentFailure(error)
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
  if (!props.token || !activeRunId.value) return
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
  if (!props.token) return
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
    messages.value = [...mergedMessages, preserved]
    return
  }
  messages.value = mergedMessages
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
      agentError.value = "第三方模型平台的内容风控未通过，本次没有生成结果。请换一种更安全、明确的描述后重试。"
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

function normalizeModality(value?: string | null) {
  return (value || "TEXT").trim().toUpperCase()
}

function recommendToolsForAsset(asset: AssetPreviewItem): AssetPreviewRecommendation[] {
  const target = asset.kind === "image" ? "IMAGE" : asset.kind === "video" ? "VIDEO" : asset.kind === "audio" ? "AUDIO" : ""
  const keyword = asset.kind === "image" ? /图|图片|影像|photo|image|img|改图|参考/i : asset.kind === "video" ? /视频|短片|video|clip|movie/i : /音频|音乐|audio|voice|tts/i
  const matches = previewTools.value.filter((tool) => {
    const input = normalizeModality(tool.inputModality)
    const text = `${tool.toolName} ${tool.description || ""} ${tool.configNote || ""} ${tool.toolCode}`
    return (
      (target && (input.includes(target) || input.includes("MULTIMODAL") || input.includes("FILE"))) ||
      keyword.test(text)
    )
  })
  return (matches.length ? matches : previewTools.value).slice(0, 8)
}

async function openAssetPreview(asset: AssetPreviewItem, message?: AgentMessage) {
  let enriched: AssetPreviewItem = { ...asset }
  const runEvents = message?.runId != null ? runEventsForMessage(message) : []
  const taskId = resolveTaskIdFromRunEvents(runEvents, asset.url)

  if (taskId && props.token) {
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
  openDashboardWithAsset(asset, tool)
  previewAsset.value = null
}

function openPreviewTask(asset: AssetPreviewItem) {
  if (!asset.taskId) {
    previewAsset.value = null
    return
  }
  window.location.href = `/tasks/${asset.taskId}/result`
}

async function publishPreviewAsset(asset: AssetPreviewItem) {
  if (!auth.token || !asset.taskId) return
  try {
    const post = await publishCommunityPost(
      {
        taskId: asset.taskId,
        title: asset.title,
        description: asset.subtitle || null,
        promptVisible: asset.promptVisible ?? auth.user?.promptPublicByDefault ?? false,
      },
      { token: auth.token },
    )
    previewAsset.value = { ...asset, communityPostId: post.id, promptVisible: post.promptVisible }
  } catch (err) {
    const message = err instanceof Error ? err.message : "发布失败"
    window.alert(message)
  }
}

async function unpublishPreviewAsset(asset: AssetPreviewItem) {
  if (!auth.token || !asset.communityPostId) return
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
  persistChatScroll()
}

function scheduleNavLayoutUpdate() {
  navLayoutTick.value += 1
}

function navigateToMessage(messageId: number) {
  const container = messageContainerRef.value
  if (!container) return
  const el = container.querySelector(`[data-message-id="${messageId}"]`) as HTMLElement | null
  el?.scrollIntoView({ behavior: "smooth", block: "center" })
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
  () => props.sessionId,
  () => {
    loadPersistedRunEventCache()
    stickToBottom.value = true
    scrollOffset.value = 0
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
      ref="messageContainerRef"
      class="message-container"
      @scroll.passive="onMessageContainerScroll"
    >
      <ConversationScrollNav
        v-if="!paneLoading && messages.length > 0"
        :nodes="scrollNavNodes"
        @navigate="navigateToMessage"
      />

      <div v-if="paneLoading" class="empty-state">
        <Loader2 class="h-5 w-5 animate-spin" />
      </div>

      <div v-else-if="messages.length === 0" class="empty-state">
        <div class="empty-mark"><Sparkles class="h-6 w-6" /></div>
        <h2>想完成什么，直接告诉我</h2>
        <p>Agent 会先分析需求，推荐合适工具，首次调用前让你确认。</p>
        <div class="suggestions">
          <button v-for="item in suggestions" :key="item" type="button" @click="submitMessage(item)">
            {{ item }}
          </button>
        </div>
      </div>

      <template v-else>
        <template v-for="(message, index) in messages" :key="message.id">
          <div v-if="shouldShowTimeDivider(message, index)" class="time-divider">
            {{ messageDividerTime(message.createdAt) }}
          </div>
          <AgentMessageRow
            :message="message"
            :index="index"
            :run-events="runEventsForMessage(message)"
            :user-avatar-url="auth.user?.avatarUrl"
            :user-display-name="auth.user?.nickname || auth.user?.username || '我'"
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
            :is-streaming="message.id === streamingAssistantMessageId"
            :asset-ref-map="sessionAssetRefMap"
            @copy="copyMessage"
            @start-edit="startEditMessage"
            @cancel-edit="cancelEditMessage"
            @submit-edit="submitEditedMessage"
            @regenerate="regenerateAssistantMessage"
            @preview="(asset, message) => openAssetPreview(asset, message)"
            @reference="addReferenceAttachment"
          />
        </template>

        <article v-if="showGenerationLoading" class="agent-message assistant generating-message">
          <div class="generating-main">
            <div class="assistant-name-row">
              <AgentAvatar state="thinking" />
              <strong>科创点AI</strong>
            </div>
            <div class="thinking-line">
              <span>思考中</span>
              <div class="typing-dots typing-dots--under-avatar" aria-hidden="true">
                <i></i>
                <i></i>
                <i></i>
              </div>
            </div>
          </div>
        </article>

        <article v-if="showInlineRunTimeline" class="agent-message assistant run-progress">
          <div class="avatar">
            <img src="/logo.svg" alt="AI" />
          </div>
          <div class="bubble">
            <RunTimeline :events="events" :inline-mode="true" />
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

        <CreditRechargeModal
          v-if="creditModalOpen"
          @close="creditModalOpen = false"
          @credits-updated="creditModalOpen = false"
        />

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
      </template>
    </div>

    <div v-if="!paneLoading && messages.length > 0" class="chat-floating-actions">
      <ConversationPhaseTimeline
        :phases="conversationPhases"
        @navigate="navigateToMessage"
      />
      <button
        v-if="!stickToBottom"
        type="button"
        class="scroll-to-bottom"
        aria-label="回到底部"
        @click="stickToBottom = true; scrollBottom(true)"
      >
        ↓
      </button>
    </div>

    <div ref="composerDockRef" class="composer-dock">
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
        @update:draft="emit('update:draft', $event)"
        @update:selected-tool-code="selectedToolCode = $event"
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
        @refresh-agent-tools="loadAgentTools"
        @add-reference-attachment="addReferenceAttachment"
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
            <button type="button" class="primary-btn" :disabled="memorySaving || memoryWorkspaceId == null" @click="saveMemory">
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
            还没有长期记忆。你可以手动添加，或在对话里明确告诉 Agent “记住……”
          </div>
          <section v-for="group in groupedMemoryItems" v-else :key="group.type" class="memory-group">
            <p class="memory-group-title">{{ group.label }}</p>
            <article v-for="item in group.items" :key="item.id" class="memory-item">
              <div class="memory-item-main">
                <div class="memory-item-title-row">
                  <strong>{{ item.title || `记忆 #${item.id}` }}</strong>
                  <span v-if="item.sourceRunId">Run #{{ item.sourceRunId }}</span>
                  <span v-else>手动/历史</span>
                </div>
                <p>{{ item.content }}</p>
              </div>
              <div class="memory-item-actions">
                <button type="button" class="memory-icon-btn" aria-label="编辑记忆" @click="editMemory(item)">
                  <Pencil class="h-4 w-4" />
                </button>
                <button type="button" class="memory-icon-btn danger" :disabled="memoryDeletingId === item.id" aria-label="删除记忆" @click="removeMemory(item)">
                  <Loader2 v-if="memoryDeletingId === item.id" class="h-4 w-4 animate-spin" />
                  <Trash2 v-else class="h-4 w-4" />
                </button>
              </div>
            </article>
          </section>
        </div>
      </aside>
    </div>
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
    radial-gradient(circle at 50% 100%, rgb(176 92 255 / 0.045), transparent 34%),
    #0a0a0d;
}

.scroll-to-bottom {
  width: 36px;
  height: 36px;
  border-radius: 999px;
  border: 1px solid rgb(255 255 255 / 0.12);
  background: rgb(24 24 28 / 0.88);
  color: rgb(255 255 255 / 0.78);
  cursor: pointer;
  backdrop-filter: blur(12px);
  box-shadow: 0 8px 24px rgb(0 0 0 / 0.35);
  transition: transform 0.18s ease, border-color 0.18s ease;
  flex-shrink: 0;
}

.scroll-to-bottom:hover {
  transform: translateY(-2px);
  border-color: var(--agent-accent-soft);
  color: #fff;
}

.chat-scroll-anchor {
  flex-shrink: 0;
  width: 100%;
  pointer-events: none;
}

.chat-floating-actions {
  position: absolute;
  left: calc(50% + min(360px, calc(50vw - 56px)) + 12px);
  right: auto;
  top: calc(100% - var(--chat-composer-inset, 210px));
  bottom: auto;
  z-index: 5;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  pointer-events: none;
}

@media (max-width: 900px) {
  .chat-floating-actions {
    left: auto;
    right: 18px;
  }
}

.chat-floating-actions > * {
  pointer-events: auto;
}

.message-container {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  position: relative;
  z-index: 1;
  height: 100%;
  max-height: none;
  padding: 64px clamp(40px, 7vw, 128px) 24px;
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
  padding: 18px 0 max(18px, env(safe-area-inset-bottom));
  background: transparent;
  pointer-events: none;
}

.composer-dock::before {
  content: "";
  position: absolute;
  inset: -80px 0 0;
  z-index: -1;
  background: linear-gradient(180deg, transparent, rgb(10 10 13 / 0.24) 54%, rgb(10 10 13 / 0.36));
  pointer-events: none;
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
  width: min(720px, calc(100% - 112px));
  margin: 0 auto;
  border: 1px solid rgb(255 255 255 / 0.105);
  border-radius: 28px;
  background: var(--agent-composer-bg);
  padding: 10px 12px 11px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-shrink: 0;
  position: relative;
  z-index: 2;
  box-shadow:
    0 -18px 56px var(--agent-accent-glow, rgb(176 92 255 / 0.10)),
    0 24px 72px rgb(0 0 0 / 0.52),
    0 0 0 1px color-mix(in srgb, var(--theme-color), transparent 86%),
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
  min-height: 60vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: rgb(255 255 255 / 0.48);
}

.empty-mark {
  width: 62px;
  height: 62px;
  display: grid;
  place-items: center;
  border-radius: 24px;
  border: 1px solid var(--agent-accent-soft);
  background: linear-gradient(145deg, var(--agent-accent-soft), rgb(255 255 255 / 0.05));
  color: var(--agent-accent);
  animation: breathe-soft 2.8s ease-in-out infinite;
}

.empty-state h2 {
  margin: 18px 0 8px;
  font-size: 28px;
  color: #fff;
}

.suggestions {
  margin-top: 22px;
  display: grid;
  grid-template-columns: repeat(2, minmax(180px, 1fr));
  gap: 10px;
  width: min(620px, 100%);
}

.suggestions button {
  min-height: 44px;
  border: 1px solid rgb(255 255 255 / 0.09);
  border-radius: 18px;
  background: linear-gradient(180deg, rgb(255 255 255 / 0.07), rgb(255 255 255 / 0.035));
  color: rgb(255 255 255 / 0.74);
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease, color 0.18s ease, transform 0.18s ease;
}

.suggestions button:hover {
  border-color: var(--agent-accent-soft);
  background: var(--agent-accent-soft);
  color: #fff;
  transform: translateY(-1px);
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
  animation: message-rise 0.18s ease-out;
}

.generating-main {
  min-width: 0;
  padding-top: 2px;
  margin-left: -4px;
}

.assistant-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 0;
  margin-bottom: 6px;
}

.assistant-name-row :deep(.agent-avatar) {
  width: auto;
  height: auto;
}

.assistant-name-row :deep(.agent-avatar--md) {
  width: 28px;
  height: 28px;
}

.assistant-name-row :deep(.agent-avatar__logo) {
  width: 22px;
  height: 22px;
}

.assistant-name-row strong {
  margin: 0;
  color: var(--agent-text-primary);
  font-size: 16px;
  font-weight: 700;
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
  background: rgb(0 0 0 / 0.48);
  backdrop-filter: blur(8px);
}

.memory-panel {
  width: min(460px, calc(100% - 24px));
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 14px;
  border-left: 1px solid rgb(255 255 255 / 0.10);
  background:
    radial-gradient(circle at 20% 0%, var(--agent-composer-tint), transparent 34%),
    rgb(18 18 22 / 0.96);
  padding: 20px;
  color: rgb(255 255 255 / 0.88);
  box-shadow: -20px 0 80px rgb(0 0 0 / 0.42);
  overflow-y: auto;
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

@media (max-width: 900px) {
  .chat-floating-actions {
    right: 12px;
    left: auto;
    top: calc(100% - var(--chat-composer-inset, 196px));
    bottom: auto;
  }
  .message-container {
    padding: 36px 14px 16px;
  }
  .composer {
    width: calc(100% - 24px);
    border-radius: 24px;
  }
  .composer-dock {
    padding: 14px 0 max(14px, env(safe-area-inset-bottom));
  }
  .suggestions {
    grid-template-columns: 1fr;
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
