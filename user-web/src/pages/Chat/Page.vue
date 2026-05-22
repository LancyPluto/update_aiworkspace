<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue"
import { RouterLink, useRoute, useRouter } from "vue-router"
import { AlertCircle, ArrowLeft, Loader2, Send, Sparkles } from "lucide-vue-next"
import CapabilityControls from "./CapabilityControls.vue"
import { getApiOrigin } from "@/api/client"
import { fetchAIToolById } from "@/api/aiToolApi"
import { createTask } from "@/api/taskApi"
import type { AITool, ChatMessage } from "@/api/aiToolTypes"
import { ApiBusinessError } from "@/api/client"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

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

const showWelcome = computed(() => messages.value.length === 0 && !sending.value)
const coreField = computed(() => (tool.value?.fields || []).find((field) => isCoreField(field)) || null)
const inputPlaceholder = computed(() => {
  if (!coreField.value) return "输入生成需求，Enter 发送，Shift+Enter 换行"
  return coreField.value.placeholder || `请输入${coreField.value.fieldName}`
})

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

async function loadTool() {
  loading.value = true
  loadError.value = null
  sendError.value = null
  messages.value = []
  createdTaskId.value = null
  try {
    tool.value = await fetchAIToolById(toolId.value, { token: auth.token })
    if (!tool.value.enabled) {
      router.replace({ path: "/marketplace", query: { notice: "offline" } })
      return
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
  } catch (e) {
    if (e instanceof ApiBusinessError && (e.code === "CREDIT_NOT_ENOUGH" || e.code === "AGENT_CREDIT_NOT_ENOUGH")) {
      sendError.value = "算力不足，请前往会员与算力页充值后再试"
    } else if (e instanceof ApiBusinessError && e.code === "TOOL_OFFLINE") {
      sendError.value = "该工具已下架，无法创建任务"
    } else {
      sendError.value = (e as Error).message || "创建任务失败"
    }
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
  void loadTool()
})
</script>

<template>
  <div class="flex h-[calc(100vh-4rem)] flex-col bg-background">
    <header class="flex h-14 shrink-0 items-center justify-between border-b border-border px-4">
      <div class="flex min-w-0 items-center gap-3">
        <RouterLink
          to="/marketplace"
          class="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft class="h-4 w-4" />
          返回超市
        </RouterLink>
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
      <div class="flex-1 overflow-y-auto">
        <div
          v-if="showWelcome"
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
            输入你的需求，并在底部选择比例、数量、参考图等参数。
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
              <pre v-if="msg.params && Object.keys(msg.params).length" class="mt-2 whitespace-pre-wrap rounded bg-background/40 p-2 text-[11px] opacity-80">{{ JSON.stringify(msg.params, null, 2) }}</pre>
            </div>
          </div>
          <div v-if="sending" class="flex justify-start">
            <div class="rounded-2xl bg-secondary px-4 py-2.5 text-sm text-muted-foreground">
              <Loader2 class="mr-2 inline h-4 w-4 animate-spin" />
              正在创建生成任务…
            </div>
          </div>
          <div ref="messagesEndRef" />
        </div>
      </div>

      <div class="shrink-0 border-t border-border bg-card">
        <CapabilityControls
          ref="capabilityRef"
          :capabilities="tool.capabilities || []"
          :fields="tool.fields || []"
          :core-field-key="coreField?.fieldKey"
        />
        <div v-if="sendError" class="border-b border-destructive/20 bg-destructive/5 px-4 py-2 text-xs text-destructive">
          {{ sendError }}
        </div>
        <div class="flex items-end gap-2 px-4 py-3">
          <div class="min-w-0 flex-1">
            <label v-if="coreField" class="mb-1 block text-[11px] font-medium text-muted-foreground">
              {{ coreField.fieldName }}<span v-if="coreField.required" class="text-destructive"> *</span>
            </label>
            <textarea
              v-model="inputText"
              rows="1"
              class="max-h-28 min-h-[44px] w-full resize-none rounded-xl border border-border bg-background px-4 py-2.5 text-sm outline-none focus:border-primary"
              :placeholder="inputPlaceholder"
              @keydown="handleKeydown"
            />
          </div>
          <button
            type="button"
            class="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-primary text-primary-foreground disabled:opacity-50"
            :disabled="!inputText.trim() || sending"
            @click="handleSend"
          >
            <Loader2 v-if="sending" class="h-4 w-4 animate-spin" />
            <Send v-else class="h-4 w-4" />
          </button>
        </div>
      </div>
    </template>
  </div>
</template>
