<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from "vue"
import { AlertTriangle, Bot, Check, FileText, Loader2, Plus, Send, Sparkles, Store, Upload, X } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import WorkspaceMemoryPanel from "./WorkspaceMemoryPanel.vue"
import RunTimeline from "./RunTimeline.vue"
import { useAuthStore } from "@/store/authStore"
import {
  ApiBusinessError,
  confirmAgentTool,
  createAgentSession,
  fetchAgentMessages,
  fetchAgentFiles,
  fetchAgentRunEvents,
  fetchAgentSessions,
  fetchAgentWorkspaces,
  sendAgentMessage,
  streamAgentRunEvents,
  uploadAgentFile,
} from "@/api"
import type { AgentFile, AgentMessage, AgentRunEvent, AgentSession, AgentWorkspace } from "@/api/types"

const auth = useAuthStore()
const workspaces = ref<AgentWorkspace[]>([])
const activeWorkspaceId = ref<number | null>(null)
const sessions = ref<AgentSession[]>([])
const activeSessionId = ref<number | null>(null)
const messages = ref<AgentMessage[]>([])
const files = ref<AgentFile[]>([])
const events = ref<AgentRunEvent[]>([])
const input = ref("")
const loading = ref(false)
const sending = ref(false)
const uploading = ref(false)
const agentError = ref<string | null>(null)
const rememberTool = ref(true)
const activeRunId = ref<number | null>(null)
const pollTimer = ref<number | null>(null)
const streamController = ref<AbortController | null>(null)
const bottomRef = ref<HTMLElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

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
    .map((event) => ({ event, payload: parseEventJson(event.eventJson) })),
)

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
  return session
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
  if (!text || !auth.token || sending.value) return
  sending.value = true
  agentError.value = null
  try {
    let sessionId = activeSessionId.value
    if (!sessionId) {
      const session = await startSession(text.slice(0, 28))
      sessionId = session?.id ?? null
    }
    if (!sessionId) return
    input.value = ""
    messages.value.push({
      id: Date.now(),
      sessionId,
      role: "USER",
      contentText: text,
      createdAt: new Date().toISOString(),
    })
    const res = await sendAgentMessage(
      sessionId,
      { content: text, clientRequestId: crypto.randomUUID() },
      { token: auth.token },
    )
    activeRunId.value = res.runId
    await pollRun(res.runId, true)
    startRunStream(res.runId)
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
  if (error instanceof ApiBusinessError) {
    return error.message || error.code
  }
  return error instanceof Error ? error.message : "Agent 请求失败，请稍后重试"
}

async function pollRun(runId: number, reset = false) {
  if (!auth.token) return
  const afterEventId = reset ? undefined : events.value.at(-1)?.id
  const res = await fetchAgentRunEvents(runId, { token: auth.token, afterEventId })
  if (reset) events.value = res.list
  else events.value.push(...res.list)
  if (activeSessionId.value) {
    const messageRes = await fetchAgentMessages(activeSessionId.value, { token: auth.token })
    messages.value = messageRes.list
  }
  await scrollBottom()
}

function startPolling(runId: number) {
  stopRunUpdates()
  pollTimer.value = window.setInterval(() => {
    pollRun(runId)
  }, 1600)
}

function startRunStream(runId: number) {
  stopRunUpdates()
  const controller = new AbortController()
  streamController.value = controller
  streamAgentRunEvents(runId, {
    token: auth.token,
    afterEventId: events.value.at(-1)?.id,
    signal: controller.signal,
    onEvent: async (event) => {
      appendRunEvent(event)
      if (event.eventType === "message.completed" || event.eventType === "run.completed") {
        await refreshMessages()
      }
      await scrollBottom()
    },
  }).catch(() => {
    if (!controller.signal.aborted) {
      startPolling(runId)
    }
  })
}

function stopRunUpdates() {
  if (pollTimer.value) {
    window.clearInterval(pollTimer.value)
    pollTimer.value = null
  }
  streamController.value?.abort()
  streamController.value = null
}

async function confirmTool(toolCode: string, approved: boolean) {
  if (!auth.token || !activeRunId.value) return
  await confirmAgentTool(
    activeRunId.value,
    { toolCode, approved, autoCallEnabled: approved && rememberTool.value },
    { token: auth.token },
  )
  if (approved) startRunStream(activeRunId.value)
  else await pollRun(activeRunId.value)
}

async function refreshMessages() {
  if (!auth.token || !activeSessionId.value) return
  const messageRes = await fetchAgentMessages(activeSessionId.value, { token: auth.token })
  messages.value = messageRes.list
}

function appendRunEvent(event: AgentRunEvent) {
  if (events.value.some((item) => item.id === event.id)) return
  events.value.push(event)
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
  void loadWorkspaces()
  void loadSessions()
})
onUnmounted(stopRunUpdates)
</script>

<template>
  <AppShell title="Agent" description="用自然语言让系统推荐、确认并调用工具">
    <div class="agent-page">
      <aside class="agent-sidebar">
        <button class="new-chat" type="button" @click="startSession()">
          <Plus class="h-4 w-4" />
          新会话
        </button>
        <div class="session-list">
          <button
            v-for="session in sessions"
            :key="session.id"
            type="button"
            class="session-item"
            :class="{ active: session.id === activeSessionId }"
            @click="selectSession(session.id)"
          >
            <Bot class="h-4 w-4" />
            <span>{{ session.title }}</span>
          </button>
        </div>
      </aside>

      <section class="chat-pane">
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
              <div class="bubble">{{ message.contentText }}</div>
            </article>

            <article v-if="agentError" class="agent-error-card">
              <div class="card-icon error"><AlertTriangle class="h-4 w-4" /></div>
              <div class="card-body error">
                <p class="card-title">Agent 暂时无法启动</p>
                <p class="card-desc">{{ agentError }}</p>
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
                  <button type="button" class="ghost-btn" @click="confirmTool(String(payload.toolCode || event.eventText), false)">
                    <X class="h-4 w-4" />
                    取消
                  </button>
                  <button type="button" class="primary-btn" @click="confirmTool(String(payload.toolCode || event.eventText), true)">
                    <Check class="h-4 w-4" />
                    确认调用
                  </button>
                </div>
              </div>
            </article>
          </template>
          <div ref="bottomRef" />
        </div>

        <RunTimeline :events="events" />

        <div class="file-context-bar" v-if="files.length > 0">
          <div v-for="file in files" :key="file.id" class="file-chip" :class="file.status.toLowerCase()">
            <FileText class="h-4 w-4" />
            <span class="file-name">{{ file.originalFilename }}</span>
            <span class="file-meta">{{ file.status }} · {{ formatFileSize(file.fileSize) }}</span>
          </div>
        </div>

        <form class="composer" @submit.prevent="submitMessage()">
          <input ref="fileInputRef" type="file" class="sr-only" @change="handleFileSelected" />
          <button class="upload-btn" type="button" :disabled="uploading || sending" @click="openFilePicker">
            <Loader2 v-if="uploading" class="h-4 w-4 animate-spin" />
            <Upload v-else class="h-4 w-4" />
          </button>
          <textarea
            v-model="input"
            rows="1"
            placeholder="描述你的目标，例如：帮我写一篇小红书种草笔记"
            @keydown.enter.exact.prevent="submitMessage()"
          />
          <button class="send-btn" type="submit" :disabled="sending || !input.trim()">
            <Loader2 v-if="sending" class="h-4 w-4 animate-spin" />
            <Send v-else class="h-4 w-4" />
          </button>
        </form>
      </section>

      <WorkspaceMemoryPanel :workspace-id="activeWorkspaceId" :token="auth.token" />
    </div>
  </AppShell>
</template>

<style scoped>
.agent-page {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr) 320px;
  min-height: calc(100vh - 64px);
}

.agent-sidebar {
  border-right: 1px solid var(--border);
  background: var(--card);
  padding: 14px;
}

.new-chat,
.session-item,
.composer button,
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
}

.session-item {
  width: 100%;
  border: 0;
  background: transparent;
  padding: 10px;
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

.session-item.active,
.session-item:hover {
  background: var(--secondary);
}

.chat-pane {
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  min-width: 0;
}

.message-scroll {
  overflow-y: auto;
  padding: 28px clamp(18px, 4vw, 64px);
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

.file-chip {
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

.composer textarea {
  min-height: 42px;
  max-height: 160px;
  resize: vertical;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--foreground);
  font: inherit;
  line-height: 1.5;
}

.composer button {
  width: 44px;
  height: 42px;
  border: 0;
  background: var(--foreground);
  color: var(--primary-foreground);
}

.composer button:disabled {
  opacity: 0.5;
}

@media (max-width: 900px) {
  .agent-page {
    grid-template-columns: 1fr;
  }

  .agent-sidebar {
    display: none;
  }

  :deep(.workspace-memory-panel) {
    border-left: 0;
    border-top: 1px solid var(--border);
  }

  .suggestions {
    grid-template-columns: 1fr;
  }

  .agent-message,
  .agent-error-card,
  .confirmation-card {
    grid-template-columns: 30px minmax(0, 1fr);
  }
}
</style>
