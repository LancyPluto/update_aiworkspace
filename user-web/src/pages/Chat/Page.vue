<script setup lang="ts">
import { ref, computed, onMounted, nextTick, watch } from "vue"
import { useRoute, useRouter, RouterLink } from "vue-router"
import {
  ArrowLeft,
  Loader2,
  MessageSquarePlus,
  Send,
  Trash2,
  AlertCircle,
} from "lucide-vue-next"
import CapabilityControls from "./CapabilityControls.vue"
import { getApiOrigin } from "@/api/client"
import {
  fetchAIToolById,
  fetchChatSessions,
  createChatSession,
  deleteChatSession,
  fetchChatMessages,
  sendChatMessage,
  uploadChatFile,
} from "@/api/aiToolApi"
import type { AITool, ChatMessage, ChatSession } from "@/api/aiToolTypes"
import { ApiBusinessError } from "@/api/client"
import { useAuthStore } from "@/store/authStore"

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const toolId = computed(() => String(route.params.toolId || ""))
const tool = ref<AITool | null>(null)
const sessions = ref<ChatSession[]>([])
const currentSessionId = ref<string | null>(null)
const messages = ref<ChatMessage[]>([])
const inputText = ref("")
const loading = ref(true)
const loadError = ref<string | null>(null)
const sending = ref(false)
const sendError = ref<string | null>(null)
const deletingSessionId = ref<string | null>(null)
const pendingDeleteSession = ref<ChatSession | null>(null)
const capabilityRef = ref<InstanceType<typeof CapabilityControls> | null>(null)
const messagesEndRef = ref<HTMLElement | null>(null)
const abortController = ref<AbortController | null>(null)

const currentSession = computed(() => sessions.value.find((s) => s.id === currentSessionId.value) || null)
const showWelcome = computed(() => messages.value.length === 0 && !sending.value)
const sortedSessions = computed(() =>
  [...sessions.value].sort((a, b) => b.updatedAt - a.updatedAt),
)

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

async function loadToolAndSessions() {
  loading.value = true
  loadError.value = null
  try {
    tool.value = await fetchAIToolById(toolId.value, { token: auth.token })
    if (!tool.value.enabled) {
      router.replace({ path: "/marketplace", query: { notice: "offline" } })
      return
    }
    sessions.value = await fetchChatSessions(toolId.value, { token: auth.token })
    if (sessions.value.length === 0) {
      const created = await createChatSession(toolId.value, { token: auth.token })
      sessions.value = [created]
    }
    currentSessionId.value = sortedSessions.value[0]?.id || null
    if (currentSessionId.value) {
      await loadMessages(currentSessionId.value)
    }
  } catch (e) {
    if (e instanceof ApiBusinessError && (e.code === "TOOL_NOT_FOUND" || e.code === "TOOL_OFFLINE")) {
      router.replace({ path: "/marketplace", query: { notice: "offline" } })
      return
    }
    loadError.value = (e as Error).message || "加载失败"
  } finally {
    loading.value = false
  }
}

async function loadMessages(sessionId: string) {
  messages.value = await fetchChatMessages(sessionId, { token: auth.token })
  await scrollToBottom()
}

async function switchSession(session: ChatSession) {
  if (sending.value) {
    alert("正在回复中，请稍后切换")
    return
  }
  currentSessionId.value = session.id
  inputText.value = ""
  capabilityRef.value?.resetState()
  sendError.value = null
  await loadMessages(session.id)
}

async function handleNewSession() {
  if (sending.value) {
    alert("正在回复中，请稍后再新建对话")
    return
  }
  const created = await createChatSession(toolId.value, { token: auth.token })
  sessions.value = [created, ...sessions.value]
  currentSessionId.value = created.id
  messages.value = []
  inputText.value = ""
  capabilityRef.value?.resetState()
  sendError.value = null
}

async function confirmDeleteSession() {
  const target = pendingDeleteSession.value
  if (!target) return
  deletingSessionId.value = target.id
  try {
    await deleteChatSession(target.id, { token: auth.token })
    sessions.value = sessions.value.filter((s) => s.id !== target.id)
    pendingDeleteSession.value = null
    if (currentSessionId.value === target.id) {
      if (sessions.value.length === 0) {
        await handleNewSession()
      } else {
        await switchSession(sortedSessions.value[0])
      }
    }
  } catch (e) {
    alert((e as Error).message || "删除失败")
  } finally {
    deletingSessionId.value = null
  }
}

async function handleFileUpload(file: File, localId: string) {
  try {
    const result = await uploadChatFile(file, { token: auth.token })
    capabilityRef.value?.markUploadSuccess(localId, result.fileId)
  } catch (e) {
    capabilityRef.value?.markUploadError(localId, (e as Error).message || "上传失败")
  }
}

function buildOptimisticUserMessage(content: string): ChatMessage {
  return {
    id: `temp-${Date.now()}`,
    role: "user",
    content,
    timestamp: Date.now(),
    params: capabilityRef.value?.getRequestParams(),
  }
}

async function handleSend() {
  const content = inputText.value.trim()
  if (!content || !currentSessionId.value || sending.value) return
  if (capabilityRef.value?.hasPendingUploads()) {
    alert("文件上传中，请稍候")
    return
  }

  sendError.value = null
  const sessionId = currentSessionId.value
  const optimistic = buildOptimisticUserMessage(content)
  messages.value = [...messages.value, optimistic]
  inputText.value = ""
  const params = capabilityRef.value?.getRequestParams() || {}
  const attachments = capabilityRef.value?.getAttachmentIds() || []
  capabilityRef.value?.resetState()
  sending.value = true
  await scrollToBottom()

  abortController.value = new AbortController()
  try {
    const response = await sendChatMessage(
      {
        toolId: toolId.value,
        sessionId,
        content,
        attachments: attachments.length > 0 ? attachments : undefined,
        params: Object.keys(params).length > 0 ? params : undefined,
      },
      { token: auth.token, signal: abortController.value.signal },
    )
    messages.value = messages.value
      .filter((m) => m.id !== optimistic.id)
      .concat(response.userMessage, response.assistantMessage)
    sessions.value = sessions.value.map((s) =>
      s.id === sessionId ? { ...s, updatedAt: Date.now(), title: s.title === "新对话" ? content.slice(0, 20) : s.title } : s,
    )
  } catch (e) {
    if ((e as Error).name !== "AbortError") {
      sendError.value = (e as Error).message || "发送失败"
    }
  } finally {
    sending.value = false
    abortController.value = null
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
    void loadToolAndSessions()
  },
)

onMounted(() => {
  void loadToolAndSessions()
})
</script>

<template>
  <div class="flex h-[calc(100vh-4rem)] flex-col bg-background">
    <header class="flex h-14 shrink-0 items-center gap-3 border-b border-border px-4">
      <RouterLink
        to="/marketplace"
        class="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
      >
        <ArrowLeft class="h-4 w-4" />
        返回超市
      </RouterLink>
      <div v-if="tool" class="flex items-center gap-2">
        <img
          v-if="tool.iconUrl"
          :src="normalizeMediaUrl(tool.iconUrl)"
          :alt="tool.name"
          class="h-8 w-8 rounded-full object-cover"
        />
        <span class="text-sm font-semibold">{{ tool.name }}</span>
      </div>
    </header>

    <div v-if="loading" class="flex flex-1 items-center justify-center">
      <Loader2 class="h-6 w-6 animate-spin text-muted-foreground" />
    </div>

    <div v-else-if="loadError" class="flex flex-1 flex-col items-center justify-center gap-4 p-6">
      <AlertCircle class="h-10 w-10 text-destructive" />
      <p class="text-sm text-destructive">{{ loadError }}</p>
      <div class="flex gap-3">
        <button
          type="button"
          class="rounded-md border border-border px-4 py-2 text-sm"
          @click="loadToolAndSessions"
        >
          重试
        </button>
        <RouterLink to="/marketplace" class="rounded-md bg-primary px-4 py-2 text-sm text-primary-foreground">
          返回超市
        </RouterLink>
      </div>
    </div>

    <div v-else class="flex min-h-0 flex-1">
      <!-- 左侧会话列表 -->
      <aside class="hidden w-[260px] shrink-0 flex-col border-r border-border bg-card/50 md:flex">
        <div class="flex items-center justify-between border-b border-border px-4 py-3">
          <span class="text-sm font-medium">历史记录</span>
          <button
            type="button"
            class="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-primary hover:bg-primary/10"
            @click="handleNewSession"
          >
            <MessageSquarePlus class="h-3.5 w-3.5" />
            新建对话
          </button>
        </div>
        <div class="flex-1 overflow-y-auto p-2">
          <button
            v-for="session in sortedSessions"
            :key="session.id"
            type="button"
            class="group mb-1 flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm transition"
            :class="
              session.id === currentSessionId
                ? 'bg-primary/10 text-primary font-medium'
                : 'text-foreground/80 hover:bg-secondary'
            "
            @click="switchSession(session)"
          >
            <span class="flex-1 truncate">{{ session.title || "新对话" }}</span>
            <button
              type="button"
              class="shrink-0 rounded p-1 opacity-0 transition hover:bg-destructive/10 hover:text-destructive group-hover:opacity-100"
              :disabled="deletingSessionId === session.id"
              @click.stop="pendingDeleteSession = session"
            >
              <Trash2 class="h-3.5 w-3.5" />
            </button>
          </button>
        </div>
      </aside>

      <!-- 右侧聊天区 -->
      <div class="flex min-w-0 flex-1 flex-col">
        <div class="flex-1 overflow-y-auto">
          <!-- 欢迎态 -->
          <div
            v-if="showWelcome && tool"
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
            </div>
            <p v-if="tool.welcomeMessage" class="max-w-md text-sm text-muted-foreground">
              {{ tool.welcomeMessage }}
            </p>
            <p v-else class="text-sm text-muted-foreground">你好，我是 {{ tool.name }}，有什么可以帮你？</p>
          </div>

          <!-- 消息列表 -->
          <div v-else class="mx-auto max-w-3xl space-y-4 px-4 py-6">
            <div
              v-for="msg in messages"
              :key="msg.id"
              class="flex"
              :class="msg.role === 'user' ? 'justify-end' : 'justify-start'"
            >
              <div
                class="max-w-[80%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed"
                :class="
                  msg.role === 'user'
                    ? 'bg-primary text-primary-foreground'
                    : 'bg-secondary text-foreground'
                "
              >
                <p class="whitespace-pre-wrap">{{ msg.content }}</p>
              </div>
            </div>
            <div v-if="sending" class="flex justify-start">
              <div class="rounded-2xl bg-secondary px-4 py-2.5 text-sm text-muted-foreground">
                <Loader2 class="mr-2 inline h-4 w-4 animate-spin" />
                AI 正在思考…
              </div>
            </div>
            <div ref="messagesEndRef" />
          </div>
        </div>

        <!-- 输入区 -->
        <div class="shrink-0 border-t border-border bg-card">
          <CapabilityControls
            v-if="tool"
            ref="capabilityRef"
            :capabilities="tool.capabilities || []"
            @upload="handleFileUpload"
          />
          <div v-if="sendError" class="border-b border-destructive/20 bg-destructive/5 px-4 py-2 text-xs text-destructive">
            {{ sendError }}
            <button type="button" class="ml-2 underline" @click="handleSend">重试</button>
          </div>
          <div class="flex items-end gap-2 px-4 py-3">
            <textarea
              v-model="inputText"
              rows="1"
              class="max-h-28 min-h-[44px] flex-1 resize-none rounded-xl border border-border bg-background px-4 py-2.5 text-sm outline-none focus:border-primary"
              placeholder="输入消息，Enter 发送，Shift+Enter 换行"
              @keydown="handleKeydown"
            />
            <button
              type="button"
              class="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-primary text-primary-foreground disabled:opacity-50"
              :disabled="!inputText.trim() || sending"
              @click="handleSend"
            >
              <Send class="h-4 w-4" />
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 删除确认 -->
    <div
      v-if="pendingDeleteSession"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      @click.self="pendingDeleteSession = null"
    >
      <div class="w-full max-w-sm rounded-xl border border-border bg-card p-6 shadow-lg">
        <p class="text-sm font-medium">确认删除此对话？</p>
        <p class="mt-1 text-xs text-muted-foreground">删除后无法恢复</p>
        <div class="mt-4 flex justify-end gap-2">
          <button type="button" class="rounded-md border border-border px-3 py-1.5 text-sm" @click="pendingDeleteSession = null">
            取消
          </button>
          <button
            type="button"
            class="rounded-md bg-destructive px-3 py-1.5 text-sm text-destructive-foreground"
            :disabled="deletingSessionId === pendingDeleteSession.id"
            @click="confirmDeleteSession"
          >
            删除
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
