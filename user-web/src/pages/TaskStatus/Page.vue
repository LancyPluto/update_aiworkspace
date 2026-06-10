<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue"
import { RouterLink } from "vue-router"
import { ArrowLeft, CheckCircle2, ChevronRight, Loader2, X as XIcon } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import TaskStatusTag from "@/components/TaskStatusTag/TaskStatusTag.vue"
import { fetchTaskById, fetchTaskStatus, streamTaskStatus, submitWorkflowFeedback } from "@/api/taskApi"
import type { TaskDetail, TaskStatus, TaskStatusPayload } from "@/api/types"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { taskStatusDocLabel } from "@/utils/taskStatusLabels"

const props = defineProps<{
  taskId: string
}>()

const auth = useAuthStore()

const statusData = ref<TaskStatusPayload | null>(null)
const taskDetailFail = ref<TaskDetail | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const streamConnected = ref(false)
const feedbackText = ref("")
const feedbackSubmitting = ref(false)
const feedbackError = ref<string | null>(null)

const toolCode = computed(
  () => statusData.value?.toolCode || taskDetailFail.value?.toolCode || "",
)

const isComicDrama = computed(() => toolCode.value === "ai_comic_drama_agent")

const FEEDBACK_LABEL_TO_KEY: Record<string, string> = {
  脚本意见: "scriptFeedback",
  分镜意见: "storyboardFeedback",
  场景图意见: "sceneFeedback",
  "BGM意见": "bgmFeedback",
  "BGM 意见": "bgmFeedback",
}

const awaitingFeedback = computed(() => statusData.value?.status === "AWAITING_USER")

const awaitingStageLabel = computed(() => {
  const msg = (statusData.value?.progressMessage || "").trim()
  const match = msg.match(/等待您的(.+?)（/)
  return match?.[1]?.trim() || "阶段意见"
})

const awaitingFieldKey = computed(() => FEEDBACK_LABEL_TO_KEY[awaitingStageLabel.value] || null)

const taskStages = computed(() => {
  if (isComicDrama.value) {
    return [
      { label: "剧本与分镜", progress: 15 },
      { label: "脚本/分镜确认", progress: 28 },
      { label: "关键帧生成", progress: 42 },
      { label: "配音与视频", progress: 68 },
      { label: "合成输出", progress: 92 },
    ]
  }
  if (toolCode.value === "digital_human_agent") {
    return [
      { label: "脚本与语音准备", progress: 18 },
      { label: "数字人形象生成", progress: 36 },
      { label: "背景画面生成", progress: 52 },
      { label: "形象驱动视频生成", progress: 78 },
      { label: "字幕整理与结果输出", progress: 96 },
    ]
  }
  return [
    { label: "任务排队", progress: 10 },
    { label: "AI 生成内容", progress: 60 },
    { label: "结果整理输出", progress: 96 },
  ]
})

function stageState(stageProgress: number): "done" | "current" | "pending" {
  const progress = statusData.value?.progress ?? 0
  if (progress >= stageProgress) return "done"
  if (progress >= stageProgress - 18) return "current"
  return "pending"
}

function mapStatus(s: TaskStatus): "running" | "success" | "failed" | "queued" {
  switch (s) {
    case "PROCESSING":
    case "AWAITING_USER":
    case "RETRYING":
      return "running"
    case "SUCCESS":
      return "success"
    case "FAILED":
    case "TIMEOUT":
    case "CANCELLED":
      return "failed"
    case "CREATED":
    case "QUEUED":
    default:
      return "queued"
  }
}

function isTerminal(s: TaskStatus): boolean {
  return ["SUCCESS", "FAILED", "TIMEOUT", "CANCELLED"].includes(s)
}

function pollDelayMs(s: TaskStatus): number {
  return s === "CREATED" || s === "QUEUED" ? 3000 : 5000
}

const failureHint = computed(() => {
  if (!statusData.value) return ""
  const d = taskDetailFail.value
  const msg = (d?.progressMessage || statusData.value.progressMessage || "").trim()
  if (msg) return msg
  if (statusData.value.status === "TIMEOUT") return "任务执行超时，请稍后重试或联系支持。"
  if (statusData.value.status === "CANCELLED") return "任务已取消。"
  if (statusData.value.status === "FAILED") return "生成失败，请检查参数后重试。"
  return "任务未成功完成。"
})

let pollTimer: ReturnType<typeof setTimeout> | null = null
let statusLoadAbort: AbortController | undefined
let statusStreamAbort: AbortController | undefined

function clearPollTimer() {
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function clearStatusPolling() {
  clearPollTimer()
  statusLoadAbort?.abort()
  statusLoadAbort = undefined
}

function clearStatusStream() {
  statusStreamAbort?.abort()
  statusStreamAbort = undefined
  streamConnected.value = false
}

function scheduleNextPoll() {
  clearPollTimer()
  if (!statusData.value || isTerminal(statusData.value.status)) return
  const delay = pollDelayMs(statusData.value.status)
  pollTimer = setTimeout(() => {
    void loadStatus()
  }, delay)
}

function isAbortError(e: unknown): boolean {
  return e instanceof DOMException && e.name === "AbortError"
}

async function loadFailDetailOnce() {
  if (!props.taskId || taskDetailFail.value) return
  const s = statusData.value?.status
  if (!s || !["FAILED", "TIMEOUT", "CANCELLED"].includes(s)) return
  try {
    taskDetailFail.value = await fetchTaskById(props.taskId, { token: auth.token })
  } catch {
    // 忽略，仍用 status 接口文案
  }
}

async function submitFeedback(skip = false) {
  if (!props.taskId || feedbackSubmitting.value) return
  feedbackSubmitting.value = true
  feedbackError.value = null
  try {
    const key = awaitingFieldKey.value || "scriptFeedback"
    const fields: Record<string, string> = { [key]: skip ? "" : feedbackText.value.trim() }
    const payload = await submitWorkflowFeedback(props.taskId, fields, { token: auth.token })
    feedbackText.value = ""
    await applyStatus(payload)
  } catch (e) {
    feedbackError.value = (e as Error).message || "提交意见失败"
  } finally {
    feedbackSubmitting.value = false
  }
}

async function applyStatus(payload: TaskStatusPayload) {
  statusData.value = payload
  loading.value = false
  error.value = null
  if (isTerminal(payload.status)) {
    clearStatusPolling()
    clearStatusStream()
    if (mapStatus(payload.status) === "failed") {
      await loadFailDetailOnce()
    }
  } else {
    scheduleNextPoll()
  }
}

async function loadStatus() {
  if (!props.taskId) return
  statusLoadAbort?.abort()
  statusLoadAbort = new AbortController()
  const signal = statusLoadAbort.signal
  try {
    const payload = await fetchTaskStatus(props.taskId, { token: auth.token, signal })
    if (signal.aborted) return
    await applyStatus(payload)
  } catch (e) {
    if (signal.aborted || isAbortError(e)) return
    error.value = (e as Error).message || "获取任务状态失败"
    scheduleNextPoll()
  } finally {
    if (!signal.aborted) loading.value = false
  }
}

function startStatusStream() {
  clearStatusStream()
  if (!props.taskId) return
  statusStreamAbort = new AbortController()
  void streamTaskStatus(
    props.taskId,
    (payload) => {
      streamConnected.value = true
      void applyStatus(payload)
    },
    { token: auth.token, signal: statusStreamAbort.signal },
  ).catch((e) => {
    if (isAbortError(e)) return
    streamConnected.value = false
  })
}

function onTaskStatusVisibilityChange() {
  if (typeof document === "undefined" || document.hidden) return
  if (!props.taskId) return
  if (statusData.value && isTerminal(statusData.value.status)) return
  void loadStatus()
}

watch(
  () => props.taskId,
  () => {
    taskDetailFail.value = null
    statusData.value = null
    loading.value = true
    error.value = null
    clearStatusPolling()
    startStatusStream()
    void loadStatus()
  },
)

onMounted(() => {
  void loadStatus()
  startStatusStream()
  document.addEventListener("visibilitychange", onTaskStatusVisibilityChange)
})

onUnmounted(() => {
  document.removeEventListener("visibilitychange", onTaskStatusVisibilityChange)
  clearStatusPolling()
  clearStatusStream()
})
</script>

<template>
  <AppShell title="任务状态" :description="'任务 ' + taskId + ' · 实时进度'">
    <div class="px-6 py-6 max-w-4xl mx-auto space-y-5">
      <nav class="flex items-center gap-1.5 text-xs text-muted-foreground flex-wrap">
        <RouterLink :to="userRoutes.myTasks" class="hover:text-foreground inline-flex items-center gap-1">
          <ArrowLeft class="h-3 w-3" /> 我的任务
        </RouterLink>
        <ChevronRight class="h-3 w-3" />
        <span class="text-foreground">任务进度</span>
      </nav>

      <div v-if="loading" class="flex justify-center py-12">
        <span class="text-sm text-muted-foreground">加载中...</span>
      </div>

      <div v-else-if="error" class="rounded-xl border border-destructive/30 bg-destructive/5 p-6 text-center">
        <p class="text-sm text-destructive">{{ error }}</p>
      </div>

      <div
        v-else-if="statusData"
        class="rounded-xl border"
        :class="
          mapStatus(statusData.status) === 'success'
            ? 'border-success/30 bg-success/5'
            : mapStatus(statusData.status) === 'failed'
              ? 'border-destructive/30 bg-destructive/5'
              : 'border-primary/20 bg-accent/30'
        "
      >
        <div class="p-6 shadow-sm">
          <div class="flex items-center justify-between mb-3 flex-wrap gap-2">
            <div class="flex items-center gap-2">
              <Loader2 v-if="!isTerminal(statusData.status)" class="h-4 w-4 text-primary animate-spin" />
              <CheckCircle2 v-else-if="mapStatus(statusData.status) === 'success'" class="h-4 w-4 text-success" />
              <XIcon v-else class="h-4 w-4 text-destructive" />
              <h3 class="text-sm font-semibold">任务状态</h3>
              <TaskStatusTag
                :status="mapStatus(statusData.status)"
                :label="taskStatusDocLabel(statusData.status)"
              />
            </div>
            <div class="flex items-center gap-2 text-xs text-muted-foreground">
              <span>{{ streamConnected ? '实时进度已连接' : '轮询进度更新中' }}</span>
              <span class="font-mono">任务 ID：{{ statusData.taskNo }}</span>
            </div>
          </div>

          <div v-if="statusData.progress != null" class="space-y-2">
            <div class="flex items-center justify-between text-xs">
              <span class="text-muted-foreground">{{ statusData.progressMessage || "处理中" }}</span>
              <span class="font-medium">{{ statusData.progress }}%</span>
            </div>
            <div class="h-2 overflow-hidden rounded-full bg-secondary">
              <div
                class="h-full rounded-full bg-gradient-to-r from-primary to-chart-2"
                :style="{ width: statusData.progress + '%' }"
              />
            </div>
          </div>

          <p class="mt-3 text-xs text-muted-foreground">
            {{ taskStatusDocLabel(statusData.status) }}
          </p>

          <div
            v-if="isTerminal(statusData.status) && mapStatus(statusData.status) === 'failed'"
            class="mt-4 rounded-lg border border-destructive/25 bg-destructive/5 p-3 text-sm text-destructive"
          >
            {{ failureHint }}
          </div>

          <div
            v-if="awaitingFeedback && isComicDrama"
            class="mt-5 rounded-lg border border-primary/25 bg-background p-4 space-y-3"
          >
            <p class="text-sm font-medium">交互式短剧 · {{ awaitingStageLabel }}</p>
            <p class="text-xs text-muted-foreground">
              可填写修改意见后提交；若满意可直接点「跳过继续」进入下一步。
            </p>
            <textarea
              v-model="feedbackText"
              rows="4"
              class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
              :placeholder="'请输入' + awaitingStageLabel + '（可选）'"
            />
            <p v-if="feedbackError" class="text-xs text-destructive">{{ feedbackError }}</p>
            <div class="flex flex-wrap gap-2">
              <button
                type="button"
                class="inline-flex h-9 items-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:opacity-90 disabled:opacity-50"
                :disabled="feedbackSubmitting"
                @click="submitFeedback(false)"
              >
                {{ feedbackSubmitting ? "提交中..." : "提交并继续" }}
              </button>
              <button
                type="button"
                class="inline-flex h-9 items-center rounded-md border px-4 text-sm hover:bg-accent disabled:opacity-50"
                :disabled="feedbackSubmitting"
                @click="submitFeedback(true)"
              >
                跳过继续
              </button>
            </div>
          </div>

          <div
            v-if="!isTerminal(statusData.status) && !awaitingFeedback"
            class="mt-5 grid gap-2"
            :class="taskStages.length >= 5 ? 'sm:grid-cols-5' : 'sm:grid-cols-3'"
          >
            <div
              v-for="stage in taskStages"
              :key="stage.label"
              class="rounded-lg border px-3 py-2 text-xs"
              :class="
                stageState(stage.progress) === 'done'
                  ? 'border-success/30 bg-success/10 text-success'
                  : stageState(stage.progress) === 'current'
                    ? 'border-primary/30 bg-primary/10 text-primary'
                    : 'border-border bg-background text-muted-foreground'
              "
            >
              {{ stage.label }}
            </div>
          </div>
        </div>

        <div v-if="mapStatus(statusData.status) === 'success'" class="px-6 pb-6">
          <RouterLink
            :to="userRoutes.taskResult(taskId)"
            class="inline-flex h-10 items-center justify-center rounded-md bg-primary px-6 text-sm font-medium text-primary-foreground hover:opacity-90"
          >
            查看结果
          </RouterLink>
        </div>
      </div>
    </div>
  </AppShell>
</template>
