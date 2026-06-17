<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch, nextTick } from "vue"
import { RouterLink } from "vue-router"
import {
  ArrowLeft,
  CheckCircle2,
  ChevronRight,
  Download,
  Edit3,
  GripVertical,
  Loader2,
  Pause,
  Play,
  RefreshCw,
  Send,
  SkipForward,
  Sparkles,
  Workflow,
  X as XIcon,
} from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import TaskStatusTag from "@/components/TaskStatusTag/TaskStatusTag.vue"
import {
  fetchTaskById,
  fetchTaskStatus,
  streamTaskStatus,
  submitWorkflowFeedback,
} from "@/api/taskApi"
import type {
  TaskDetail,
  TaskStatus,
  TaskStatusPayload,
  WorkflowStagePreview,
  WorkflowSceneScript,
} from "@/api/types"
import { normalizeMediaUrl } from "@/utils/toolCoverMedia"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import {
  taskFailureHint,
  taskProgressMessage,
  taskStatusDocLabel,
  taskStatusViewKind,
} from "@/utils/taskStatusLabels"

const props = defineProps<{ taskId: string }>()
const auth = useAuthStore()

const statusData = ref<TaskStatusPayload | null>(null)
const taskDetailFail = ref<TaskDetail | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const streamConnected = ref(false)

const activeTab = ref<"script" | "storyboard" | "scene" | "video">("script")

const feedbackText = ref("")
const sceneFeedbackTexts = ref<Record<number, string>>({})
const feedbackSubmitting = ref(false)
const feedbackError = ref<string | null>(null)
const editingSection = ref<string | null>(null)

// Timeline state
const timelineClipOrder = ref<number[]>([])
const draggingClipIndex = ref<number | null>(null)
const dragOverIndex = ref<number | null>(null)
const isPlaying = ref(false)
const currentPlayingClip = ref(0)
const playProgress = ref(0)
const videoPreviewRef = ref<HTMLVideoElement | null>(null)

const toolCode = computed(
  () => statusData.value?.toolCode || taskDetailFail.value?.toolCode || "",
)

const FEEDBACK_LABEL_TO_KEY: Record<string, string> = {
  "脚本意见": "scriptFeedback",
  "分镜意见": "storyboardFeedback",
  "场景图意见": "sceneFeedback",
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
  () =>
    statusData.value?.workflowPreview?.fieldKey ||
    FEEDBACK_LABEL_TO_KEY[awaitingStageLabel.value] ||
    null,
)

const workflowPreview = computed<WorkflowStagePreview | null>(
  () => statusData.value?.workflowPreview ?? null,
)
const previewStageLabel = computed(
  () => workflowPreview.value?.stageLabel || awaitingStageLabel.value,
)
const previewScenes = computed(
  () => workflowPreview.value?.script?.scenes ?? [],
)
const previewSceneImages = computed(
  () => workflowPreview.value?.images ?? [],
)
const previewSceneAudios = computed(
  () => workflowPreview.value?.audios ?? [],
)

const sceneCount = computed(() => {
  const fromScenes = previewScenes.value.length
  const fromImages = previewSceneImages.value.length
  const fromAudios = previewSceneAudios.value.length
  return Math.max(fromScenes, fromImages, fromAudios, 1)
})

function sceneImageFor(index: number): string {
  const match = previewSceneImages.value.find((item) => item.sceneIndex === index)
  return match?.imageUrl || ""
}

function sceneAudioFor(index: number): { audioUrl: string; speechText?: string } | null {
  return previewSceneAudios.value.find((item) => item.sceneIndex === index) || null
}

const taskStages = [
  { label: "剧本策划", progress: 18 },
  { label: "生成场景图", progress: 46 },
  { label: "逐镜生成视频", progress: 65 },
  { label: "字幕合成与拼接", progress: 95 },
  { label: "成片输出", progress: 100 },
]

const activeStageIndex = computed(() => {
  const progress = statusData.value?.progress ?? 0
  const index = taskStages.findIndex((stage) => progress < stage.progress)
  return index === -1 ? taskStages.length - 1 : Math.max(0, index)
})

function stageState(stageProgress: number): "done" | "current" | "pending" {
  const progress = statusData.value?.progress ?? 0
  if (progress >= stageProgress) return "done"
  const prevStage = taskStages.find((s) => s.progress === stageProgress)
  const prevIndex = prevStage ? taskStages.indexOf(prevStage) : -1
  const prevProgress = prevIndex > 0 ? taskStages[prevIndex - 1].progress : 0
  if (progress >= prevProgress) return "current"
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
  return taskFailureHint(statusData.value.status, [
    d?.progressMessage,
    statusData.value.progressMessage,
  ])
})

const hasVideo = computed(() => Boolean(workflowPreview.value?.videoUrl || workflowPreview.value?.finalVideoUrl))
const finalVideoUrl = computed(() => {
  const url = workflowPreview.value?.finalVideoUrl || workflowPreview.value?.videoUrl
  return url ? normalizeMediaUrl(url) : ""
})

// -- SSE / Polling --
let pollTimer: ReturnType<typeof setTimeout> | null = null
let statusLoadAbort: AbortController | undefined
let statusStreamAbort: AbortController | undefined

function clearPollTimer() {
  if (pollTimer !== null) { clearTimeout(pollTimer); pollTimer = null }
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
  pollTimer = setTimeout(() => { void loadStatus() }, pollDelayMs(statusData.value.status))
}
function isAbortError(e: unknown): boolean {
  return e instanceof DOMException && e.name === "AbortError"
}

async function loadFailDetailOnce() {
  if (!props.taskId || taskDetailFail.value) return
  const s = statusData.value?.status
  if (!s || !["FAILED", "TIMEOUT", "CANCELLED"].includes(s)) return
  try { taskDetailFail.value = await fetchTaskById(props.taskId, { token: auth.token }) } catch { /* noop */ }
}

async function applyStatus(payload: TaskStatusPayload) {
  statusData.value = payload
  loading.value = false
  error.value = null
  if (isTerminal(payload.status)) {
    clearStatusPolling()
    clearStatusStream()
    if (["FAILED", "TIMEOUT", "CANCELLED"].includes(payload.status)) await loadFailDetailOnce()
    initTimelineFromScenes()
  } else if (payload.status === "AWAITING_USER") {
    autoActivateTab()
  }
  if (previewScenes.value.length > 0 && timelineClipOrder.value.length === 0) {
    initTimelineFromScenes()
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
    if (!statusLoadAbort?.signal.aborted) loading.value = false
  }
}

function startStatusStream() {
  clearStatusStream()
  if (!props.taskId) return
  statusStreamAbort = new AbortController()
  void streamTaskStatus(
    props.taskId,
    (payload) => { streamConnected.value = true; void applyStatus(payload) },
    { token: auth.token, signal: statusStreamAbort.signal },
  ).catch((e) => { if (!isAbortError(e)) streamConnected.value = false })
}

function onVisibilityChange() {
  if (typeof document === "undefined" || document.hidden) return
  if (!props.taskId) return
  if (statusData.value && isTerminal(statusData.value.status)) return
  void loadStatus()
}

// -- Feedback --
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

async function submitFeedback(skip = false) {
  if (!props.taskId || feedbackSubmitting.value) return
  feedbackSubmitting.value = true
  feedbackError.value = null
  try {
    const key = awaitingFieldKey.value || "scriptFeedback"
    const payload = await submitWorkflowFeedback(
      props.taskId,
      { [key]: buildFeedbackValue(skip) },
      { token: auth.token },
    )
    feedbackText.value = ""
    sceneFeedbackTexts.value = {}
    editingSection.value = null
    await applyStatus(payload)
  } catch (e) {
    feedbackError.value = (e as Error).message || "提交意见失败"
  } finally {
    feedbackSubmitting.value = false
  }
}

function autoActivateTab() {
  const key = awaitingFieldKey.value
  if (key === "scriptFeedback" || key === "storyboardFeedback") activeTab.value = "script"
  else if (key === "sceneFeedback") activeTab.value = "scene"
  else if (previewSceneImages.value.length > 0 && previewScenes.value.length > 0) activeTab.value = "storyboard"
}

// -- Timeline --
function initTimelineFromScenes() {
  const count = sceneCount.value
  if (count <= 0 || timelineClipOrder.value.length > 0) return
  timelineClipOrder.value = Array.from({ length: count }, (_, i) => i + 1)
}

function onDragStart(idx: number, event: DragEvent) {
  draggingClipIndex.value = idx
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = "move"
    event.dataTransfer.setData("text/plain", String(idx))
  }
}
function onDragOver(idx: number, event: DragEvent) {
  event.preventDefault()
  dragOverIndex.value = idx
}
function onDragLeave() {
  dragOverIndex.value = null
}
function onDrop(targetIdx: number) {
  if (draggingClipIndex.value === null || draggingClipIndex.value === targetIdx) {
    draggingClipIndex.value = null
    dragOverIndex.value = null
    return
  }
  const arr = [...timelineClipOrder.value]
  const [moved] = arr.splice(draggingClipIndex.value, 1)
  arr.splice(targetIdx, 0, moved)
  timelineClipOrder.value = arr
  draggingClipIndex.value = null
  dragOverIndex.value = null
}
function onDragEnd() {
  draggingClipIndex.value = null
  dragOverIndex.value = null
}

// -- Preview playback --
let playbackTimer: ReturnType<typeof setInterval> | null = null
const CLIP_DURATION_SECONDS = 5

function togglePlayback() {
  if (isPlaying.value) {
    pausePlayback()
  } else {
    startPlayback()
  }
}

function startPlayback() {
  if (!hasVideo.value && previewSceneImages.value.length === 0) return
  isPlaying.value = true
  currentPlayingClip.value = 0
  playProgress.value = 0

  if (hasVideo.value && videoPreviewRef.value) {
    videoPreviewRef.value.currentTime = 0
    videoPreviewRef.value.play()
    return
  }

  playbackTimer = setInterval(() => {
    playProgress.value += 100 / (CLIP_DURATION_SECONDS * 10)
    if (playProgress.value >= 100) {
      playProgress.value = 0
      currentPlayingClip.value++
      if (currentPlayingClip.value >= timelineClipOrder.value.length) {
        pausePlayback()
      }
    }
  }, 100)
}

function pausePlayback() {
  isPlaying.value = false
  if (playbackTimer) { clearInterval(playbackTimer); playbackTimer = null }
  if (videoPreviewRef.value) videoPreviewRef.value.pause()
}

function seekToClip(idx: number) {
  currentPlayingClip.value = idx
  playProgress.value = 0
}

function handleExport() {
  if (!finalVideoUrl.value) return
  const a = document.createElement("a")
  a.href = finalVideoUrl.value
  a.download = `comic-drama-${props.taskId}.mp4`
  a.target = "_blank"
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

// -- Lifecycle --
watch(() => props.taskId, () => {
  taskDetailFail.value = null
  statusData.value = null
  loading.value = true
  error.value = null
  timelineClipOrder.value = []
  clearStatusPolling()
  startStatusStream()
  void loadStatus()
})

onMounted(() => {
  void loadStatus()
  startStatusStream()
  document.addEventListener("visibilitychange", onVisibilityChange)
})

onUnmounted(() => {
  document.removeEventListener("visibilitychange", onVisibilityChange)
  clearStatusPolling()
  clearStatusStream()
  pausePlayback()
})

const tabs = [
  { key: "script" as const, label: "剧本与分镜", icon: "📜" },
  { key: "storyboard" as const, label: "分镜画面", icon: "🎬" },
  { key: "scene" as const, label: "场景关键帧", icon: "🎨" },
  { key: "video" as const, label: "分镜视频", icon: "🎥" },
]

const totalTimelineDuration = computed(() => timelineClipOrder.value.length * CLIP_DURATION_SECONDS)
const playheadPosition = computed(() => {
  if (timelineClipOrder.value.length === 0) return 0
  const clipFraction = playProgress.value / 100
  const clipWidth = 100 / timelineClipOrder.value.length
  return currentPlayingClip.value * clipWidth + clipFraction * clipWidth
})
</script>

<template>
  <AppShell title="工作流工作室" :description="'AI漫剧工作流 · 任务 ' + taskId">
    <div class="flex min-h-screen flex-col bg-[#0a0a0f]">
      <!-- Top navigation -->
      <nav class="sticky top-0 z-30 border-b border-white/10 bg-[#0a0a0f]/95 backdrop-blur-sm px-6 py-3">
        <div class="mx-auto flex max-w-7xl items-center justify-between gap-4">
          <div class="flex items-center gap-3">
            <RouterLink
              :to="userRoutes.dashboard"
              class="inline-flex items-center gap-1.5 text-xs text-white/50 hover:text-white/80 transition"
            >
              <ArrowLeft class="h-3.5 w-3.5" /> 返回工作台
            </RouterLink>
            <span class="text-white/20">|</span>
            <div class="flex items-center gap-2">
              <Workflow class="h-4 w-4 text-primary" />
              <span class="text-sm font-medium text-white/90">AI 漫剧工作室</span>
            </div>
          </div>
          <div class="flex items-center gap-3">
            <span class="text-xs font-mono text-white/35">{{ statusData?.taskNo || taskId }}</span>
            <span
              class="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px]"
              :class="streamConnected ? 'bg-emerald-500/15 text-emerald-300' : 'bg-amber-500/15 text-amber-300'"
            >
              <span class="h-1.5 w-1.5 rounded-full" :class="streamConnected ? 'bg-emerald-400 animate-pulse' : 'bg-amber-400'" />
              {{ streamConnected ? "实时连接" : "轮询中" }}
            </span>
            <TaskStatusTag
              v-if="statusData"
              :status="taskStatusViewKind(statusData.status)"
              :label="taskStatusDocLabel(statusData.status)"
            />
          </div>
        </div>
      </nav>

      <div v-if="loading" class="flex flex-1 items-center justify-center">
        <div class="text-center space-y-3">
          <Loader2 class="mx-auto h-8 w-8 animate-spin text-primary" />
          <p class="text-sm text-white/50">正在加载任务数据...</p>
        </div>
      </div>

      <div v-else-if="error" class="flex flex-1 items-center justify-center px-6">
        <div class="rounded-xl border border-red-500/30 bg-red-500/5 p-8 text-center max-w-md">
          <p class="text-sm text-red-400">{{ error }}</p>
          <button
            class="mt-4 rounded-md bg-primary px-4 py-2 text-sm text-white hover:opacity-90"
            @click="loadStatus"
          >
            重试
          </button>
        </div>
      </div>

      <template v-else-if="statusData">
        <div class="mx-auto w-full max-w-7xl flex-1 space-y-5 px-6 py-5">
          <!-- Progress pipeline -->
          <div class="rounded-xl border border-white/10 bg-white/[0.02] p-5">
            <div class="mb-4 flex items-center justify-between">
              <h3 class="text-sm font-medium text-white/70">工作流进度</h3>
              <span class="text-xs text-white/40">
                {{ taskProgressMessage(statusData.status, statusData.progressMessage) }}
                · {{ statusData.progress ?? 0 }}%
              </span>
            </div>
            <div class="grid grid-cols-5 gap-2">
              <div
                v-for="(stage, index) in taskStages"
                :key="stage.label"
                class="relative rounded-lg border px-3 py-3 transition-all duration-300"
                :class="
                  stageState(stage.progress) === 'done'
                    ? 'border-emerald-400/30 bg-emerald-400/10'
                    : index === activeStageIndex && !isTerminal(statusData.status)
                      ? 'border-primary/50 bg-primary/15 shadow-[0_0_20px_rgba(168,85,247,0.2)]'
                      : 'border-white/8 bg-white/[0.02]'
                "
              >
                <div class="flex items-center justify-between gap-1">
                  <span
                    class="grid h-6 w-6 place-items-center rounded-full text-[10px] font-bold"
                    :class="
                      stageState(stage.progress) === 'done'
                        ? 'bg-emerald-400/20 text-emerald-300'
                        : index === activeStageIndex
                          ? 'bg-primary/30 text-primary'
                          : 'bg-white/8 text-white/30'
                    "
                  >{{ index + 1 }}</span>
                  <Sparkles
                    v-if="index === activeStageIndex && !isTerminal(statusData.status)"
                    class="h-3.5 w-3.5 text-primary animate-pulse"
                  />
                  <CheckCircle2
                    v-else-if="stageState(stage.progress) === 'done'"
                    class="h-3.5 w-3.5 text-emerald-400"
                  />
                </div>
                <p
                  class="mt-2 text-[11px] font-medium leading-4"
                  :class="
                    stageState(stage.progress) === 'done'
                      ? 'text-emerald-200'
                      : index === activeStageIndex
                        ? 'text-white'
                        : 'text-white/30'
                  "
                >{{ stage.label }}</p>
              </div>
            </div>
            <div class="mt-3 h-1.5 overflow-hidden rounded-full bg-white/8">
              <div
                class="h-full rounded-full bg-gradient-to-r from-primary via-fuchsia-500 to-pink-500 transition-all duration-700"
                :style="{ width: (statusData.progress ?? 0) + '%' }"
              />
            </div>
          </div>

          <!-- Failure banner -->
          <div
            v-if="isTerminal(statusData.status) && ['FAILED','TIMEOUT','CANCELLED'].includes(statusData.status)"
            class="rounded-xl border border-red-500/25 bg-red-500/5 p-4 text-sm text-red-400"
          >
            {{ failureHint }}
          </div>

          <!-- Video preview area -->
          <div class="rounded-xl border border-white/10 bg-white/[0.02] overflow-hidden">
            <div class="flex items-center justify-between border-b border-white/8 px-5 py-3">
              <h3 class="text-sm font-medium text-white/70">预览</h3>
              <div class="flex items-center gap-2">
                <button
                  v-if="hasVideo || previewSceneImages.length > 0"
                  class="inline-flex items-center gap-1.5 rounded-md border border-white/10 bg-white/5 px-3 py-1.5 text-xs text-white/70 hover:bg-white/10 transition"
                  @click="togglePlayback"
                >
                  <component :is="isPlaying ? Pause : Play" class="h-3.5 w-3.5" />
                  {{ isPlaying ? "暂停" : "播放预览" }}
                </button>
                <button
                  v-if="finalVideoUrl"
                  class="inline-flex items-center gap-1.5 rounded-md bg-primary px-3 py-1.5 text-xs font-medium text-white hover:opacity-90 transition"
                  @click="handleExport"
                >
                  <Download class="h-3.5 w-3.5" />
                  导出视频
                </button>
              </div>
            </div>
            <div class="relative aspect-video bg-black/60 flex items-center justify-center">
              <video
                v-if="hasVideo"
                ref="videoPreviewRef"
                :src="finalVideoUrl"
                class="h-full w-full object-contain"
                controls
                @ended="pausePlayback"
              />
              <div v-else-if="previewSceneImages.length > 0" class="relative h-full w-full">
                <img
                  v-for="(img, idx) in previewSceneImages"
                  :key="img.sceneIndex ?? idx"
                  :src="normalizeMediaUrl(img.imageUrl)"
                  class="absolute inset-0 h-full w-full object-contain transition-opacity duration-500"
                  :class="(timelineClipOrder[currentPlayingClip] === (img.sceneIndex ?? idx + 1)) ? 'opacity-100' : 'opacity-0'"
                />
                <div class="absolute bottom-4 left-4 rounded-lg bg-black/60 px-3 py-1.5 text-xs text-white/80">
                  分镜 {{ timelineClipOrder[currentPlayingClip] ?? 1 }} / {{ sceneCount }}
                </div>
              </div>
              <div v-else class="text-center space-y-2">
                <Workflow class="mx-auto h-12 w-12 text-white/15" />
                <p class="text-sm text-white/30">等待生成中间结果...</p>
              </div>
            </div>
          </div>

          <!-- Content sections with tabs -->
          <div class="rounded-xl border border-white/10 bg-white/[0.02]">
            <div class="flex items-center gap-1 border-b border-white/8 px-5 overflow-x-auto">
              <button
                v-for="tab in tabs"
                :key="tab.key"
                class="flex-shrink-0 border-b-2 px-4 py-3 text-xs font-medium transition-colors"
                :class="
                  activeTab === tab.key
                    ? 'border-primary text-white'
                    : 'border-transparent text-white/40 hover:text-white/60'
                "
                @click="activeTab = tab.key"
              >
                {{ tab.label }}
              </button>
            </div>

            <div class="p-5">
              <!-- AWAITING_USER feedback banner -->
              <div
                v-if="awaitingFeedback"
                class="mb-5 rounded-lg border border-primary/30 bg-primary/10 p-4 space-y-3"
              >
                <p class="text-sm font-medium text-white/90">
                  当前等待您的反馈：{{ previewStageLabel }}
                </p>
                <textarea
                  v-model="feedbackText"
                  rows="3"
                  class="w-full rounded-md border border-white/15 bg-white/5 px-3 py-2 text-sm text-white placeholder:text-white/30 focus:border-primary focus:outline-none"
                  :placeholder="'请输入' + previewStageLabel + '（可选，留空则跳过）'"
                />
                <p v-if="feedbackError" class="text-xs text-red-400">{{ feedbackError }}</p>
                <div class="flex gap-2">
                  <button
                    class="inline-flex items-center gap-1.5 rounded-md bg-primary px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
                    :disabled="feedbackSubmitting"
                    @click="submitFeedback(false)"
                  >
                    <Send class="h-3.5 w-3.5" />
                    {{ feedbackSubmitting ? "提交中..." : "提交意见" }}
                  </button>
                  <button
                    class="inline-flex items-center gap-1.5 rounded-md border border-white/15 bg-white/5 px-4 py-2 text-sm text-white/70 hover:bg-white/10 disabled:opacity-50"
                    :disabled="feedbackSubmitting"
                    @click="submitFeedback(true)"
                  >
                    <SkipForward class="h-3.5 w-3.5" />
                    跳过继续
                  </button>
                </div>
              </div>

              <!-- Tab: 剧本与分镜 -->
              <div v-if="activeTab === 'script'" class="space-y-4">
                <!-- 剧本概述 -->
                <div v-if="workflowPreview?.script?.title" class="rounded-lg border border-white/10 bg-white/[0.03] p-4 space-y-3">
                  <h4 class="text-lg font-semibold text-white/90">{{ workflowPreview.script.title }}</h4>
                  <div v-if="workflowPreview.script.synopsis" class="text-sm text-white/60">
                    <span class="text-white/30 text-xs font-medium">故事梗概：</span>
                    {{ workflowPreview.script.synopsis }}
                  </div>
                  <div v-if="workflowPreview.script.genre" class="text-sm text-white/50">
                    <span class="text-white/30 text-xs font-medium">题材类型：</span>
                    {{ workflowPreview.script.genre }}
                  </div>
                </div>

                <!-- 角色列表 -->
                <div v-if="workflowPreview?.script?.characters?.length" class="rounded-lg border border-white/10 bg-white/[0.03] p-4 space-y-2">
                  <p class="text-xs font-medium text-white/50 mb-2">角色设计</p>
                  <div
                    v-for="(char, ci) in workflowPreview.script.characters"
                    :key="ci"
                    class="flex gap-3 py-2 border-b border-white/5 last:border-0"
                  >
                    <div class="flex-1 space-y-1">
                      <p class="text-sm font-medium text-white/80">{{ char.name }}</p>
                      <p v-if="char.appearance" class="text-xs text-white/50">{{ char.appearance }}</p>
                      <p v-if="char.personality" class="text-xs text-white/40 italic">{{ char.personality }}</p>
                    </div>
                  </div>
                </div>

                <!-- 场景列表 -->
                <div v-if="workflowPreview?.script?.locations?.length" class="rounded-lg border border-white/10 bg-white/[0.03] p-4 space-y-2">
                  <p class="text-xs font-medium text-white/50 mb-2">场景设计</p>
                  <div
                    v-for="(loc, li) in workflowPreview.script.locations"
                    :key="li"
                    class="py-2 border-b border-white/5 last:border-0 space-y-1"
                  >
                    <p class="text-sm font-medium text-white/80">{{ loc.name }}</p>
                    <p v-if="loc.description" class="text-xs text-white/50">{{ loc.description }}</p>
                  </div>
                </div>

                <!-- 分镜详情 -->
                <template v-if="previewScenes.length > 0">
                  <p class="text-xs font-medium text-white/50">分镜脚本</p>
                  <div
                    v-for="(scene, idx) in previewScenes"
                    :key="scene.index ?? idx"
                    class="rounded-lg border border-white/10 bg-white/[0.03] p-4 space-y-2"
                  >
                    <div class="flex items-center justify-between">
                      <p class="text-sm font-medium text-white/80">
                        S{{ scene.index ?? idx + 1 }}
                        <template v-if="scene.sceneTitle"> · {{ scene.sceneTitle }}</template>
                      </p>
                      <span class="text-[11px] text-white/30">{{ scene.durationSeconds ?? 5 }}s</span>
                    </div>
                    <div v-if="scene.characterScene" class="text-xs text-white/45">
                      <span class="text-white/30">角色/场景：</span>{{ scene.characterScene }}
                    </div>
                    <div v-if="scene.cameraLanguage" class="text-xs text-white/45">
                      <span class="text-white/30">镜头语言：</span>{{ scene.cameraLanguage }}
                    </div>
                    <div v-if="scene.plot" class="text-sm text-white/60">
                      <span class="text-white/30 text-xs">情节：</span>{{ scene.plot }}
                    </div>
                    <div v-if="scene.dialogue" class="text-sm">
                      <span class="text-white/30 text-xs">台词：</span>
                      <span class="text-white/70">{{ scene.dialogue }}</span>
                    </div>
                    <div v-if="scene.narration" class="text-sm">
                      <span class="text-white/30 text-xs">旁白：</span>
                      <span class="text-white/70">{{ scene.narration }}</span>
                    </div>
                    <div v-if="scene.voiceDirection" class="text-xs text-white/40 italic">
                      {{ scene.voiceDirection }}
                    </div>
                    <textarea
                      v-if="awaitingFeedback && (awaitingFieldKey === 'scriptFeedback' || awaitingFieldKey === 'storyboardFeedback')"
                      v-model="sceneFeedbackTexts[scene.index ?? idx + 1]"
                      rows="2"
                      class="w-full rounded-md border border-white/10 bg-white/5 px-3 py-2 text-sm text-white placeholder:text-white/25 focus:border-primary focus:outline-none"
                      :placeholder="`分镜 ${scene.index ?? idx + 1} 修改意见（可选）`"
                    />
                  </div>
                </template>
                <p v-else class="text-sm text-white/30 py-8 text-center">剧本脚本生成中，请稍候...</p>
              </div>

              <!-- Tab: 分镜脚本 -->
              <div v-else-if="activeTab === 'storyboard'" class="space-y-4">
                <div
                  v-if="previewScenes.length > 0"
                  v-for="(scene, idx) in previewScenes"
                  :key="scene.index ?? idx"
                  class="flex gap-4 rounded-lg border border-white/10 bg-white/[0.03] p-4"
                >
                  <div class="flex-shrink-0 w-40">
                    <img
                      v-if="sceneImageFor(scene.index ?? idx + 1)"
                      :src="normalizeMediaUrl(sceneImageFor(scene.index ?? idx + 1))"
                      class="w-full rounded-md border border-white/10 aspect-video object-cover"
                    />
                    <div
                      v-else
                      class="w-full aspect-video rounded-md border border-white/10 bg-white/5 flex items-center justify-center"
                    >
                      <span class="text-xs text-white/20">待生成</span>
                    </div>
                  </div>
                  <div class="flex-1 space-y-1.5">
                    <p class="text-sm font-medium text-white/80">
                      分镜 {{ scene.index ?? idx + 1 }}
                      <template v-if="scene.sceneTitle"> · {{ scene.sceneTitle }}</template>
                    </p>
                    <p v-if="scene.plot || scene.cameraLanguage" class="text-xs text-white/45 line-clamp-2">{{ scene.plot }}{{ scene.cameraLanguage ? ` · ${scene.cameraLanguage}` : '' }}</p>
                    <p v-if="scene.dialogue" class="text-xs text-white/60">{{ scene.dialogue }}</p>
                    <p class="text-[11px] text-white/25">
                      {{ scene.durationSeconds ?? 5 }}s · {{ scene.presenterGender ?? "自动" }}
                    </p>
                    <textarea
                      v-if="awaitingFeedback && awaitingFieldKey === 'storyboardFeedback'"
                      v-model="sceneFeedbackTexts[scene.index ?? idx + 1]"
                      rows="2"
                      class="w-full rounded-md border border-white/10 bg-white/5 px-3 py-2 text-xs text-white placeholder:text-white/25 focus:border-primary focus:outline-none"
                      :placeholder="`分镜 ${scene.index ?? idx + 1} 修改意见`"
                    />
                  </div>
                </div>
                <p v-else class="text-sm text-white/30 py-8 text-center">分镜脚本生成中...</p>
              </div>

              <!-- Tab: 场景关键帧 -->
              <div v-else-if="activeTab === 'scene'" class="space-y-4">
                <div
                  v-if="previewSceneImages.length > 0"
                  class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
                >
                  <figure
                    v-for="image in previewSceneImages"
                    :key="image.sceneIndex ?? image.imageUrl"
                    class="group relative overflow-hidden rounded-lg border border-white/10 bg-white/[0.03]"
                  >
                    <img
                      :src="normalizeMediaUrl(image.imageUrl)"
                      class="aspect-video w-full object-cover"
                    />
                    <div class="px-3 py-2 flex items-center justify-between">
                      <span class="text-xs text-white/50">场景 {{ image.sceneIndex }}</span>
                    </div>
                    <textarea
                      v-if="awaitingFeedback && awaitingFieldKey === 'sceneFeedback'"
                      v-model="sceneFeedbackTexts[image.sceneIndex ?? 0]"
                      rows="2"
                      class="w-full border-t border-white/8 bg-white/5 px-3 py-2 text-xs text-white placeholder:text-white/25 focus:outline-none"
                      :placeholder="`场景 ${image.sceneIndex} 修改意见`"
                    />
                  </figure>
                </div>
                <p v-else class="text-sm text-white/30 py-8 text-center">场景图片生成中...</p>
              </div>

              <!-- Tab: 分镜视频 -->
              <div v-else-if="activeTab === 'video'" class="space-y-4">
                <div v-if="hasVideo" class="space-y-4">
                  <div class="rounded-lg border border-white/10 bg-white/[0.03] p-4">
                    <p class="text-sm font-medium text-white/70 mb-3">成片视频</p>
                    <video
                      :src="finalVideoUrl"
                      controls
                      class="w-full rounded-md border border-white/10"
                    />
                  </div>
                </div>
                <!-- Per-scene audio as proxy for scene clips -->
                <div v-if="previewSceneAudios.length > 0" class="space-y-3">
                  <p class="text-xs text-white/40">分镜配音</p>
                  <div
                    v-for="audio in previewSceneAudios"
                    :key="audio.sceneIndex ?? audio.audioUrl"
                    class="flex items-start gap-3 rounded-lg border border-white/10 bg-white/[0.03] p-3"
                  >
                    <img
                      v-if="sceneImageFor(audio.sceneIndex ?? 0)"
                      :src="normalizeMediaUrl(sceneImageFor(audio.sceneIndex ?? 0))"
                      class="w-20 rounded-md border border-white/10 aspect-video object-cover flex-shrink-0"
                    />
                    <div class="flex-1 space-y-1.5">
                      <p class="text-xs text-white/60">
                        分镜 {{ audio.sceneIndex }}
                        <template v-if="audio.speechText"> · {{ audio.speechText }}</template>
                      </p>
                      <audio :src="normalizeMediaUrl(audio.audioUrl)" controls class="w-full h-8" />
                    </div>
                  </div>
                </div>
                <p
                  v-if="!hasVideo && previewSceneAudios.length === 0"
                  class="text-sm text-white/30 py-8 text-center"
                >
                  分镜视频生成中...
                </p>
              </div>
            </div>
          </div>

          <!-- Video Timeline Editor -->
          <div class="rounded-xl border border-white/10 bg-white/[0.02] overflow-hidden">
            <div class="flex items-center justify-between border-b border-white/8 px-5 py-3">
              <div class="flex items-center gap-3">
                <h3 class="text-sm font-medium text-white/70">视频时间线</h3>
                <span class="text-[11px] text-white/30">
                  {{ timelineClipOrder.length }} 个片段 · 约 {{ totalTimelineDuration }}s
                </span>
              </div>
              <div class="flex items-center gap-2">
                <button
                  class="inline-flex items-center gap-1.5 rounded-md border border-white/10 bg-white/5 px-3 py-1.5 text-xs text-white/60 hover:bg-white/10 transition disabled:opacity-30"
                  :disabled="!hasVideo && previewSceneImages.length === 0"
                  @click="togglePlayback"
                >
                  <component :is="isPlaying ? Pause : Play" class="h-3 w-3" />
                  {{ isPlaying ? "暂停" : "预览" }}
                </button>
                <button
                  class="inline-flex items-center gap-1.5 rounded-md bg-primary/80 px-3 py-1.5 text-xs font-medium text-white hover:bg-primary transition disabled:opacity-30"
                  :disabled="!finalVideoUrl"
                  @click="handleExport"
                >
                  <Download class="h-3 w-3" />
                  导出
                </button>
              </div>
            </div>

            <!-- Time scale -->
            <div class="relative px-5 pt-3">
              <div class="flex items-end text-[10px] text-white/25 mb-1">
                <span
                  v-for="i in Math.max(timelineClipOrder.length, 1)"
                  :key="i"
                  class="flex-1 text-center"
                >
                  {{ (i - 1) * CLIP_DURATION_SECONDS }}s
                </span>
                <span class="flex-shrink-0 w-8 text-right">{{ totalTimelineDuration }}s</span>
              </div>
            </div>

            <!-- Timeline track -->
            <div class="relative px-5 pb-5">
              <!-- Playhead -->
              <div
                v-if="isPlaying"
                class="absolute top-0 z-20 w-0.5 bg-red-500 transition-all pointer-events-none"
                :style="{ left: `calc(1.25rem + ${playheadPosition}% * (100% - 2.5rem) / 100)`, height: '100%' }"
              >
                <div class="absolute -top-1 -left-1 h-2.5 w-2.5 rounded-full bg-red-500" />
              </div>

              <!-- Video track -->
              <div class="flex gap-1.5 min-h-[80px]">
                <div
                  v-if="timelineClipOrder.length === 0"
                  class="flex-1 rounded-md border border-dashed border-white/10 bg-white/[0.02] flex items-center justify-center"
                >
                  <span class="text-xs text-white/20">等待视频片段生成...</span>
                </div>
                <div
                  v-for="(sceneIdx, arrayIdx) in timelineClipOrder"
                  :key="`clip-${sceneIdx}-${arrayIdx}`"
                  class="flex-1 min-w-[80px] rounded-md border overflow-hidden cursor-grab active:cursor-grabbing transition-all duration-200"
                  :class="[
                    dragOverIndex === arrayIdx ? 'border-primary ring-1 ring-primary/30' : 'border-white/12',
                    currentPlayingClip === arrayIdx && isPlaying ? 'ring-2 ring-primary/50 border-primary/40' : '',
                    'bg-white/[0.04] hover:bg-white/[0.06]',
                  ]"
                  draggable="true"
                  @dragstart="onDragStart(arrayIdx, $event)"
                  @dragover="onDragOver(arrayIdx, $event)"
                  @dragleave="onDragLeave"
                  @drop="onDrop(arrayIdx)"
                  @dragend="onDragEnd"
                  @click="seekToClip(arrayIdx)"
                >
                  <div class="relative aspect-video">
                    <img
                      v-if="sceneImageFor(sceneIdx)"
                      :src="normalizeMediaUrl(sceneImageFor(sceneIdx))"
                      class="h-full w-full object-cover"
                    />
                    <div v-else class="h-full w-full bg-gradient-to-br from-primary/20 to-fuchsia-500/20 flex items-center justify-center">
                      <span class="text-xs text-white/30">{{ sceneIdx }}</span>
                    </div>
                    <div class="absolute top-1 left-1 flex items-center gap-1">
                      <GripVertical class="h-3 w-3 text-white/40" />
                      <span class="rounded bg-black/60 px-1 py-0.5 text-[10px] text-white/70">{{ sceneIdx }}</span>
                    </div>
                    <span class="absolute bottom-1 right-1 rounded bg-black/60 px-1 py-0.5 text-[10px] text-white/50">
                      {{ CLIP_DURATION_SECONDS }}s
                    </span>
                  </div>
                </div>
              </div>

              <!-- Audio track (simplified) -->
              <div v-if="previewSceneAudios.length > 0" class="mt-1.5 flex gap-1.5">
                <div
                  v-for="(sceneIdx, arrayIdx) in timelineClipOrder"
                  :key="`audio-${sceneIdx}`"
                  class="flex-1 min-w-[80px] h-6 rounded-sm flex items-center justify-center"
                  :class="sceneAudioFor(sceneIdx) ? 'bg-emerald-500/15 border border-emerald-500/20' : 'bg-white/[0.02] border border-white/5'"
                >
                  <span class="text-[9px]" :class="sceneAudioFor(sceneIdx) ? 'text-emerald-400/60' : 'text-white/15'">
                    {{ sceneAudioFor(sceneIdx) ? '♪ 配音' : '—' }}
                  </span>
                </div>
              </div>
            </div>
          </div>

          <!-- Success action -->
          <div v-if="statusData.status === 'SUCCESS' && finalVideoUrl" class="flex justify-center pb-6">
            <button
              class="inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-primary to-fuchsia-600 px-8 py-3 text-sm font-semibold text-white shadow-lg hover:shadow-primary/25 transition"
              @click="handleExport"
            >
              <Download class="h-4 w-4" />
              导出成片视频
            </button>
          </div>
        </div>
      </template>
    </div>
  </AppShell>
</template>
