<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue"
import { RouterLink, useRoute, useRouter } from "vue-router"
import { AlertCircle, ArrowLeft, Loader2, PanelLeft, Send, RefreshCw, Download } from "lucide-vue-next"
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
const taskHistory = ref<TaskDetail[]>([])
const taskHistoryLoading = ref(false)
const activeHistoryTaskId = ref<number | null>(null)

const runningTaskTimers = new Map<number, ReturnType<typeof setTimeout>>()

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
          type: mediaTypeFromValue(value),
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
    taskHistory.value = response.list
  } finally {
    taskHistoryLoading.value = false
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
  taskHistory.value = []
  activeHistoryTaskId.value = null
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

function buildMessagesFromTask(task: TaskDetail): LocalChatMessage[] {
  const userMessage = buildOptimisticUserMessage(taskPrompt(task), task.params || {})
  userMessage.id = `task-${task.taskId}-user`
  userMessage.timestamp = Date.parse(task.createdAt || "") || Date.now()

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

async function selectTaskHistory(taskId: number) {
  activeHistoryTaskId.value = taskId
  sendError.value = null
  switchingSession.value = true
  try {
    const detail = await fetchTaskById(taskId, { token: auth.token })
    messages.value = buildMessagesFromTask(detail)
    if (!isTerminalStatus(detail.status)) {
      pollTaskUntilDone(detail.taskId)
    }
    await scrollToBottom()
  } catch (e) {
    sendError.value = (e as Error).message || "加载历史任务失败"
  } finally {
    switchingSession.value = false
  }
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
          pending: true,
        }),
      ]
      capabilityRef.value?.resetState()
      activeHistoryTaskId.value = response.taskId
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
        pending: true,
      }),
    ]
    activeHistoryTaskId.value = response.taskId
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

onMounted(() => {
  const savedSidebar = localStorage.getItem(SESSION_SIDEBAR_KEY)
  if (savedSidebar === "0") sessionSidebarOpen.value = false
  if (savedSidebar === "1") sessionSidebarOpen.value = true

  const savedTask = localStorage.getItem(TASK_SIDEBAR_KEY)
  if (savedTask === "0") taskSidebarOpen.value = false
  if (savedTask === "1") taskSidebarOpen.value = true

  void loadTool()
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
          class="relative border-r border-border bg-card transition-all duration-300 overflow-hidden"
        >
          <div v-if="taskSidebarOpen" class="p-3 h-full flex flex-col">
            <div class="mb-3 flex items-center justify-between">
              <p class="text-xs font-medium text-muted-foreground">历史任务</p>
              <button type="button" class="rounded-md border border-border px-2 py-1 text-xs" @click="loadTaskHistory">
                刷新
              </button>
            </div>

            <div v-if="taskHistoryLoading" class="flex justify-center py-6 text-muted-foreground">
              <Loader2 class="h-4 w-4 animate-spin" />
            </div>
            <div v-else-if="taskHistory.length === 0" class="rounded-lg border border-dashed border-border p-4 text-center text-xs text-muted-foreground">
              暂无记录
            </div>
            <div v-else class="flex-1 overflow-y-auto space-y-1">
              <button
                v-for="task in taskHistory"
                :key="task.taskId"
                class="w-full rounded-lg px-3 py-2 text-left hover:bg-secondary transition-colors"
                :class="task.taskId === activeHistoryTaskId ? 'bg-primary/10' : ''"
                @click="selectTaskHistory(task.taskId)"
              >
                <div class="flex items-center justify-between">
                  <span class="truncate text-sm">{{ taskPrompt(task) }}</span>
                  <span class="shrink-0 rounded-full bg-secondary px-1.5 py-0.5 text-[10px] text-muted-foreground">
                    {{ task.status }}
                  </span>
                </div>
                <p class="mt-0.5 truncate font-mono text-[11px] text-muted-foreground">{{ task.taskNo }}</p>
              </button>
            </div>
          </div>
        </aside>

        <div class="flex min-w-0 flex-1 flex-col">
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

            <div v-else class="mx-auto max-w-5xl w-full space-y-6 px-6 py-8">
              <div
                v-for="msg in messages"
                :key="msg.id"
                class="flex flex-col"
                :class="msg.role === 'user' ? 'items-end' : 'items-start'"
              >
                <div class="max-w-full text-base leading-relaxed whitespace-pre-wrap">
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

                <div v-if="msg.resultBlocks?.length" class="mt-3">
                  <ResultRenderer :blocks="msg.resultBlocks" />
                </div>

                <div
                  v-if="msg.role === 'assistant' && !msg.pending && msg.resultBlocks?.length"
                  class="mt-3 flex items-center gap-3"
                >
                  <button
                    type="button"
                    class="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
                    @click="regenerateMessage(messages.find((m, idx, arr) => arr.findIndex(item => item.id === msg.id) > idx))"
                    :disabled="sending"
                  >
                    <RefreshCw class="h-3.5 w-3.5" />
                    重新生成
                  </button>
                  <button
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

          <div class="shrink-0 px-6 py-4 mt-6">
            <div class="mx-auto max-w-5xl rounded-xl border border-gray-200/60 p-4">
              <CapabilityControls
                ref="capabilityRef"
                :capabilities="tool.capabilities || []"
                :fields="tool.fields || []"
                :core-field-key="coreField?.fieldKey"
                :tool-id="tool.id"
                class="mb-2"
              />
              <div class="flex items-center gap-3">
                <textarea
                  v-model="inputText"
                  rows="1"
                  class="max-h-28 min-h-[40px] w-full resize-none border-none bg-transparent text-base outline-none placeholder:text-muted-foreground/70"
                  placeholder="输入消息..."
                  @keydown="handleKeydown"
                />
                <button
                  type="button"
                  class="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary text-white disabled:opacity-50"
                  :disabled="!inputText.trim() || sending"
                  @click="handleSend"
                >
                  <Loader2 v-if="sending" class="h-4 w-4 animate-spin" />
                  <Send v-else class="h-4 w-4" />
                </button>
              </div>
              <div v-if="sendError" class="mt-1 text-xs text-destructive">{{ sendError }}</div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>