<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue"
import {
  AlertTriangle,
  Bot,
  Check,
  FileText,
  Loader2,
  Maximize2,
  Minimize2,
  Send,
  Sparkles,
  Store,
  Upload,
  X,
  StopCircle
} from "lucide-vue-next"
import RunTimeline from "./RunTimeline.vue"
import ChatMessage from "./ChatMessage.vue"
import {
  ApiBusinessError,
  cancelAgentRun,
  confirmAgentTool,
  fetchAgentMessages,
  fetchAgentFiles,
  fetchAgentRun,
  fetchAgentRunEvents,
  sendAgentMessage,
  uploadAgentFile,
} from "@/api"
import type {
  AgentFile,
  AgentMessage,
  AgentRun,
  AgentRunEvent,
  AgentRunStatus,
  AgentSession,
} from "@/api/types"

const props = defineProps<{
  sessionId: number
  token: string | null
  draft: string
  sessionSidebarOpen: boolean
  sessions: AgentSession[]
}>()

const emit = defineEmits<{
  "update:draft": [value: string]
  "toggle-session-sidebar": []
}>()

const messages = ref<AgentMessage[]>([])
const files = ref<AgentFile[]>([])
const events = ref<AgentRunEvent[]>([])
const paneLoading = ref(true)
const sending = ref(false)
const uploading = ref(false)
const agentError = ref<string | null>(null)
const rememberTool = ref(true)
const activeRunId = ref<number | null>(null)
const runConnectionStatus = ref<
  "idle" | "running" | "awaiting_confirmation" | "completed" | "failed"
>("idle")
const confirmingEventIds = ref<Set<number>>(new Set())
const confirmationError = ref<string | null>(null)
const dismissedConfirmationIds = ref<Set<number>>(new Set())
const recoveryRunId = ref<number | null>(null)
const showActiveRunLimitHint = ref(false)
const cancellingRun = ref(false)
const bottomRef = ref<HTMLElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const composerTextareaRef = ref<HTMLTextAreaElement | null>(null)
const composerExpanded = ref(false)

const input = computed({
  get: () => props.draft,
  set: (val: string) => emit("update:draft", val),
})

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

const runStatusText = computed(() => {
  if (runConnectionStatus.value === "running") return "Agent 正在运行"
  if (runConnectionStatus.value === "awaiting_confirmation") return "等待你确认工具调用"
  if (runConnectionStatus.value === "completed") return "Agent 已完成"
  if (runConnectionStatus.value === "failed") return "Agent 运行失败"
  return ""
})

const hasActiveRun = computed(() => {
  if (!activeRunId.value) return false
  return (
    runConnectionStatus.value === "running" ||
    runConnectionStatus.value === "awaiting_confirmation"
  )
})

const showRunRecoveryBanner = computed(
  () => recoveryRunId.value != null && (showActiveRunLimitHint.value || hasActiveRun.value),
)

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
    await resumePendingRunForSession()
  } finally {
    paneLoading.value = false
    await scrollBottom()
  }
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
    showActiveRunLimitHint.value = false
    recoveryRunId.value = null
    activeRunId.value = null
    runConnectionStatus.value = "idle"
    events.value = []
    dismissedConfirmationIds.value = new Set()
    await refreshMessages()
  } catch (error) {
    agentError.value = formatAgentError(error)
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
    await scrollBottom()
    return
  }
  if (activeRunId.value && !cancellingRun.value) {
    cancellingRun.value = true
    agentError.value = null
    try {
      await cancelAgentRun(activeRunId.value, { token: props.token })
      activeRunId.value = null
      runConnectionStatus.value = "idle"
      events.value = []
      dismissedConfirmationIds.value = new Set()
      recoveryRunId.value = null
      showActiveRunLimitHint.value = false
      await refreshMessages()
    } catch (error) {
      agentError.value = formatAgentError(error)
    } finally {
      cancellingRun.value = false
      sending.value = false
      await scrollBottom()
    }
  }
}

async function loadFiles() {
  if (!props.token) return
  const res = await fetchAgentFiles(props.sessionId, { token: props.token })
  files.value = res.list
}

function openFilePicker() {
  fileInputRef.value?.click()
}

async function handleFileSelected(event: Event) {
  const target = event.target as HTMLInputElement
  const selected = target.files?.[0]
  target.value = ""
  if (!selected || !props.token || uploading.value) return
  uploading.value = true
  try {
    const uploaded = await uploadAgentFile(props.sessionId, selected, { token: props.token })
    files.value = [uploaded, ...files.value.filter((item) => item.id !== uploaded.id)]
  } finally {
    uploading.value = false
  }
}

async function submitMessage(content = input.value) {
  const text = content.trim()
  if (!text && files.value.length === 0) return
  if (!props.token || sending.value || hasActiveRun.value) return
  sending.value = true
  agentError.value = null
  confirmationError.value = null
  runConnectionStatus.value = "idle"
  try {
    input.value = ""
    messages.value.push({
      id: Date.now(),
      sessionId: props.sessionId,
      role: "USER",
      contentText: text,
      createdAt: new Date().toISOString(),
    })
    events.value = []
    const res = await sendAgentMessage(
      props.sessionId,
      { content: text, clientRequestId: crypto.randomUUID() },
      { token: props.token },
    )
    activeRunId.value = res.runId
    await waitForRunComplete(res.runId)
  } catch (error) {
    if (error instanceof ApiBusinessError && error.code === "AGENT_ACTIVE_RUN_LIMIT") {
      showActiveRunLimitHint.value = true
      const runId = await discoverActiveRunId(props.sessionId)
      if (runId != null) recoveryRunId.value = runId
    }
    agentError.value = formatAgentError(error)
  } finally {
    sending.value = false
    await scrollBottom()
  }
}

function formatAgentError(error: unknown) {
  if (error instanceof ApiBusinessError && error.code === "MODEL_CALL_FAILED") {
    const detail = error.message ? `后端返回：${error.message}` : "后端没有返回更多细节。"
    return `模型连接验证失败。请在管理端检查 provider、baseUrl、API Key、模型名称和 MiniMax Group ID 后重试。${detail}`
  }
  if (error instanceof ApiBusinessError && error.code === "AGENT_ACTIVE_RUN_LIMIT") {
    return "上一次 Agent 任务尚未结束，占用了运行名额。请点击下方「取消进行中的任务」后再发送；若任务仍在进行，也可等待其完成。"
  }
  if (error instanceof ApiBusinessError && error.code === "AGENT_RATE_LIMITED") {
    return "Agent 请求过于频繁，请稍后重试。"
  }
  if (error instanceof ApiBusinessError && error.code === "AGENT_CREDIT_NOT_ENOUGH") {
    return "可用算力不足，暂时无法启动 Agent。请先补充或释放算力。"
  }
  if (error instanceof ApiBusinessError) {
    return error.message || error.code
  }
  return error instanceof Error ? error.message : "Agent 请求失败，请稍后重试"
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

async function syncRunEvents(runId: number) {
  if (!props.token) return
  const afterEventId = events.value.length ? events.value.at(-1)!.id : undefined
  const res = await fetchAgentRunEvents(runId, { token: props.token, afterEventId })
  res.list.forEach(appendRunEvent)
}

async function waitForRunComplete(runId: number) {
  runConnectionStatus.value = "running"
  const POLL_INTERVAL_MS = 1200
  const MAX_WAIT_MS = 5 * 60 * 1000
  const startTime = Date.now()
  try {
    while (Date.now() - startTime < MAX_WAIT_MS) {
      const run = await fetchAgentRun(runId, { token: props.token })
      if (run.status === "WAITING_USER_CONFIRMATION") {
        await syncRunEvents(runId)
        runConnectionStatus.value = "awaiting_confirmation"
        await scrollBottom()
        return
      }
      if (isTerminalRunStatus(run.status)) {
        await syncRunEvents(runId)
        settleRunStatus(run)
        await refreshMessages()
        await scrollBottom()
        return
      }
      await delay(POLL_INTERVAL_MS)
    }
    runConnectionStatus.value = "failed"
    agentError.value = "Agent 运行超时，请稍后重试。"
    recoveryRunId.value = runId
    activeRunId.value = null
  } catch (error) {
    runConnectionStatus.value = "failed"
    agentError.value = formatAgentError(error)
    recoveryRunId.value = runId
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

async function refreshMessages() {
  if (!props.token) return
  const messageRes = await fetchAgentMessages(props.sessionId, { token: props.token })
  messages.value = messageRes.list
}

function appendRunEvent(event: AgentRunEvent) {
  if (events.value.some((item) => item.id === event.id)) return
  events.value.push(event)
  if (event.eventType === "run.started") {
    agentError.value = null
    runConnectionStatus.value = "running"
  }
  if (event.eventType === "run.failed") {
    const payload = parseEventJson(event.eventJson)
    const errorCode = typeof payload.errorCode === "string" ? payload.errorCode : ""
    const errorMessage = typeof payload.errorMessage === "string" ? payload.errorMessage : event.eventText
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
    if (errorCode === "MODEL_CALL_FAILED") {
      agentError.value = errorMessage || "模型调用失败，请稍后重试。"
      return
    }
    agentError.value = errorMessage || "Agent 运行失败，请稍后再试。"
  }
}

function settleRunStatus(run?: AgentRun) {
  if (run) {
    runConnectionStatus.value = run.status === "FAILED" ? "failed" : "completed"
  } else {
    const terminalEvent = [...events.value].reverse().find(isTerminalRunEvent)
    runConnectionStatus.value = terminalEvent?.eventType === "run.failed" ? "failed" : "completed"
  }
  activeRunId.value = null
  recoveryRunId.value = null
  showActiveRunLimitHint.value = false
}

function isTerminalRunEvent(event: AgentRunEvent) {
  return event.eventType === "run.completed" || event.eventType === "run.failed"
}

function parseEventJson(value?: string | null) {
  if (!value) return {} as Record<string, unknown>
  try {
    return JSON.parse(value) as Record<string, unknown>
  } catch {
    return {} as Record<string, unknown>
  }
}

function messageClass(role: string) {
  return role === "USER" ? "agent-message user" : "agent-message assistant"
}

function formatFileSize(size: number) {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function toggleComposerExpanded() {
  composerExpanded.value = !composerExpanded.value
  void nextTick(() => {
    const el = composerTextareaRef.value
    if (el) el.style.removeProperty("height")
    adjustComposerTextareaHeight()
  })
}

function adjustComposerTextareaHeight() {
  const el = composerTextareaRef.value
  if (!el) return
  const minH = composerExpanded.value ? 120 : 28
  const maxH = composerExpanded.value ? Math.min(window.innerHeight * 0.5, 420) : 150
  el.style.overflowY = "hidden"
  el.style.height = "0px"
  void el.offsetHeight
  const scrollH = el.scrollHeight
  const target = Math.min(Math.max(scrollH, minH), maxH)
  el.style.height = `${target}px`
  el.style.overflowY = scrollH > maxH ? "auto" : "hidden"
}

async function scrollBottom() {
  await nextTick()
  bottomRef.value?.scrollIntoView({ block: "end" })
}

function showError(message: string) {
  agentError.value = message
}

watch(input, () => {
  void nextTick(() => adjustComposerTextareaHeight())
})

watch(composerExpanded, () => {
  void nextTick(() => adjustComposerTextareaHeight())
})

onMounted(() => {
  window.addEventListener("resize", adjustComposerTextareaHeight)
  void loadPane()
  void nextTick(() => adjustComposerTextareaHeight())
})

onUnmounted(() => {
  window.removeEventListener("resize", adjustComposerTextareaHeight())
})

defineExpose({
  hasActiveRun,
  showError,
})
</script>

<template>
  <div class="agent-chat-pane">
    <!-- 聊天内容区域（内部独立滚动）-->
    <div class="message-container">
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
        <article v-for="message in messages" :key="message.id" :class="messageClass(message.role)">
          <div class="avatar">
            <Bot v-if="message.role !== 'USER'" class="h-4 w-4" />
            <span v-else>我</span>
          </div>
          <div class="bubble">
            <ChatMessage :message="message.contentText" :is-user="message.role === 'USER'" />
          </div>
        </article>

        <article v-if="events.length" class="agent-message assistant run-progress">
          <div class="avatar">
            <Bot class="h-4 w-4" />
          </div>
          <div class="bubble">
            <RunTimeline :events="events" :inline-mode="true" />
          </div>
        </article>

        <article v-if="runStatusText" class="run-status-card" :class="runConnectionStatus">
          <Loader2
            v-if="runConnectionStatus === 'running' || runConnectionStatus === 'awaiting_confirmation'"
            class="h-4 w-4 animate-spin"
          />
          <Check v-else-if="runConnectionStatus === 'completed'" class="h-4 w-4" />
          <AlertTriangle v-else-if="runConnectionStatus === 'failed'" class="h-4 w-4" />
          <span>{{ runStatusText }}</span>
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
          </div>
        </article>

        <article v-if="confirmationError" class="agent-error-card">
          <div class="card-icon error"><AlertTriangle class="h-4 w-4" /></div>
          <div class="card-body error">
            <p class="card-title">工具确认失败</p>
            <p class="card-desc">{{ confirmationError }}</p>
          </div>
        </article>

        <article
          v-for="{ event, payload } in confirmationEvents"
          :key="event.id"
          class="confirmation-card"
        >
          <div class="card-icon"><Store class="h-4 w-4" /></div>
          <div class="card-body">
            <p class="card-title">建议调用 {{ payload.toolName || payload.toolCode || event.eventText }}</p>
            <p class="card-desc">{{ payload.description || "确认后 Agent 会继续执行该工具并生成结果。" }}</p>
            <label class="remember-row">
              <input v-model="rememberTool" type="checkbox" />
              以后类似需求自动调用这个工具
            </label>
            <div class="card-actions">
              <button
                type="button"
                class="ghost-btn"
                :disabled="confirmingEventIds.has(event.id)"
                @click="confirmTool(event.id, String(payload.toolCode || event.eventText), false)"
              >
                <Loader2 v-if="confirmingEventIds.has(event.id)" class="h-4 w-4 animate-spin" />
                <X v-else class="h-4 w-4" />
                取消
              </button>
              <button
                type="button"
                class="primary-btn"
                :disabled="confirmingEventIds.has(event.id)"
                @click="confirmTool(event.id, String(payload.toolCode || event.eventText), true)"
              >
                <Loader2 v-if="confirmingEventIds.has(event.id)" class="h-4 w-4 animate-spin" />
                <Check v-else class="h-4 w-4" />
                确认调用
              </button>
            </div>
          </div>
        </article>

        <div ref="bottomRef" />
      </template>
    </div>

    <!-- 输入框区域（固定在底部，不滚动）-->
    <form class="composer" @submit.prevent="submitMessage()">
      <input ref="fileInputRef" type="file" class="sr-only" @change="handleFileSelected" />

      <!-- 上传的文件显示在输入框内部 -->
      <div v-if="files.length > 0" class="inner-file-list">
        <div v-for="file in files" :key="file.id" class="inner-file-item">
          <FileText class="h-4 w-4" />
          <div class="inner-file-info">
            <span class="inner-file-name">{{ file.originalFilename }}</span>
            <span class="inner-file-size">{{ formatFileSize(file.fileSize) }}</span>
          </div>
          <button class="inner-file-close" @click="files = files.filter(x => x.id !== file.id)">
            <X class="h-3 w-3" />
          </button>
        </div>
      </div>

      <div class="input-wrap">
        <textarea
          ref="composerTextareaRef"
          v-model="input"
          rows="1"
          class="chat-input"
          :class="{ 'input-expand': composerExpanded }"
          :placeholder="hasActiveRun ? 'Agent 正在处理当前请求' : '输入消息，回车发送'"
          :disabled="hasActiveRun"
          @keydown.enter.exact.prevent="submitMessage()"
        />
        <button
          class="expand-btn"
          type="button"
          :disabled="hasActiveRun"
          @click="toggleComposerExpanded"
        >
          <Minimize2 v-if="composerExpanded" class="h-4 w-4" />
          <Maximize2 v-else class="h-4 w-4" />
        </button>
      </div>

      <div class="toolbar-row">
        <div class="left-tools">
          <button class="tool-btn" :disabled="uploading || sending || hasActiveRun" @click="openFilePicker">
            <Upload class="h-4 w-4" />
            附件
          </button>
          <button class="tool-btn">
            <Sparkles class="h-4 w-4" />
            深度思考
          </button>
          <button class="tool-btn">
            <Store class="h-4 w-4" />
            智能搜索
          </button>
        </div>

        <button
          class="send-circle-btn"
          :class="{ stop: sending || hasActiveRun }"
          :disabled="(!sending && !hasActiveRun && !input.trim() && !files.length) || cancellingRun"
          @click="(sending || hasActiveRun) ? cancelCurrentRun() : submitMessage()"
        >
          <Loader2 v-if="cancellingRun" class="h-4 w-4 animate-spin" />
          <StopCircle v-else-if="sending || hasActiveRun" class="h-4 w-4" />
          <Send v-else class="h-4 w-4" />
        </button>
      </div>
    </form>
  </div>
</template>

<style scoped>
/* 组件最外层 */
.agent-chat-pane {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden !important;
}

.message-container {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 24px;
  height: 100%;
  max-height: calc(100vh - 140px);
}

/* 输入框固定在底部，不参与滚动 */
.composer {
  width: min(860px, calc(100% - 32px));
  margin: 0 auto 16px;
  border: 1px solid var(--border);
  border-radius: 20px;
  background: var(--card);
  padding: 14px 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  flex-shrink: 0;
}

/* 输入框内部文件预览 */
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
  padding: 6px 10px;
  background: var(--secondary);
  border-radius: 10px;
  font-size: 12px;
}
.inner-file-info {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
}
.inner-file-name {
  color: var(--foreground);
  font-weight: 500;
}
.inner-file-size {
  color: var(--muted-foreground);
  font-size: 11px;
}
.inner-file-close {
  background: transparent;
  border: none;
  color: var(--muted-foreground);
  cursor: pointer;
  padding: 2px;
}
.inner-file-close:hover {
  color: var(--foreground);
}

/* 输入框 */
.input-wrap {
  position: relative;
}
.chat-input {
  width: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-size: 14px;
  line-height: 1.6;
  min-height: 28px;
  max-height: 150px;
  resize: none;
  padding: 4px 32px 4px 0;
  color: var(--foreground);
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
  bottom: 4px;
  border: none;
  background: transparent;
  color: var(--muted-foreground);
  cursor: pointer;
  padding: 2px;
}
.expand-btn:hover {
  color: var(--foreground);
}
.expand-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.toolbar-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.left-tools {
  display: flex;
  gap: 8px;
}
.tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  padding: 5px 12px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: transparent;
  color: var(--foreground);
  cursor: pointer;
  transition: all 0.2s;
}
.tool-btn:hover:not(:disabled) {
  background: var(--secondary);
}
.tool-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.send-circle-btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: none;
  background: var(--foreground);
  color: var(--primary-foreground);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background 0.2s;
}
.send-circle-btn.stop {
  background: #f53f3f;
}
.send-circle-btn.stop:hover {
  background: #d92c2c;
}
.send-circle-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 以下是原有样式，保持不变 */
.empty-state {
  min-height: 60vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: var(--muted-foreground);
}

.empty-mark {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border-radius: 12px;
  background: var(--foreground);
  color: var(--primary-foreground);
}

.empty-state h2 {
  margin: 18px 0 8px;
  font-size: 24px;
  color: var(--foreground);
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
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--card);
  color: var(--foreground);
}

.agent-message {
  display: grid;
  grid-template-columns: 34px minmax(0, 760px);
  gap: 12px;
  margin: 18px auto;
  max-width: 900px;
}

.agent-message.user {
  grid-template-columns: minmax(0, 760px) 34px;
}

.agent-message.user .avatar {
  grid-column: 2;
  grid-row: 1;
  background: var(--primary);
  color: var(--primary-foreground);
}

.agent-message.user .bubble {
  grid-column: 1;
  justify-self: end;
  background: var(--foreground);
  color: var(--primary-foreground);
}

.avatar,
.card-icon {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: var(--secondary);
  color: var(--foreground);
  font-size: 12px;
  font-weight: 700;
}

.bubble {
  width: fit-content;
  max-width: 100%;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--card);
  padding: 12px 14px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.agent-message.run-progress .bubble {
  border-color: color-mix(in srgb, var(--foreground) 20%, var(--border));
}

.confirmation-card,
.agent-error-card {
  display: grid;
  grid-template-columns: 34px minmax(0, 760px);
  gap: 12px;
  max-width: 900px;
  margin: 18px auto;
}

.run-status-card {
  width: fit-content;
  max-width: min(860px, calc(100% - 32px));
  min-height: 34px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--card);
  color: var(--muted-foreground);
  font-size: 13px;
  margin: 8px auto 18px;
  padding: 8px 12px;
}

.run-status-card.running,
.run-status-card.awaiting_confirmation {
  border-color: color-mix(in srgb, var(--foreground) 12%, var(--border));
}

.run-status-card.awaiting_confirmation {
  border-color: #fedf89;
  background: #fffcf5;
  color: #933708;
}

.run-status-card.completed {
  border-color: #abefc6;
  background: #f6fef9;
  color: #027a48;
}

.run-status-card.failed {
  border-color: #fecdca;
  background: #fffbfa;
  color: #b42318;
}

.card-icon.error {
  background: #fef3f2;
  color: #b42318;
}

.card-body {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--card);
  padding: 14px;
}

.card-body.error {
  border-color: #fecdca;
  background: #fffbfa;
}

.card-title {
  margin: 0;
  font-weight: 700;
}

.card-desc {
  margin: 6px 0 12px;
  color: var(--muted-foreground);
  font-size: 13px;
}

.remember-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--foreground);
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
  border: 1px solid var(--border);
  padding: 0 12px;
}

.primary-btn:disabled,
.ghost-btn:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.primary-btn {
  border-color: var(--foreground);
  background: var(--foreground);
  color: var(--primary-foreground);
}

.ghost-btn {
  background: var(--card);
  color: var(--foreground);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
}

@media (max-width: 900px) {
  .suggestions {
    grid-template-columns: 1fr;
  }
  .agent-message,
  .confirmation-card,
  .agent-error-card {
    grid-template-columns: 30px minmax(0, 1fr);
  }
  .run-status-card {
    max-width: 100%;
  }
}
</style>