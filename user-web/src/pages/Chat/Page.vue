<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue"
import { RouterLink, useRoute, useRouter } from "vue-router"
import { AlertCircle, ArrowLeft, ChevronRight, Loader2, PanelLeft, Send, Sparkles } from "lucide-vue-next"
import CapabilityControls from "./CapabilityControls.vue"
import ChatSessionSidebar from "./ChatSessionSidebar.vue"
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
import { createTask } from "@/api/taskApi"
import type { AITool, ChatMessage, ChatSession } from "@/api/aiToolTypes"
import { ApiBusinessError } from "@/api/client"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const SESSION_SIDEBAR_KEY = "ai_tool_market_marketplace_session_sidebar_open"

const toolId = computed(() => String(route.params.toolId || ""))
const tool = ref<AITool | null>(null)
const messages = ref<ChatMessage[]>([])
const inputText = ref("")
const loading = ref(true)
const loadError = ref<string | null>(null)
const sending = ref(false)
const sendError = ref<string | null>(null)
const capabilityRef = ref<InstanceType<typeof CapabilityControls> | null>(null)
const messagesEndRef = ref<HTMLElement | null>(null)
const createdTaskId = ref<number | null>(null)
const activeSessionId = ref<string | null>(null)

const sessions = ref<ChatSession[]>([])
const sessionsLoading = ref(false)
const sessionSidebarOpen = ref(true)
const deletingSessionId = ref<string | null>(null)
const deleteSessionError = ref<string | null>(null)
const switchingSession = ref(false)

const isMarketplaceChat = computed(() => isMarketplaceMockToolId(toolId.value))
const chatBackPath = computed(() => "/marketplace")

const showWelcome = computed(() => messages.value.length === 0 && !sending.value && !switchingSession.value)
const coreField = computed(() => (tool.value?.fields || []).find((field) => isCoreField(field)) || null)
const inputPlaceholder = computed(() => {
  if (!coreField.value) return "输入消息..."
  return coreField.value.placeholder || `请输入${coreField.value.fieldName}`
})

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

async function loadTool() {
  loading.value = true
  loadError.value = null
  sendError.value = null
  messages.value = []
  createdTaskId.value = null
  activeSessionId.value = null
  sessions.value = []
  deleteSessionError.value = null
  try {
    tool.value = await fetchAIToolById(toolId.value, { token: auth.token })
    if (!tool.value.enabled) {
      router.replace({ path: chatBackPath.value, query: { notice: "offline" } })
      return
    }
    if (isMarketplaceMockToolId(toolId.value)) {
      await initMarketplaceSessions()
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

function buildOptimisticUserMessage(content: string, params: Record<string, unknown>): ChatMessage {
  return {
    id: `temp-user-${Date.now()}`,
    role: "user",
    content,
    timestamp: Date.now(),
    params,
  }
}

function buildAssistantMessage(content: string): ChatMessage {
  return {
    id: `temp-assistant-${Date.now()}`,
    role: "assistant",
    content,
    timestamp: Date.now(),
  }
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
      createdTaskId.value = response.taskId
      messages.value = [
        ...messages.value,
        buildAssistantMessage(`任务已创建：${response.taskNo}\n当前已进入生成队列，可在任务进度页查看处理状态。`),
      ]
      capabilityRef.value?.resetState()
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
  const saved = localStorage.getItem(SESSION_SIDEBAR_KEY)
  if (saved === "0") sessionSidebarOpen.value = false
  if (saved === "1") sessionSidebarOpen.value = true
  void loadTool()
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
          :aria-label="sessionSidebarOpen ? '收起历史记录' : '展开历史记录'"
          @click="toggleSessionSidebar"
        >
          <PanelLeft v-if="sessionSidebarOpen" class="h-4 w-4" />
          <ChevronRight v-else class="h-4 w-4" />
        </button>
        <div v-if="tool" class="flex min-w-0 items-center gap-2">
          <img
            v-if="tool.iconUrl"
            :src="normalizeMediaUrl(tool.iconUrl)"
            :alt="tool.name"
            class="h-8 w-8 rounded-full object-cover"
          />
          <span class="truncate text-sm font-semibold">{{ tool.name }}</span>
        </div>
      </div>
      <RouterLink
        v-if="createdTaskId"
        :to="userRoutes.taskStatus(String(createdTaskId))"
        class="rounded-md border border-border px-3 py-1.5 text-xs hover:bg-secondary"
      >
        查看进度
      </RouterLink>
    </header>

    <div v-if="loading" class="flex flex-1 items-center justify-center">
      <Loader2 class="h-6 w-6 animate-spin text-muted-foreground" />
    </div>

    <div v-else-if="loadError" class="flex flex-1 flex-col items-center justify-center gap-4 p-6">
      <AlertCircle class="h-10 w-10 text-destructive" />
      <p class="text-sm text-destructive">{{ loadError }}</p>
      <button type="button" class="rounded-md border border-border px-4 py-2 text-sm" @click="loadTool">
        重试
      </button>
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

        <div class="flex min-w-0 flex-1 flex-col">
          <div class="flex-1 overflow-y-auto">
            <div v-if="switchingSession" class="flex h-full items-center justify-center">
              <Loader2 class="h-5 w-5 animate-spin text-muted-foreground" />
            </div>
            <div
              v-else-if="showWelcome"
              class="flex h-full flex-col items-center justify-center px-6 py-12 text-center"
            >
              <div
                class="mb-6 flex h-[120px] w-[120px] items-center justify-center overflow-hidden rounded-full ring-2 ring-border"
                :style="{ backgroundColor: tool.primaryColor ? `${tool.primaryColor}18` : undefined }"
              >
                <img
                  v-if="tool.iconUrl"
                  :src="normalizeMediaUrl(tool.iconUrl)"
                  :alt="tool.name"
                  class="h-full w-full object-cover"
                />
                <Sparkles v-else class="h-10 w-10 text-primary" />
              </div>
              <p v-if="tool.welcomeMessage" class="max-w-xl text-sm text-muted-foreground">
                {{ tool.welcomeMessage }}
              </p>
              <p v-else class="max-w-xl text-sm text-muted-foreground">
                输入你的需求，开始对话。
              </p>
            </div>

            <div v-else class="mx-auto max-w-3xl space-y-4 px-4 py-6">
              <div
                v-for="msg in messages"
                :key="msg.id"
                class="flex"
                :class="msg.role === 'user' ? 'justify-end' : 'justify-start'"
              >
                <div
                  class="max-w-[80%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed"
                  :class="msg.role === 'user' ? 'bg-primary text-primary-foreground' : 'bg-secondary text-foreground'"
                >
                  <p class="whitespace-pre-wrap">{{ msg.content }}</p>
                </div>
              </div>
              <div v-if="sending" class="flex justify-start">
                <div class="rounded-2xl bg-secondary px-4 py-2.5 text-sm text-muted-foreground">
                  <Loader2 class="mr-2 inline h-4 w-4 animate-spin" />
                  {{ isMarketplaceChat ? "正在生成回复…" : "正在创建生成任务…" }}
                </div>
              </div>
              <div ref="messagesEndRef" />
            </div>
          </div>

          <div class="shrink-0 border-t border-border bg-card px-4 py-3">
            <div class="rounded-xl border border-border/60 bg-background p-3 shadow-sm">
              <CapabilityControls
                ref="capabilityRef"
                :capabilities="tool.capabilities || []"
                :fields="tool.fields || []"
                :core-field-key="coreField?.fieldKey"
                :tool-id="tool.id"
                class="mb-2"
              />

              <div class="flex items-center gap-2">
                <textarea
                  v-model="inputText"
                  rows="1"
                  class="max-h-24 min-h-[36px] w-full resize-none border-none bg-transparent text-sm outline-none placeholder:text-muted-foreground/70"
                  :placeholder="inputPlaceholder"
                  @keydown="handleKeydown"
                />
                <button
                  type="button"
                  class="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground disabled:opacity-50"
                  :disabled="!inputText.trim() || sending"
                  @click="handleSend"
                >
                  <Loader2 v-if="sending" class="h-4 w-4 animate-spin" />
                  <Send v-else class="h-4 w-4" />
                </button>
              </div>

              <div v-if="sendError" class="mt-1 text-[11px] text-destructive">
                {{ sendError }}
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
