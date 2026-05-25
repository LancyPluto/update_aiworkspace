<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue"
import { RouterLink, useRoute, useRouter } from "vue-router"
import {
  AlertCircle,
  ArrowLeft,
  Download,
  Loader2,
  Maximize2,
  MessageSquare,
  Minimize2,
  PanelLeft,
  Plus,
  RefreshCw,
  Send,
  Trash2,
} from "lucide-vue-next"
import CapabilityControls from "./CapabilityControls.vue"
import ChatSessionSidebar from "./ChatSessionSidebar.vue"
import ResultRenderer from "@/components/ResultRenderer/ResultRenderer.vue"
import { getApiOrigin } from "@/api/client"
import {
  createChatSession,
  deleteChatSession,
  fetchAIToolById,
  fetchChatMessages,
  fetchChatSessions,
  isMarketplaceMockToolId,
  sendChatMessage,
} from "@/api/aiToolApi"
import { createTask, fetchTaskById, fetchTasks, fetchTaskStatus } from "@/api/taskApi"
import type { AITool, ChatMessage, ChatSession } from "@/api/aiToolTypes"
import type { TaskDetail, TaskStatus } from "@/api/types"
import type { ResultBlock } from "@/types/result"
import { ApiBusinessError } from "@/api/client"
import { useAuthStore } from "@/store/authStore"
import { buildTaskResultBlocks } from "@/utils/taskResultBlocks"

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const SESSION_SIDEBAR_KEY = "ai_tool_market_marketplace_session_sidebar_open"
const TASK_SIDEBAR_KEY = "ai_tool_task_sidebar_open"

type LocalChatMessage = ChatMessage & {
  taskId?: number
  taskNo?: string
  taskStatus?: TaskStatus
  progress?: number
  progressMessage?: string
  resultBlocks?: ResultBlock[]
  pending?: boolean
  failed?: boolean
}

type ChatMediaInput = {
  key: string
  label: string
  url: string
  type: "image" | "video" | "audio" | "file"
}

type TaskWindow = {
  id: string
  title: string
  taskIds: number[]
  taskSnapshots?: TaskInputSnapshot[]
  createdAt: number
  updatedAt: number
}

type TaskInputSnapshot = {
  taskId: number
  prompt: string
  params: Record<string, unknown>
  createdAt: number
}

const toolId = computed(() => String(route.params.toolId || ""))
const tool = ref<AITool | null>(null)
const messages = ref<LocalChatMessage[]>([])
const inputText = ref("")
const loading = ref(true)
const loadError = ref<string | null>(null)
const sending = ref(false)
const sendError = ref<string | null>(null)
const capabilityRef = ref<InstanceType<typeof CapabilityControls> | null>(null)
const messagesEndRef = ref<HTMLElement | null>(null)
const activeSessionId = ref<string | null>(null)

const sessions = ref<ChatSession[]>([])
const sessionsLoading = ref(false)
const sessionSidebarOpen = ref(true)
const deletingSessionId = ref<string | null>(null)
const deleteSessionError = ref<string | null>(null)
const switchingSession = ref(false)

const taskSidebarOpen = ref(true)
const taskHistoryLoading = ref(false)
const taskWindows = ref<TaskWindow[]>([])
const activeTaskWindowId = ref<string | null>(null)
const deletingTaskWindowId = ref<string | null>(null)

const runningTaskTimers = new Map<number, ReturnType<typeof setTimeout>>()

// ----- 输入框放大与自动扩高相关 -----
const textareaRef = ref<HTMLTextAreaElement | null>(null)
const isExpanded = ref(false) // 手动放大模式

function autoResizeTextarea() {
  const el = textareaRef.value
  if (!el) return
  if (isExpanded.value) {
    // 手动放大模式：固定高度 120px，超出滚动
    el.style.height = '120px'
    el.style.overflowY = 'auto'
  } else {
    // 自动模式：根据内容高度调整，最大 200px
    el.style.height = 'auto'
    const scrollH = el.scrollHeight
    const newHeight = Math.min(scrollH, 200)
    el.style.height = `${newHeight}px`
    el.style.overflowY = scrollH > 200 ? 'auto' : 'hidden'
  }
}

function toggleExpand() {
  isExpanded.value = !isExpanded.value
  nextTick(() => autoResizeTextarea())
}
// ---------------------------------

const isMarketplaceChat = computed(() => isMarketplaceMockToolId(toolId.value))
const chatBackPath = computed(() => "/marketplace")
const usesTaskChat = computed(() => !isMarketplaceChat.value)

const showWelcome = computed(() => messages.value.length === 0 && !sending.value && !switchingSession.value)
const coreField = computed(() => (tool.value?.fields || []).find((field) => isCoreField(field)) || null)
const inputPlaceholder = computed(() => {
  if (!coreField.value) return "输入消息..."
  return coreField.value.placeholder || `请输入${coreField.value.fieldName}`
})

const chatIconUrl = computed(() => {
  if (!tool.value) return ""
  if (tool.value.modelIconUrl) return tool.value.modelIconUrl
  if (tool.value.mediaDisplayMode !== "effect" && tool.value.iconUrl && !isVideoPreviewUrl(tool.value.iconUrl)) {
    return tool.value.iconUrl
  }
  return ""
})
const chatAvatarText = computed(() => {
  const source = tool.value?.modelConfigName || tool.value?.modelName || tool.value?.name || "AI"
  const latin = source.match(/[A-Za-z0-9]+/g)?.join("") || ""
  if (latin) return latin.slice(0, 2).toUpperCase()
  return source.trim().slice(0, 2) || "AI"
})
const chatAvatarTitle = computed(() => tool.value?.modelConfigName || tool.value?.modelName || tool.value?.name || "AI")

function lastSessionStorageKey(id: string) {
  return `ai_tool_market_marketplace_last_session_${id}`
}

function taskWindowsStorageKey(id: string) {
  return `ai_tool_market_task_windows_${id}`
}

function lastTaskWindowStorageKey(id: string) {
  return `ai_tool_market_last_task_window_${id}`
}

function createTaskWindow(title = "新窗口"): TaskWindow {
  const now = Date.now()
  return {
    id: `window-${now}-${Math.random().toString(36).slice(2, 8)}`,
    title,
    taskIds: [],
    taskSnapshots: [],
    createdAt: now,
    updatedAt: now,
  }
}

function readStoredTaskWindows(): TaskWindow[] {
  try {
    const raw = localStorage.getItem(taskWindowsStorageKey(toolId.value))
    const parsed = raw ? (JSON.parse(raw) as TaskWindow[]) : []
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((item) => item && typeof item.id === "string")
      .map((item) => ({
        id: item.id,
        title: item.title || "新窗口",
        taskIds: Array.isArray(item.taskIds) ? item.taskIds.filter((id) => Number.isFinite(Number(id))).map(Number) : [],
        taskSnapshots: Array.isArray(item.taskSnapshots)
          ? item.taskSnapshots
              .filter((snapshot) => snapshot && Number.isFinite(Number(snapshot.taskId)))
              .map((snapshot) => ({
                taskId: Number(snapshot.taskId),
                prompt: typeof snapshot.prompt === "string" ? snapshot.prompt : "",
                params:
                  snapshot.params && typeof snapshot.params === "object" && !Array.isArray(snapshot.params)
                    ? (snapshot.params as Record<string, unknown>)
                    : {},
                createdAt: Number(snapshot.createdAt) || Date.now(),
              }))
          : [],
        createdAt: Number(item.createdAt) || Date.now(),
        updatedAt: Number(item.updatedAt) || Number(item.createdAt) || Date.now(),
      }))
  } catch {
    return []
  }
}

function persistTaskWindows() {
  localStorage.setItem(taskWindowsStorageKey(toolId.value), JSON.stringify(taskWindows.value))
  if (activeTaskWindowId.value) {
    localStorage.setItem(lastTaskWindowStorageKey(toolId.value), activeTaskWindowId.value)
  }
}

function setTaskWindows(next: TaskWindow[]) {
  taskWindows.value = [...next].sort((a, b) => b.updatedAt - a.updatedAt)
  persistTaskWindows()
}

function isCoreField(field: { options?: unknown; optionsJson?: string | null }): boolean {
  if (field.options && typeof field.options === "object" && !Array.isArray(field.options)) {
    const options = field.options as { core?: unknown; isCore?: unknown }
    if (options.core === true || options.isCore === true) return true
  }
  if (!field.optionsJson) return false
  try {
    const parsed = JSON.parse(field.optionsJson) as unknown
    return Boolean(
      parsed &&
      typeof parsed === "object" &&
      !Array.isArray(parsed) &&
      ((parsed as { core?: unknown }).core === true || (parsed as { isCore?: unknown }).isCore === true),
    )
  } catch {
    return false
  }
}

function normalizeMediaUrl(value?: string | null): string {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

function isVideoPreviewUrl(value?: string | null): boolean {
  const raw = value?.split(/[?#]/)[0]?.toLowerCase() || ""
  return [".mp4", ".webm", ".mov", ".m4v"].some((ext) => raw.endsWith(ext))
}

function isMediaParamKey(key: string): boolean {
  return /(image|img|frame|tail|avatar|reference|audio|voice|video|file|attachment|cover)/i.test(key)
}

function mediaTypeFromValue(value: string): ChatMediaInput["type"] {
  const normalized = value.split("?", 1)[0].toLowerCase()
  if (normalized.startsWith("data:image/") || /\.(png|jpe?g|webp|gif|bmp)$/i.test(normalized)) return "image"
  if (normalized.startsWith("data:video/") || /\.(mp4|mov|webm|m4v)$/i.test(normalized)) return "video"
  if (normalized.startsWith("data:audio/") || /\.(mp3|wav|m4a|ogg|aac)$/i.test(normalized)) return "audio"
  return "file"
}

function mediaTypeFromKey(key: string): ChatMediaInput["type"] | null {
  if (/(image|img|frame|tail|avatar|reference|cover)/i.test(key)) return "image"
  if (/(video|clip|movie)/i.test(key)) return "video"
  if (/(audio|voice|sound|speech|music)/i.test(key)) return "audio"
  return null
}

function labelForMediaParam(key: string): string {
  const labels: Record<string, string> = {
    image: "参考图",
    imageUrl: "参考图",
    image_url: "参考图",
    referenceImage: "参考图",
    referenceImageUrl: "参考图",
    reference_image_url: "参考图",
    firstFrameUrl: "首帧",
    lastFrameUrl: "尾帧",
    imageTail: "尾帧",
    image_tail: "尾帧",
    tailImageUrl: "尾帧",
    avatarImageUrl: "形象图",
    audioUrl: "音频",
    videoUrl: "视频",
    coverUrl: "封面",
  }
  return labels[key] || key
}

function collectMediaInputs(params?: Record<string, unknown>): ChatMediaInput[] {
  if (!params) return []
  const media: ChatMediaInput[] = []
  const addValue = (key: string, raw: unknown) => {
    if (typeof raw === "string" && raw.trim()) {
      const value = raw.trim()
      if (
        value.startsWith("/") ||
        value.startsWith("http://") ||
        value.startsWith("https://") ||
        value.startsWith("data:")
      ) {
        media.push({
          key,
          label: labelForMediaParam(key),
          url: normalizeMediaUrl(value),
          type: mediaTypeFromKey(key) || mediaTypeFromValue(value),
        })
      }
      return
    }
    if (Array.isArray(raw)) {
      raw.forEach((item, index) => addValue(`${key}${index + 1}`, item))
    }
  }
  Object.entries(params).forEach(([key, value]) => {
    if (!isMediaParamKey(key)) return
    addValue(key, value)
  })
  return media
}

async function scrollToBottom() {
  await nextTick()
  messagesEndRef.value?.scrollIntoView({ behavior: "smooth" })
}

async function loadSessionsList() {
  sessionsLoading.value = true
  try {
    sessions.value = await fetchChatSessions(toolId.value, { token: auth.token })
  } finally {
    sessionsLoading.value = false
  }
}

async function loadTaskHistory() {
  if (!usesTaskChat.value || !tool.value) return
  taskHistoryLoading.value = true
  try {
    const response = await fetchTasks({
      token: auth.token,
      query: { pageNo: 1, pageSize: 30, toolCode: tool.value.id },
    })
    mergeTaskWindowsFromHistory(response.list)
  } finally {
    taskHistoryLoading.value = false
  }
}

function mergeTaskWindowsFromHistory(history: TaskDetail[]) {
  const stored = readStoredTaskWindows()
  const claimed = new Set(stored.flatMap((window) => window.taskIds))
  const orphanWindows = history
    .filter((task) => !claimed.has(task.taskId))
    .map((task) => ({
      id: `task-window-${task.taskId}`,
      title: taskPrompt(task),
      taskIds: [task.taskId],
      createdAt: Date.parse(task.createdAt || "") || Date.now(),
      updatedAt: Date.parse(task.finishedAt || task.createdAt || "") || Date.now(),
    }))
  const merged = [...stored, ...orphanWindows]
  setTaskWindows(merged)

  const saved = localStorage.getItem(lastTaskWindowStorageKey(toolId.value))
  const target =
    merged.find((window) => window.id === activeTaskWindowId.value) ||
    merged.find((window) => window.id === saved) ||
    merged[0] ||
    null
  activeTaskWindowId.value = target?.id ?? null
  if (!messages.value.length && target && target.taskIds.length > 0) {
    void selectTaskWindow(target.id)
  }
}

async function selectSession(sessionId: string) {
  if (activeSessionId.value === sessionId && !switchingSession.value) return
  switchingSession.value = true
  sendError.value = null
  activeSessionId.value = sessionId
  try {
    messages.value = await fetchChatMessages(sessionId, { token: auth.token })
    localStorage.setItem(lastSessionStorageKey(toolId.value), sessionId)
    await scrollToBottom()
  } catch (e) {
    loadError.value = (e as Error).message || "加载消息失败"
  } finally {
    switchingSession.value = false
  }
}

async function initMarketplaceSessions() {
  await loadSessionsList()
  if (sessions.value.length === 0) {
    const session = await createChatSession(toolId.value, { token: auth.token })
    sessions.value = [session]
    activeSessionId.value = session.id
    messages.value = []
    localStorage.setItem(lastSessionStorageKey(toolId.value), session.id)
    return
  }
  const saved = localStorage.getItem(lastSessionStorageKey(toolId.value))
  const target = sessions.value.find((s) => s.id === saved) ?? sessions.value[0]
  activeSessionId.value = target.id
  messages.value = await fetchChatMessages(target.id, { token: auth.token })
}

async function startNewSession() {
  deleteSessionError.value = null
  const session = await createChatSession(toolId.value, { token: auth.token })
  sessions.value = [session, ...sessions.value.filter((item) => item.id !== session.id)]
  activeSessionId.value = session.id
  messages.value = []
  sendError.value = null
  localStorage.setItem(lastSessionStorageKey(toolId.value), session.id)
}

async function removeSession(session: ChatSession, event: MouseEvent) {
  event.stopPropagation()
  if (deletingSessionId.value) return
  if (!confirm(`确定删除「${session.title || "新对话"}」？`)) return
  deletingSessionId.value = session.id
  deleteSessionError.value = null
  try {
    await deleteChatSession(session.id, { token: auth.token })
    const wasActive = activeSessionId.value === session.id
    sessions.value = sessions.value.filter((item) => item.id !== session.id)
    if (wasActive) {
      if (sessions.value.length > 0) {
        await selectSession(sessions.value[0].id)
      } else {
        await startNewSession()
      }
    }
  } catch (e) {
    deleteSessionError.value = (e as Error).message || "删除会话失败"
  } finally {
    deletingSessionId.value = null
  }
}

function toggleSessionSidebar() {
  sessionSidebarOpen.value = !sessionSidebarOpen.value
  localStorage.setItem(SESSION_SIDEBAR_KEY, sessionSidebarOpen.value ? "1" : "0")
}

function toggleTaskSidebar() {
  taskSidebarOpen.value = !taskSidebarOpen.value
  localStorage.setItem(TASK_SIDEBAR_KEY, taskSidebarOpen.value ? "1" : "0")
}

async function loadTool() {
  loading.value = true
  loadError.value = null
  sendError.value = null
  messages.value = []
  activeSessionId.value = null
  sessions.value = []
  taskWindows.value = []
  activeTaskWindowId.value = null
  deleteSessionError.value = null
  clearTaskPolling()
  try {
    tool.value = await fetchAIToolById(toolId.value, { token: auth.token })
    if (!tool.value.enabled) {
      router.replace({ path: chatBackPath.value, query: { notice: "offline" } })
      return
    }
    if (isMarketplaceMockToolId(toolId.value)) {
      await initMarketplaceSessions()
    } else {
      await loadTaskHistory()
    }
  } catch (e) {
    if (e instanceof ApiBusinessError && (e.code === "TOOL_NOT_FOUND" || e.code === "TOOL_OFFLINE")) {
      router.replace({ path: chatBackPath.value, query: { notice: "offline" } })
      return
    }
    loadError.value = (e as Error).message || "加载失败"
  } finally {
    loading.value = false
  }
}

function buildOptimisticUserMessage(content: string, params: Record<string, unknown>): LocalChatMessage {
  return {
    id: `temp-user-${Date.now()}`,
    role: "user",
    content,
    timestamp: Date.now(),
    params,
  }
}

function buildAssistantMessage(content: string, extra: Partial<LocalChatMessage> = {}): LocalChatMessage {
  return {
    id: `temp-assistant-${Date.now()}`,
    role: "assistant",
    content,
    timestamp: Date.now(),
    ...extra,
  }
}

function isTerminalStatus(status?: TaskStatus): boolean {
  return Boolean(status && ["SUCCESS", "FAILED", "TIMEOUT", "CANCELLED"].includes(status))
}

function taskPrompt(task: TaskDetail): string {
  const params = task.params || {}
  const value = params.prompt || params.text || params.description || params.videoTopic || params.productName
  return typeof value === "string" && value.trim() ? value.trim() : `任务 ${task.taskNo}`
}

function buildMessagesFromTask(task: TaskDetail, snapshot?: TaskInputSnapshot): LocalChatMessage[] {
  const userParams = Object.keys(snapshot?.params || {}).length > 0 ? snapshot!.params : task.params || {}
  const userPrompt = snapshot?.prompt?.trim() || taskPrompt(task)
  const userMessage = buildOptimisticUserMessage(userPrompt, userParams)
  userMessage.id = `task-${task.taskId}-user`
  userMessage.timestamp = snapshot?.createdAt || Date.parse(task.createdAt || "") || Date.now()

  const assistantContent =
    task.status === "SUCCESS"
      ? "生成完成"
      : task.status === "FAILED" || task.status === "TIMEOUT" || task.status === "CANCELLED"
        ? task.progressMessage || "任务未成功完成"
        : task.progressMessage || "任务处理中"
  const assistantMessage = buildAssistantMessage(assistantContent, {
    id: `task-${task.taskId}-assistant`,
    taskId: task.taskId,
    taskNo: task.taskNo,
    taskStatus: task.status,
    progress: task.progress,
    progressMessage: task.progressMessage,
    pending: !isTerminalStatus(task.status),
    failed: ["FAILED", "TIMEOUT", "CANCELLED"].includes(task.status),
    resultBlocks: task.result?.contentText ? buildTaskResultBlocks(task.result.contentText, task) : undefined,
  })
  assistantMessage.timestamp = Date.parse(task.finishedAt || task.createdAt || "") || Date.now()
  return [userMessage, assistantMessage]
}

async function selectTaskWindow(windowId: string) {
  if (activeTaskWindowId.value === windowId && !switchingSession.value) return
  const window = taskWindows.value.find((item) => item.id === windowId)
  if (!window) return
  activeTaskWindowId.value = windowId
  sendError.value = null
  switchingSession.value = true
  try {
    if (window.taskIds.length === 0) {
      messages.value = []
      persistTaskWindows()
      return
    }
    const snapshots = new Map((window.taskSnapshots || []).map((snapshot) => [snapshot.taskId, snapshot]))
    const details = await Promise.all(window.taskIds.map((id) => fetchTaskById(id, { token: auth.token })))
    messages.value = details.flatMap((detail) => buildMessagesFromTask(detail, snapshots.get(detail.taskId)))
    details.filter((detail) => !isTerminalStatus(detail.status)).forEach((detail) => pollTaskUntilDone(detail.taskId))
    persistTaskWindows()
    await scrollToBottom()
  } catch (e) {
    sendError.value = (e as Error).message || "加载窗口失败"
  } finally {
    switchingSession.value = false
  }
}

function startNewTaskWindow() {
  const window = createTaskWindow()
  setTaskWindows([window, ...taskWindows.value])
  activeTaskWindowId.value = window.id
  messages.value = []
  sendError.value = null
  persistTaskWindows()
  nextTick(() => autoResizeTextarea())
}

function removeTaskWindow(window: TaskWindow, event: MouseEvent) {
  event.stopPropagation()
  if (deletingTaskWindowId.value) return
  if (!confirm(`确定删除「${window.title || "新窗口"}」？任务资产仍会保留在素材库。`)) return
  deletingTaskWindowId.value = window.id
  const wasActive = activeTaskWindowId.value === window.id
  const next = taskWindows.value.filter((item) => item.id !== window.id)
  setTaskWindows(next)
  if (wasActive) {
    const target = next[0] || null
    activeTaskWindowId.value = target?.id ?? null
    if (target) {
      void selectTaskWindow(target.id)
    } else {
      messages.value = []
    }
  }
  deletingTaskWindowId.value = null
}

function appendTaskToActiveWindow(taskId: number, prompt: string, params: Record<string, unknown>) {
  let window = taskWindows.value.find((item) => item.id === activeTaskWindowId.value)
  if (!window) {
    window = createTaskWindow(prompt.slice(0, 28) || "新窗口")
    activeTaskWindowId.value = window.id
    taskWindows.value = [window, ...taskWindows.value]
  }
  const snapshot: TaskInputSnapshot = {
    taskId,
    prompt,
    params: structuredClone(params),
    createdAt: Date.now(),
  }
  const nextWindow = {
    ...window,
    title: window.taskIds.length === 0 ? prompt.slice(0, 28) || "新窗口" : window.title,
    taskIds: [...window.taskIds.filter((id) => id !== taskId), taskId],
    taskSnapshots: [...(window.taskSnapshots || []).filter((item) => item.taskId !== taskId), snapshot],
    updatedAt: Date.now(),
  }
  setTaskWindows(taskWindows.value.map((item) => (item.id === nextWindow.id ? nextWindow : item)))
}

function clearTaskPolling(taskId?: number) {
  if (taskId != null) {
    const timer = runningTaskTimers.get(taskId)
    if (timer) clearTimeout(timer)
    runningTaskTimers.delete(taskId)
    return
  }
  runningTaskTimers.forEach((timer) => clearTimeout(timer))
  runningTaskTimers.clear()
}

function updateAssistantTaskMessage(taskId: number, patch: Partial<LocalChatMessage>) {
  messages.value = messages.value.map((msg) => (msg.taskId === taskId ? { ...msg, ...patch } : msg))
}

async function finalizeTaskInChat(taskId: number) {
  const detail = await fetchTaskById(taskId, { token: auth.token })
  updateAssistantTaskMessage(taskId, {
    content:
      detail.status === "SUCCESS"
        ? "生成完成"
        : detail.progressMessage || "任务未成功完成",
    taskStatus: detail.status,
    progress: detail.progress,
    progressMessage: detail.progressMessage,
    pending: false,
    failed: ["FAILED", "TIMEOUT", "CANCELLED"].includes(detail.status),
    resultBlocks: detail.result?.contentText ? buildTaskResultBlocks(detail.result.contentText, detail) : undefined,
  })
  await loadTaskHistory()
  await scrollToBottom()
}

function pollTaskUntilDone(taskId: number) {
  clearTaskPolling(taskId)
  const tick = async () => {
    try {
      const status = await fetchTaskStatus(taskId, { token: auth.token })
      updateAssistantTaskMessage(taskId, {
        taskStatus: status.status,
        progress: status.progress,
        progressMessage: status.progressMessage,
        content: status.progressMessage || status.status,
        pending: !isTerminalStatus(status.status),
      })
      if (isTerminalStatus(status.status)) {
        clearTaskPolling(taskId)
        await finalizeTaskInChat(taskId)
        return
      }
    } catch (e) {
      updateAssistantTaskMessage(taskId, {
        content: (e as Error).message || "任务状态刷新失败",
        pending: true,
      })
    }
    runningTaskTimers.set(taskId, setTimeout(tick, 2500))
  }
  runningTaskTimers.set(taskId, setTimeout(tick, 1200))
}

async function handleSend() {
  if (!tool.value || sending.value) return
  const content = inputText.value.trim()
  if (!content) return

  const validation = capabilityRef.value?.validate()
  if (validation && !validation.valid) {
    sendError.value = validation.message || "请完善参数"
    return
  }
  if (capabilityRef.value?.hasPendingUploads()) {
    sendError.value = "文件上传中，请稍候"
    return
  }

  sendError.value = null
  const params = capabilityRef.value?.getRequestParams() || {}
  if (coreField.value) {
    params[coreField.value.fieldKey] = content
  }
  const attachments = capabilityRef.value?.getAttachmentIds() || []
  const taskParams = {
    ...params,
    prompt: content,
    text: content,
    attachments,
  }

  messages.value = [...messages.value, buildOptimisticUserMessage(content, params)]
  inputText.value = ""
  sending.value = true
  await scrollToBottom()

  try {
    if (isMarketplaceChat.value) {
      if (!activeSessionId.value) {
        await startNewSession()
      }
      const response = await sendChatMessage(
        {
          toolId: tool.value.id,
          sessionId: activeSessionId.value!,
          content,
          attachments,
          params,
        },
        { token: auth.token },
      )
      messages.value = messages.value.filter((msg) => !msg.id.startsWith("temp-"))
      messages.value = [...messages.value, response.userMessage, response.assistantMessage]
      await loadSessionsList()
      capabilityRef.value?.resetState()
    } else {
      const response = await createTask(
        {
          toolCode: tool.value.id,
          params: taskParams,
          clientRequestId: crypto.randomUUID(),
        },
        { token: auth.token },
      )
      messages.value = [
        ...messages.value,
        buildAssistantMessage(`正在生成…`, {
          taskId: response.taskId,
          taskNo: response.taskNo,
          taskStatus: response.status,
          pending: true,
        }),
      ]
      appendTaskToActiveWindow(response.taskId, content, taskParams)
      capabilityRef.value?.resetState()
      await loadTaskHistory()
      pollTaskUntilDone(response.taskId)
    }
  } catch (e) {
    if (e instanceof ApiBusinessError && (e.code === "CREDIT_NOT_ENOUGH" || e.code === "AGENT_CREDIT_NOT_ENOUGH")) {
      sendError.value = "算力不足，请前往会员与算力页充值后再试"
    } else if (e instanceof ApiBusinessError && e.code === "TOOL_OFFLINE") {
      sendError.value = "该工具已下架，无法创建任务"
    } else if (isMarketplaceChat.value) {
      sendError.value = (e as Error).message || "发送失败"
    } else {
      sendError.value = (e as Error).message || "创建任务失败"
    }
    if (!isMarketplaceChat.value) {
      messages.value = [...messages.value, buildAssistantMessage(sendError.value)]
    } else {
      messages.value = messages.value.filter((msg) => !msg.id.startsWith("temp-"))
    }
  } finally {
    sending.value = false
    await scrollToBottom()
  }
}

function exportMessageContent(content: string) {
  const blob = new Blob([content], { type: "text/plain;charset=utf-8" })
  const url = URL.createObjectURL(blob)
  const a = document.createElement("a")
  a.href = url
  a.download = `文案_${Date.now()}.txt`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function findPrecedingUserMessage(assistantMsg: LocalChatMessage): LocalChatMessage | null {
  const index = messages.value.findIndex((item) => item.id === assistantMsg.id)
  if (index <= 0) return null
  for (let i = index - 1; i >= 0; i--) {
    const candidate = messages.value[i]
    if (candidate.role === "user") return candidate
  }
  return null
}

async function regenerateFromAssistant(assistantMsg: LocalChatMessage) {
  const userMsg = findPrecedingUserMessage(assistantMsg)
  if (!userMsg) return
  await regenerateMessage(userMsg)
}

async function regenerateMessage(msg: LocalChatMessage) {
  if (sending.value || msg.role !== "user") return
  sending.value = true
  await scrollToBottom()
  try {
    const params = msg.params || {}
    const attachments = capabilityRef.value?.getAttachmentIds() || []
    const response = await createTask(
      {
        toolCode: tool.value!.id,
        params: {
          ...params,
          prompt: msg.content,
          text: msg.content,
          attachments,
        },
        clientRequestId: crypto.randomUUID(),
      },
      { token: auth.token },
    )
    messages.value = [
      ...messages.value,
      buildAssistantMessage(`正在重新生成…`, {
        taskId: response.taskId,
        taskNo: response.taskNo,
        taskStatus: response.status,
        pending: true,
      }),
    ]
    appendTaskToActiveWindow(response.taskId, msg.content, {
      ...params,
      prompt: msg.content,
      text: msg.content,
      attachments,
    })
    await loadTaskHistory()
    pollTaskUntilDone(response.taskId)
  } catch (e) {
    sendError.value = (e as Error).message || "重新生成失败"
    messages.value = [...messages.value, buildAssistantMessage(sendError.value)]
  } finally {
    sending.value = false
    await scrollToBottom()
  }
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault()
    void handleSend()
  }
}

watch(
  () => route.params.toolId,
  () => {
    void loadTool()
  },
)

// 监听输入内容变化，自动调整高度
watch(inputText, () => {
  nextTick(() => autoResizeTextarea())
})

onMounted(() => {
  const savedSidebar = localStorage.getItem(SESSION_SIDEBAR_KEY)
  if (savedSidebar === "0") sessionSidebarOpen.value = false
  if (savedSidebar === "1") sessionSidebarOpen.value = true

  const savedTask = localStorage.getItem(TASK_SIDEBAR_KEY)
  if (savedTask === "0") taskSidebarOpen.value = false
  if (savedTask === "1") taskSidebarOpen.value = true

  void loadTool()
  nextTick(() => autoResizeTextarea())
})

onUnmounted(() => {
  clearTaskPolling()
})
</script>

<template>
  <div class="flex h-[calc(100vh-4rem)] flex-col bg-background">
    <header class="flex h-14 shrink-0 items-center justify-between border-b border-border px-4">
      <div class="flex min-w-0 items-center gap-3">
        <RouterLink
          :to="chatBackPath"
          class="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft class="h-4 w-4" />
          返回超市
        </RouterLink>

        <button
          v-if="isMarketplaceChat && tool"
          type="button"
          class="inline-flex h-8 w-8 items-center justify-center rounded-md border border-border text-muted-foreground hover:bg-secondary hover:text-foreground"
          @click="toggleSessionSidebar"
        >
          <PanelLeft class="h-4 w-4" />
        </button>

        <button
          v-else-if="usesTaskChat && tool"
          type="button"
          class="inline-flex h-8 w-8 items-center justify-center rounded-md border border-border text-muted-foreground hover:bg-secondary hover:text-foreground"
          @click="toggleTaskSidebar"
        >
          <PanelLeft class="h-4 w-4" />
        </button>

        <div v-if="tool" class="flex min-w-0 items-center gap-2">
          <img
            v-if="chatIconUrl"
            :src="normalizeMediaUrl(chatIconUrl)"
            :alt="chatAvatarTitle"
            class="h-8 w-8 rounded-full object-cover"
          />
          <span
            v-else
            class="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full border border-border bg-secondary text-[11px] font-semibold text-primary"
            :title="chatAvatarTitle"
          >
            {{ chatAvatarText }}
          </span>
          <span class="truncate text-sm font-semibold">{{ tool.name }}</span>
        </div>
      </div>
    </header>

    <div v-if="loading" class="flex flex-1 items-center justify-center">
      <Loader2 class="h-6 w-6 animate-spin text-muted-foreground" />
    </div>

    <div v-else-if="loadError" class="flex flex-1 flex-col items-center justify-center gap-4 p-6">
      <AlertCircle class="h-10 w-10 text-destructive" />
      <p class="text-sm text-destructive">{{ loadError }}</p>
      <button type="button" class="rounded-md border border-border px-4 py-2 text-sm" @click="loadTool">重试</button>
    </div>

    <template v-else-if="tool">
      <div class="flex min-h-0 flex-1">
        <ChatSessionSidebar
          v-if="isMarketplaceChat"
          :sessions="sessions"
          :active-session-id="activeSessionId"
          :loading="sessionsLoading"
          :deleting-session-id="deletingSessionId"
          :delete-error="deleteSessionError"
          :open="sessionSidebarOpen"
          @create="startNewSession"
          @select="selectSession"
          @delete="removeSession"
        />

        <aside
          v-else
          :class="taskSidebarOpen ? 'w-72' : 'w-0 opacity-0'"
          class="relative overflow-hidden border-r border-border bg-card transition-all duration-300"
        >
          <div v-if="taskSidebarOpen" class="flex h-full flex-col p-3">
            <button
              type="button"
              class="inline-flex h-9 w-full items-center justify-center gap-2 rounded-lg bg-primary text-sm font-medium text-primary-foreground hover:opacity-90"
              @click="startNewTaskWindow"
            >
              <Plus class="h-4 w-4" />
              新建窗口
            </button>

            <div class="mt-3 flex items-center justify-between px-1">
              <p class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">窗口</p>
              <button type="button" class="rounded-md border border-border px-2 py-1 text-xs text-muted-foreground hover:text-foreground" @click="loadTaskHistory">
                <Loader2 v-if="taskHistoryLoading" class="h-3.5 w-3.5 animate-spin" />
                <span v-else>刷新</span>
              </button>
            </div>

            <div v-if="taskHistoryLoading" class="flex justify-center py-6 text-muted-foreground">
              <Loader2 class="h-4 w-4 animate-spin" />
            </div>
            <div v-else-if="taskWindows.length === 0" class="mt-3 rounded-lg border border-dashed border-border p-4 text-center text-xs text-muted-foreground">
              暂无窗口，点击上方新建
            </div>
            <div v-else class="mt-3 min-h-0 flex-1 space-y-1 overflow-y-auto">
              <div
                v-for="window in taskWindows"
                :key="window.id"
                class="group flex items-stretch rounded-lg"
                :class="window.id === activeTaskWindowId ? 'bg-primary/10' : 'hover:bg-secondary/80'"
              >
                <button
                  type="button"
                  class="flex min-w-0 flex-1 items-center gap-2 px-2.5 py-2 text-left"
                  @click="selectTaskWindow(window.id)"
                >
                  <MessageSquare class="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
                  <div class="min-w-0 flex-1">
                    <p class="truncate text-sm">{{ window.title || "新窗口" }}</p>
                    <p class="mt-0.5 text-[11px] text-muted-foreground">{{ window.taskIds.length }} 个任务</p>
                  </div>
                </button>
                <button
                  type="button"
                  class="flex w-8 shrink-0 items-center justify-center text-muted-foreground opacity-0 transition-opacity hover:text-destructive group-hover:opacity-100"
                  :class="window.id === activeTaskWindowId ? 'opacity-100' : ''"
                  :disabled="deletingTaskWindowId === window.id"
                  :aria-label="`删除窗口：${window.title}`"
                  @click="removeTaskWindow(window, $event)"
                >
                  <Loader2 v-if="deletingTaskWindowId === window.id" class="h-3.5 w-3.5 animate-spin" />
                  <Trash2 v-else class="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
          </div>
        </aside>

        <div class="flex min-w-0 flex-1 flex-col bg-muted/20">
          <div class="flex-1 overflow-y-auto">
            <div v-if="switchingSession" class="flex h-full items-center justify-center">
              <Loader2 class="h-5 w-5 animate-spin text-muted-foreground" />
            </div>

            <div
              v-else-if="showWelcome"
              class="flex h-full flex-col items-center justify-center px-6 text-center"
            >
              <div
                class="mb-6 flex h-[120px] w-[120px] items-center justify-center rounded-full ring-2 ring-border"
                :style="{ backgroundColor: tool.primaryColor ? `${tool.primaryColor}18` : undefined }"
              >
                <img
                  v-if="chatIconUrl"
                  :src="normalizeMediaUrl(chatIconUrl)"
                  :alt="chatAvatarTitle"
                  class="h-full w-full object-cover"
                />
                <span v-else class="text-3xl font-semibold text-primary">{{ chatAvatarText }}</span>
              </div>
              <p v-if="tool.welcomeMessage" class="max-w-xl text-sm text-muted-foreground">
                {{ tool.welcomeMessage }}
              </p>
              <p v-else class="max-w-xl text-sm text-muted-foreground">
                输入你的需求，开始对话。
              </p>
            </div>

            <div v-else class="mx-auto w-full max-w-5xl space-y-6 px-6 py-8">
              <div
                v-for="msg in messages"
                :key="msg.id"
                class="flex flex-col"
                :class="msg.role === 'user' ? 'items-end' : 'items-start'"
              >
                <div
                  class="max-w-[min(820px,100%)] whitespace-pre-wrap text-base leading-relaxed"
                  :class="msg.role === 'user'
                    ? 'rounded-2xl bg-primary px-4 py-3 text-primary-foreground shadow-sm'
                    : 'rounded-2xl border border-border bg-card px-4 py-3 text-foreground shadow-sm'"
                >
                  {{ msg.content }}
                </div>

                <div
                  v-if="msg.role === 'user' && collectMediaInputs(msg.params).length"
                  class="mt-3 grid gap-2 sm:grid-cols-2"
                >
                  <div
                    v-for="media in collectMediaInputs(msg.params)"
                    :key="`${msg.id}-${media.key}-${media.url}`"
                    class="overflow-hidden rounded-lg border border-white/10 bg-black/10"
                  >
                    <img
                      v-if="media.type === 'image'"
                      :src="media.url"
                      :alt="media.label"
                      class="max-h-40 w-full object-contain"
                    />
                    <video
                      v-else-if="media.type === 'video'"
                      :src="media.url"
                      controls
                      class="max-h-40 w-full bg-black"
                    />
                    <audio
                      v-else-if="media.type === 'audio'"
                      :src="media.url"
                      controls
                      class="w-full p-2"
                    />
                  </div>
                </div>

                <div v-if="msg.resultBlocks?.length" class="mt-3 w-full">
                  <ResultRenderer :blocks="msg.resultBlocks" />
                </div>

                <div
                  v-if="msg.role === 'assistant' && !msg.pending && (msg.resultBlocks?.length || msg.failed)"
                  class="mt-3 flex items-center gap-3"
                >
                  <button
                    type="button"
                    class="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
                    @click="regenerateFromAssistant(msg)"
                    :disabled="sending"
                  >
                    <RefreshCw class="h-3.5 w-3.5" />
                    重新生成
                  </button>
                  <button
                    v-if="msg.resultBlocks?.length"
                    type="button"
                    class="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
                    @click="exportMessageContent(msg.content)"
                  >
                    <Download class="h-3.5 w-3.5" />
                    导出
                  </button>
                </div>
              </div>

              <div v-if="sending" class="flex items-center text-sm text-muted-foreground">
                <Loader2 class="mr-2 inline h-4 w-4 animate-spin" />
                正在生成回复…
              </div>
              <div ref="messagesEndRef" />
            </div>
          </div>

          <div class="shrink-0 bg-gradient-to-t from-muted/70 via-muted/40 to-transparent px-6 pb-5 pt-3">
            <div
              class="mx-auto max-w-5xl rounded-2xl border border-border/80 bg-background/95 p-4 shadow-[0_18px_60px_rgba(15,23,42,0.12)] backdrop-blur"
            >
              <CapabilityControls
                ref="capabilityRef"
                :capabilities="tool.capabilities || []"
                :fields="tool.fields || []"
                :core-field-key="coreField?.fieldKey"
                :tool-id="tool.id"
                class="mb-2"
              />
              <div class="flex items-end gap-3">
                <div class="min-w-0 flex-1">
                  <textarea
                    ref="textareaRef"
                    v-model="inputText"
                    rows="1"
                    class="max-h-40 min-h-[56px] w-full resize-none rounded-xl border border-transparent bg-secondary/60 px-4 py-3 text-base leading-6 outline-none transition focus:border-primary/40 focus:bg-background placeholder:text-muted-foreground/70"
                    :placeholder="inputPlaceholder"
                    @keydown="handleKeydown"
                  ></textarea>
                </div>
                <button
                  type="button"
                  class="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-full border border-border bg-background text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
                  @click="toggleExpand"
                  title="展开输入框"
                >
                  <Maximize2 v-if="!isExpanded" class="h-4 w-4" />
                  <Minimize2 v-else class="h-4 w-4" />
                </button>
                <button
                  type="button"
                  class="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-primary text-white shadow-sm transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-45"
                  :disabled="!inputText.trim() || sending"
                  @click="handleSend"
                  title="发送"
                >
                  <Loader2 v-if="sending" class="h-4 w-4 animate-spin" />
                  <Send v-else class="h-4 w-4" />
                </button>
              </div>
              <div class="mt-2 flex items-center justify-between gap-3 text-xs text-muted-foreground">
                <span>Enter 发送，Shift + Enter 换行</span>
                <span v-if="sendError" class="text-destructive">{{ sendError }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
textarea {
  transition: height 0.1s ease;
}
</style>
