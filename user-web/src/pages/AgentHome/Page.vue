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
    PanelLeft,
    PanelLeftClose,
    Plus,
    Send,
    Sparkles,
    Store,
    Trash2,
    Upload,
    X,
  } from "lucide-vue-next"
  import AppShell from "@/components/AppShell.vue"
  {/* import WorkspaceMemoryPanel from "./WorkspaceMemoryPanel.vue" */}
  import RunTimeline from "./RunTimeline.vue"
  import ChatMessage from "./ChatMessage.vue"
  import { useAuthStore } from "@/store/authStore"
  import {
    ApiBusinessError,
    confirmAgentTool,
    createAgentSession,
    deleteAgentSession,
    fetchAgentMessages,
    fetchAgentFiles,
    fetchAgentRun,
    fetchAgentRunEvents,
    fetchAgentSessions,
    fetchAgentWorkspaces,
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
    AgentWorkspace,
  } from "@/api/types"

  const auth = useAuthStore()
  const workspaces = ref<AgentWorkspace[]>([])
  const activeWorkspaceId = ref<number | null>(null)
  const sessions = ref<AgentSession[]>([])
  const activeSessionId = ref<number | null>(null)
  const messages = ref<AgentMessage[]>([])
  const files = ref<AgentFile[]>([])
  const events = ref<AgentRunEvent[]>([])

  // ====================== 【修改开始：会话级草稿隔离】 ======================
  // 草稿存储：每个会话保存自己的输入内容
  const sessionDrafts = ref<Record<number, string>>({})

  // 计算属性：自动绑定当前会话的草稿
  const input = computed({
    get() {
      if (!activeSessionId.value) return ""
      return sessionDrafts.value[activeSessionId.value] || ""
    },
    set(val) {
      if (!activeSessionId.value) return
      sessionDrafts.value[activeSessionId.value] = val
    },
  })
  // ====================== 【修改结束】 ======================

  const loading = ref(false)
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
  const bottomRef = ref<HTMLElement | null>(null)
  const fileInputRef = ref<HTMLInputElement | null>(null)
  const composerTextareaRef = ref<HTMLTextAreaElement | null>(null)
  /** 点击图标放大输入区高度（仅前端布局） */
  const composerExpanded = ref(false)

  function toggleComposerExpanded() {
    composerExpanded.value = !composerExpanded.value
    void nextTick(() => {
      const el = composerTextareaRef.value
      if (el) el.style.removeProperty("height")
      adjustComposerTextareaHeight()
    })
  }

  /** 按内容自动调整输入框高度（受当前折叠/放大上限约束）；先压到 0 再量 scrollHeight，避免只能增高不能收回 */
  function adjustComposerTextareaHeight() {
    const el = composerTextareaRef.value
    if (!el) return
    const minH = composerExpanded.value ? 120 : 42
    const maxH = composerExpanded.value ? Math.min(window.innerHeight * 0.5, 420) : 160
    el.style.overflowY = "hidden"
    el.style.height = "0px"
    void el.offsetHeight
    const scrollH = el.scrollHeight
    const target = Math.min(Math.max(scrollH, minH), maxH)
    el.style.height = `${target}px`
    el.style.overflowY = scrollH > maxH ? "auto" : "hidden"
  }

  watch(input, () => {
    void nextTick(() => adjustComposerTextareaHeight())
  })

  watch(composerExpanded, () => {
    void nextTick(() => adjustComposerTextareaHeight())
  })

  const AGENT_SESSION_SIDEBAR_KEY = "ai_tool_market_agent_session_sidebar_open"
  const sessionSidebarOpen = ref(true)
  const deletingSessionId = ref<number | null>(null)

  function toggleSessionSidebar() {
    sessionSidebarOpen.value = !sessionSidebarOpen.value
  }

  watch(sessionSidebarOpen, (open) => {
    localStorage.setItem(AGENT_SESSION_SIDEBAR_KEY, open ? "1" : "0")
  })

  const suggestions = [
    "帮我写一篇小红书种草笔记",
    "帮我优化一个电商商品标题",
    "给朋友圈生成一段新品文案",
    "我想做公众号长文，先推荐工具",
  ]

  async function loadWorkspaces() {
    if (!auth.token) return
    const res = await fetchAgentWorkspaces({ token: auth.token })
    workspaces.value = res.list
    if (!activeWorkspaceId.value && workspaces.value[0]) {
      activeWorkspaceId.value = workspaces.value[0].id
    }
  }

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

  async function loadSessions() {
    if (!auth.token) return
    loading.value = true
    try {
      const res = await fetchAgentSessions({ token: auth.token })
      sessions.value = res.list
      if (!activeSessionId.value && sessions.value[0]) {
        await selectSession(sessions.value[0].id)
      }
    } finally {
      loading.value = false
    }
  }

  async function selectSession(sessionId: number) {
    if (!auth.token) return
    activeSessionId.value = sessionId
    events.value = []
    agentError.value = null
    confirmationError.value = null
    activeRunId.value = null
    runConnectionStatus.value = "idle"
    stopRunUpdates()
    const res = await fetchAgentMessages(sessionId, { token: auth.token })
    messages.value = res.list
    await loadFiles(sessionId)
    await scrollBottom()
  }

  async function startSession(title = "新的 Agent 会话") {
    if (!auth.token) return null
    const session = await createAgentSession({ title }, { token: auth.token })
    sessions.value = [session, ...sessions.value.filter((item) => item.id !== session.id)]
    activeSessionId.value = session.id
    messages.value = []
    files.value = []
    events.value = []
    agentError.value = null
    confirmationError.value = null
    activeRunId.value = null
    runConnectionStatus.value = "idle"
    return session
  }

  async function removeSession(session: AgentSession, event: MouseEvent) {
    event.stopPropagation()
    if (!auth.token || deletingSessionId.value != null) return
    if (session.id === activeSessionId.value && hasActiveRun.value) {
      agentError.value = "当前会话 Agent 仍在运行，请稍后再删除。"
      return
    }
    if (!confirm(`确定删除「${session.title}」？`)) return
    deletingSessionId.value = session.id
    agentError.value = null
    try {
      await deleteAgentSession(session.id, { token: auth.token })
      const wasActive = activeSessionId.value === session.id
      sessions.value = sessions.value.filter((item) => item.id !== session.id)
      if (wasActive) {
        stopRunUpdates()
        activeSessionId.value = null
        messages.value = []
        files.value = []
        events.value = []
        activeRunId.value = null
        runConnectionStatus.value = "idle"
        confirmationError.value = null
        const next = sessions.value[0]
        if (next) await selectSession(next.id)
      }
    } catch (error) {
      agentError.value = formatAgentError(error)
    } finally {
      deletingSessionId.value = null
    }
  }

  async function loadFiles(sessionId = activeSessionId.value) {
    if (!auth.token || !sessionId) return
    const res = await fetchAgentFiles(sessionId, { token: auth.token })
    files.value = res.list
  }

  function openFilePicker() {
    fileInputRef.value?.click()
  }

  async function handleFileSelected(event: Event) {
    const target = event.target as HTMLInputElement
    const selected = target.files?.[0]
    target.value = ""
    if (!selected || !auth.token || uploading.value) return
    uploading.value = true
    try {
      let sessionId = activeSessionId.value
      if (!sessionId) {
        const session = await startSession(selected.name.slice(0, 28))
        sessionId = session?.id ?? null
      }
      if (!sessionId) return
      const uploaded = await uploadAgentFile(sessionId, selected, { token: auth.token })
      files.value = [uploaded, ...files.value.filter((item) => item.id !== uploaded.id)]
    } finally {
      uploading.value = false
    }
  }

  async function submitMessage(content = input.value) {
    const text = content.trim()
    if (!text || !auth.token || sending.value || hasActiveRun.value) return
    sending.value = true
    agentError.value = null
    confirmationError.value = null
    runConnectionStatus.value = "idle"
    try {
      let sessionId = activeSessionId.value
      if (!sessionId) {
        const session = await startSession(text.slice(0, 28))
        sessionId = session?.id ?? null
      }
      if (!sessionId) return

      // 发送后清空当前会话草稿
      input.value = ""

      messages.value.push({
        id: Date.now(),
        sessionId,
        role: "USER",
        contentText: text,
        createdAt: new Date().toISOString(),
      })
      events.value = []
      const res = await sendAgentMessage(
        sessionId,
        { content: text, clientRequestId: crypto.randomUUID() },
        { token: auth.token },
      )
      activeRunId.value = res.runId
      await waitForRunComplete(res.runId)
    } catch (error) {
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
      return "当前已有 Agent 在运行，请等待上一次执行结束后再试。"
    }
    if (error instanceof ApiBusinessError && error.code === "AGENT_RATE_LIMITED") {
      return "Agent 请求过于频繁，请稍后再试。"
    }
    if (error instanceof ApiBusinessError && error.code === "AGENT_CREDIT_NOT_ENOUGH") {
      return "可用算力不足，暂时无法启动 Agent。请先补充或释放算力后再试。"
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
    if (!auth.token) return
    const afterEventId = events.value.length ? events.value.at(-1)!.id : undefined
    const res = await fetchAgentRunEvents(runId, { token: auth.token, afterEventId })
    res.list.forEach(appendRunEvent)
  }

  async function waitForRunComplete(runId: number) {
    stopRunUpdates()
    runConnectionStatus.value = "running"

    const POLL_INTERVAL_MS = 1200
    const MAX_WAIT_MS = 5 * 60 * 1000
    const startTime = Date.now()

    try {
      while (Date.now() - startTime < MAX_WAIT_MS) {
        const run = await fetchAgentRun(runId, { token: auth.token })

        if (run.status === "WAITING_USER_CONFIRMATION") {
          await syncRunEvents(runId)
          runConnectionStatus.value = "awaiting_confirmation"
          await scrollBottom()
          return
        }

        if (isTerminalRunStatus(run.status)) {
          await syncRunEvents(runId)
          stopRunUpdates()
          settleRunStatus(run)
          await refreshMessages()
          await scrollBottom()
          return
        }

        await delay(POLL_INTERVAL_MS)
      }

      runConnectionStatus.value = "failed"
      agentError.value = "Agent 运行超时，请稍后重试。"
      stopRunUpdates()
      activeRunId.value = null
    } catch (error) {
      runConnectionStatus.value = "failed"
      agentError.value = formatAgentError(error)
      stopRunUpdates()
      activeRunId.value = null
    }
  }

  function resumeRunAfterToolConfirmation(runId: number) {
    void waitForRunComplete(runId)
  }

  function stopRunUpdates() {
    /* reserved for future cancellation wiring */
  }

  async function confirmTool(eventId: number, toolCode: string, approved: boolean) {
    if (!auth.token || !activeRunId.value) return
    confirmationError.value = null
    const nextConfirming = new Set(confirmingEventIds.value)
    nextConfirming.add(eventId)
    confirmingEventIds.value = nextConfirming
    try {
      await confirmAgentTool(
        activeRunId.value,
        { toolCode, approved, autoCallEnabled: approved && rememberTool.value },
        { token: auth.token },
      )
      const nextDismissed = new Set(dismissedConfirmationIds.value)
      nextDismissed.add(eventId)
      dismissedConfirmationIds.value = nextDismissed
      if (approved) resumeRunAfterToolConfirmation(activeRunId.value)
      else {
        await syncRunEvents(activeRunId.value)
        await refreshMessages()
        const run = await fetchAgentRun(runId, { token: auth.token })
        if (isTerminalRunStatus(run.status)) {
          stopRunUpdates()
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
    if (!auth.token || !activeSessionId.value) return
    const messageRes = await fetchAgentMessages(activeSessionId.value, { token: auth.token })
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
        agentError.value = errorMessage || "这条请求包含敏感指令或内部信息索取要求，Agent 已拒绝执行。"
        return
      }
      if (errorCode === "AGENT_RUN_BUDGET_EXCEEDED") {
        agentError.value = errorMessage || "这次 Agent 运行超出了当前算力预算。"
        return
      }
      if (errorCode === "AGENT_MODEL_CALL_LIMIT" || errorCode === "AGENT_TOOL_CALL_LIMIT") {
        agentError.value = errorMessage || "这次 Agent 运行已达到安全限制，系统已停止继续执行。"
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

  async function scrollBottom() {
    await nextTick()
    bottomRef.value?.scrollIntoView({ block: "end" })
  }

  onMounted(() => {
    window.addEventListener("resize", adjustComposerTextareaHeight)
    const saved = localStorage.getItem(AGENT_SESSION_SIDEBAR_KEY)
    if (saved === "0") sessionSidebarOpen.value = false
    if (saved === "1") sessionSidebarOpen.value = true

    void loadWorkspaces()
    void loadSessions()
    void nextTick(() => adjustComposerTextareaHeight())
  })
  onUnmounted(() => {
    window.removeEventListener("resize", adjustComposerTextareaHeight)
    stopRunUpdates()
  })
</script>

<template>
    <AppShell title="Agent" description="用自然语言让系统推荐、确认并调用工具">
      <div class="agent-page" :class="{ 'agent-page--session-collapsed': !sessionSidebarOpen }">
        <aside class="agent-sidebar" :class="{ 'agent-sidebar--collapsed': !sessionSidebarOpen }">
          <button class="new-chat" type="button" @click="startSession()">
            <Plus class="h-4 w-4" />
            新会话
          </button>
          <div class="session-list">
            <div
              v-for="session in sessions"
              :key="session.id"
              class="session-row"
              :class="{ active: session.id === activeSessionId }"
            >
              <button type="button" class="session-item" @click="selectSession(session.id)">
                <Bot class="h-4 w-4 shrink-0" />
                <span>{{ session.title }}</span>
              </button>
              <button
                type="button"
                class="session-delete"
                :disabled="deletingSessionId === session.id"
                :aria-label="`删除会话：${session.title}`"
                @click="removeSession(session, $event)"
              >
                <Loader2 v-if="deletingSessionId === session.id" class="h-4 w-4 animate-spin" aria-hidden="true" />
                <Trash2 v-else class="h-4 w-4" aria-hidden="true" />
              </button>
            </div>
          </div>
        </aside>

        <section class="chat-pane">
          <div class="session-sidebar-toggle-row">
            <button
              type="button"
              class="session-sidebar-toggle-btn"
              :aria-label="sessionSidebarOpen ? '隐藏会话列表' : '显示会话列表'"
              :aria-expanded="sessionSidebarOpen"
              @click="toggleSessionSidebar"
            >
              <PanelLeftClose v-if="sessionSidebarOpen" class="h-4 w-4" aria-hidden="true" />
              <PanelLeft v-else class="h-4 w-4" aria-hidden="true" />
              <span>{{ sessionSidebarOpen ? '隐藏会话列表' : '显示会话列表' }}</span>
            </button>
          </div>
          <div class="message-scroll">
            <div v-if="loading" class="empty-state">
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
            </template>
            <div ref="bottomRef" />
          </div>

          <div class="file-context-bar" v-if="files.length > 0">
            <div v-for="file in files" :key="file.id" class="file-chip" :class="file.status.toLowerCase()">
              <FileText class="h-4 w-4" />
              <span class="file-name">{{ file.originalFilename }}</span>
              <span class="file-meta">{{ file.status }} · {{ formatFileSize(file.fileSize) }}</span>
              <button 
                  <button 
                  type="button"
                  class="file-delete-btn"
                  @click="files = files.filter(item => item.id !== file.id)"
                >
                  <X class="h-3 w-3" />
                </button>
            </div>
          </div>

          <form class="composer" @submit.prevent="submitMessage()">
            <input ref="fileInputRef" type="file" class="sr-only" @change="handleFileSelected" />
            <button class="upload-btn" type="button" :disabled="uploading || sending || hasActiveRun" @click="openFilePicker">
              <Loader2 v-if="uploading" class="h-4 w-4 animate-spin" />
              <Upload v-else class="h-4 w-4" />
            </button>
            <div class="composer-field">
              <textarea
                ref="composerTextareaRef"
                v-model="input"
                rows="1"
                class="composer-textarea"
                :class="{ 'composer-textarea--expanded': composerExpanded }"
                :placeholder="hasActiveRun ? 'Agent 正在处理当前请求' : '描述你的目标，例如：帮我写一篇小红书种草笔记'"
                :disabled="hasActiveRun"
                @keydown.enter.exact.prevent="submitMessage()"
              />
              <button
                type="button"
                class="composer-expand-btn"
                :disabled="hasActiveRun"
                :aria-label="composerExpanded ? '缩小输入框' : '放大输入框'"
                :aria-pressed="composerExpanded"
                @click="toggleComposerExpanded"
              >
                <Minimize2 v-if="composerExpanded" class="h-4 w-4" aria-hidden="true" />
                <Maximize2 v-else class="h-4 w-4" aria-hidden="true" />
              </button>
            </div>
            <button class="send-btn" type="submit" :disabled="sending || hasActiveRun || !input.trim()">
              <Loader2 v-if="sending" class="h-4 w-4 animate-spin" />
              <Send v-else class="h-4 w-4" />
            </button>
          </form>
        </section>

        <div class="agent-memory-panel">
          <WorkspaceMemoryPanel :workspace-id="activeWorkspaceId" :token="auth.token" />
        </div>
      </div>
    </AppShell>
</template>

<style scoped>
  .agent-page {
    display: grid;
    grid-template-columns: 280px minmax(0, 1fr) 320px;
    min-height: calc(100vh - 64px);
  }

  .agent-page--session-collapsed {
    grid-template-columns: 0 minmax(0, 1fr) 320px;
  }

  .agent-sidebar {
    border-right: 1px solid var(--border);
    background: var(--card);
    padding: 14px;
    min-width: 0;
    transition: opacity 0.15s ease, padding 0.15s ease;
    height: calc(100vh - 64px);
    overflow-y: auto;
    overflow-x: hidden;
    display: flex;
    flex-direction: column;
  }

  .agent-sidebar--collapsed {
    width: 0;
    max-width: 0;
    padding: 0;
    border-right-width: 0;
    overflow: hidden;
    opacity: 0;
    pointer-events: none;
  }

  .new-chat,
  .session-item,
  .session-delete,
  .composer > button,
  .primary-btn,
  .ghost-btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    border-radius: 8px;
  }

  .new-chat {
    width: 100%;
    height: 40px;
    border: 1px solid var(--border);
    background: var(--foreground);
    color: var(--primary-foreground);
    font-size: 14px;
  }

  .session-list {
    margin-top: 14px;
    display: flex;
    flex-direction: column;
    gap: 6px;
    flex: 1;
    min-height: 0;
  }

  .session-row {
    display: flex;
    align-items: stretch;
    gap: 2px;
    border-radius: 8px;
    min-width: 0;
  }

  .session-row.active,
  .session-row:hover {
    background: var(--secondary);
  }

  .session-item {
    flex: 1;
    min-width: 0;
    border: 0;
    background: transparent;
    padding: 10px 6px 10px 10px;
    color: var(--foreground);
    font-size: 13px;
    text-align: left;
  }

  .session-item span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .session-delete {
    flex-shrink: 0;
    width: 36px;
    border: 0;
    background: transparent;
    padding: 0;
    color: var(--muted-foreground);
    cursor: pointer;
  }

  .session-delete:hover:not(:disabled) {
    color: var(--destructive);
  }

  .session-delete:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .chat-pane {
    display: grid;
    grid-template-rows: auto minmax(0, 1fr) auto auto auto;
    min-width: 0;
    min-height: 0;
    height: 100%;
  }

  .session-sidebar-toggle-row {
    display: flex;
    align-items: center;
    padding: 8px 12px;
    border-bottom: 1px solid var(--border);
    background: var(--card);
    flex-shrink: 0;
  }

  .session-sidebar-toggle-btn {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 6px 10px;
    border-radius: 8px;
    border: 1px solid var(--border);
    background: var(--background);
    color: var(--foreground);
    font-size: 13px;
    cursor: pointer;
  }

  .session-sidebar-toggle-btn:hover {
    background: var(--secondary);
  }

  .message-scroll {
    overflow-y: auto;
    height: 100%;
    padding: 28px clamp(18px, 4vw, 64px);
    max-height: calc(100vh - 220px);
    flex: 1;
    min-height: 0;
  }

  .empty-state {
    min-height: 58vh;
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

  .confirmation-card {
    display: grid;
    grid-template-columns: 34px minmax(0, 760px);
    gap: 12px;
    max-width: 900px;
    margin: 18px auto;
  }

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
    color: #93370d;
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

  .composer {
    margin: 0 auto 24px;
    width: min(860px, calc(100% - 32px));
    display: grid;
    grid-template-columns: 44px minmax(0, 1fr) 44px;
    gap: 10px;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--card);
    padding: 10px;
  }

  .file-context-bar {
    width: min(860px, calc(100% - 32px));
    margin: 0 auto 10px;
    display: flex;
    gap: 8px;
    overflow-x: auto;
  }

.file-delete-btn {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  border: none;
  background: #00000080;
  color: white;
  cursor: pointer;
  opacity: 0; /* 默认隐藏 */
  transition: opacity 0.15s ease;
}

.file-chip:hover .file-delete-btn {
  opacity: 1; /* hover 时才显示 */
}
  .file-chip {
    position: relative;
    min-width: 0;
    max-width: 260px;
    display: inline-grid;
    grid-template-columns: 16px minmax(80px, 1fr);
    column-gap: 8px;
    row-gap: 2px;
    align-items: center;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--card);
    color: var(--foreground);
    padding: 8px 10px;
    font-size: 12px;
  }

  .file-chip.ready {
    border-color: color-mix(in srgb, var(--foreground) 24%, var(--border));
  }

  .file-chip.failed {
    color: #b42318;
  }

  .file-name {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .file-meta {
    grid-column: 2;
    color: var(--muted-foreground);
    font-size: 11px;
  }

  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
  }

  .composer-field {
    position: relative;
    min-width: 0;
    display: flex;
    align-items: stretch;
  }

  .composer-textarea {
    width: 100%;
    min-height: 42px;
    max-height: 160px;
    resize: none;
    overflow-x: hidden;
    border: 0;
    outline: 0;
    background: transparent;
    color: var(--foreground);
    font: inherit;
    line-height: 1.5;
    padding-right: 36px;
    box-sizing: border-box;
  }

  .composer-textarea--expanded {
    min-height: 120px;
    max-height: min(50vh, 420px);
  }

  .composer-expand-btn {
    position: absolute;
    right: 2px;
    bottom: 6px;
    z-index: 1;
    display: grid;
    place-items: center;
    width: 28px;
    height: 28px;
    padding: 0;
    border: 0;
    border-radius: 6px;
    background: color-mix(in srgb, var(--card) 88%, var(--muted-foreground));
    color: var(--muted-foreground);
    cursor: pointer;
    box-shadow: 0 0 0 1px color-mix(in srgb, var(--border) 80%, transparent);
  }

  .composer-expand-btn:hover:not(:disabled) {
    color: var(--foreground);
    background: var(--secondary);
  }

  .composer-expand-btn:disabled {
    cursor: not-allowed;
    opacity: 0.45;
  }

  .composer-textarea:disabled {
    cursor: not-allowed;
    opacity: 0.7;
  }

  .composer > button {
    width: 44px;
    height: 42px;
    border: 0;
    background: var(--foreground);
    color: var(--primary-foreground);
  }

  .composer > button:disabled {
    opacity: 0.5;
  }

  .agent-memory-panel {
    min-width: 0;
    min-height: 0;
  }

  @media (max-width: 900px) {
    .agent-page {
      grid-template-columns: minmax(140px, 36vw) minmax(0, 1fr);
      grid-template-rows: minmax(0, 1fr) auto;
    }

    .agent-page--session-collapsed {
      grid-template-columns: 0 minmax(0, 1fr);
    }

    .agent-memory-panel {
      grid-column: 1 / -1;
      border-top: 1px solid var(--border);
    }

    :deep(.workspace-memory-panel) {
      border-left: 0;
    }

    .suggestions {
      grid-template-columns: 1fr;
    }

    .agent-message,
    .agent-error-card,
    .confirmation-card {
      grid-template-columns: 30px minmax(0, 1fr);
    }

    .run-status-card {
      max-width: 100%;
    }

    .agent-sidebar::-webkit-scrollbar {
      width: 4px;
    }
    .agent-sidebar::-webkit-scrollbar-thumb {
      background: var(--muted-foreground);
      border-radius: 4px;
      opacity: 0;
      transition: opacity 0.2s;
    }
    .agent-sidebar:hover::-webkit-scrollbar-thumb {
      opacity: 1;
    }
  }
</style>