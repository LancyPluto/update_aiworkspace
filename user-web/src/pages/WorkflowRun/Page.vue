<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue"
import { RouterLink } from "vue-router"
import { ArrowLeft, Clock3, Loader2, RefreshCw, ShieldAlert, Workflow } from "lucide-vue-next"
import { ApiBusinessError } from "@/api/client"
import { streamTaskStatus } from "@/api/taskApi"
import {
  cancelRun,
  getRun,
  resumeRun,
  submitFeedback,
  type WorkflowArtifact,
  type WorkflowFeedbackAction,
  type WorkflowRun,
} from "@/api/workflowApi"
import WorkflowArtifactViewer from "@/components/workflow/WorkflowArtifactViewer.vue"
import WorkflowCostSummary from "@/components/workflow/WorkflowCostSummary.vue"
import WorkflowRunActions from "@/components/workflow/WorkflowRunActions.vue"
import WorkflowStepList from "@/components/workflow/WorkflowStepList.vue"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { nextRefreshPlan, shouldApplyWorkflowResponse, isWorkflowTerminal, type WorkflowStreamOutcome } from "@/utils/workflowPolling"
import { runStatusView } from "@/utils/workflowPresentation"
import { workflowFeedbackIdempotencyKey } from "@/utils/workflowIdempotency"
import { randomUUID } from "@/utils/randomUUID"

const props = defineProps<{ taskId: string }>()
const auth = useAuthStore()
const run = ref<WorkflowRun | null>(null)
const loading = ref(true)
const pageState = ref<"ready" | "error" | "403" | "404">("ready")
const error = ref<string | null>(null)
const refreshError = ref<string | null>(null)
const actionError = ref<string | null>(null)
const actionSubmitting = ref(false)
let requestSequence = 0
let pollFailures = 0
let disposed = false
let loadController: AbortController | null = null
let streamController: AbortController | null = null
let pollTimer: ReturnType<typeof setTimeout> | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null

const statusView = computed(() => run.value ? runStatusView(run.value.status) : null)
const progress = computed(() => Math.max(0, Math.min(100, run.value?.progress ?? 0)))
const emptySteps = computed(() => !(run.value?.steps?.length))
const artifacts = computed<WorkflowArtifact[]>(() => {
  const direct = run.value?.artifacts ?? []
  if (direct.length) return direct
  return (run.value?.steps ?? []).flatMap((step) => step.artifacts ?? [])
})
const emptyArtifacts = computed(() => artifacts.value.length === 0)
const legacy = computed(() => pageState.value === "404")

function formatTimestamp(value?: string | null): string {
  if (!value) return "--"
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false })
}

function statusClass(status?: string): string {
  if (status === "SUCCESS") return "bg-success/10 text-success"
  if (status === "FAILED" || status === "TIMEOUT") return "bg-destructive/10 text-destructive"
  if (status === "AWAITING_USER" || status === "AWAITING_FUNDS") return "bg-warning/10 text-warning"
  return "bg-primary/10 text-primary"
}

function clearTimer(timer: ReturnType<typeof setTimeout> | null) {
  if (timer) clearTimeout(timer)
}

function stopRefresh(abortLoad = false) {
  clearTimer(pollTimer)
  clearTimer(reconnectTimer)
  pollTimer = null
  reconnectTimer = null
  streamController?.abort()
  streamController = null
  if (abortLoad) {
    loadController?.abort()
    loadController = null
  }
}

function schedulePoll() {
  clearTimer(pollTimer)
  pollTimer = null
  if (disposed || document.hidden || isWorkflowTerminal(run.value?.status)) return
  const plan = nextRefreshPlan({
    status: run.value?.status ?? "RUNNING",
    streamOutcome: "idle",
    failures: pollFailures,
  })
  if (plan.stop || plan.pollAfterMs == null) return
  pollTimer = setTimeout(() => void refreshRun(), plan.pollAfterMs)
}

function handleStreamClosed(outcome: WorkflowStreamOutcome) {
  streamController = null
  if (disposed || document.hidden || isWorkflowTerminal(run.value?.status)) return
  const plan = nextRefreshPlan({
    status: run.value?.status ?? "RUNNING",
    streamOutcome: outcome,
    failures: pollFailures,
  })
  schedulePoll()
  clearTimer(reconnectTimer)
  if (!plan.stop && plan.reconnectAfterMs != null) {
    reconnectTimer = setTimeout(connectStream, plan.reconnectAfterMs)
  }
}

function connectStream() {
  if (disposed || document.hidden || isWorkflowTerminal(run.value?.status)) return
  streamController?.abort()
  const controller = new AbortController()
  streamController = controller
  void streamTaskStatus(
    props.taskId,
    () => void refreshRun(),
    { token: auth.token, signal: controller.signal },
  ).then(
    () => handleStreamClosed("eof"),
    () => {
      if (!controller.signal.aborted) handleStreamClosed("error")
    },
  )
}

function classifyError(loadError: unknown) {
  if (loadError instanceof ApiBusinessError) {
    const code = String(loadError.code)
    if (code === "FORBIDDEN" || code === "ADMIN_FORBIDDEN") return "403" as const
    if (code.includes("NOT_FOUND")) return "404" as const
  }
  return "error" as const
}

async function refreshRun() {
  const responseRequest = ++requestSequence
  loadController?.abort()
  const controller = new AbortController()
  loadController = controller
  try {
    const result = await getRun(props.taskId, { token: auth.token, signal: controller.signal })
    if (!shouldApplyWorkflowResponse(requestSequence, responseRequest) || disposed) return
    run.value = result
    pageState.value = "ready"
    error.value = null
    refreshError.value = null
    pollFailures = 0
    if (isWorkflowTerminal(result.status)) stopRefresh()
  } catch (loadError) {
    if (controller.signal.aborted || !shouldApplyWorkflowResponse(requestSequence, responseRequest) || disposed) return
    pollFailures += 1
    if (!run.value) {
      pageState.value = classifyError(loadError)
      error.value = loadError instanceof Error ? loadError.message : "运行详情暂时不可用"
    } else {
      refreshError.value = "状态刷新中断，正在重试"
    }
  } finally {
    if (shouldApplyWorkflowResponse(requestSequence, responseRequest) && !disposed) {
      loading.value = false
      schedulePoll()
    }
  }
}

async function restart() {
  stopRefresh(true)
  requestSequence += 1
  pollFailures = 0
  run.value = null
  loading.value = true
  pageState.value = "ready"
  error.value = null
  refreshError.value = null
  await refreshRun()
  const refreshedRun = run.value as WorkflowRun | null
  if (!disposed && refreshedRun && !isWorkflowTerminal(refreshedRun.status)) connectStream()
}

async function mutateRun(operation: () => Promise<WorkflowRun>) {
  if (actionSubmitting.value) return
  actionSubmitting.value = true
  actionError.value = null
  stopRefresh(true)
  requestSequence += 1
  try {
    run.value = await operation()
    await refreshRun()
    if (run.value && !isWorkflowTerminal(run.value.status)) connectStream()
  } catch (mutationError) {
    actionError.value = mutationError instanceof Error ? mutationError.message : "操作失败，请重试"
    schedulePoll()
    if (run.value && !isWorkflowTerminal(run.value.status)) connectStream()
  } finally {
    actionSubmitting.value = false
  }
}

function handleCancel() {
  void mutateRun(() => cancelRun(props.taskId, { token: auth.token }))
}

function handleResume() {
  void mutateRun(() => resumeRun(props.taskId, { token: auth.token }))
}

function handleFeedback(payload: { action: WorkflowFeedbackAction; fields: Record<string, unknown> }) {
  const action = run.value?.userAction ?? run.value?.confirmation
  if (!action) {
    actionError.value = "确认信息已失效，请刷新后重试"
    return
  }
  void mutateRun(() => submitFeedback(props.taskId, {
    stepId: action.stepId,
    action: payload.action,
    fields: payload.fields,
    confirmationToken: action.confirmationToken,
    idempotencyKey: workflowFeedbackIdempotencyKey(
      props.taskId,
      action.stepId,
      action.confirmationToken,
      payload.action,
      randomUUID,
      typeof sessionStorage === "undefined" ? undefined : sessionStorage,
    ),
  }, { token: auth.token }))
}

function onVisibilityChange() {
  if (document.hidden) {
    stopRefresh(true)
    requestSequence += 1
    return
  }
  void refreshRun().then(() => {
    if (run.value && !isWorkflowTerminal(run.value.status)) connectStream()
  })
}

watch(() => props.taskId, restart, { immediate: true })
document.addEventListener("visibilitychange", onVisibilityChange)
onBeforeUnmount(() => {
  disposed = true
  requestSequence += 1
  stopRefresh(true)
  document.removeEventListener("visibilitychange", onVisibilityChange)
})
</script>

<template>
  <main class="mx-auto w-full max-w-6xl px-5 py-7 sm:px-7 lg:py-10">
    <RouterLink :to="userRoutes.agentTools" class="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground">
      <ArrowLeft class="h-4 w-4" />
      工具中心
    </RouterLink>

    <div v-if="loading" class="flex min-h-72 items-center justify-center text-sm text-muted-foreground">
      <Loader2 class="mr-2 h-4 w-4 animate-spin" />
      正在加载运行详情
    </div>

    <section v-else-if="pageState === '403'" class="flex min-h-72 flex-col items-center justify-center text-center">
      <ShieldAlert class="h-9 w-9 text-warning" />
      <h1 class="mt-4 text-lg font-semibold">无权查看此运行</h1>
      <p class="mt-2 text-sm text-muted-foreground">请确认当前账号与任务归属一致。</p>
    </section>

    <section v-else-if="pageState === '404'" class="flex min-h-72 flex-col items-center justify-center text-center">
      <Workflow class="h-9 w-9 text-muted-foreground" />
      <h1 class="mt-4 text-lg font-semibold">未找到运行记录</h1>
      <p class="mt-2 max-w-lg text-sm leading-6 text-muted-foreground">
        {{ legacy ? "旧版历史任务可能尚未同步到新的工作流运行视图。" : "任务不存在或已不可用。" }}
      </p>
      <RouterLink :to="userRoutes.myTasks" class="mt-5 text-sm text-primary hover:underline">返回我的任务</RouterLink>
    </section>

    <section v-else-if="pageState === 'error'" class="flex min-h-72 flex-col items-center justify-center text-center">
      <Workflow class="h-9 w-9 text-muted-foreground" />
      <h1 class="mt-4 text-lg font-semibold">无法加载运行详情</h1>
      <p class="mt-2 max-w-lg text-sm text-muted-foreground">{{ error }}</p>
      <button type="button" class="mt-5 inline-flex h-9 items-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary" @click="restart">
        <RefreshCw class="h-4 w-4" />
        重新加载
      </button>
    </section>

    <template v-else-if="run">
      <header class="mt-6 border-b border-border pb-6">
        <div class="flex flex-wrap items-start justify-between gap-4">
          <div>
            <div class="mb-2 text-xs text-muted-foreground">任务 {{ run.taskNo || run.taskId }}</div>
            <h1 class="text-2xl font-semibold">{{ run.toolName || "工作流运行" }}</h1>
            <p class="mt-2 text-sm text-muted-foreground">{{ run.progressMessage || "运行状态会自动更新。" }}</p>
          </div>
          <span class="rounded-md px-2.5 py-1 text-xs font-medium" :class="statusClass(run.status)">{{ statusView?.label || run.status }}</span>
        </div>
        <div class="mt-5 h-2 overflow-hidden rounded-full bg-secondary">
          <div class="h-full rounded-full bg-primary transition-[width]" :style="{ width: `${progress}%` }" />
        </div>
        <div class="mt-2 flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
          <span>{{ progress }}%</span>
          <span class="inline-flex items-center gap-1.5"><Clock3 class="h-3.5 w-3.5" /> 创建于 {{ formatTimestamp(run.createdAt) }}</span>
        </div>
        <p v-if="refreshError" class="mt-3 text-xs text-warning">{{ refreshError }}</p>
        <p v-if="run.errorMessage" class="mt-3 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">{{ run.errorMessage }}</p>
      </header>

      <WorkflowRunActions
        class="mt-6"
        :run="run"
        :submitting="actionSubmitting"
        :error="actionError"
        @cancel="handleCancel"
        @resume="handleResume"
        @feedback="handleFeedback"
      />

      <div class="grid gap-8 py-7 lg:grid-cols-[minmax(0,1fr)_280px]">
        <div class="space-y-9">
          <WorkflowStepList :steps="run.steps ?? []" :current-step-id="run.currentStepId" />
          <WorkflowArtifactViewer :artifacts="artifacts" :adapter-key="run.adapterKey" :adapter-data="run.adapterData" />
          <span v-if="emptySteps || emptyArtifacts" class="sr-only">运行数据仍在汇总</span>
        </div>
        <aside class="border-t border-border pt-7 lg:border-l lg:border-t-0 lg:pl-7 lg:pt-0">
          <WorkflowCostSummary :run="run" />
          <dl class="mt-6 space-y-3 border-t border-border pt-5 text-xs">
            <div class="flex justify-between gap-3"><dt class="text-muted-foreground">开始时间</dt><dd class="text-right">{{ formatTimestamp(run.startedAt) }}</dd></div>
            <div class="flex justify-between gap-3"><dt class="text-muted-foreground">更新时间</dt><dd class="text-right">{{ formatTimestamp(run.updatedAt) }}</dd></div>
            <div class="flex justify-between gap-3"><dt class="text-muted-foreground">完成时间</dt><dd class="text-right">{{ formatTimestamp(run.completedAt) }}</dd></div>
          </dl>
        </aside>
      </div>
    </template>
  </main>
</template>
