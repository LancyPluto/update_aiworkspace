<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from "vue"
import { RouterLink } from "vue-router"
import { ArrowLeft, ChevronRight, CheckCircle2, Loader2, X as XIcon } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import TaskStatusTag from "@/components/TaskStatusTag/TaskStatusTag.vue"
import { userRoutes } from "@/router/userRoutes"
import { fetchTaskStatus, streamTaskStatus } from "@/api/taskApi"
import type { TaskStatusPayload, TaskStatus } from "@/api/types"
import { useAuthStore } from "@/store/authStore"

const props = defineProps<{
  taskId: string
}>()

const auth = useAuthStore()

const statusData = ref<TaskStatusPayload | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const streamConnected = ref(false)

const digitalHumanStages = computed(() => [
  { label: "脚本与语音准备", progress: 18 },
  { label: "数字人形象生成", progress: 36 },
  { label: "背景画面生成", progress: 52 },
  { label: "形象驱动视频生成", progress: 78 },
  { label: "字幕整理与结果输出", progress: 96 },
])

const enterpriseDiagnosisStages = computed(() => [
  { label: "整理企业名称", progress: 12 },
  { label: "准备诊断提示词", progress: 28 },
  { label: "联网检索与分析", progress: 55 },
  { label: "生成诊断报告", progress: 86 },
  { label: "保存报告结果", progress: 94 },
])

const visibleStages = computed(() => {
  if (statusData.value?.toolCode === "enterprise_diagnosis_agent") {
    return enterpriseDiagnosisStages.value
  }
  if (statusData.value?.toolCode === "digital_human_agent") {
    return digitalHumanStages.value
  }
  return []
})

function stageState(stageProgress: number): "done" | "current" | "pending" {
  const progress = statusData.value?.progress ?? 0
  if (progress >= stageProgress) return "done"
  if (progress >= stageProgress - 18) return "current"
  return "pending"
}

// 后端状态 → 前端标签状态
function mapStatus(s: TaskStatus): "running" | "success" | "failed" | "queued" {
  switch (s) {
    case "PROCESSING":
    case "RETRYING":
      return "running"
    case "SUCCESS":
      return "success"
    case "FAILED":
    case "TIMEOUT":
      return "failed"
    case "CREATED":
    case "QUEUED":
    default:
      return "queued"
  }
}

// 是否已完成终态
function isTerminal(s: TaskStatus): boolean {
  return ["SUCCESS", "FAILED", "TIMEOUT", "CANCELLED"].includes(s)
}

async function loadStatus() {
  if (!props.taskId) return
  statusLoadAbort?.abort()
  statusLoadAbort = new AbortController()
  const signal = statusLoadAbort.signal
  try {
    statusData.value = await fetchTaskStatus(props.taskId, { token: auth.token, signal })
    if (signal.aborted) return
    error.value = null
    if (statusData.value && isTerminal(statusData.value.status)) {
      clearStatusPolling()
    }
  } catch (e) {
    if (signal.aborted || isAbortError(e)) return
    error.value = (e as Error).message || "获取任务状态失败"
  } finally {
    if (!signal.aborted) loading.value = false
  }
}

function isAbortError(e: unknown): boolean {
  return e instanceof DOMException && e.name === "AbortError"
}

let intervalId: ReturnType<typeof setInterval> | undefined
let statusLoadAbort: AbortController | undefined
let statusStreamAbort: AbortController | undefined

function clearStatusPolling() {
  if (intervalId !== undefined) {
    clearInterval(intervalId)
    intervalId = undefined
  }
  statusLoadAbort?.abort()
  statusLoadAbort = undefined
}

function clearStatusStream() {
  statusStreamAbort?.abort()
  statusStreamAbort = undefined
  streamConnected.value = false
}

function applyStatus(payload: TaskStatusPayload) {
  statusData.value = payload
  loading.value = false
  error.value = null
  if (isTerminal(payload.status)) {
    clearStatusPolling()
    clearStatusStream()
  }
}

function tickStatusPoll() {
  if (typeof document !== "undefined" && document.hidden) return
  if (statusData.value && isTerminal(statusData.value.status)) {
    clearStatusPolling()
    return
  }
  void loadStatus()
}

function onTaskStatusVisibilityChange() {
  if (typeof document === "undefined" || document.hidden) return
  if (!props.taskId) return
  if (statusData.value && isTerminal(statusData.value.status)) return
  void loadStatus()
}

onMounted(() => {
  void loadStatus()
  statusStreamAbort = new AbortController()
  void streamTaskStatus(
    props.taskId,
    (payload) => {
      streamConnected.value = true
      applyStatus(payload)
    },
    { token: auth.token, signal: statusStreamAbort.signal },
  ).catch((e) => {
    if (isAbortError(e)) return
    streamConnected.value = false
  })
  intervalId = setInterval(tickStatusPoll, 5000)
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

      <!-- 加载中 -->
      <div v-if="loading" class="flex justify-center py-12">
        <span class="text-sm text-muted-foreground">加载中…</span>
      </div>

      <!-- 错误 -->
      <div v-else-if="error" class="rounded-xl border border-destructive/30 bg-destructive/5 p-6 text-center">
        <p class="text-sm text-destructive">{{ error }}</p>
      </div>

      <!-- 状态卡片 -->
      <div v-else-if="statusData" class="rounded-xl border" :class="
        mapStatus(statusData.status) === 'success'
          ? 'border-success/30 bg-success/5'
          : mapStatus(statusData.status) === 'failed'
            ? 'border-destructive/30 bg-destructive/5'
            : 'border-primary/20 bg-accent/30'
      ">
        <div class="p-6 shadow-sm">
          <div class="flex items-center justify-between mb-3 flex-wrap gap-2">
            <div class="flex items-center gap-2">
              <Loader2 v-if="!isTerminal(statusData.status)" class="h-4 w-4 text-primary animate-spin" />
              <CheckCircle2 v-else-if="mapStatus(statusData.status) === 'success'" class="h-4 w-4 text-success" />
              <XIcon v-else class="h-4 w-4 text-destructive" />
              <h3 class="text-sm font-semibold">任务状态</h3>
              <TaskStatusTag :status="mapStatus(statusData.status)" />
            </div>
            <div class="flex items-center gap-2 text-xs text-muted-foreground">
              <span>{{ streamConnected ? '实时进度已连接' : '轮询进度更新中' }}</span>
              <span class="font-mono">任务 ID：{{ statusData.taskNo }}</span>
            </div>
          </div>

          <div v-if="statusData.progress != null" class="space-y-2">
            <div class="flex items-center justify-between text-xs">
              <span class="text-muted-foreground">{{ statusData.progressMessage || '处理中' }}</span>
              <span class="font-medium">{{ statusData.progress }}%</span>
            </div>
            <div class="h-2 overflow-hidden rounded-full bg-secondary">
              <div
                class="h-full rounded-full bg-gradient-to-r from-primary to-chart-2"
                :style="{ width: statusData.progress + '%' }"
              />
            </div>
          </div>

          <div class="mt-3 text-xs text-muted-foreground">
            状态：{{ statusData.status }}
          </div>

          <div v-if="!isTerminal(statusData.status) && visibleStages.length > 0" class="mt-5 grid gap-2 sm:grid-cols-5">
            <div
              v-for="stage in visibleStages"
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

        <!-- 完成后显示查看结果链接 -->
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
