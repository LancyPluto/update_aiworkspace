<script setup lang="ts">
// 任务状态页：漫剧工作流支持逐分镜预览与逐分镜意见
import { computed, onMounted, onUnmounted, ref, watch } from "vue"
import { RouterLink, useRouter } from "vue-router"
import { ArrowLeft, CheckCircle2, ChevronRight, Loader2, Sparkles, Workflow, X as XIcon } from "lucide-vue-next"
import TaskStatusTag from "@/components/TaskStatusTag/TaskStatusTag.vue"
import { fetchTaskById, fetchTaskStatus, streamTaskStatus, submitWorkflowFeedback } from "@/api/taskApi"
import type { TaskDetail, TaskStatus, TaskStatusPayload, WorkflowStagePreview } from "@/api/types"
import { normalizeMediaUrl } from "@/utils/toolCoverMedia"
import { isWorkflowToolCode } from "@/adapters/toolPresentationAdapter"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { taskFailureHint, taskProgressMessage, taskStatusDocLabel, taskStatusViewKind } from "@/utils/taskStatusLabels"

const props = defineProps<{
  taskId: string
}>()

const router = useRouter()
const auth = useAuthStore()

const statusData = ref<TaskStatusPayload | null>(null)
const taskDetailFail = ref<TaskDetail | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const streamConnected = ref(false)
const feedbackText = ref("")
/** 逐分镜意见：key 为分镜序号（1 起） */
const sceneFeedbackTexts = ref<Record<number, string>>({})
const feedbackSubmitting = ref(false)
const feedbackError = ref<string | null>(null)

const toolCode = computed(
  () => statusData.value?.toolCode || taskDetailFail.value?.toolCode || "",
)

const isComicDrama = computed(() => toolCode.value === "ai_comic_drama_agent")

watch(toolCode, (code) => {
  if (isWorkflowToolCode(code)) router.replace(userRoutes.workflowStudio(props.taskId))
}, { immediate: false })

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

const awaitingFieldKey = computed(
  () => statusData.value?.workflowPreview?.fieldKey || FEEDBACK_LABEL_TO_KEY[awaitingStageLabel.value] || null,
)

const workflowPreview = computed<WorkflowStagePreview | null>(() => statusData.value?.workflowPreview ?? null)

const previewStageLabel = computed(
  () => workflowPreview.value?.stageLabel || awaitingStageLabel.value,
)

/** 分镜脚本列表（剧本台词在第一步即全部生成） */
const previewScenes = computed(() => workflowPreview.value?.script?.scenes ?? [])

/** 分镜关键帧（场景图意见阶段逐镜展示） */
const previewSceneImages = computed(() => workflowPreview.value?.images ?? [])

/** 分镜配音列表 */
const previewSceneAudios = computed(() => workflowPreview.value?.audios ?? [])

/** 当前阶段是否支持逐分镜意见 */
const supportsPerSceneFeedback = computed(() => {
  const key = awaitingFieldKey.value
  if (key === "storyboardFeedback") return previewScenes.value.length > 1
  if (key === "sceneFeedback") return previewSceneImages.value.length > 1 || previewScenes.value.length > 1
  return false
})

function sceneImageFor(index?: number): string {
  if (!index) return ""
  const match = previewSceneImages.value.find((item) => item.sceneIndex === index)
  return match?.imageUrl || ""
}

/** 把整体意见 + 逐分镜意见合并成提交值：纯文本（仅整体）或 JSON（含逐分镜） */
function buildFeedbackValue(skip: boolean): string {
  if (skip) return ""
  const overall = feedbackText.value.trim()
  const perScene: Record<string, string> = {}
  for (const [index, text] of Object.entries(sceneFeedbackTexts.value)) {
    const trimmed = (text || "").trim()
    if (trimmed) perScene[index] = trimmed
  }
  if (Object.keys(perScene).length === 0) return overall
  return JSON.stringify(overall ? { all: overall, ...perScene } : perScene)
}

function previewHelpText(preview: WorkflowStagePreview | null, stageLabel: string): string {
  if (!preview) {
    return "生成内容准备中，请稍候；出现本面板后即可查看脚本并填写意见。"
  }
  if (stageLabel.includes("脚本") || stageLabel.includes("分镜")) {
    return "全部分镜的脚本和台词已一次性生成。可在每个分镜下方单独填写意见，也可在底部填写整体意见；满意可点「跳过继续」。"
  }
  if (stageLabel.includes("场景")) {
    return "请逐镜查看关键帧画面，可对每个分镜单独说明构图、光影或人物表情等修改意见；满意可跳过。"
  }
  if (stageLabel.includes("配音")) {
    return "请试听角色配音，说明语速、情绪或停顿；满意可跳过。"
  }
  return "可填写修改意见，或点「跳过继续」进入下一步。"
}

const taskStages = computed(() => {
  if (isComicDrama.value) {
    // 对齐 15 节点 SOP（剧本→分镜→角色/场景/关键帧→质检→图生视频→质检→配音→字幕合成→人审→成片）
    return [
      { label: "剧本策划与分镜", progress: 15 },
      { label: "角色/场景/关键帧", progress: 40 },
      { label: "逐镜图生视频", progress: 65 },
      { label: "配音与字幕合成", progress: 88 },
      { label: "人工审核与成片", progress: 96 },
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

const activeStageIndex = computed(() => {
  const progress = statusData.value?.progress ?? 0
  const index = taskStages.value.findIndex((stage) => progress < stage.progress)
  return index === -1 ? taskStages.value.length - 1 : Math.max(0, index)
})

function stageState(stageProgress: number): "done" | "current" | "pending" {
  const progress = statusData.value?.progress ?? 0
  if (progress >= stageProgress) return "done"
  if (progress >= stageProgress - 18) return "current"
  return "pending"
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
  return taskFailureHint(statusData.value.status, [d?.progressMessage, statusData.value.progressMessage])
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
    const fields: Record<string, string> = { [key]: buildFeedbackValue(skip) }
    const payload = await submitWorkflowFeedback(props.taskId, fields, { token: auth.token })
    feedbackText.value = ""
    sceneFeedbackTexts.value = {}
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
    if (taskStatusViewKind(payload.status) === "failed" || taskStatusViewKind(payload.status) === "cancelled") {
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
    <div class="px-6 py-6 mx-auto max-w-6xl space-y-5">
      <nav class="flex items-center gap-1.5 text-xs text-muted-foreground flex-wrap">
        <RouterLink :to="userRoutes.dashboard" class="hover:text-foreground inline-flex items-center gap-1">
          <ArrowLeft class="h-3 w-3" /> 生成工作台
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
        class="overflow-hidden rounded-xl border"
        :class="
          taskStatusViewKind(statusData.status) === 'success'
            ? 'border-success/30 bg-success/5'
            : taskStatusViewKind(statusData.status) === 'failed'
              ? 'border-destructive/30 bg-destructive/5'
              : taskStatusViewKind(statusData.status) === 'cancelled'
                ? 'border-border bg-muted/50'
                : 'border-primary/20 bg-accent/30'
        "
      >
        <div
          v-if="isComicDrama"
          class="relative overflow-hidden border-b border-white/10 bg-[#08070d] px-6 py-5 text-white"
        >
          <div class="pointer-events-none absolute inset-0 opacity-60">
            <div class="absolute left-10 top-0 h-32 w-32 rounded-full bg-primary/25 blur-3xl" />
            <div class="absolute right-20 bottom-0 h-36 w-36 rounded-full bg-fuchsia-500/20 blur-3xl" />
          </div>
          <div class="relative flex flex-wrap items-center justify-between gap-4">
            <div>
              <div class="inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/10 px-3 py-1 text-xs text-white/75">
                <Workflow class="h-3.5 w-3.5" />
                AI 漫剧生成链条
              </div>
              <h2 class="mt-3 text-2xl font-semibold tracking-normal">实时中间结果与人工调整</h2>
              <p class="mt-1 max-w-2xl text-sm leading-6 text-white/55">
                剧本、关键帧、配音、视频片段会按节点刷新；需要调整时在当前阶段提交意见，系统会从对应节点继续生成。
              </p>
            </div>
            <div class="rounded-lg border border-white/10 bg-white/[0.06] px-4 py-3 text-right">
              <p class="text-xs text-white/45">当前节点</p>
              <p class="mt-1 text-sm font-medium">{{ taskStages[activeStageIndex]?.label || previewStageLabel }}</p>
              <p class="mt-1 text-xs" :class="streamConnected ? 'text-emerald-300' : 'text-amber-200'">
                {{ streamConnected ? 'SSE 实时连接中' : '轮询兜底中' }}
              </p>
            </div>
          </div>
          <div class="relative mt-6 grid gap-2 md:grid-cols-5">
            <div
              v-for="(stage, index) in taskStages"
              :key="stage.label"
              class="relative min-h-20 rounded-lg border px-3 py-3"
              :class="
                stageState(stage.progress) === 'done'
                  ? 'border-emerald-300/35 bg-emerald-300/10 text-emerald-100'
                  : index === activeStageIndex
                    ? 'border-primary/60 bg-primary/20 text-white shadow-[0_0_28px_rgba(168,85,247,0.25)]'
                    : 'border-white/10 bg-white/[0.04] text-white/45'
              "
            >
              <div class="flex items-center justify-between gap-2">
                <span class="grid h-7 w-7 place-items-center rounded-full bg-white/10 text-xs font-semibold">{{ index + 1 }}</span>
                <Sparkles v-if="index === activeStageIndex && !isTerminal(statusData.status)" class="h-4 w-4 text-primary" />
                <CheckCircle2 v-else-if="stageState(stage.progress) === 'done'" class="h-4 w-4 text-emerald-300" />
              </div>
              <p class="mt-2 text-xs font-medium leading-5">{{ stage.label }}</p>
            </div>
          </div>
        </div>

        <div class="p-6 shadow-sm">
          <div class="flex items-center justify-between mb-3 flex-wrap gap-2">
            <div class="flex items-center gap-2">
              <Loader2 v-if="!isTerminal(statusData.status)" class="h-4 w-4 text-primary animate-spin" />
              <CheckCircle2 v-else-if="taskStatusViewKind(statusData.status) === 'success'" class="h-4 w-4 text-success" />
              <XIcon v-else class="h-4 w-4" :class="taskStatusViewKind(statusData.status) === 'cancelled' ? 'text-muted-foreground' : 'text-destructive'" />
              <h3 class="text-sm font-semibold">任务状态</h3>
              <TaskStatusTag
                :status="taskStatusViewKind(statusData.status)"
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
              <span class="text-muted-foreground">{{ taskProgressMessage(statusData.status, statusData.progressMessage) }}</span>
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
            v-if="isTerminal(statusData.status) && (taskStatusViewKind(statusData.status) === 'failed' || taskStatusViewKind(statusData.status) === 'cancelled')"
            class="mt-4 rounded-lg border border-destructive/25 bg-destructive/5 p-3 text-sm text-destructive"
            :class="taskStatusViewKind(statusData.status) === 'cancelled' ? 'border-border bg-muted/50 text-muted-foreground' : ''"
          >
            {{ failureHint }}
          </div>

          <div
            v-if="(awaitingFeedback || workflowPreview) && isComicDrama"
            class="mt-5 rounded-lg border border-primary/25 bg-background p-4 shadow-sm space-y-4"
          >
            <div>
              <p class="text-sm font-medium">交互式短剧 · {{ previewStageLabel }}</p>
              <p class="mt-1 text-xs text-muted-foreground">
                {{ previewHelpText(workflowPreview, previewStageLabel) }}
              </p>
            </div>

            <!-- 多分镜模式：脚本与台词一次性生成，逐镜展示并支持逐镜意见 -->
            <div v-if="previewScenes.length > 1" class="space-y-3">
              <p class="text-xs text-muted-foreground">
                共 {{ previewScenes.length }} 个分镜 · 每镜 5 秒 · 约 {{ previewScenes.length * 5 }} 秒成片
              </p>
              <div
                v-for="(scene, idx) in previewScenes"
                :key="scene.index ?? idx"
                class="rounded-md border border-border bg-muted/20 p-3 space-y-2 text-sm"
              >
                <p class="font-medium">
                  分镜 {{ scene.index ?? idx + 1 }}<template v-if="scene.sceneTitle"> · {{ scene.sceneTitle }}</template>
                </p>
                <img
                  v-if="sceneImageFor(scene.index ?? idx + 1)"
                  :src="normalizeMediaUrl(sceneImageFor(scene.index ?? idx + 1))"
                  :alt="`分镜 ${scene.index ?? idx + 1} 关键帧`"
                  class="max-h-56 w-full rounded-md border border-border object-cover"
                />
                <p v-if="scene.sceneDescription" class="text-muted-foreground whitespace-pre-wrap">
                  {{ scene.sceneDescription }}
                </p>
                <p v-if="scene.dialogue">
                  <span class="text-xs text-muted-foreground">台词：</span>{{ scene.dialogue }}
                </p>
                <p v-if="scene.narration">
                  <span class="text-xs text-muted-foreground">旁白：</span>{{ scene.narration }}
                </p>
                <p v-if="scene.subtitleZh && scene.subtitleZh !== scene.dialogue">
                  <span class="text-xs text-muted-foreground">字幕：</span>{{ scene.subtitleZh }}
                </p>
                <textarea
                  v-if="awaitingFeedback && supportsPerSceneFeedback"
                  v-model="sceneFeedbackTexts[scene.index ?? idx + 1]"
                  rows="2"
                  class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
                  :placeholder="`分镜 ${scene.index ?? idx + 1} 的修改意见（可选）`"
                />
              </div>
            </div>

            <!-- 单镜/旧版兼容 -->
            <div
              v-else-if="workflowPreview?.script"
              class="rounded-md border border-border bg-muted/20 p-3 space-y-2 text-sm"
            >
              <p v-if="workflowPreview.script.sceneTitle" class="font-medium">
                {{ workflowPreview.script.sceneTitle }}
              </p>
              <p v-if="workflowPreview.script.sceneDescription" class="text-muted-foreground whitespace-pre-wrap">
                {{ workflowPreview.script.sceneDescription }}
              </p>
              <p v-if="workflowPreview.script.dialogue">
                <span class="text-xs text-muted-foreground">对白：</span>{{ workflowPreview.script.dialogue }}
              </p>
              <p v-if="workflowPreview.script.narration">
                <span class="text-xs text-muted-foreground">旁白：</span>{{ workflowPreview.script.narration }}
              </p>
              <p v-if="workflowPreview.script.subtitleZh">
                <span class="text-xs text-muted-foreground">字幕：</span>{{ workflowPreview.script.subtitleZh }}
              </p>
            </div>

            <!-- 无 scenes 数据时的关键帧（多图/单图）展示 -->
            <div v-if="previewScenes.length <= 1 && previewSceneImages.length > 1" class="grid grid-cols-2 gap-2 sm:grid-cols-3">
              <figure v-for="image in previewSceneImages" :key="image.sceneIndex ?? image.imageUrl" class="space-y-1">
                <img
                  :src="normalizeMediaUrl(image.imageUrl)"
                  :alt="`分镜 ${image.sceneIndex ?? ''} 关键帧`"
                  class="aspect-video w-full rounded-md border border-border object-cover"
                />
                <figcaption class="text-center text-xs text-muted-foreground">分镜 {{ image.sceneIndex }}</figcaption>
              </figure>
            </div>
            <img
              v-else-if="previewScenes.length <= 1 && workflowPreview?.imageUrl"
              :src="normalizeMediaUrl(workflowPreview.imageUrl)"
              alt="关键帧预览"
              class="max-h-72 w-full rounded-md border border-border object-cover"
            />

            <!-- 配音预览：多镜逐条试听 -->
            <div v-if="previewSceneAudios.length > 1" class="space-y-2">
              <div
                v-for="audio in previewSceneAudios"
                :key="audio.sceneIndex ?? audio.audioUrl"
                class="rounded-md border border-border bg-muted/20 p-2"
              >
                <p class="mb-1 text-xs text-muted-foreground">
                  分镜 {{ audio.sceneIndex }}<template v-if="audio.speechText"> · {{ audio.speechText }}</template>
                </p>
                <audio :src="normalizeMediaUrl(audio.audioUrl)" controls class="w-full" />
              </div>
            </div>
            <audio
              v-else-if="workflowPreview?.audioUrl"
              :src="normalizeMediaUrl(workflowPreview.audioUrl)"
              controls
              class="w-full"
            />

            <video
              v-if="workflowPreview?.videoUrl"
              :src="normalizeMediaUrl(workflowPreview.videoUrl)"
              controls
              class="max-h-72 w-full rounded-md border border-border"
            />

            <textarea
              v-if="awaitingFeedback"
              v-model="feedbackText"
              rows="4"
              class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
              :placeholder="supportsPerSceneFeedback ? '整体' + previewStageLabel + '（可选，逐镜意见请填在各分镜下方）' : '请输入' + previewStageLabel + '（可选）'"
            />
            <template v-if="awaitingFeedback">
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
            </template>
          </div>

          <div
            v-if="!isTerminal(statusData.status) && !awaitingFeedback && !isComicDrama"
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

        <div v-if="taskStatusViewKind(statusData.status) === 'success'" class="px-6 pb-6">
          <RouterLink
            :to="userRoutes.taskResult(taskId)"
            class="inline-flex h-10 items-center justify-center rounded-md bg-primary px-6 text-sm font-medium text-primary-foreground hover:opacity-90"
          >
            查看结果
          </RouterLink>
        </div>
      </div>
    </div>
</template>
