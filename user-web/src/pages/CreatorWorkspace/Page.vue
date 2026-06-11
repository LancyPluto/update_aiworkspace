<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue"
import { useRoute, useRouter } from "vue-router"
import { AlertCircle, CheckCircle2, Clock3, FileUp, Loader2, RefreshCw, Sparkles, Trash2, XCircle } from "lucide-vue-next"
import WorkspaceShell from "@/components/workspace/WorkspaceShell.vue"
import WorkspaceComposer from "@/components/workspace/WorkspaceComposer.vue"
import ResultRenderer from "@/components/ResultRenderer/ResultRenderer.vue"
import AssetPreviewModal from "@/components/AssetPreviewModal.vue"
import { ApiBusinessError } from "@/api/client"
import { publishCommunityPost, unpublishCommunityPost } from "@/api/communityApi"
import { createTask, deleteTask, fetchTasks, regenerateTask, streamTaskStatus } from "@/api/taskApi"
import { fetchToolByCode, fetchTools } from "@/api/toolApi"
import type { CreateTaskResponse, TaskDetail, TaskStatus, TaskStatusPayload, ToolDetail, ToolSummary } from "@/api/types"
import {
  creatorModeForTool,
  resolveCreatorTask,
  selectDefaultTool,
  type CreatorMode,
  type ComposerState,
} from "@/adapters/creatorAdapter"
import { useAuthStore } from "@/store/authStore"
import { confirmDelete } from "@/composables/useConfirmDelete"
import type { AssetPreviewItem, AssetPreviewRecommendation } from "@/types/assetPreview"
import { consumeCreatePendingAsset, openCreateWithAsset } from "@/utils/assetReplay"
import { assetFromTask } from "@/utils/assetPreviewAdapter"
import {
  buildCreateTimelineItems,
  CREATE_TOOL_MODES,
  type CreateTimelineItem,
} from "@/utils/createTimeline"
import { randomUUID } from "@/utils/randomUUID"
import { buildTaskResultBlocks } from "@/utils/taskResultBlocks"
import { taskProgressMessage, taskStatusViewKind } from "@/utils/taskStatusLabels"
import { buildTaskProgressView } from "@/utils/taskProgressView"

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const tools = ref<ToolSummary[]>([])
const tasks = ref<TaskDetail[]>([])
const selectedMode = ref<CreatorMode>("video")
const selectedToolCode = ref("")
const selectedToolDetail = ref<ToolDetail | null>(null)
const advancedParams = ref<Record<string, unknown>>({})
const lastComposerState = ref<ComposerState | null>(null)
const pendingAssetReplay = ref<AssetPreviewItem | null>(null)
const initialPrompt = ref("")
const initialRatio = ref<string | null>(null)
const initialDurationSeconds = ref<number | null>(null)
const initialQuality = ref<string | null>(null)
const initialOutputCount = ref<number | null>(null)
const initialUploadedAssetUrl = ref<string | null>(null)
const initialUploadedAssetName = ref("")
const initialModelConfigId = ref<number | null>(null)
const initialModelLabel = ref("")
const composerResetKey = ref(0)
const pendingAutoSubmit = ref(false)
const autoSubmitConsumed = ref(false)
const loading = ref(false)
const tasksLoading = ref(false)
const detailLoading = ref(false)
const submitting = ref(false)
const regeneratingTaskIds = ref<Set<number>>(new Set())
const deletingTaskIds = ref<Set<number>>(new Set())
const error = ref("")
const notice = ref("")
const ACTIVE_TASK_STATUSES = new Set<TaskStatus>(["CREATED", "QUEUED", "PROCESSING", "RETRYING"])
const TERMINAL_TASK_STATUSES = new Set<TaskStatus>(["SUCCESS", "FAILED", "TIMEOUT", "CANCELLED"])
const timelinePollTimer = ref<ReturnType<typeof window.setInterval> | null>(null)
const progressClockTimer = ref<ReturnType<typeof window.setInterval> | null>(null)
const progressNow = ref(Date.now())
const composerCondensed = ref(false)
const bottomSentinelRef = ref<HTMLElement | null>(null)
const timelineScrollTimers: Array<ReturnType<typeof window.setTimeout>> = []
const taskStatusStreamControllers = new Map<number, AbortController>()
const previewAsset = ref<AssetPreviewItem | null>(null)

const createModeOptions = computed(() =>
  CREATE_TOOL_MODES.map((mode) => ({
    key: mode.key === "digitalHuman" ? "digitalHuman" : mode.key,
    label: mode.label,
  })) as Array<{ key: CreatorMode; label: string }>,
)

const emptyComposerState = computed<ComposerState>(() => ({
  mode: selectedMode.value,
  prompt: "",
}))

const selectedSummary = computed(() => tools.value.find((tool) => tool.toolCode === selectedToolCode.value) || null)
const resolvedPreview = computed(() =>
  selectedToolDetail.value
    ? resolveCreatorTask(lastComposerState.value || emptyComposerState.value, selectedToolDetail.value, advancedParams.value)
    : null,
)
const costLabel = computed(() => {
  const cost = selectedSummary.value?.estimatedCreditCost
  return cost ? `生成 ${cost}` : "生成"
})
const timelineItems = computed(() => buildCreateTimelineItems(tasks.value, tools.value))
const hasActiveTimelineTasks = computed(() => tasks.value.some((task) => ACTIVE_TASK_STATUSES.has(task.status)))
const previewRecommendations = computed<AssetPreviewRecommendation[]>(() =>
  previewAsset.value ? recommendToolsForAsset(previewAsset.value) : [],
)

function routeStringParam(name: string): string | undefined {
  const value = route.query[name]
  if (typeof value === "string") return value
  if (Array.isArray(value)) return typeof value[0] === "string" ? value[0] : undefined
  return undefined
}

function routeNumberParam(name: string): number | null {
  const value = routeStringParam(name)
  if (!value) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function sourcePostIdFromRoute(): number | undefined {
  const value = routeStringParam("sourcePost")
  if (!value) return undefined
  const id = Number(value)
  return Number.isFinite(id) ? id : undefined
}

function modeFromRoute(): CreatorMode | null {
  const modality = routeStringParam("modality")?.toLowerCase()
  if (!modality) return null
  if (modality.includes("image")) return "image"
  if (modality.includes("audio")) return "audio"
  if (modality.includes("digital")) return "digitalHuman"
  if (modality.includes("video")) return "video"
  if (modality.includes("agent")) return "agent"
  return null
}

function assetFieldKind(field: { fieldType: string; fieldKey: string; fieldName: string; placeholder?: string | null }) {
  if (field.fieldType === "image" || field.fieldType === "image_upload") return "image"
  if (field.fieldType === "video_upload") return "video"
  if (field.fieldType === "audio_upload") return "audio"
  const text = `${field.fieldKey} ${field.fieldName} ${field.placeholder || ""}`.toLowerCase()
  if (/image|img|picture|photo|frame|cover|avatar|poster|图片|图像|照片|封面|首图/.test(text)) return "image"
  if (/video|clip|movie|视频|短片|影片/.test(text)) return "video"
  if (/audio|voice|sound|speech|music|音频|语音|声音|音乐/.test(text)) return "audio"
  return "file"
}

function assetReplayParams(fields: ToolDetail["fields"], asset: AssetPreviewItem): Record<string, unknown> {
  const params: Record<string, unknown> = {
    sourceAssetUrl: asset.url,
    sourceTaskId: asset.taskId,
    sourceTaskNo: asset.taskNo,
  }
  if (!asset.url) return params
  const mediaFields = fields.filter((field) =>
    field.fieldType === "image" ||
    field.fieldType === "file" ||
    field.fieldType === "image_upload" ||
    field.fieldType === "video_upload" ||
    field.fieldType === "audio_upload"
  )
  const target =
    mediaFields.find((field) => assetFieldKind(field) === asset.kind) ||
    mediaFields.find((field) => assetFieldKind(field) === "file") ||
    mediaFields[0]
  if (target) params[target.fieldKey] = asset.url
  return params
}

function consumePendingAssetReplay() {
  const asset = consumeCreatePendingAsset()
  if (!asset) return
  pendingAssetReplay.value = asset
  initialPrompt.value = asset.prompt || asset.rawText || ""
  initialUploadedAssetUrl.value = asset.url || null
  initialUploadedAssetName.value = asset.title || asset.taskNo || asset.toolName || ""
}

function consumeInitialComposerRoute() {
  initialPrompt.value = routeStringParam("prompt") || initialPrompt.value
  initialRatio.value = routeStringParam("ratio") || null
  initialDurationSeconds.value = routeNumberParam("duration")
  initialQuality.value = routeStringParam("quality") || null
  initialOutputCount.value = routeNumberParam("count")
  initialModelConfigId.value = routeNumberParam("modelConfigId")
  initialModelLabel.value = routeStringParam("modelLabel") || ""
  pendingAutoSubmit.value = routeStringParam("autoSubmit") === "1"
  initialUploadedAssetUrl.value = routeStringParam("asset") || initialUploadedAssetUrl.value
  if (initialUploadedAssetUrl.value && !initialUploadedAssetName.value) {
    initialUploadedAssetName.value = "首页上传素材"
  }
}

function routeComposerState(): ComposerState {
  const summary = selectedSummary.value
  const ratioValue = initialRatio.value || undefined
  return {
    mode: selectedMode.value,
    prompt: initialPrompt.value,
    generationType: selectedMode.value === "image" ? "文本/图像生成图片" : "文本/图像生成视频",
    modelLabel: initialModelLabel.value || summary?.modelConfigName || summary?.modelName || undefined,
    toolCode: selectedToolCode.value || undefined,
    modelConfigId: initialModelConfigId.value,
    ratio: ratioValue,
    durationSeconds: selectedMode.value === "image" ? undefined : initialDurationSeconds.value ?? undefined,
    quality: initialQuality.value || undefined,
    outputCount: selectedMode.value === "image" ? initialOutputCount.value ?? undefined : undefined,
    uploadedAssetUrl: initialUploadedAssetUrl.value,
    uploadedAssetUrls: initialUploadedAssetUrl.value ? [initialUploadedAssetUrl.value] : [],
  }
}

function routeQueryWithout(omittedKeys: string[] = []): Record<string, string | string[]> {
  const omitted = new Set(omittedKeys)
  const query: Record<string, string | string[]> = {}
  for (const [key, value] of Object.entries(route.query)) {
    if (omitted.has(key)) continue
    if (typeof value === "string") {
      query[key] = value
    } else if (Array.isArray(value)) {
      query[key] = value.filter((item): item is string => typeof item === "string")
    }
  }
  return query
}

function replaceCreateRouteQuery(query: Record<string, string | string[]>) {
  void router.replace({ path: route.path, query })
}

function rememberComposerSelection(mode: CreatorMode, toolCode?: string | null) {
  const query = routeQueryWithout(["autoSubmit"])
  query.modality = mode
  if (toolCode) query.tool = toolCode
  else delete query.tool
  replaceCreateRouteQuery(query)
}

function rememberComposerStateSelection(state: ComposerState) {
  selectedMode.value = state.mode
  if (state.toolCode) selectedToolCode.value = state.toolCode
  rememberComposerSelection(state.mode, state.toolCode || selectedToolCode.value)
}

function clearAutoSubmitRouteFlag() {
  replaceCreateRouteQuery(routeQueryWithout(["autoSubmit"]))
}

function maybeAutoSubmitFromRoute() {
  if (!pendingAutoSubmit.value || autoSubmitConsumed.value) return
  if (loading.value || detailLoading.value || submitting.value || !selectedToolDetail.value) return
  if (!initialPrompt.value.trim()) return
  autoSubmitConsumed.value = true
  pendingAutoSubmit.value = false
  clearAutoSubmitRouteFlag()
  void submitFromComposer(routeComposerState())
}

async function loadTools() {
  loading.value = true
  error.value = ""
  try {
    const page = await fetchTools({ token: auth.token, query: { pageNo: 1, pageSize: 160 } })
    tools.value = page.list
    const routeMode = modeFromRoute()
    if (routeMode) {
      selectedMode.value = routeMode
    }
    const routeTool = routeStringParam("tool")
    let defaultTool = routeTool ? page.list.find((tool) => tool.toolCode === routeTool) : undefined
    if (routeMode && (!defaultTool || creatorModeForTool(defaultTool) !== routeMode)) {
      defaultTool = selectDefaultTool(page.list, routeMode) || undefined
    }
    if (!defaultTool) {
      defaultTool = selectDefaultTool(page.list, selectedMode.value) || undefined
    }
    if (defaultTool) {
      selectedMode.value = routeMode || creatorModeForTool(defaultTool)
    }
    selectedToolCode.value = defaultTool?.toolCode || ""
  } catch (e) {
    error.value = (e as Error).message || "加载创作工具失败"
  } finally {
    loading.value = false
  }
}

function upsertTask(task: TaskDetail) {
  const nextTask = mergeTaskModelDisplay([task])[0] || task
  const index = tasks.value.findIndex((item) => item.taskId === task.taskId)
  if (index >= 0) {
    tasks.value = [
      ...tasks.value.slice(0, index),
      nextTask,
      ...tasks.value.slice(index + 1),
    ]
    return
  }
  tasks.value = [...tasks.value, nextTask]
}

function normalizedTaskProgress(value?: number | null): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) return null
  return Math.max(0, Math.min(100, Math.round(value)))
}

function monotonicTaskProgress(existing: TaskDetail | undefined, incomingProgress?: number | null) {
  const previous = normalizedTaskProgress(existing?.progress)
  const incoming = normalizedTaskProgress(incomingProgress)
  if (incoming == null) return previous
  if (previous == null) return incoming
  return Math.max(previous, incoming)
}

function mergeTaskModelDisplay(incoming: TaskDetail[]) {
  const existingById = new Map(tasks.value.map((task) => [task.taskId, task]))
  return incoming.map((task) => {
    const existing = existingById.get(task.taskId)
    if (task.modelConfigName || task.modelName || !existing) {
      return {
        ...task,
        progress: monotonicTaskProgress(existing, task.progress),
      }
    }
    return {
      ...task,
      progress: monotonicTaskProgress(existing, task.progress),
      modelConfigId: task.modelConfigId ?? existing?.modelConfigId ?? null,
      modelConfigName: task.modelConfigName ?? existing?.modelConfigName ?? null,
      modelName: task.modelName ?? existing?.modelName ?? null,
    }
  })
}

function applyTaskStatusUpdate(payload: TaskStatusPayload) {
  const index = tasks.value.findIndex((task) => task.taskId === payload.taskId)
  if (index < 0) return
  const existing = tasks.value[index]
  tasks.value = [
    ...tasks.value.slice(0, index),
    {
      ...existing,
      status: payload.status,
      progress: monotonicTaskProgress(existing, payload.progress),
      progressMessage: payload.progressMessage ?? existing.progressMessage,
    },
    ...tasks.value.slice(index + 1),
  ]
  if (TERMINAL_TASK_STATUSES.has(payload.status)) {
    stopTaskStatusStream(payload.taskId)
    void refreshTimeline({ showLoading: false })
  }
}

function stopTaskStatusStream(taskId: number) {
  const controller = taskStatusStreamControllers.get(taskId)
  if (!controller) return
  controller.abort()
  taskStatusStreamControllers.delete(taskId)
}

function stopAllTaskStatusStreams() {
  for (const taskId of taskStatusStreamControllers.keys()) {
    stopTaskStatusStream(taskId)
  }
}

function subscribeActiveTaskStatus(task: TaskDetail) {
  if (!auth.isLoggedIn || !ACTIVE_TASK_STATUSES.has(task.status) || taskStatusStreamControllers.has(task.taskId)) return
  const controller = new AbortController()
  taskStatusStreamControllers.set(task.taskId, controller)
  void streamTaskStatus(task.taskId, applyTaskStatusUpdate, {
    token: auth.token,
    signal: controller.signal,
  }).catch((streamError) => {
    if (!controller.signal.aborted) {
      console.debug("task progress stream closed", task.taskId, streamError)
    }
  }).finally(() => {
    if (taskStatusStreamControllers.get(task.taskId) === controller) {
      taskStatusStreamControllers.delete(task.taskId)
    }
  })
}

function syncTaskStatusStreams() {
  const visibleTaskIds = new Set(tasks.value.map((task) => task.taskId))
  for (const [taskId, controller] of taskStatusStreamControllers.entries()) {
    const task = tasks.value.find((item) => item.taskId === taskId)
    if (!visibleTaskIds.has(taskId) || !task || !ACTIVE_TASK_STATUSES.has(task.status)) {
      controller.abort()
      taskStatusStreamControllers.delete(taskId)
    }
  }
  for (const task of tasks.value) {
    subscribeActiveTaskStatus(task)
  }
}

function clearComposerDraftAfterSubmit() {
  initialPrompt.value = ""
  initialUploadedAssetUrl.value = null
  initialUploadedAssetName.value = ""
  lastComposerState.value = null
  pendingAssetReplay.value = null
  advancedParams.value = {}
  composerResetKey.value += 1
}

function scrollTimelineToBottom(behavior: ScrollBehavior = "smooth") {
  requestAnimationFrame(() => {
    window.scrollTo({ top: document.documentElement.scrollHeight, behavior })
  })
}

function settleTimelineToBottom(behavior: ScrollBehavior = "auto") {
  scrollTimelineToBottom(behavior)
  for (const delay of [220, 900]) {
    timelineScrollTimers.push(window.setTimeout(() => {
      scrollTimelineToBottom("auto")
      updateComposerCondensedFromScroll()
    }, delay))
  }
}

function updateComposerCondensedFromScroll() {
  const bottomDistance = document.documentElement.scrollHeight - window.scrollY - window.innerHeight
  composerCondensed.value = bottomDistance > 180
}

function expandComposerFromClick(event: MouseEvent) {
  if (!composerCondensed.value) return
  const target = event.target
  if (target instanceof HTMLElement && target.closest("textarea")) {
    composerCondensed.value = false
  }
}

function startTimelinePolling() {
  if (timelinePollTimer.value) return
  timelinePollTimer.value = window.setInterval(() => void refreshTimeline({ showLoading: false }), 2500)
}

function stopTimelinePolling() {
  if (!timelinePollTimer.value) return
  window.clearInterval(timelinePollTimer.value)
  timelinePollTimer.value = null
}

function startProgressClock() {
  if (progressClockTimer.value) return
  progressNow.value = Date.now()
  progressClockTimer.value = window.setInterval(() => {
    progressNow.value = Date.now()
  }, 1000)
}

function stopProgressClock() {
  if (!progressClockTimer.value) return
  window.clearInterval(progressClockTimer.value)
  progressClockTimer.value = null
}

async function refreshTimeline(options: { showLoading?: boolean } = {}) {
  if (!auth.isLoggedIn) return
  const showLoading = options.showLoading ?? true
  if (showLoading) tasksLoading.value = true
  try {
    const response = await fetchTasks({ token: auth.token, query: { pageNo: 1, pageSize: 30 } })
    tasks.value = mergeTaskModelDisplay(response.list)
    if (showLoading || !composerCondensed.value) {
      scrollTimelineToBottom("auto")
    }
  } catch (e) {
    error.value = (e as Error).message || "加载生成记录失败"
  } finally {
    if (showLoading) tasksLoading.value = false
  }
}

function refreshTimelineFromClick() {
  void refreshTimeline()
}

async function loadSelectedToolDetail() {
  if (!selectedToolCode.value) {
    selectedToolDetail.value = null
    return
  }
  detailLoading.value = true
  error.value = ""
  try {
    selectedToolDetail.value = await fetchToolByCode(selectedToolCode.value, { token: auth.token })
    advancedParams.value = pendingAssetReplay.value ? assetReplayParams(selectedToolDetail.value.fields || [], pendingAssetReplay.value) : {}
  } catch (e) {
    selectedToolDetail.value = null
    error.value = (e as Error).message || "读取工具字段失败"
  } finally {
    detailLoading.value = false
  }
}

function onModeChange(mode: CreatorMode) {
  selectedMode.value = mode
  const toolCode = selectDefaultTool(tools.value, mode)?.toolCode || ""
  selectedToolCode.value = toolCode
  rememberComposerSelection(mode, toolCode)
}

function onToolSelect(toolCode: string) {
  selectedToolCode.value = toolCode
  const tool = tools.value.find((item) => item.toolCode === toolCode)
  if (tool) selectedMode.value = creatorModeForTool(tool)
  rememberComposerSelection(selectedMode.value, toolCode)
}

function resultBlocks(item: CreateTimelineItem) {
  return item.task.result?.contentText ? buildTaskResultBlocks(item.task.result.contentText, item.task) : []
}

function progressView(item: CreateTimelineItem) {
  return buildTaskProgressView(item.task, progressNow.value)
}

function openTaskPreview(task: TaskDetail) {
  const blocks = task.result?.contentText ? buildTaskResultBlocks(task.result.contentText, task) : []
  previewAsset.value = assetFromTask(task, {
    blocks,
    idPrefix: "create",
    source: "private",
    modality: task.outputModality || task.result?.resourceType || "TEXT",
  })
}

function normalizeModality(value?: string | null) {
  return (value || "TEXT").trim().toUpperCase()
}

function recommendToolsForAsset(asset: AssetPreviewItem): AssetPreviewRecommendation[] {
  const target = asset.kind === "image" ? "IMAGE" : asset.kind === "video" ? "VIDEO" : asset.kind === "audio" ? "AUDIO" : ""
  const keyword = asset.kind === "image" ? /图|图片|影像|photo|image|img|改图|参考/i : asset.kind === "video" ? /视频|短片|video|clip|movie/i : /音频|音乐|audio|voice|tts/i
  const matches = tools.value.filter((tool) => {
    const input = normalizeModality(tool.inputModality)
    const text = `${tool.toolName} ${tool.description || ""} ${tool.configNote || ""} ${tool.toolCode}`
    return (
      (target && (input.includes(target) || input.includes("MULTIMODAL") || input.includes("FILE"))) ||
      keyword.test(text)
    )
  })
  return (matches.length ? matches : tools.value).slice(0, 8)
}

function usePreviewAssetWithTool(tool: AssetPreviewRecommendation, asset: AssetPreviewItem) {
  openCreateWithAsset(asset, tool)
  previewAsset.value = null
}

function updateTaskCommunityState(taskId: number, communityPostId?: number, promptVisible?: boolean) {
  tasks.value = tasks.value.map((task) =>
    task.taskId === taskId
      ? {
          ...task,
          communityPostId: communityPostId ?? null,
          communityPromptVisible: communityPostId != null ? Boolean(promptVisible) : null,
        }
      : task,
  )
}

async function publishPreviewAsset(asset: AssetPreviewItem) {
  if (!auth.token || !asset.taskId) return
  try {
    const post = await publishCommunityPost(
      {
        taskId: asset.taskId,
        title: asset.title,
        description: asset.subtitle || null,
        promptVisible: asset.promptVisible ?? false,
      },
      { token: auth.token },
    )
    updateTaskCommunityState(asset.taskId, post.id, post.promptVisible)
    previewAsset.value = { ...asset, communityPostId: post.id, promptVisible: post.promptVisible }
  } catch (e) {
    error.value = (e as Error).message || "发布失败"
  }
}

async function unpublishPreviewAsset(asset: AssetPreviewItem) {
  if (!auth.token || !asset.communityPostId) return
  try {
    await unpublishCommunityPost(asset.communityPostId, { token: auth.token })
    if (asset.taskId) updateTaskCommunityState(asset.taskId)
    previewAsset.value = { ...asset, communityPostId: undefined }
  } catch (e) {
    error.value = (e as Error).message || "撤回失败"
  }
}

function statusIcon(item: CreateTimelineItem) {
  const kind = taskStatusViewKind(item.task.status)
  if (kind === "success") return CheckCircle2
  if (kind === "failed") return XCircle
  if (kind === "running" || kind === "queued") return Sparkles
  return Clock3
}

function statusLabel(item: CreateTimelineItem) {
  const kind = taskStatusViewKind(item.task.status)
  if (kind === "success") return "已完成"
  if (kind === "failed") return item.task.errorCode || "生成失败"
  if (kind === "queued") return "排队中"
  if (kind === "running") return taskProgressMessage(item.task.status, item.task.progressMessage)
  return "已取消"
}

function optimisticInitialProgress(status: TaskStatus, state: ComposerState) {
  if (status !== "PROCESSING") return 0
  return state.mode === "image" ? 1 : 20
}

function optimisticTaskFromResponse(response: CreateTaskResponse, params: Record<string, unknown>, state: ComposerState): TaskDetail {
  const tool = selectedSummary.value
  const detail = selectedToolDetail.value
  const now = new Date().toISOString()
  return {
    taskId: response.taskId,
    taskNo: response.taskNo,
    status: response.status,
    progress: optimisticInitialProgress(response.status, state),
    progressMessage: response.status === "PROCESSING"
      ? state.mode === "image" ? "图片生成任务已开始" : "任务处理中"
      : "任务已提交，等待调度",
    userId: auth.user?.id ?? 0,
    toolCode: resolvedPreview.value?.toolCode || detail?.toolCode || tool?.toolCode || "",
    toolName: detail?.toolName || tool?.toolName || "AI 工具",
    modelConfigId: state.modelConfigId ?? null,
    modelConfigName: state.modelLabel || null,
    modelName: state.modelLabel || null,
    toolType: detail?.toolType || tool?.toolType || undefined,
    inputModality: detail?.inputModality || tool?.inputModality || undefined,
    outputModality: detail?.outputModality || tool?.outputModality || undefined,
    params,
    result: null,
    createdAt: now,
    queuedAt: now,
    startedAt: response.status === "PROCESSING" ? now : null,
    finishedAt: null,
  }
}

async function handleRegenerate(task: TaskDetail) {
  if (regeneratingTaskIds.value.has(task.taskId)) return
  regeneratingTaskIds.value = new Set([...regeneratingTaskIds.value, task.taskId])
  try {
    await regenerateTask(
      task.taskId,
      {
        params: { ...(task.params || {}) },
        clientRequestId: `${task.taskNo || task.taskId}-retry-${Date.now()}`,
      },
      { token: auth.token },
    )
    await refreshTimeline()
  } catch (e) {
    error.value = (e as Error).message || "重新生成失败"
  } finally {
    const next = new Set(regeneratingTaskIds.value)
    next.delete(task.taskId)
    regeneratingTaskIds.value = next
  }
}

async function handleDelete(task: TaskDetail) {
  if (deletingTaskIds.value.has(task.taskId)) return
  const confirmed = await confirmDelete({
    title: "删除生成记录",
    description: `确定删除「${itemPromptPreview(task)}」这条生成记录吗？删除后生成页和资产库中将不再显示。`,
  })
  if (!confirmed) return

  deletingTaskIds.value = new Set([...deletingTaskIds.value, task.taskId])
  error.value = ""
  try {
    await deleteTask(task.taskId, { token: auth.token })
    tasks.value = tasks.value.filter((item) => item.taskId !== task.taskId)
    notice.value = "生成记录已删除"
  } catch (e) {
    error.value = (e as Error).message || "删除生成记录失败"
  } finally {
    const next = new Set(deletingTaskIds.value)
    next.delete(task.taskId)
    deletingTaskIds.value = next
  }
}

function itemPromptPreview(task: TaskDetail): string {
  const prompt = task.params?.prompt
  if (typeof prompt === "string" && prompt.trim()) return prompt.trim().slice(0, 24)
  return task.toolName || task.taskNo || "这条记录"
}

async function submitFromComposer(state: ComposerState) {
  lastComposerState.value = state
  notice.value = ""
  error.value = ""
  rememberComposerStateSelection(state)

  if (!auth.isLoggedIn) {
    await router.push({ name: "RootLogin", query: { redirect: "/create" } })
    return
  }

  if (!selectedToolDetail.value) {
    error.value = "请选择一个可用工具"
    return
  }

  const resolved = resolveCreatorTask(state, selectedToolDetail.value, advancedParams.value)
  if (resolved.missingRequiredFields.length > 0) {
    error.value = `请完善高级参数：${resolved.missingRequiredFields.join(", ")}`
    return
  }

  submitting.value = true
  try {
    const created = await createTask(
      {
        toolCode: resolved.toolCode,
        params: resolved.params,
        modelConfigId: state.modelConfigId ?? undefined,
        clientRequestId: randomUUID(),
        sourcePostId: sourcePostIdFromRoute(),
      },
      { token: auth.token },
    )
    upsertTask(optimisticTaskFromResponse(created, resolved.params, state))
    clearComposerDraftAfterSubmit()
    notice.value = "任务已开始"
    startTimelinePolling()
    settleTimelineToBottom("smooth")
    await refreshTimeline({ showLoading: false })
  } catch (e) {
    if (e instanceof ApiBusinessError && (e.code === "CREDIT_NOT_ENOUGH" || e.code === "AGENT_CREDIT_NOT_ENOUGH")) {
      error.value = "算力不足，请先进入会员与算力页面充值。"
      return
    }
    error.value = (e as Error).message || "创建任务失败"
  } finally {
    submitting.value = false
  }
}

watch(selectedToolCode, loadSelectedToolDetail)
watch(() => [selectedToolDetail.value, detailLoading.value, loading.value] as const, () => {
  maybeAutoSubmitFromRoute()
})
watch(hasActiveTimelineTasks, (active) => {
  if (active) {
    startTimelinePolling()
    startProgressClock()
  } else {
    stopTimelinePolling()
    stopProgressClock()
    stopAllTaskStatusStreams()
  }
})
watch(timelineItems, () => {
  syncTaskStatusStreams()
  if (!composerCondensed.value) scrollTimelineToBottom("auto")
})

onMounted(async () => {
  consumePendingAssetReplay()
  consumeInitialComposerRoute()
  await loadTools()
  await refreshTimeline()
  window.addEventListener("scroll", updateComposerCondensedFromScroll, { passive: true })
  window.addEventListener("resize", updateComposerCondensedFromScroll)
  settleTimelineToBottom("auto")
})

onBeforeUnmount(() => {
  stopTimelinePolling()
  stopProgressClock()
  stopAllTaskStatusStreams()
  window.removeEventListener("scroll", updateComposerCondensedFromScroll)
  window.removeEventListener("resize", updateComposerCondensedFromScroll)
  for (const timer of timelineScrollTimers) window.clearTimeout(timer)
})
</script>

<template>
  <WorkspaceShell>
    <section class="workspace-create-feed-page">
      <div class="workspace-create-feed-head">
        <div>
          <p>生成</p>
          <h1>生成记录</h1>
        </div>
        <button type="button" :disabled="tasksLoading" @click="refreshTimelineFromClick">
          <RefreshCw :size="15" :class="{ 'animate-spin': tasksLoading }" />
          刷新
        </button>
      </div>

      <div v-if="notice" class="workspace-alert">{{ notice }}</div>
      <div v-if="error" class="workspace-alert">
        <AlertCircle :size="15" />
        {{ error }}
      </div>

      <div v-if="tasksLoading && timelineItems.length === 0" class="workspace-state">
        <Loader2 class="animate-spin" :size="18" />
        正在加载生成记录
      </div>
      <div v-else-if="timelineItems.length === 0" class="workspace-create-empty">
        <Sparkles :size="22" />
        <strong>还没有生成记录</strong>
        <span>在底部输入想法，选择模型后开始第一条生成。</span>
      </div>

      <div v-else class="workspace-create-feed">
        <article v-for="item in timelineItems" :key="item.task.taskId" class="workspace-create-card">
          <header class="workspace-create-card-head">
            <div class="workspace-create-brand">
              <span>P</span>
              <strong>{{ item.brandName }}</strong>
            </div>
            <i />
            <span class="workspace-create-chip">{{ item.typeLabel }}</span>
            <span class="workspace-create-chip">{{ item.modelLabel }}</span>
            <time>{{ item.startedAtLabel }}</time>
          </header>

          <div class="workspace-create-prompt">
            <div v-if="item.materials.length" class="workspace-create-materials">
              <figure v-for="material in item.materials" :key="material.key + material.url">
                <img v-if="material.kind === 'image'" :src="material.url" alt="" />
                <video v-else-if="material.kind === 'video'" :src="material.url" muted playsinline />
                <FileUp v-else :size="18" />
              </figure>
            </div>
            <p>{{ item.promptText }}</p>
          </div>

          <div class="workspace-create-result">
            <div
              v-if="resultBlocks(item).length"
              class="workspace-create-result-link"
              role="link"
              tabindex="0"
              aria-label="查看生成详情"
              @click="openTaskPreview(item.task)"
              @keydown.enter.prevent="openTaskPreview(item.task)"
              @keydown.space.prevent="openTaskPreview(item.task)"
            >
              <ResultRenderer :blocks="resultBlocks(item)" mode="compact" />
            </div>
            <div v-else class="workspace-create-status workspace-create-status-hero" :class="taskStatusViewKind(item.task.status)">
              <span class="workspace-create-status-mark">
                <component :is="statusIcon(item)" :size="28" />
              </span>
              <strong>{{ progressView(item).percentLabel }}</strong>
              <span class="workspace-create-progress-caption">{{ progressView(item).caption || statusLabel(item) }}</span>
              <div class="workspace-create-progress">
                <b :style="{ width: progressView(item).percent + '%' }" />
              </div>
            </div>
          </div>

          <footer class="workspace-create-actions">
            <button type="button" @click="initialPrompt = item.promptText">重新输入提示词</button>
            <button type="button" :disabled="regeneratingTaskIds.has(item.task.taskId)" @click="handleRegenerate(item.task)">
              <Loader2 v-if="regeneratingTaskIds.has(item.task.taskId)" :size="14" class="animate-spin" />
              <RefreshCw v-else :size="14" />
              重新生成
            </button>
            <button
              type="button"
              class="workspace-create-delete"
              :disabled="deletingTaskIds.has(item.task.taskId)"
              aria-label="删除生成记录"
              title="删除生成记录"
              @click="handleDelete(item.task)"
            >
              <Loader2 v-if="deletingTaskIds.has(item.task.taskId)" :size="14" class="animate-spin" />
              <Trash2 v-else :size="14" />
              删除
            </button>
          </footer>
        </article>
        <div ref="bottomSentinelRef" class="workspace-create-bottom-sentinel" aria-hidden="true" />
      </div>

      <div class="workspace-create-composer-spacer" />
      <div class="workspace-create-composer-dock" :class="{ condensed: composerCondensed }" @click.capture="expandComposerFromClick">
        <WorkspaceComposer
          compact
          :show-mode-tabs="false"
          :mode-options="createModeOptions"
          :initial-mode="selectedMode"
          :submitting="submitting"
          :disabled="loading || detailLoading"
          :cost-label="costLabel"
          :tool-id="selectedToolCode"
          :tools="tools"
          :tool-detail="selectedToolDetail"
          :tools-loading="loading || detailLoading"
          :initial-prompt="initialPrompt"
          :initial-ratio="initialRatio"
          :initial-duration-seconds="initialDurationSeconds"
          :initial-quality="initialQuality"
          :initial-output-count="initialOutputCount"
          :initial-uploaded-asset-url="initialUploadedAssetUrl"
          :initial-uploaded-asset-name="initialUploadedAssetName"
          :initial-model-config-id="initialModelConfigId"
          :reset-key="composerResetKey"
          @mode-change="onModeChange"
          @tool-select="onToolSelect"
          @submit="submitFromComposer"
        />
          </div>
        </section>
        <AssetPreviewModal
          :asset="previewAsset"
          :recommendations="previewRecommendations"
          @close="previewAsset = null"
          @use-tool="usePreviewAssetWithTool"
          @publish="publishPreviewAsset"
          @unpublish="unpublishPreviewAsset"
        />
      </WorkspaceShell>
    </template>
