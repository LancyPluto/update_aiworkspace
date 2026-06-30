<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue"
import { RouterLink, useRoute, useRouter } from "vue-router"
import {
  ArrowRight,
  Bot,
  ChevronDown,
  Clock,
  Download,
  ExternalLink,
  FileText,
  Image as ImageIcon,
  LayoutGrid,
  Loader2,
  MessageSquareText,
  MoreHorizontal,
  Music,
  Pause,
  Play,
  Plus,
  Rows3,
  Search,
  Send,
  Sparkles,
  Store,
  Trash2,
  Video,
  Volume2,
  WandSparkles,
  Workflow,
  X,
  Zap,
} from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import AssetPreviewModal from "@/components/AssetPreviewModal.vue"
import ImageStackPreview from "@/components/ImageStackPreview.vue"
import CapabilityControls from "@/pages/Chat/CapabilityControls.vue"
import type { PrimaryReferenceMaterialInfo } from "@/pages/Chat/CapabilityControls.vue"
import DashboardModalityDock from "./DashboardModalityDock.vue"
import { fetchCreditAccount } from "@/api/creditApi"
import { ApiBusinessError } from "@/api/client"
import {
  cancelTask,
  createTask,
  deleteTask,
  fetchTaskById,
  fetchTasks,
  fetchTaskStatus,
  regenerateTask,
  streamTaskStatus,
} from "@/api/taskApi"
import { unpublishCommunityPost } from "@/api/communityApi"
import { emitCommunityPostUnpublished } from "@/utils/communitySync"
import { publishAssetToCommunity, type CommunityPublishPayload } from "@/utils/publishCommunityAsset"
import { fetchAIToolById, fetchTools } from "@/api/toolApi"
import type { AITool } from "@/api/aiToolTypes"
import type { CreditAccount, TaskDetail, TaskStatus, TaskStatusPayload, ToolField, ToolSummary } from "@/api/types"
import type { AssetPreviewItem, AssetPreviewRecommendation } from "@/types/assetPreview"
import type { AudioTrackItem, ResultBlock } from "@/types/result"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { buildTaskResultBlocks, formatAudioDuration, resolveAudioTracks } from "@/utils/taskResultBlocks"
import { isCoreField } from "@/utils/fieldUiMeta"
import { consumeDashboardPendingAsset } from "@/utils/assetReplay"
import { cleanToolDisplayText, toolDisplayDescription } from "@/utils/toolDisplayText"
import { formatLiveCreditEstimate, formatMarketplaceCostLabel, usesVariableWorkflowCredits } from "@/utils/toolCreditLabel"
import { useTaskEstimate, type UseTaskEstimateInput } from "@/composables/useTaskEstimate"
import { randomUUID } from "@/utils/randomUUID"
import {
  dashboardAttributionFromRoute,
  mergePendingAssetAttribution,
  type DashboardAttributionContext,
} from "./dashboardAttribution"
import { buildDashboardTaskParams, buildOptimisticDashboardTask } from "./dashboardTaskFactory"
import { normalizeMediaUrl } from "@/utils/toolCoverMedia"
import { resolveCommunityDerivativeUrl, resolveOssVideoPosterUrl } from "@/utils/communityPostMedia"
import { forceDownload } from "@/utils/download"
import { isWorkflowToolCode } from "@/adapters/toolPresentationAdapter"
import { taskFailureHint, taskProgressMessage } from "@/utils/taskStatusLabels"
import { buildTaskProgressView } from "@/utils/taskProgressView"

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const HISTORY_VIEW_KEY = "ai_tool_market_dashboard_history_view"
const DASHBOARD_FEATURED_PREVIEW_LIMIT = 12

const loading = ref(false)
const credit = ref<CreditAccount | null>(null)
const tools = ref<ToolSummary[]>([])
const tasks = ref<TaskDetail[]>([])
const taskPageNo = ref(1)
const taskHasNext = ref(false)
const tasksLoadingMore = ref(false)
const selectedModality = ref("IMAGE")
const selectedToolCode = ref<string | null>(null)
const promptText = ref("")
const modelPickerOpen = ref(false)
const modelSearch = ref("")
const selectedChatTool = ref<AITool | null>(null)
const selectedToolDetailLoading = ref(false)
const capabilityRef = ref<InstanceType<typeof CapabilityControls> | null>(null)
const capabilityParams = ref<Record<string, unknown>>({})
const primaryReferenceInfo = ref<PrimaryReferenceMaterialInfo>({
  available: false,
  fieldName: "",
  kind: "file",
  count: 0,
  maxCount: 1,
  previewUrls: [],
  uploading: false,
})
const composerRootRef = ref<HTMLElement | null>(null)
const composerOpen = ref(false)
const composerManuallyClosed = ref(false)
const replayParams = ref<Record<string, unknown> | null>(null)
const submitting = ref(false)
const submitError = ref("")
const submitNotice = ref("")
const activePanel = ref<"models" | "tasks">("models")
const historyView = ref<"cards" | "feed">("cards")
const featuredToolsExpanded = ref(false)
const modalityDockOpen = ref(true)
const historySentinelRef = ref<HTMLElement | null>(null)
const historyFeedStartRef = ref<HTMLElement | null>(null)
const historyFeedEndRef = ref<HTMLElement | null>(null)
const dashboardMainRef = ref<HTMLElement | null>(null)
const expandedPromptIds = ref<Set<number>>(new Set())
const shouldScrollHistoryFeedToBottom = ref(false)
const showHistoryScrollBottom = ref(false)
let historyObserver: IntersectionObserver | null = null
let historyFeedAutoStickUntil = 0
let historyFeedAnchorUntil = 0
let historyFeedAnchorHeight = 0
let historyScrollContainer: HTMLElement | null = null
const taskPollTimers = new Map<number, number>()
const taskStatusStreamControllers = new Map<number, AbortController>()
const progressNow = ref(Date.now())
let progressClockTimer: number | null = null
const retryingTaskIds = ref<Set<number>>(new Set())
const deletingTaskIds = ref<Set<number>>(new Set())
const cancellingTaskIds = ref<Set<number>>(new Set())
const previewAsset = ref<AssetPreviewItem | null>(null)
const pendingAssetReplay = ref<AssetPreviewItem | null>(null)
const attribution = ref<DashboardAttributionContext>(dashboardAttributionFromRoute(route))
const audioElementRef = ref<HTMLAudioElement | null>(null)
const activeAudioId = ref("")
const audioPlaying = ref(false)
const audioCurrentTime = ref(0)
const audioDuration = ref(0)
const audioVolume = ref(0.8)
const lyricsExpanded = ref(false)

type DashboardAudioTrack = AudioTrackItem & {
  id: string
  taskId: number
  taskNo: string
  task: TaskDetail
  version: number
  totalVersions: number
  blockTitle: string
  createdAt?: string | null
}

const modalityLabels: Record<string, string> = {
  IMAGE: "图像",
  VIDEO: "视频",
  AUDIO: "音乐",
  TEXT: "文本",
}

const modalityDescriptions: Record<string, string> = {
  IMAGE: "海报、商品图、场景图",
  VIDEO: "短片、运镜、动态素材",
  AUDIO: "配音、音效、音乐",
  TEXT: "文案、脚本、营销内容",
}

const modalityIcons = {
  IMAGE: ImageIcon,
  VIDEO: Video,
  AUDIO: Music,
  TEXT: FileText,
}

function resolveDashboardModality(tool: Pick<ToolSummary, "toolCode" | "outputModality">) {
  return normalizeModality(tool.outputModality)
}

const toolsByModality = computed(() => {
  const groups = new Map<string, ToolSummary[]>()
  for (const tool of tools.value) {
    const key = resolveDashboardModality(tool)
    groups.set(key, [...(groups.get(key) || []), tool])
  }
  return groups
})

const modalityTabs = computed(() => {
  const order = ["IMAGE", "VIDEO", "AUDIO", "TEXT"]
  return order
    .map((key) => ({
      key,
      label: modalityLabels[key] || key,
      description: modalityDescriptions[key] || "AI 生成工具",
      count: toolsByModality.value.get(key)?.length || 0,
      icon: modalityIcons[key as keyof typeof modalityIcons] || Sparkles,
    }))
})

const currentTools = computed(() => toolsByModality.value.get(selectedModality.value) || [])

const filteredCurrentTools = computed(() => {
  const keyword = modelSearch.value.trim().toLowerCase()
  if (!keyword) return currentTools.value
  return currentTools.value.filter((tool) =>
    [tool.toolName, tool.modelConfigName, tool.modelName, tool.description, tool.toolCode]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(keyword)),
  )
})

const selectedTool = computed(() => {
  const byCode = tools.value.find((tool) => tool.toolCode === selectedToolCode.value)
  return byCode && resolveDashboardModality(byCode) === selectedModality.value
    ? byCode
    : currentTools.value[0] || null
})

const coreField = computed(() => {
  const fields = selectedChatTool.value?.fields || []
  return fields.find((field) => isCoreField(field)) || null
})

const coreFieldPlaceholder = computed(() => {
  const field = coreField.value
  if (!field?.placeholder?.trim()) return `你想创作什么${modalityLabel(selectedModality.value)}内容？`
  return field.placeholder
})

const estimateInput = computed<UseTaskEstimateInput | null>(() => {
  const tool = selectedTool.value
  if (!tool?.toolCode) return null
  const params = { ...capabilityParams.value }
  const content = promptText.value.trim()
  if (content) {
    const key = coreField.value?.fieldKey
    if (key) params[key] = content
    else if (!("prompt" in params)) params.prompt = content
  }
  return {
    toolCode: tool.toolCode,
    params,
    modelConfigId: tool.modelConfigId ?? null,
    skip: usesVariableWorkflowCredits(tool),
  }
})

const { estimate: liveEstimate, loading: estimateLoading } = useTaskEstimate(estimateInput)

const liveCreditView = computed(() =>
  formatLiveCreditEstimate(liveEstimate.value, {
    loading: estimateLoading.value,
    fallbackTool: selectedTool.value,
  }),
)

const creditInsufficient = computed(() => liveCreditView.value.insufficient)

const sortedCurrentTools = computed(() => {
  const list = currentTools.value.length > 0 ? [...currentTools.value] : [...tools.value]
  return list.sort((a, b) => a.id - b.id)
})

const visibleFeaturedTools = computed(() => {
  const list = sortedCurrentTools.value
  if (featuredToolsExpanded.value || list.length <= DASHBOARD_FEATURED_PREVIEW_LIMIT) {
    return list
  }
  return list.slice(0, DASHBOARD_FEATURED_PREVIEW_LIMIT)
})

const hiddenFeaturedToolCount = computed(() =>
  Math.max(0, sortedCurrentTools.value.length - DASHBOARD_FEATURED_PREVIEW_LIMIT),
)

const hasMoreFeaturedTools = computed(() => hiddenFeaturedToolCount.value > 0)

const recentTasks = computed(() => tasks.value)
const taskMaterials = computed(() =>
  recentTasks.value
    .map((task) => {
      const blocks = buildTaskResultBlocks(task.result?.contentText || "", task)
      return {
        task,
        blocks,
        modality: inferTaskModality(task, blocks),
        historyCardClass: historyCardClass(task, blocks),
      }
    }),
)
const historyFeedMaterials = computed(() =>
  [...taskMaterials.value].sort((a, b) => {
    const timeA = Date.parse(a.task.createdAt || "") || 0
    const timeB = Date.parse(b.task.createdAt || "") || 0
    if (timeA !== timeB) return timeA - timeB
    return a.task.taskId - b.task.taskId
  }),
)
const previewRecommendations = computed<AssetPreviewRecommendation[]>(() =>
  previewAsset.value ? recommendToolsForAsset(previewAsset.value) : [],
)
const runningCount = computed(() =>
  tasks.value.filter((task) => ["CREATED", "QUEUED", "PROCESSING", "RETRYING"].includes(task.status)).length,
)

watch(recentTasks, () => {
  syncTaskStatusStreams()
})

watch(runningCount, (count) => {
  if (count > 0) startProgressClock()
  else {
    stopProgressClock()
    stopAllTaskStatusStreams()
  }
})
const audioTaskMaterials = computed(() =>
  taskMaterials.value.filter((item) => item.task.status === "SUCCESS" && primaryBlock(item.blocks)?.type === "audio"),
)
const audioStatusMaterials = computed(() =>
  taskMaterials.value.filter((item) => item.task.status !== "SUCCESS" || primaryBlock(item.blocks)?.type !== "audio"),
)
const audioWorkbenchVisible = computed(() => selectedModality.value === "AUDIO" && recentTasks.value.length > 0)
const isHistoryFeedView = computed(() => activePanel.value === "tasks" && historyView.value === "feed")

watch(historyView, (view) => {
  localStorage.setItem(HISTORY_VIEW_KEY, view)
  historyScrollContainer = null
  if (view === "feed") composerManuallyClosed.value = false
  if (view !== "feed") showHistoryScrollBottom.value = false
  void nextTick(() => {
    setupHistoryObserver()
    if (view === "feed" && activePanel.value === "tasks") {
      scrollHistoryFeedToBottom("smooth")
      shouldScrollHistoryFeedToBottom.value = false
      updateHistoryScrollBottomVisibility()
    }
  })
})

watch(activePanel, async (panel) => {
  historyScrollContainer = null
  if (panel !== "tasks") {
    showHistoryScrollBottom.value = false
    return
  }
  await nextTick()
  setupHistoryObserver()
  if (historyView.value === "feed") {
    composerManuallyClosed.value = false
    scrollHistoryFeedToBottom("smooth")
    shouldScrollHistoryFeedToBottom.value = false
    updateHistoryScrollBottomVisibility()
  } else {
    dashboardMainRef.value?.scrollTo({ top: 0, behavior: "smooth" })
  }
})

watch(
  () => tasks.value.length,
  async () => {
    if (!isHistoryFeedView.value || !shouldScrollHistoryFeedToBottom.value) return
    await nextTick()
    scrollHistoryFeedToBottom("smooth")
    shouldScrollHistoryFeedToBottom.value = false
  },
)

const primaryAudioStatusItem = computed(() => audioStatusMaterials.value.find((item) => isTaskRunning(item.task.status)) || audioStatusMaterials.value[0] || null)
const audioRows = computed<DashboardAudioTrack[]>(() =>
  audioTaskMaterials.value.flatMap((item) => {
    const block = primaryBlock(item.blocks)
    if (block?.type !== "audio") return []
    const tracks = resolveAudioTracks(block)
    return tracks.map((track, index) => ({
      ...track,
      id: `${item.task.taskId}:${index}:${track.url}`,
      taskId: item.task.taskId,
      taskNo: item.task.taskNo,
      task: item.task,
      version: index + 1,
      totalVersions: tracks.length,
      blockTitle: block.title,
      createdAt: item.task.createdAt,
    }))
  }),
)
const activeAudioTrack = computed(() => {
  if (!audioRows.value.length) return null
  return audioRows.value.find((track) => track.id === activeAudioId.value) || audioRows.value[0]
})
const activeAudioProgress = computed(() => {
  const total = normalizedAudioDuration(activeAudioTrack.value)
  return total > 0 ? Math.min(1, Math.max(0, audioCurrentTime.value / total)) : 0
})

watch(selectedTool, (tool) => {
  if (tool && selectedToolCode.value !== tool.toolCode) selectedToolCode.value = tool.toolCode
})

watch(
  () => route.query.modality,
  (value) => {
    const raw = Array.isArray(value) ? value[0] : value
    if (!raw) return
    const key = normalizeModality(raw)
    if (key && key !== selectedModality.value) selectModality(key)
  },
)

watch(
  () => route.query.tool,
  (value) => {
    const code = Array.isArray(value) ? value[0] : value
    if (!code || !tools.value.length) return
    selectToolByCode(code, true)
  },
)

watch(
  () => route.query.prompt,
  (value) => {
    const raw = Array.isArray(value) ? value[0] : value
    if (typeof raw === "string") promptText.value = raw
  },
)

watch(
  () => route.query.sourcePost,
  () => {
    attribution.value = mergePendingAssetAttribution(dashboardAttributionFromRoute(route), pendingAssetReplay.value)
  },
)

watch(audioRows, (rows) => {
  if (!rows.length) {
    activeAudioId.value = ""
    stopDashboardAudio()
    return
  }
  if (!rows.some((track) => track.id === activeAudioId.value)) activeAudioId.value = rows[0].id
})

watch(activeAudioTrack, (track, previous) => {
  if (!track || track.id === previous?.id) return
  audioCurrentTime.value = 0
  audioDuration.value = track.duration || 0
  lyricsExpanded.value = false
  const audio = audioElementRef.value
  if (audio) {
    audio.src = normalizeMediaUrl(track.url)
    audio.volume = audioVolume.value
    audio.load()
    if (audioPlaying.value) void audio.play().catch(() => {
      audioPlaying.value = false
    })
  }
})

watch(audioVolume, (value) => {
  const audio = audioElementRef.value
  if (audio) audio.volume = value
})

watch(
  selectedToolCode,
  (code) => {
    if (code) void loadSelectedToolDetail(code)
    else selectedChatTool.value = null
  },
  { immediate: false },
)

async function loadDashboard() {
  loading.value = true
  try {
    const [creditRes, toolRes, taskRes] = await Promise.all([
      fetchCreditAccount({ token: auth.token }),
      fetchTools({ token: auth.token, query: { pageNo: 1, pageSize: 120 } }),
      fetchTasks({ token: auth.token, query: { pageNo: 1, pageSize: 12 } }),
    ])
    credit.value = creditRes
    tools.value = toolRes.list
    tasks.value = taskRes.list
    taskPageNo.value = taskRes.pageNo
    taskHasNext.value = taskRes.hasNext
    const rawRouteModality = Array.isArray(route.query.modality) ? route.query.modality[0] : route.query.modality
    const rawRoutePrompt = Array.isArray(route.query.prompt) ? route.query.prompt[0] : route.query.prompt
    if (rawRouteModality) selectedModality.value = normalizeModality(rawRouteModality)
    if (typeof rawRoutePrompt === "string") promptText.value = rawRoutePrompt
    ensureSelectedModality()
    const rawRouteTool = Array.isArray(route.query.tool) ? route.query.tool[0] : route.query.tool
    const pendingAsset = consumePendingAssetFromStorage()
    if (pendingAsset) {
      attribution.value = mergePendingAssetAttribution(attribution.value, pendingAsset)
      pendingAssetReplay.value = pendingAsset
      promptText.value = pendingAsset.prompt || promptText.value
    }
    if (rawRouteTool) selectToolByCode(rawRouteTool, true)
    else if (pendingAsset) expandComposer()
    await reloadTasksForCurrentModality()
    startPollingVisibleTasks()
  } finally {
    loading.value = false
  }
}

function ensureSelectedModality() {
  if (currentTools.value.length > 0) {
    selectedToolCode.value = currentTools.value[0]?.toolCode || null
    return
  }
  const fallback = ["IMAGE", "VIDEO", "AUDIO", "TEXT"].find(
    (key) => (toolsByModality.value.get(key)?.length || 0) > 0,
  )
  if (fallback) {
    selectedModality.value = fallback
    selectedToolCode.value = toolsByModality.value.get(fallback)?.[0]?.toolCode || null
  }
}

function normalizeModality(value?: string | null) {
  return (value || "TEXT").trim().toUpperCase()
}

function selectModality(key: string) {
  selectedModality.value = key
  selectedToolCode.value = toolsByModality.value.get(key)?.[0]?.toolCode || null
  featuredToolsExpanded.value = false
  modelSearch.value = ""
  modelPickerOpen.value = false
  replayParams.value = null
  void reloadTasksForCurrentModality()
}

function selectTool(tool: ToolSummary) {
  selectedToolCode.value = tool.toolCode
  modelPickerOpen.value = false
  expandComposer()
  replayParams.value = null
  submitError.value = ""
  submitNotice.value = ""
}

function selectToolByCode(toolCode: string, openComposer = false) {
  const tool = tools.value.find((item) => item.toolCode === toolCode)
  if (!tool) return
  selectedModality.value = resolveDashboardModality(tool)
  selectedToolCode.value = tool.toolCode
  if (openComposer) expandComposer()
}

function expandComposer() {
  composerManuallyClosed.value = false
  composerOpen.value = true
}

function updatePrimaryReferenceInfo(info: PrimaryReferenceMaterialInfo) {
  primaryReferenceInfo.value = info
}

function onCapabilityParamsChange(params: Record<string, unknown>) {
  capabilityParams.value = params
}

function openPrimaryReferencePicker() {
  capabilityRef.value?.openReferenceMaterialPicker("upload")
  expandComposer()
}

function removePrimaryReferenceAt(index: number, event: MouseEvent) {
  event.stopPropagation()
  capabilityRef.value?.removePrimaryReferenceMaterialAt(index)
}

function collapseComposerForPreview(manual = true) {
  if (!composerOpen.value || submitting.value) return
  if (manual) composerManuallyClosed.value = true
  composerOpen.value = false
  modelPickerOpen.value = false
}

function autoExpandComposerAtFeedBottom() {
  if (!isHistoryFeedView.value || composerManuallyClosed.value) return
  composerOpen.value = true
}

function handleDashboardPointerDown(event: PointerEvent) {
  if (!composerOpen.value || submitting.value) return
  if (capabilityRef.value?.hasOpenOverlay?.()) return
  const root = composerRootRef.value
  const target = event.target
  if (!root || !(target instanceof Node) || root.contains(target)) return
  if (target instanceof Element && target.closest("[data-capability-overlay]")) return
  collapseComposerForPreview()
}

async function createWithSelectedTool() {
  expandComposer()
  const tool = selectedTool.value
  if (!tool || submitting.value) {
    if (!tool) submitError.value = "请先选择模型"
    return
  }
  submitError.value = ""
  submitNotice.value = ""

  if (selectedToolDetailLoading.value) {
    submitError.value = "工具配置加载中，请稍候"
    return
  }
  if (!selectedChatTool.value) {
    submitError.value = "工具配置加载失败，请重新选择模型"
    return
  }
  if (capabilityRef.value?.hasPendingUploads()) {
    submitError.value = "文件上传中，请稍候"
    return
  }
  const check = capabilityRef.value?.validate()
  if (check && !check.valid) {
    submitError.value = check.message || "请完善必填项"
    return
  }

  const content = promptText.value.trim()
  if (!content) {
    submitError.value = "请输入创作提示词"
    return
  }
  const params = capabilityRef.value?.getRequestParams() || {}
  const attachments = capabilityRef.value?.getAttachmentIds() || []
  const taskParams = buildDashboardTaskParams({
    prompt: content,
    params,
    coreFieldKey: coreField.value?.fieldKey,
    attachments,
  })
  attribution.value = mergePendingAssetAttribution(dashboardAttributionFromRoute(route), pendingAssetReplay.value)
  const sourcePostId = attribution.value.sourcePostId

  submitting.value = true
  try {
    const response = await createTask(
      {
        toolCode: tool.toolCode,
        params: taskParams,
        clientRequestId: randomUUID(),
        sourcePostId,
      },
      { token: auth.token },
    )
    const optimisticTask = buildOptimisticDashboardTask({
      taskId: response.taskId,
      taskNo: response.taskNo,
      status: response.status,
      tool,
      params: taskParams,
      selectedModality: selectedModality.value,
      userId: auth.user?.id ?? 0,
    })
    shouldScrollHistoryFeedToBottom.value = true
    upsertTask(optimisticTask, true)
    activePanel.value = "tasks"
    submitNotice.value = `已进入工作历史：${response.taskNo}`
    replayParams.value = null
    startTaskPolling(response.taskId)
    syncTaskStatusStreams()
    await nextTick()
    setupHistoryObserver()
    if (historyView.value === "feed") {
      scrollHistoryFeedToBottom()
      shouldScrollHistoryFeedToBottom.value = false
    }
  } catch (e) {
    if (e instanceof ApiBusinessError && (e.code === "CREDIT_NOT_ENOUGH" || e.code === "AGENT_CREDIT_NOT_ENOUGH")) {
      submitError.value = "算力不足，请前往会员与算力页充值后再试"
    } else if (e instanceof ApiBusinessError && e.code === "TOOL_OFFLINE") {
      submitError.value = "该工具已下架，无法创建任务"
    } else {
      submitError.value = (e as Error).message || "创建任务失败"
    }
  } finally {
    submitting.value = false
  }
}

function upsertTask(task: TaskDetail, prepend = false) {
  const index = tasks.value.findIndex((item) => item.taskId === task.taskId)
  if (index >= 0) {
    const next = [...tasks.value]
    next[index] = { ...next[index], ...task }
    tasks.value = next
    return
  }
  tasks.value = prepend ? [task, ...tasks.value] : [...tasks.value, task]
}

async function reloadTasksForCurrentModality() {
  tasksLoadingMore.value = true
  try {
    let pageNo = 1
    let hasNext = true
    const matched: TaskDetail[] = []

    while (hasNext && matched.length < 12) {
      const response = await fetchTasks({
        token: auth.token,
        query: { pageNo, pageSize: 20 },
      })
      matched.push(...response.list.filter(taskMatchesSelectedModality))
      pageNo = response.pageNo + 1
      hasNext = response.hasNext
      taskPageNo.value = response.pageNo
      taskHasNext.value = response.hasNext
    }

    tasks.value = matched
    taskHasNext.value = hasNext
    startPollingVisibleTasks()
    syncTaskStatusStreams()
  } finally {
    tasksLoadingMore.value = false
  }
}

async function loadMoreTasks(options: { preserveFeedAnchor?: boolean } = {}) {
  if (tasksLoadingMore.value || !taskHasNext.value) return
  const scrollContainer = options.preserveFeedAnchor ? resolveHistoryScrollContainer() : null
  const previousScrollHeight = scrollContainer?.scrollHeight ?? 0
  const previousScrollTop = scrollContainer?.scrollTop ?? 0
  tasksLoadingMore.value = true
  try {
    const existing = new Set(tasks.value.map((task) => task.taskId))
    const matched: TaskDetail[] = []
    let pageNo = taskPageNo.value + 1
    let hasNext = taskHasNext.value

    while (hasNext && matched.length < 8) {
      const next = await fetchTasks({
        token: auth.token,
        query: { pageNo, pageSize: 20 },
      })
      matched.push(
        ...next.list.filter((task) => taskMatchesSelectedModality(task) && !existing.has(task.taskId)),
      )
      pageNo = next.pageNo + 1
      hasNext = next.hasNext
      taskPageNo.value = next.pageNo
      taskHasNext.value = next.hasNext
    }

    tasks.value = [...tasks.value, ...matched]
    taskHasNext.value = hasNext
    if (options.preserveFeedAnchor && scrollContainer) {
      await nextTick()
      const heightDelta = scrollContainer.scrollHeight - previousScrollHeight
      scrollContainer.scrollTop = previousScrollTop + Math.max(0, heightDelta)
      beginHistoryFeedAnchorPreservation(scrollContainer)
      updateHistoryScrollBottomVisibility()
    }
    startPollingVisibleTasks()
    syncTaskStatusStreams()
  } finally {
    tasksLoadingMore.value = false
  }
}

function taskMatchesSelectedModality(task: TaskDetail): boolean {
  return normalizeModality(task.outputModality || task.result?.resourceType) === selectedModality.value
}

function setupHistoryObserver() {
  historyObserver?.disconnect()
  if (historyView.value === "feed") return
  historyObserver = new IntersectionObserver((entries) => {
    if (entries.some((entry) => entry.isIntersecting)) void loadMoreTasks()
  }, {
    root: resolveHistoryScrollContainer(),
    rootMargin: "0px 0px 260px 0px",
  })
  if (historySentinelRef.value) historyObserver.observe(historySentinelRef.value)
}

function normalizedTaskProgress(value?: number | null): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) return null
  return Math.max(0, Math.min(100, Math.round(value)))
}

function monotonicTaskProgress(existing: TaskDetail | undefined, incomingProgress?: number | null): number | undefined {
  const previous = normalizedTaskProgress(existing?.progress)
  const incoming = normalizedTaskProgress(incomingProgress)
  if (incoming == null) return previous ?? existing?.progress
  if (previous == null) return incoming
  return Math.max(previous, incoming)
}

async function syncTerminalTaskDetail(taskId: number) {
  try {
    const detail = await fetchTaskById(taskId, { token: auth.token })
    if (taskMatchesSelectedModality(detail)) upsertTask(detail)
  } catch {
    // detail sync may be briefly unavailable; polling will retry on next tick
  }
}

function applyTaskStatusPayload(payload: TaskStatusPayload) {
  const current = tasks.value.find((task) => task.taskId === payload.taskId)
  if (!current) return
  upsertTask({
    ...current,
    status: payload.status,
    progress: monotonicTaskProgress(current, payload.progress),
    progressMessage: taskProgressMessage(payload.status, payload.progressMessage ?? current.progressMessage),
  })
  if (isTaskTerminal(payload.status)) {
    stopTaskPolling(payload.taskId)
    stopTaskStatusStream(payload.taskId)
    void syncTerminalTaskDetail(payload.taskId)
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
  if (!auth.isLoggedIn || !isTaskRunning(task.status) || taskStatusStreamControllers.has(task.taskId)) return
  const controller = new AbortController()
  taskStatusStreamControllers.set(task.taskId, controller)
  void streamTaskStatus(task.taskId, applyTaskStatusPayload, {
    token: auth.token,
    signal: controller.signal,
  }).catch((streamError) => {
    if (!controller.signal.aborted) {
      console.debug("dashboard task progress stream closed", task.taskId, streamError)
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
    if (!visibleTaskIds.has(taskId) || !task || !isTaskRunning(task.status)) {
      controller.abort()
      taskStatusStreamControllers.delete(taskId)
    }
  }
  for (const task of tasks.value) {
    subscribeActiveTaskStatus(task)
  }
}

function startPollingVisibleTasks() {
  for (const task of tasks.value) {
    if (isTaskRunning(task.status)) startTaskPolling(task.taskId)
  }
}

function startTaskPolling(taskId: number) {
  if (taskPollTimers.has(taskId)) return
  void refreshTaskStatus(taskId)
  const timer = window.setInterval(() => {
    void refreshTaskStatus(taskId)
  }, 2500)
  taskPollTimers.set(taskId, timer)
}

function stopTaskPolling(taskId: number) {
  const timer = taskPollTimers.get(taskId)
  if (timer) window.clearInterval(timer)
  taskPollTimers.delete(taskId)
}

async function refreshTaskStatus(taskId: number) {
  const current = tasks.value.find((task) => task.taskId === taskId)
  if (!current || !isTaskRunning(current.status)) {
    stopTaskPolling(taskId)
    return
  }
  try {
    const status = await fetchTaskStatus(taskId, { token: auth.token })
    applyTaskStatusPayload(status)
  } catch {
    // keep polling as SSE fallback when status requests fail transiently
  }
}

function isTaskRunning(status?: TaskStatus): boolean {
  return status === "CREATED" || status === "QUEUED" || status === "PROCESSING" || status === "RETRYING"
}

function isTaskTerminal(status?: TaskStatus): boolean {
  return status === "SUCCESS" || status === "FAILED" || status === "TIMEOUT" || status === "CANCELLED"
}

function canRetryTask(status?: TaskStatus): boolean {
  return status === "FAILED" || status === "TIMEOUT"
}

function canDeleteTask(status?: TaskStatus): boolean {
  return status === "FAILED" || status === "TIMEOUT" || status === "CANCELLED"
}

function canCancelTask(status?: TaskStatus): boolean {
  return status === "QUEUED"
}

function taskProgressSubtitle(task: TaskDetail, runningFallback: string): string {
  if (canRetryTask(task.status)) {
    return taskFailureHint(task.status, [task.progressMessage]) || runningFallback
  }
  return taskProgressMessage(task.status, task.progressMessage) || runningFallback
}

function startProgressClock() {
  if (progressClockTimer) return
  progressNow.value = Date.now()
  progressClockTimer = window.setInterval(() => {
    progressNow.value = Date.now()
  }, 1000)
}

function stopProgressClock() {
  if (!progressClockTimer) return
  window.clearInterval(progressClockTimer)
  progressClockTimer = null
}

function taskProgressView(task: TaskDetail) {
  return buildTaskProgressView(task, progressNow.value)
}

function taskStatusLabel(status?: TaskStatus): string {
  const labels: Record<TaskStatus, string> = {
    CREATED: "已创建",
    QUEUED: "排队中",
    PROCESSING: "正在生成",
    RETRYING: "重试中",
    SUCCESS: "已完成",
    FAILED: "生成失败",
    TIMEOUT: "生成超时",
    CANCELLED: "已取消",
  }
  return status ? labels[status] : "任务状态"
}

function toolSummaryForRetry(task: TaskDetail): ToolSummary {
  const matched = tools.value.find((item) => item.toolCode === task.toolCode)
  if (matched) return matched
  return {
    id: 0,
    toolCode: task.toolCode,
    toolName: task.toolName,
    categoryId: 0,
    categoryName: "",
    status: "ONLINE",
    estimatedCreditCost: 0,
    toolType: task.toolType,
    inputModality: task.inputModality,
    outputModality: task.outputModality,
  }
}

async function retryTask(task: TaskDetail) {
  if (retryingTaskIds.value.has(task.taskId)) return
  retryingTaskIds.value = new Set([...retryingTaskIds.value, task.taskId])
  submitError.value = ""
  const previousTaskId = task.taskId
  try {
    const response = await regenerateTask(
      task.taskId,
      {
        params: { ...(task.params || {}) },
        clientRequestId: randomUUID(),
      },
      { token: auth.token },
    )
    const optimisticTask = buildOptimisticDashboardTask({
      taskId: response.taskId,
      taskNo: response.taskNo,
      status: response.status,
      tool: toolSummaryForRetry(task),
      params: { ...(task.params || {}) },
      selectedModality: normalizeModality(task.outputModality) || selectedModality.value,
      userId: auth.user?.id ?? task.userId,
    })
    stopTaskPolling(previousTaskId)
    stopTaskStatusStream(previousTaskId)
    if (response.taskId !== previousTaskId) {
      tasks.value = tasks.value.filter((item) => item.taskId !== previousTaskId)
      upsertTask(optimisticTask, true)
      try {
        await deleteTask(previousTaskId, { token: auth.token })
      } catch {
        // The replacement task is already visible; keep the workspace clear even if cleanup is retried later.
      }
    } else {
      upsertTask(optimisticTask)
    }
    activePanel.value = "tasks"
    startTaskPolling(response.taskId)
    syncTaskStatusStreams()
  } catch (e) {
    submitError.value = (e as Error).message || "重试任务失败"
  } finally {
    const next = new Set(retryingTaskIds.value)
    next.delete(previousTaskId)
    retryingTaskIds.value = next
  }
}

async function cancelQueuedTask(task: TaskDetail) {
  if (!canCancelTask(task.status) || cancellingTaskIds.value.has(task.taskId)) return
  cancellingTaskIds.value = new Set([...cancellingTaskIds.value, task.taskId])
  submitError.value = ""
  try {
    const response = await cancelTask(task.taskId, { token: auth.token })
    stopTaskPolling(task.taskId)
    stopTaskStatusStream(task.taskId)
    const current = tasks.value.find((item) => item.taskId === task.taskId)
    if (current) {
      upsertTask({
        ...current,
        status: response.status,
        progress: response.progress ?? current.progress,
        progressMessage: response.progressMessage ?? current.progressMessage,
        finishedAt: new Date().toISOString(),
      })
    }
  } catch (e) {
    submitError.value = (e as Error).message || "取消任务失败"
  } finally {
    const next = new Set(cancellingTaskIds.value)
    next.delete(task.taskId)
    cancellingTaskIds.value = next
  }
}

async function removeTask(task: TaskDetail) {
  if (!canDeleteTask(task.status) || deletingTaskIds.value.has(task.taskId)) return
  deletingTaskIds.value = new Set([...deletingTaskIds.value, task.taskId])
  submitError.value = ""
  try {
    stopTaskPolling(task.taskId)
    stopTaskStatusStream(task.taskId)
    await deleteTask(task.taskId, { token: auth.token })
    tasks.value = tasks.value.filter((item) => item.taskId !== task.taskId)
  } catch (e) {
    submitError.value = (e as Error).message || "删除任务失败"
  } finally {
    const next = new Set(deletingTaskIds.value)
    next.delete(task.taskId)
    deletingTaskIds.value = next
  }
}

function taskPrompt(task: TaskDetail): string {
  const params = task.params || {}
  const value = params.prompt || params.text || params.description || params.videoTopic || params.productName
  return typeof value === "string" && value.trim() ? value.trim() : ""
}

function taskModelTag(task: TaskDetail): string {
  return String(task.params?.model || task.params?.modelName || task.toolCode || task.outputModality || "AI")
}

function inferTaskModality(task: TaskDetail, blocks: ResultBlock[]): string {
  const raw = (task.outputModality || task.result?.resourceType || "").toUpperCase()
  if (raw === "IMAGE" || blocks.some((block) => block.type === "image")) return "图像"
  if (raw === "VIDEO" || blocks.some((block) => block.type === "video")) return "视频"
  if (raw === "AUDIO" || blocks.some((block) => block.type === "audio")) return "音频"
  if (raw === "TEXT" || blocks.some((block) => block.type === "text" || block.type === "report")) return "文本"
  return "其他"
}

function primaryBlock(blocks: ResultBlock[]): ResultBlock | null {
  return blocks.find((block) => block.type === "image" || block.type === "video" || block.type === "audio") || blocks[0] || null
}

function imageItemsForBlocks(blocks: ResultBlock[]) {
  const block = primaryBlock(blocks)
  return block?.type === "image" ? block.images : []
}

function videoUrlForBlocks(blocks: ResultBlock[]) {
  const block = primaryBlock(blocks)
  return block?.type === "video" ? block.url : ""
}

function firstDownloadUrl(blocks: ResultBlock[]): string {
  const block = primaryBlock(blocks)
  if (block?.type === "image") return block.images[0]?.downloadUrl || block.images[0]?.url || ""
  if (block?.type === "video") return block.downloadUrl || block.url
  if (block?.type === "audio") {
    const track = resolveAudioTracks(block)[0]
    return track?.downloadUrl || track?.url || ""
  }
  return ""
}

function firstDownloadFilename(item: { task: { taskNo?: string | null } }): string {
  return `${item.task.taskNo || 'asset'}-result`
}

function isPromptExpanded(taskId: number) {
  return expandedPromptIds.value.has(taskId)
}

function togglePrompt(taskId: number) {
  const next = new Set(expandedPromptIds.value)
  if (next.has(taskId)) next.delete(taskId)
  else next.add(taskId)
  expandedPromptIds.value = next
}

function resolveHistoryScrollContainer(): HTMLElement | null {
  if (historyScrollContainer && document.contains(historyScrollContainer)) return historyScrollContainer
  const fallback = (document.scrollingElement || document.documentElement) as HTMLElement
  const candidates: HTMLElement[] = []
  let node = dashboardMainRef.value
  while (node) {
    candidates.push(node)
    node = node.parentElement
  }
  candidates.push(fallback)
  historyScrollContainer =
    candidates.find((item) => item.scrollHeight - item.clientHeight > 2) ||
    dashboardMainRef.value ||
    fallback
  return historyScrollContainer
}

function historyBottomDistance(container = resolveHistoryScrollContainer()) {
  if (!container) return 0
  return Math.max(0, container.scrollHeight - container.scrollTop - container.clientHeight)
}

function updateHistoryScrollBottomVisibility() {
  showHistoryScrollBottom.value = isHistoryFeedView.value && historyBottomDistance() > 300
}

function scrollHistoryFeedToBottom(behavior: ScrollBehavior = "smooth", stabilize = true) {
  const container = resolveHistoryScrollContainer()
  if (stabilize) historyFeedAutoStickUntil = Date.now() + 1400
  autoExpandComposerAtFeedBottom()
  if (container) {
    container.scrollTo({ top: container.scrollHeight, behavior })
  } else {
    historyFeedEndRef.value?.scrollIntoView({ behavior, block: "end" })
  }
  showHistoryScrollBottom.value = false
  if (!stabilize) return
  const settleBottom = () => {
    if (!isHistoryFeedView.value || Date.now() > historyFeedAutoStickUntil) return
    const nextContainer = resolveHistoryScrollContainer()
    if (!nextContainer) return
    nextContainer.scrollTo({ top: nextContainer.scrollHeight, behavior: "auto" })
    showHistoryScrollBottom.value = false
  }
  window.setTimeout(settleBottom, 180)
  window.setTimeout(settleBottom, 420)
  window.setTimeout(settleBottom, 900)
}

function isHistoryFeedNearTop(container: HTMLElement) {
  const start = historyFeedStartRef.value
  if (!start) return container.scrollTop <= 50
  const containerRect = container.getBoundingClientRect()
  const startRect = start.getBoundingClientRect()
  const topOffset = startRect.top - containerRect.top
  const bottomOffset = startRect.bottom - containerRect.top
  return topOffset <= 140 && bottomOffset >= -24
}

// 滚动到页面顶部（用于点击任务卡片时将任务顶上去）
function scrollToTop(event?: Event) {
  if (event) event.stopPropagation()
  const container = resolveHistoryScrollContainer()
  if (container) {
    container.scrollTo({ top: 0, behavior: 'smooth' })
  } else {
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
}

function beginHistoryFeedAnchorPreservation(container: HTMLElement) {
  historyFeedAnchorUntil = Date.now() + 1600
  historyFeedAnchorHeight = container.scrollHeight
}

function preserveHistoryFeedAnchorIfNeeded() {
  if (!isHistoryFeedView.value || Date.now() > historyFeedAnchorUntil) return
  const container = resolveHistoryScrollContainer()
  if (!container || historyFeedAnchorHeight <= 0) return
  const heightDelta = container.scrollHeight - historyFeedAnchorHeight
  if (heightDelta > 0) {
    container.scrollTop += heightDelta
    historyFeedAnchorHeight = container.scrollHeight
    updateHistoryScrollBottomVisibility()
  }
}

function handleHistoryFeedMediaLoaded() {
  if (!isHistoryFeedView.value) return
  if (Date.now() <= historyFeedAnchorUntil) {
    preserveHistoryFeedAnchorIfNeeded()
    return
  }
  if (Date.now() <= historyFeedAutoStickUntil || historyBottomDistance() < 220) {
    scrollHistoryFeedToBottom("auto", false)
  }
}

function handleDashboardScroll() {
  if (!isHistoryFeedView.value) {
    showHistoryScrollBottom.value = false
    return
  }
  const container = resolveHistoryScrollContainer()
  if (!container) return
  showHistoryScrollBottom.value = historyBottomDistance(container) > 300
  if (historyBottomDistance(container) <= 80) autoExpandComposerAtFeedBottom()
  if (isHistoryFeedNearTop(container) && taskHasNext.value && !tasksLoadingMore.value) {
    void loadMoreTasks({ preserveFeedAnchor: true })
  }
}

function audioTracksForItem(blocks: ResultBlock[]) {
  const block = primaryBlock(blocks)
  return block?.type === "audio" ? resolveAudioTracks(block) : []
}

function historyCardClass(task: TaskDetail, blocks: ResultBlock[]): string {
  if (task.status !== "SUCCESS") return "history-card-pending"
  const block = primaryBlock(blocks)
  if (block?.type !== "image") return "history-card-standard"
  return "history-card-image"
}

/**
 * Resolve the best available cover URL for a tool card.
 * Falls back through frontendStyle.comparisonEffectUrl → demoThumbnails → coverUrl
 * to handle cases where the OSS cover URL is inaccessible (e.g. encoding issues).
 */
const brokenToolCoverIds = ref<Set<number>>(new Set())

function toolCardCover(tool: ToolSummary): string {
  if (brokenToolCoverIds.value.has(tool.id)) {
    return normalizeMediaUrl(
      tool.frontendStyle?.comparisonEffectUrl ||
      tool.frontendStyle?.demoThumbnails?.[0] ||
      "",
    )
  }
  return normalizeMediaUrl(
    tool.coverUrl ||
      tool.frontendStyle?.comparisonEffectUrl ||
      tool.frontendStyle?.demoThumbnails?.[0] ||
      "",
  )
}

function onToolCoverError(tool: ToolSummary) {
  if (!brokenToolCoverIds.value.has(tool.id)) {
    brokenToolCoverIds.value = new Set([...brokenToolCoverIds.value, tool.id])
  }
}

function inferImageAspectRatio(task: TaskDetail): number {
  const params = task.params || {}
  const ratio = parseAspectRatio(findAspectRatioText(params))
  if (ratio > 0) return ratio
  const sizeRatio = parseSizeRatio(findSizeText(params))
  if (sizeRatio > 0) return sizeRatio
  return 1
}

function findAspectRatioText(value: unknown): string {
  if (!value || typeof value !== "object") return ""
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findAspectRatioText(item)
      if (found) return found
    }
    return ""
  }
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    const normalizedKey = key.toLowerCase()
    if (
      typeof raw === "string" &&
      (normalizedKey.includes("aspect") || normalizedKey.includes("ratio") || normalizedKey.includes("比例"))
    ) {
      return raw
    }
    const nested = findAspectRatioText(raw)
    if (nested) return nested
  }
  return ""
}

function findSizeText(value: unknown): string {
  if (!value || typeof value !== "object") return ""
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findSizeText(item)
      if (found) return found
    }
    return ""
  }
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    const normalizedKey = key.toLowerCase()
    if (typeof raw === "string" && (normalizedKey.includes("size") || normalizedKey.includes("resolution"))) {
      return raw
    }
    const nested = findSizeText(raw)
    if (nested) return nested
  }
  return ""
}

function parseAspectRatio(value: string): number {
  const match = value.match(/(\d+(?:\.\d+)?)\s*[:/]\s*(\d+(?:\.\d+)?)/)
  if (!match) return 0
  const width = Number(match[1])
  const height = Number(match[2])
  return width > 0 && height > 0 ? width / height : 0
}

function parseSizeRatio(value: string): number {
  const match = value.match(/(\d{2,5})\s*[x×]\s*(\d{2,5})/i)
  if (!match) return 0
  const width = Number(match[1])
  const height = Number(match[2])
  return width > 0 && height > 0 ? width / height : 0
}

function audioTaskTitle(track?: DashboardAudioTrack | null): string {
  if (!track) return "未选择音频"
  return track.title || taskPrompt(track.task) || track.blockTitle || track.task.toolName || `版本 ${track.version}`
}

function audioTaskSubtitle(track?: DashboardAudioTrack | null): string {
  if (!track) return ""
  return track.totalVersions > 1
    ? `${track.task.toolName} · 版本 ${track.version}/${track.totalVersions}`
    : track.task.toolName
}

function normalizedAudioDuration(track?: DashboardAudioTrack | null): number {
  return audioDuration.value || track?.duration || 0
}

function activeAudioTimeLabel(track?: DashboardAudioTrack | null): string {
  const total = normalizedAudioDuration(track)
  return `${formatAudioDuration(audioCurrentTime.value) || "0:00"} / ${formatAudioDuration(total) || "--:--"}`
}

function waveformBars(track?: DashboardAudioTrack | null, count = 64): number[] {
  const seed = `${track?.id || "audio"}-${track?.title || ""}`
  let hash = 0
  for (let i = 0; i < seed.length; i += 1) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0
  return Array.from({ length: count }, (_, index) => {
    hash = (hash * 1664525 + 1013904223) >>> 0
    const wave = Math.sin(index * 0.42) * 0.22 + Math.sin(index * 0.13 + 1.8) * 0.16
    const noise = (hash % 100) / 100
    return Math.max(14, Math.min(100, Math.round((0.28 + noise * 0.52 + wave) * 100)))
  })
}

function setActiveAudio(track: DashboardAudioTrack, play = false) {
  const sameTrack = activeAudioId.value === track.id
  activeAudioId.value = track.id
  if (play) {
    void nextTick(() => toggleDashboardAudio(sameTrack ? undefined : true))
  }
}

function toggleDashboardAudio(forcePlay?: boolean) {
  const track = activeAudioTrack.value
  const audio = audioElementRef.value
  if (!track || !audio) return
  if (audio.src !== normalizeMediaUrl(track.url)) {
    audio.src = normalizeMediaUrl(track.url)
    audio.volume = audioVolume.value
    audio.load()
  }
  const shouldPlay = forcePlay ?? !audioPlaying.value
  if (!shouldPlay) {
    audio.pause()
    audioPlaying.value = false
    return
  }
  void audio.play().then(() => {
    audioPlaying.value = true
  }).catch(() => {
    audioPlaying.value = false
  })
}

function stopDashboardAudio() {
  const audio = audioElementRef.value
  if (audio) audio.pause()
  audioPlaying.value = false
  audioCurrentTime.value = 0
}

function seekDashboardAudio(event: MouseEvent, track = activeAudioTrack.value) {
  if (!track) return
  setActiveAudio(track)
  const target = event.currentTarget as HTMLElement
  const rect = target.getBoundingClientRect()
  const ratio = rect.width > 0 ? Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width)) : 0
  const total = normalizedAudioDuration(track)
  if (total <= 0) return
  audioCurrentTime.value = total * ratio
  const audio = audioElementRef.value
  if (audio) audio.currentTime = audioCurrentTime.value
}

function onDashboardAudioLoaded() {
  const audio = audioElementRef.value
  if (!audio) return
  audio.volume = audioVolume.value
  audioDuration.value = Number.isFinite(audio.duration) ? audio.duration : activeAudioTrack.value?.duration || 0
}

function onDashboardAudioTimeUpdate() {
  const audio = audioElementRef.value
  if (!audio) return
  audioCurrentTime.value = audio.currentTime || 0
}

function onDashboardAudioEnded() {
  audioPlaying.value = false
  audioCurrentTime.value = normalizedAudioDuration(activeAudioTrack.value)
}

function assetFromTask(item: { task: TaskDetail; blocks: ResultBlock[]; modality: string }): AssetPreviewItem | null {
  const block = primaryBlock(item.blocks)
  if (!block) return null
  const base = {
    id: `task-${item.task.taskId}`,
    title: item.task.toolName || block.title || item.task.taskNo,
    subtitle: taskProgressSubtitle(item.task, item.task.taskNo),
    prompt: taskPrompt(item.task),
    taskId: item.task.taskId,
    taskNo: item.task.taskNo,
    toolName: item.task.toolName,
    toolCode: item.task.toolCode,
    createdAt: item.task.createdAt,
    communityPostId: item.task.communityPostId ?? undefined,
    promptVisible: item.task.communityPromptVisible ?? undefined,
  }
  if (block.type === "image") {
    const urls = block.images.map((image) => image.url).filter(Boolean)
    return {
      ...base,
      kind: "image",
      url: urls[0],
      urls,
      title: block.title || base.title,
      subtitle: urls.length > 1 ? `${base.subtitle} · 共 ${urls.length} 张` : base.subtitle,
    }
  }
  if (block.type === "video") return { ...base, kind: "video", url: block.url, title: block.title || base.title }
  if (block.type === "audio") {
    const tracks = resolveAudioTracks(block)
    const first = tracks[0]
    return {
      ...base,
      kind: "audio",
      url: first?.url || block.url,
      urls: tracks.map((track) => track.url),
      coverUrl: tracks.find((track) => track.coverUrl)?.coverUrl,
      title: first?.title || block.title || base.title,
    }
  }
  if (block.type === "text" || block.type === "json" || block.type === "report") {
    return { ...base, kind: "text", rawText: block.content, title: block.title || base.title }
  }
  if (block.type === "list") return { ...base, kind: "text", rawText: block.items.join("\n"), title: block.title || base.title }
  return { ...base, kind: "other", rawText: item.task.result?.contentText || "", title: base.title }
}

function openAssetPreview(item: { task: TaskDetail; blocks: ResultBlock[]; modality: string }) {
  if (item.task.status !== "SUCCESS") return
  previewAsset.value = assetFromTask(item)
}

function recommendToolsForAsset(asset: AssetPreviewItem): AssetPreviewRecommendation[] {
  const target = asset.kind === "image" ? "IMAGE" : asset.kind === "video" ? "VIDEO" : asset.kind === "audio" ? "AUDIO" : ""
  const keyword = asset.kind === "image" ? /图|图片|影像|photo|image|img|改图|参考/i : asset.kind === "video" ? /视频|短片|video|clip|movie/i : /音频|音乐|audio|voice|tts/i
  const matches = tools.value.filter((tool) => {
    const input = normalizeModality(tool.inputModality)
    const text = `${tool.toolName} ${tool.description || ""} ${cleanToolDisplayText(tool.configNote)} ${tool.toolCode}`
    return (
      (target && (input.includes(target) || input.includes("MULTIMODAL") || input.includes("FILE"))) ||
      keyword.test(text)
    )
  })
  return (matches.length ? matches : currentTools.value.length ? currentTools.value : tools.value).slice(0, 8)
}

function useAssetWithTool(tool: AssetPreviewRecommendation, asset: AssetPreviewItem) {
  selectModality(normalizeModality(tool.outputModality))
  selectedToolCode.value = tool.toolCode
  promptText.value = asset.prompt || promptText.value
  pendingAssetReplay.value = asset
  replayParams.value = buildAssetReplayParams(selectedChatTool.value?.fields || [], asset)
  previewAsset.value = null
  expandComposer()
}

function materialKindForField(field: ToolField): AssetPreviewItem["kind"] | "file" {
  if (field.fieldType === "image") return "image"
  const text = `${field.fieldKey} ${field.fieldName} ${field.placeholder || ""}`.toLowerCase()
  if (/image|img|picture|photo|frame|cover|avatar|poster|图片|图像|照片|帧|封面|首图/.test(text)) return "image"
  if (/video|clip|movie|视频|短片|影片/.test(text)) return "video"
  if (/audio|voice|sound|speech|music|音频|语音|声音|音乐/.test(text)) return "audio"
  return "file"
}

function buildAssetReplayParams(fields: ToolField[], asset: AssetPreviewItem): Record<string, unknown> {
  const params: Record<string, unknown> = {
    sourceAssetUrl: asset.url,
    sourceTaskId: asset.taskId,
    sourceTaskNo: asset.taskNo,
  }
  if (!asset.url) return params

  const mediaFields = fields.filter((field) => field.fieldType === "image" || field.fieldType === "file")
  const exact = mediaFields.find((field) => materialKindForField(field) === asset.kind)
  const fallback =
    exact ||
    mediaFields.find((field) => materialKindForField(field) === "file") ||
    mediaFields[0]
  if (fallback) params[fallback.fieldKey] = asset.url
  return params
}

function routeQueryString(name: string): string {
  const value = route.query[name]
  const raw = Array.isArray(value) ? value[0] : value
  return typeof raw === "string" ? raw.trim() : ""
}

function buildSubjectReplayParams(fields: ToolField[]): Record<string, unknown> | null {
  const elementId = routeQueryString("elementId")
  if (!elementId) return null
  const subjectField = fields.find((field) => field.fieldType === "subject_element_list")
  if (!subjectField) return null
  const subjectCode = routeQueryString("subjectCode")
  return {
    [subjectField.fieldKey]: [
      {
        element_id: elementId,
        subject_code: subjectCode || undefined,
      },
    ],
  }
}

function consumePendingAssetFromStorage(): AssetPreviewItem | null {
  return consumeDashboardPendingAsset()
}

function openPreviewTask(asset: AssetPreviewItem) {
  if (!asset.taskId) return
  void router.push(userRoutes.taskResult(String(asset.taskId)))
}

async function publishPreviewAsset(asset: AssetPreviewItem, payload?: CommunityPublishPayload) {
  if (!auth.token || !asset.taskId) return
  try {
    const post = await publishAssetToCommunity(asset, {
      token: auth.token,
      payload,
      defaultPromptVisible: auth.user?.promptPublicByDefault ?? false,
    })
    previewAsset.value = { ...asset, communityPostId: post.id, promptVisible: post.promptVisible, title: post.title }
  } catch (err) {
    const message = err instanceof Error ? err.message : "发布失败"
    window.alert(message)
  }
}

async function unpublishPreviewAsset(asset: AssetPreviewItem) {
  if (!auth.token || !asset.communityPostId) return
  try {
    await unpublishCommunityPost(asset.communityPostId, { token: auth.token })
    emitCommunityPostUnpublished({ postId: asset.communityPostId, taskId: asset.taskId })
    previewAsset.value = { ...asset, communityPostId: undefined }
  } catch (err) {
    const message = err instanceof Error ? err.message : "撤回失败"
    window.alert(message)
  }
}

function textPreview(blocks: ResultBlock[], task: TaskDetail): string {
  const block = primaryBlock(blocks)
  if (!block) return task.result?.contentText || ""
  if (block.type === "text" || block.type === "json" || block.type === "report") return block.content
  if (block.type === "list") return block.items.join("\n")
  return task.result?.contentText || ""
}

function formatTaskTime(value?: string | null): string {
  if (!value) return ""
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })
}

function replayTask(task: TaskDetail) {
  const modality = normalizeModality(task.outputModality)
  selectedModality.value = modality
  selectedToolCode.value = task.toolCode
  promptText.value = taskPrompt(task)
  replayParams.value = normalizeReplayParams(task.params || {})
  expandComposer()
}

function normalizeReplayParams(params: Record<string, unknown>): Record<string, unknown> {
  const next: Record<string, unknown> = { ...params }
  for (const [key, value] of Object.entries(next)) {
    if (typeof value === "string" && looksLikeStoredMediaUrl(value)) {
      next[key] = normalizeMediaUrl(value)
      continue
    }
    if (Array.isArray(value)) {
      next[key] = value.map((item) =>
        typeof item === "string" && looksLikeStoredMediaUrl(item) ? normalizeMediaUrl(item) : item,
      )
    }
  }
  return next
}

function looksLikeStoredMediaUrl(value: string): boolean {
  const raw = value.trim()
  if (!raw) return false
  if (raw.startsWith("/generated") || raw.startsWith("/uploads")) return true
  if (/^https?:\/\//i.test(raw)) {
    try {
      const parsed = new URL(raw)
      return parsed.pathname.startsWith("/generated") || parsed.pathname.startsWith("/uploads")
    } catch {
      return false
    }
  }
  return false
}

async function loadSelectedToolDetail(toolCode: string) {
  selectedToolDetailLoading.value = true
  try {
    selectedChatTool.value = await fetchAIToolById(toolCode, { token: auth.token })
    const subjectReplay = buildSubjectReplayParams(selectedChatTool.value.fields || [])
    if (subjectReplay) {
      replayParams.value = subjectReplay
      expandComposer()
    } else if (pendingAssetReplay.value) {
      replayParams.value = buildAssetReplayParams(selectedChatTool.value.fields || [], pendingAssetReplay.value)
      if (pendingAssetReplay.value.prompt) promptText.value = pendingAssetReplay.value.prompt
    }
  } finally {
    selectedToolDetailLoading.value = false
  }
}

function isVideoPreviewUrl(value?: string | null): boolean {
  const raw = value?.split(/[?#]/)[0]?.toLowerCase() || ""
  return [".mp4", ".webm", ".mov", ".m4v"].some((ext) => raw.endsWith(ext))
}

function modalityLabel(value?: string | null) {
  const key = normalizeModality(value)
  return modalityLabels[key] || key
}

onMounted(async () => {
  window.addEventListener("scroll", handleDashboardScroll, true)
  window.addEventListener("pointerdown", handleDashboardPointerDown, true)
  const savedHistoryView = localStorage.getItem(HISTORY_VIEW_KEY)
  if (savedHistoryView === "cards" || savedHistoryView === "feed") historyView.value = savedHistoryView
  await loadDashboard()
  setupHistoryObserver()
  await nextTick()
  if (isHistoryFeedView.value) scrollHistoryFeedToBottom("auto")
})

onUnmounted(() => {
  window.removeEventListener("scroll", handleDashboardScroll, true)
  window.removeEventListener("pointerdown", handleDashboardPointerDown, true)
  historyObserver?.disconnect()
  for (const timer of taskPollTimers.values()) window.clearInterval(timer)
  taskPollTimers.clear()
  stopProgressClock()
  stopAllTaskStatusStreams()
})
</script>

<template>
  <AppShell title="工作台" description="选模态、挑模型，让创意即刻落地">
    <div class="flex h-full min-h-0 bg-black text-white">
      <DashboardModalityDock
        v-model:open="modalityDockOpen"
        :tabs="modalityTabs"
        :selected-modality="selectedModality"
        :selected-label="modalityLabel(selectedModality)"
        :selected-icon="modalityIcons[selectedModality as keyof typeof modalityIcons] || Sparkles"
        :available-credits="credit?.available"
        :running-count="runningCount"
        @select="selectModality"
      />

      <section class="relative flex min-h-0 min-w-0 flex-1 flex-col">
        <div
          class="mx-auto mt-4 flex h-9 w-full max-w-5xl items-center justify-center rounded-full border border-[#d7b77a]/12 bg-[#d7b77a]/[0.055] px-5 text-xs font-medium text-[#ead7aa]/85 shadow-[inset_0_1px_0_rgb(255_255_255_/_0.04)] backdrop-blur-xl"
        >
          SVIP限时体验 · 选择模态后模型列表会自动切换
        </div>

        <div class="absolute right-6 top-8 z-10 hidden items-center gap-2 rounded-2xl border border-white/10 bg-[#12131a]/90 p-2 backdrop-blur md:flex">
          <button class="flex h-9 w-9 items-center justify-center rounded-xl text-white/70 hover:bg-white/8 hover:text-white" type="button">
            <Search class="h-5 w-5" />
          </button>
          <span class="h-5 w-px bg-white/10" />
          <RouterLink
            :to="userRoutes.toolList"
            class="flex h-9 w-9 items-center justify-center rounded-xl text-white/70 hover:bg-white/8 hover:text-white"
          >
            <Store class="h-5 w-5" />
          </RouterLink>
        </div>

        <main
          ref="dashboardMainRef"
          class="min-h-0 flex-1 overflow-y-auto px-5 pb-40 pt-6 lg:pl-[132px] xl:px-10 xl:pl-[132px]"
          @scroll="handleDashboardScroll"
        >
          <div class="mx-auto w-full max-w-[1380px]">
            <div class="relative w-full">
              <div
                class="flex flex-wrap items-center justify-between gap-4"
                :class="activePanel === 'tasks' ? 'mb-8' : 'mb-4'"
              >
                <div class="min-w-0">
                  <template v-if="activePanel === 'tasks'">
                    <p class="text-xs font-medium uppercase tracking-[0.18em] text-white/32">HISTORY</p>
                    <h2 class="mt-1 text-xl font-semibold text-white">工作历史</h2>
                    <p class="mt-1 hidden font-mono text-[12px] leading-5 text-white/36 sm:block">
                      {{ currentTools.length }} 个可用模型 · 可用算力 {{ credit?.available ?? "--" }} · 进行中 {{ runningCount }}
                    </p>
                  </template>
                  <div v-else class="flex min-w-0 flex-col">
                    <h1 class="text-2xl font-bold tracking-wide text-white/90">
                      {{ modalityLabel(selectedModality) }}创作
                    </h1>
                    <p class="mt-1 text-xs text-white/40">
                      挑选一个适合的 AI 模型，开启你的创作灵感。
                    </p>
                  </div>
                </div>
                <div class="flex flex-wrap items-center justify-end gap-3">
                  <div
                    v-if="activePanel !== 'tasks'"
                    class="hidden text-right font-mono text-[12px] leading-5 text-white/36 sm:block"
                  >
                    <p>{{ currentTools.length }} 个可用模型 · 可用算力 {{ credit?.available ?? "--" }} · 进行中 {{ runningCount }}</p>
                  </div>
                  <div
                    v-if="activePanel === 'tasks'"
                    class="h-8 w-[110px] shrink-0"
                    aria-hidden="true"
                  />
                  <button
                    v-else
                    type="button"
                    class="rounded-full border border-white/10 bg-white/[0.035] px-5 py-2.5 text-sm font-medium text-white/55 transition hover:bg-white/[0.06] hover:text-white"
                    @click="activePanel = 'tasks'"
                  >
                    工作历史
                    <span class="ml-1 text-xs opacity-70">{{ recentTasks.length }}</span>
                  </button>
                </div>
              </div>

              <div
                v-if="activePanel === 'tasks'"
                class="pointer-events-none absolute right-0 top-4 bottom-0 z-30 w-[120px]"
              >
                <div class="pointer-events-auto sticky top-4 flex justify-end">
                  <button
                    type="button"
                    class="rounded-full border border-white/10 bg-zinc-800 px-4 py-2 text-xs font-semibold text-white/90 shadow-[0_4px_20px_rgba(0,0,0,0.5)] transition-all hover:scale-[1.02] hover:bg-zinc-700 active:scale-[0.98]"
                    @click="activePanel = 'models'"
                  >
                    返回创作
                    <span class="ml-1 opacity-70">{{ recentTasks.length }}</span>
                  </button>
                </div>
              </div>

            <section>
              <div v-if="activePanel === 'models'" class="space-y-6">
                <div class="grid gap-6 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
                <article
                  v-for="tool in visibleFeaturedTools"
                  :key="tool.id"
                  class="marketplace-tool-card"
                  @click="selectTool(tool)"
                >
                  <div class="marketplace-tool-media">
                    <video
                      v-if="isVideoPreviewUrl(toolCardCover(tool))"
                      :src="normalizeMediaUrl(toolCardCover(tool))"
                      class="marketplace-tool-image"
                      muted
                      loop
                      autoplay
                      playsinline
                      preload="metadata"
                    />
                    <img
                      v-else-if="toolCardCover(tool)"
                      :src="normalizeMediaUrl(toolCardCover(tool))"
                      :alt="tool.toolName"
                      class="marketplace-tool-image"
                      @error="onToolCoverError(tool)"
                    />
                    <div v-else class="marketplace-tool-empty">
                      <Sparkles class="h-10 w-10 text-white/48" />
                    </div>
                    <span class="marketplace-modality-badge">{{ modalityLabel(tool.outputModality) }}</span>
                  </div>
                  <div class="marketplace-tool-overlay">
                    <div class="marketplace-tool-content">
                      <h3 class="marketplace-tool-title">{{ tool.toolName }}</h3>
                      <div class="marketplace-hover-reveal">
                        <p class="marketplace-tool-desc">{{ toolDisplayDescription(tool, "点击选择模型后开始创作。") }}</p>
                        <div class="marketplace-start-button">
                          开始创作
                          <ExternalLink class="h-3.5 w-3.5" />
                        </div>
                      </div>
                    </div>
                  </div>
                </article>
                </div>
                <div v-if="hasMoreFeaturedTools" class="flex justify-center pt-2">
                  <button
                    type="button"
                    class="inline-flex items-center gap-2 rounded-xl border border-white/10 bg-white/[0.04] px-5 py-2.5 text-sm text-white/70 transition hover:border-white/18 hover:bg-white/[0.07] hover:text-white"
                    @click="featuredToolsExpanded = !featuredToolsExpanded"
                  >
                    {{
                      featuredToolsExpanded
                        ? "收起"
                        : `查看更多 (${hiddenFeaturedToolCount})`
                    }}
                    <ChevronDown
                      class="h-4 w-4 transition"
                      :class="featuredToolsExpanded ? 'rotate-180' : ''"
                    />
                  </button>
                </div>
              </div>

              <div v-else>
                <div v-if="loading" class="flex justify-center py-8 text-white/45">
                  <Loader2 class="h-5 w-5 animate-spin" />
                </div>
                <div v-else-if="recentTasks.length === 0" class="rounded-2xl border border-dashed border-white/10 py-10 text-center text-sm text-white/45">
                  暂无任务，选择模型后开始第一条创作。
                </div>
                <div v-else-if="historyView === 'cards' && audioWorkbenchVisible" class="grid gap-4 xl:grid-cols-[minmax(420px,0.9fr)_minmax(0,1.35fr)]">
                  <audio
                    ref="audioElementRef"
                    class="hidden"
                    preload="metadata"
                    @loadedmetadata="onDashboardAudioLoaded"
                    @timeupdate="onDashboardAudioTimeUpdate"
                    @play="audioPlaying = true"
                    @pause="audioPlaying = false"
                    @ended="onDashboardAudioEnded"
                  />
                  <div class="overflow-hidden rounded-2xl border border-white/8 bg-[#151515]">
                    <div class="flex items-center justify-between border-b border-white/8 px-4 py-3">
                      <div>
                        <p class="text-sm font-semibold text-white">音乐任务</p>
                        <p class="text-xs text-white/40">{{ audioStatusMaterials.length }} 个任务处理中 · {{ audioRows.length }} 个版本可试听</p>
                      </div>
                      <span class="rounded-full bg-primary/12 px-2.5 py-1 text-xs text-primary">List</span>
                    </div>
                    <div class="max-h-[640px] divide-y divide-white/[0.06] overflow-y-auto">
                      <div
                        v-for="item in audioStatusMaterials"
                        :key="`audio-task-${item.task.taskId}`"
                        class="group px-3 py-3 transition hover:bg-white/[0.045]"
                        @click.stop="scrollToTop"
                      >
                        <div class="grid grid-cols-[60px_minmax(0,1fr)_auto] items-center gap-3">
                          <!-- 圆环进度指示器 -->
                          <div class="relative h-12 w-12 shrink-0">
                            <svg class="h-full w-full -rotate-90" viewBox="0 0 100 100">
                              <circle
                                cx="50"
                                cy="50"
                                r="42"
                                fill="none"
                                stroke="rgba(255, 255, 255, 0.1)"
                                stroke-width="8"
                              />
                              <circle
                                cx="50"
                                cy="50"
                                r="42"
                                fill="none"
                                :stroke="canRetryTask(item.task.status) ? '#f87171' : '#b05cff'"
                                stroke-width="8"
                                stroke-linecap="round"
                                :stroke-dasharray="`${Math.max(6, taskProgressView(item.task).percent) * 2.64} 264`"
                                class="transition-all duration-500 ease-out"
                              />
                            </svg>
                            <div class="absolute inset-0 flex items-center justify-center">
                              <Loader2 v-if="isTaskRunning(item.task.status)" class="h-4 w-4 animate-spin text-primary/60" />
                              <X v-else-if="canRetryTask(item.task.status)" class="h-4 w-4 text-red-400" />
                              <Clock v-else class="h-4 w-4 text-white/40" />
                            </div>
                          </div>
                          <div class="min-w-0">
                            <div class="flex items-center gap-2">
                              <p class="truncate text-sm font-medium text-white">{{ item.task.toolName }}</p>
                              <span
                                class="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium"
                                :class="canRetryTask(item.task.status) ? 'bg-red-500/15 text-red-100' : 'bg-primary/15 text-primary'"
                              >
                                {{ taskStatusLabel(item.task.status) }}
                              </span>
                            </div>
                            <p class="mt-0.5 truncate text-xs text-white/38">
                              {{ taskProgressSubtitle(item.task, canRetryTask(item.task.status) ? "任务生成失败，可以重试。" : "任务正在生成，完成后自动展开版本。") }}
                            </p>
                          </div>
                          <span class="text-xs tabular-nums text-white/40">{{ taskProgressView(item.task).percentLabel }}</span>
                        </div>
                        <div class="mt-3 flex items-center justify-between gap-2">
                          <p class="truncate text-xs text-white/30">{{ item.task.taskNo }}</p>
                          <div class="flex shrink-0 items-center gap-1 opacity-0 transition group-hover:opacity-100">
                            <button
                              v-if="canCancelTask(item.task.status)"
                              type="button"
                              class="rounded-full bg-white/8 px-2.5 py-1 text-xs font-medium text-white/60 transition hover:bg-white/14 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                              :disabled="
                                cancellingTaskIds.has(item.task.taskId) ||
                                deletingTaskIds.has(item.task.taskId) ||
                                retryingTaskIds.has(item.task.taskId)
                              "
                              @click.stop="cancelQueuedTask(item.task)"
                            >
                              {{ cancellingTaskIds.has(item.task.taskId) ? "取消中" : "取消" }}
                            </button>
                            <button
                              v-if="canRetryTask(item.task.status)"
                              type="button"
                              class="rounded-full bg-red-500/15 px-2.5 py-1 text-xs font-medium text-red-100 transition hover:bg-red-500 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                              :disabled="
                                retryingTaskIds.has(item.task.taskId) ||
                                deletingTaskIds.has(item.task.taskId) ||
                                cancellingTaskIds.has(item.task.taskId)
                              "
                              @click.stop="retryTask(item.task)"
                            >
                              {{ retryingTaskIds.has(item.task.taskId) ? "重试中" : "重试" }}
                            </button>
                            <button
                              v-else-if="!canCancelTask(item.task.status)"
                              type="button"
                              class="rounded-full bg-primary/15 px-2.5 py-1 text-xs font-medium text-primary transition hover:bg-primary hover:text-white"
                              @click.stop="replayTask(item.task)"
                            >
                              再次生成
                            </button>
                            <button
                              v-if="canDeleteTask(item.task.status)"
                              type="button"
                              class="inline-flex h-7 w-7 items-center justify-center rounded-full bg-white/8 text-white/45 transition hover:bg-white/14 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                              :disabled="
                                deletingTaskIds.has(item.task.taskId) ||
                                retryingTaskIds.has(item.task.taskId) ||
                                cancellingTaskIds.has(item.task.taskId)
                              "
                              @click.stop="removeTask(item.task)"
                            >
                              <Loader2 v-if="deletingTaskIds.has(item.task.taskId)" class="h-3.5 w-3.5 animate-spin" />
                              <Trash2 v-else class="h-3.5 w-3.5" />
                            </button>
                          </div>
                        </div>
                      </div>
                      <button
                        v-for="track in audioRows"
                        :key="track.id"
                        type="button"
                        class="group grid w-full grid-cols-[auto_40px_minmax(110px,1fr)_minmax(130px,0.85fr)_auto_auto] items-center gap-3 px-3 py-3 text-left transition hover:bg-white/[0.045]"
                        :class="activeAudioTrack?.id === track.id ? 'bg-primary/[0.08] ring-1 ring-inset ring-primary/25' : ''"
                        @click="setActiveAudio(track)"
                      >
                        <span
                          class="flex h-9 w-9 items-center justify-center rounded-full border border-white/10 bg-black/35 text-white transition group-hover:border-primary/40 group-hover:text-primary"
                          @click.stop="setActiveAudio(track, true)"
                        >
                          <Pause v-if="activeAudioTrack?.id === track.id && audioPlaying" class="h-4 w-4" />
                          <Play v-else class="h-4 w-4 translate-x-px" />
                        </span>
                        <span class="relative h-10 w-10 overflow-hidden rounded-lg bg-white/8">
                          <img
                            v-if="track.coverUrl"
                            :src="track.coverUrl"
                            :alt="audioTaskTitle(track)"
                            class="h-full w-full object-cover"
                            loading="lazy"
                          />
                          <span v-else class="flex h-full w-full items-center justify-center bg-primary/15 text-primary">
                            <Music class="h-4 w-4" />
                          </span>
                          <span
                            v-if="activeAudioTrack?.id === track.id && audioPlaying"
                            class="absolute inset-x-1 bottom-1 flex h-3 items-end justify-center gap-0.5 rounded bg-black/45 px-1"
                          >
                            <i v-for="bar in 4" :key="bar" class="audio-viz-bar" />
                          </span>
                        </span>
                        <span class="min-w-0">
                          <span class="block truncate text-sm font-medium text-white">{{ audioTaskTitle(track) }}</span>
                          <span class="mt-0.5 block truncate text-xs text-white/38">
                            {{ track.totalVersions > 1 ? `Version ${track.version}` : "Suno-Music" }} · {{ formatTaskTime(track.createdAt) }}
                          </span>
                        </span>
                        <span
                          class="audio-wave-hit flex h-8 items-center gap-0.5"
                          @click.stop="seekDashboardAudio($event, track)"
                        >
                          <i
                            v-for="(bar, index) in waveformBars(track, 28)"
                            :key="`${track.id}-row-${index}`"
                            class="audio-wave-bar"
                            :class="activeAudioTrack?.id === track.id && index / 28 <= activeAudioProgress ? 'is-played' : ''"
                            :style="{ height: `${bar}%` }"
                          />
                        </span>
                        <span class="whitespace-nowrap text-xs tabular-nums text-white/45">
                          {{ activeAudioTrack?.id === track.id ? activeAudioTimeLabel(track) : `0:00 / ${formatAudioDuration(track.duration) || "--:--"}` }}
                        </span>
                        <span class="flex items-center justify-end gap-1 opacity-0 transition group-hover:opacity-100">
                          <button
                            type="button"
                            class="inline-flex h-8 w-8 items-center justify-center rounded-full text-white/45 transition hover:bg-white/10 hover:text-white"
                            title="下载"
                            @click.stop="forceDownload(track.downloadUrl || normalizeMediaUrl(track.url), track.downloadName || `audio-${track.version}`)"
                          >
                            <Download class="h-4 w-4" />
                          </button>
                          <span
                            class="inline-flex h-8 w-8 items-center justify-center rounded-full text-white/45 transition hover:bg-white/10 hover:text-white"
                            title="更多"
                          >
                            <MoreHorizontal class="h-4 w-4" />
                          </span>
                        </span>
                      </button>
                    </div>
                  </div>

                  <section class="min-h-[640px] overflow-hidden rounded-2xl border border-white/8 bg-[#111]">
                    <div class="grid min-h-[640px] grid-rows-[auto_1fr_auto]">
                      <div class="flex items-start justify-between gap-4 border-b border-white/8 p-5">
                        <div class="min-w-0">
                          <p class="text-xs font-medium uppercase tracking-[0.18em] text-primary/80">Audio Stage</p>
                          <h3 class="mt-2 truncate text-2xl font-semibold text-white">
                            {{ activeAudioTrack ? audioTaskTitle(activeAudioTrack) : (primaryAudioStatusItem?.task.toolName || "音乐生成") }}
                          </h3>
                          <p class="mt-1 text-sm text-white/45">
                            {{ activeAudioTrack ? audioTaskSubtitle(activeAudioTrack) : (primaryAudioStatusItem ? taskProgressSubtitle(primaryAudioStatusItem.task, "任务正在生成，完成后会自动出现在左侧列表。") : "任务正在生成，完成后会自动出现在左侧列表。") }}
                          </p>
                        </div>
                        <div v-if="activeAudioTrack" class="flex shrink-0 gap-2">
                          <button
                            type="button"
                            class="rounded-xl border border-white/10 bg-white/[0.04] px-3 py-2 text-sm font-medium text-white/70 transition hover:border-primary/40 hover:text-white"
                            @click="activeAudioTrack && replayTask(activeAudioTrack.task)"
                          >
                            Remix
                          </button>
                          <button
                            type="button"
                            class="rounded-xl bg-primary px-3 py-2 text-sm font-semibold text-white transition hover:brightness-110"
                            @click="activeAudioTrack && replayTask(activeAudioTrack.task)"
                          >
                            Extend
                          </button>
                        </div>
                      </div>

                      <template v-if="activeAudioTrack">
                      <div class="grid gap-5 p-5 lg:grid-cols-[280px_minmax(0,1fr)]">
                        <div class="space-y-4">
                          <div class="relative aspect-square overflow-hidden rounded-2xl bg-white/8">
                            <img
                              v-if="activeAudioTrack?.coverUrl"
                              :src="activeAudioTrack.coverUrl"
                              :alt="audioTaskTitle(activeAudioTrack)"
                              class="h-full w-full object-cover"
                            />
                            <div v-else class="flex h-full w-full items-center justify-center bg-[radial-gradient(circle_at_30%_20%,rgb(176_92_255_/_0.35),transparent_38%),linear-gradient(145deg,rgb(34_34_42),rgb(8_8_10))]">
                              <Music class="h-16 w-16 text-primary" />
                            </div>
                            <div class="absolute inset-0 bg-gradient-to-t from-black/70 via-transparent to-transparent" />
                            <button
                              type="button"
                              class="absolute bottom-4 left-4 flex h-14 w-14 items-center justify-center rounded-full bg-white text-black shadow-[0_18px_36px_rgb(0_0_0_/_0.35)] transition hover:scale-105"
                              @click="toggleDashboardAudio()"
                            >
                              <Pause v-if="audioPlaying" class="h-6 w-6" />
                              <Play v-else class="h-6 w-6 translate-x-0.5" />
                            </button>
                          </div>
                          <div class="grid grid-cols-2 gap-2 text-xs text-white/45">
                            <div class="rounded-xl bg-white/[0.04] p-3">
                              <p>生成时间</p>
                              <p class="mt-1 font-medium text-white/80">{{ formatTaskTime(activeAudioTrack?.createdAt) || "-" }}</p>
                            </div>
                            <div class="rounded-xl bg-white/[0.04] p-3">
                              <p>版本</p>
                              <p class="mt-1 font-medium text-white/80">{{ activeAudioTrack ? `${activeAudioTrack.version}/${activeAudioTrack.totalVersions}` : "-" }}</p>
                            </div>
                          </div>
                        </div>

                        <div class="flex min-w-0 flex-col gap-5">
                          <div class="rounded-2xl border border-white/8 bg-black/22 p-5">
                            <div
                              class="audio-wave-hit flex h-28 items-center gap-1"
                              @click="seekDashboardAudio($event)"
                            >
                              <i
                                v-for="(bar, index) in waveformBars(activeAudioTrack, 96)"
                                :key="`stage-${activeAudioTrack?.id || 'empty'}-${index}`"
                                class="audio-wave-bar stage"
                                :class="index / 96 <= activeAudioProgress ? 'is-played' : ''"
                                :style="{ height: `${bar}%` }"
                              />
                            </div>
                            <div class="mt-3 flex items-center justify-between text-xs tabular-nums text-white/45">
                              <span>{{ formatAudioDuration(audioCurrentTime) || "0:00" }}</span>
                              <span>{{ formatAudioDuration(normalizedAudioDuration(activeAudioTrack)) || "--:--" }}</span>
                            </div>
                            <div class="mt-4 flex items-center gap-3 border-t border-white/8 pt-4">
                              <Volume2 class="h-4 w-4 shrink-0 text-white/45" />
                              <input
                                v-model.number="audioVolume"
                                type="range"
                                min="0"
                                max="1"
                                step="0.01"
                                class="audio-volume-slider"
                                aria-label="音量"
                              />
                              <span class="w-10 text-right text-xs tabular-nums text-white/45">{{ Math.round(audioVolume * 100) }}%</span>
                            </div>
                          </div>
                          <div class="rounded-2xl border border-white/8 bg-white/[0.035] p-5">
                            <div class="flex items-center justify-between gap-3">
                              <p class="text-sm font-semibold text-white">歌词面板</p>
                              <button
                                type="button"
                                class="rounded-lg bg-white/[0.06] px-2.5 py-1 text-xs font-medium text-white/55 transition hover:bg-white/10 hover:text-white"
                                @click="lyricsExpanded = !lyricsExpanded"
                              >
                                {{ lyricsExpanded ? "收起" : "展开" }}
                              </button>
                            </div>
                            <p
                              class="mt-3 whitespace-pre-line text-sm leading-7 text-white/48 transition-all"
                              :class="lyricsExpanded ? 'max-h-72 overflow-y-auto pr-2' : 'line-clamp-2'"
                            >
                                {{ taskPrompt(activeAudioTrack?.task || ({} as TaskDetail)) || "当前任务没有返回歌词文本。" }}
                            </p>
                          </div>
                        </div>
                      </div>

                      <div class="flex flex-wrap items-center justify-between gap-3 border-t border-white/8 p-5">
                        <div class="text-xs text-white/38">{{ activeAudioTrack?.taskNo }}</div>
                        <div class="flex gap-2">
                          <button
                            type="button"
                            class="rounded-xl bg-white/[0.06] px-3 py-2 text-sm font-medium text-white/65 transition hover:bg-white/10 hover:text-white"
                            @click="activeAudioTrack && openAssetPreview({ task: activeAudioTrack.task, blocks: buildTaskResultBlocks(activeAudioTrack.task.result?.contentText || '', activeAudioTrack.task), modality: '音频' })"
                          >
                            资产操作
                          </button>
                          <RouterLink
                            v-if="activeAudioTrack"
                            :to="userRoutes.taskResult(String(activeAudioTrack.taskId))"
                            class="inline-flex items-center gap-1 rounded-xl bg-white/[0.06] px-3 py-2 text-sm font-medium text-white/65 transition hover:bg-white/10 hover:text-white"
                          >
                            完整内容
                            <ArrowRight class="h-4 w-4" />
                          </RouterLink>
                        </div>
                      </div>
                      </template>
                      <div v-else class="flex flex-col justify-center p-8">
                        <!-- 圆环进度样式 -->
                        <div
                          class="relative overflow-hidden rounded-2xl border border-white/8 bg-black/40 transition hover:border-primary/30"
                          @click.stop="scrollToTop"
                        >
                          <div class="relative aspect-[16/9] w-full bg-gradient-to-br from-gray-900 via-black to-gray-900 p-6">
                            <div class="absolute inset-0 bg-gradient-to-t from-black/80 via-transparent to-transparent" />
                            
                            <div class="relative z-10 flex h-full flex-col items-center justify-center gap-4">
                              <!-- SVG圆环进度条 -->
                              <div class="relative h-28 w-28">
                                <svg class="h-full w-full -rotate-90" viewBox="0 0 100 100">
                                  <circle
                                    cx="50"
                                    cy="50"
                                    r="42"
                                    fill="none"
                                    stroke="rgba(255, 255, 255, 0.1)"
                                    stroke-width="6"
                                  />
                                  <circle
                                    cx="50"
                                    cy="50"
                                    r="42"
                                    fill="none"
                                    :stroke="canRetryTask(primaryAudioStatusItem?.task.status) ? '#f87171' : '#b05cff'"
                                    stroke-width="6"
                                    stroke-linecap="round"
                                    :stroke-dasharray="`${Math.max(6, primaryAudioStatusItem ? taskProgressView(primaryAudioStatusItem.task).percent : 0) * 2.64} 264`"
                                    class="transition-all duration-500 ease-out"
                                  />
                                </svg>
                                <div class="absolute inset-0 flex flex-col items-center justify-center">
                                  <span class="text-2xl font-bold text-white tabular-nums">{{ primaryAudioStatusItem ? taskProgressView(primaryAudioStatusItem.task).percent : 0 }}%</span>
                                  <Loader2 v-if="isTaskRunning(primaryAudioStatusItem?.task.status)" class="mt-1 h-4 w-4 animate-spin text-primary/60" />
                                  <X v-else-if="canRetryTask(primaryAudioStatusItem?.task.status)" class="mt-1 h-4 w-4 text-red-400" />
                                  <Clock v-else class="mt-1 h-4 w-4 text-white/40" />
                                </div>
                              </div>
                              
                              <!-- 状态标签 -->
                              <span
                                class="inline-flex items-center gap-2 rounded-full px-4 py-1.5 text-sm font-medium shadow-lg backdrop-blur-sm"
                                :class="
                                  canRetryTask(primaryAudioStatusItem?.task.status)
                                    ? 'bg-red-500/20 text-red-100 ring-1 ring-red-400/30'
                                    : 'bg-primary/20 text-primary ring-1 ring-primary/30'
                                "
                              >
                                <Loader2 v-if="isTaskRunning(primaryAudioStatusItem?.task.status)" class="h-4 w-4 animate-spin" />
                                <X v-else-if="canRetryTask(primaryAudioStatusItem?.task.status)" class="h-4 w-4" />
                                <Clock v-else class="h-4 w-4" />
                                {{ taskStatusLabel(primaryAudioStatusItem?.task.status) }}
                              </span>
                              
                              <!-- 进度说明文字 -->
                              <p class="max-w-xs text-center text-sm text-white/50">
                                {{ primaryAudioStatusItem ? taskProgressSubtitle(primaryAudioStatusItem.task, canRetryTask(primaryAudioStatusItem.task.status) ? "任务生成失败，可以复用参数重试。" : "音乐生成中，完成后会展示版本列表和波形播放器。") : "音乐生成中，完成后会展示版本列表和波形播放器。" }}
                              </p>
                            </div>
                          </div>
                          
                          <!-- 底部操作栏 -->
                          <div class="flex items-center justify-between border-t border-white/8 px-4 py-3">
                            <p class="truncate text-xs text-white/30">{{ primaryAudioStatusItem?.task.taskNo }}</p>
                            <div v-if="primaryAudioStatusItem" class="flex shrink-0 items-center gap-2">
                              <button
                                v-if="canCancelTask(primaryAudioStatusItem.task.status)"
                                type="button"
                                class="rounded-full bg-white/8 px-3 py-1.5 text-xs font-medium text-white/60 transition hover:bg-white/14 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                                :disabled="
                                  cancellingTaskIds.has(primaryAudioStatusItem.task.taskId) ||
                                  deletingTaskIds.has(primaryAudioStatusItem.task.taskId) ||
                                  retryingTaskIds.has(primaryAudioStatusItem.task.taskId)
                                "
                                @click.stop="cancelQueuedTask(primaryAudioStatusItem.task)"
                              >
                                {{ cancellingTaskIds.has(primaryAudioStatusItem.task.taskId) ? "取消中" : "取消" }}
                              </button>
                              <button
                                v-if="canRetryTask(primaryAudioStatusItem.task.status)"
                                type="button"
                                class="rounded-full bg-red-500/15 px-3 py-1.5 text-xs font-medium text-red-100 transition hover:bg-red-500 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                                :disabled="
                                  retryingTaskIds.has(primaryAudioStatusItem.task.taskId) ||
                                  deletingTaskIds.has(primaryAudioStatusItem.task.taskId) ||
                                  cancellingTaskIds.has(primaryAudioStatusItem.task.taskId)
                                "
                                @click.stop="retryTask(primaryAudioStatusItem.task)"
                              >
                                {{ retryingTaskIds.has(primaryAudioStatusItem.task.taskId) ? "重试中" : "重试" }}
                              </button>
                              <button
                                v-else-if="!canCancelTask(primaryAudioStatusItem.task.status)"
                                type="button"
                                class="rounded-full bg-primary/15 px-3 py-1.5 text-xs font-medium text-primary transition hover:bg-primary hover:text-white"
                                @click.stop="replayTask(primaryAudioStatusItem.task)"
                              >
                                再次生成
                              </button>
                              <button
                                v-if="canDeleteTask(primaryAudioStatusItem.task.status)"
                                type="button"
                                class="inline-flex h-8 w-8 items-center justify-center rounded-full bg-white/8 text-white/45 transition hover:bg-white/14 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                                :disabled="
                                  deletingTaskIds.has(primaryAudioStatusItem.task.taskId) ||
                                  retryingTaskIds.has(primaryAudioStatusItem.task.taskId) ||
                                  cancellingTaskIds.has(primaryAudioStatusItem.task.taskId)
                                "
                                @click.stop="removeTask(primaryAudioStatusItem.task)"
                              >
                                <Loader2 v-if="deletingTaskIds.has(primaryAudioStatusItem.task.taskId)" class="h-4 w-4 animate-spin" />
                                <Trash2 v-else class="h-4 w-4" />
                              </button>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </section>
                </div>
                <div v-else-if="historyView === 'feed'" class="dashboard-chat-feed">
                  <div ref="historyFeedStartRef" class="flex min-h-14 items-center justify-center py-4 text-sm text-white/42">
                    <template v-if="tasksLoadingMore">
                      <Loader2 class="mr-2 h-4 w-4 animate-spin" />
                      正在加载更早的工作历史...
                    </template>
                    <template v-else-if="taskHasNext">
                      上滑加载更早历史
                    </template>
                    <template v-else-if="recentTasks.length > 0">
                      已到最早的工作历史
                    </template>
                  </div>
                  <article
                    v-for="item in historyFeedMaterials"
                    :key="`feed-${item.task.taskId}`"
                    class="dashboard-chat-row"
                  >
                    <header class="flex flex-wrap items-start justify-between gap-4">
                      <div class="flex min-w-0 items-center gap-3">
                        <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border border-emerald-400/20 bg-[#12241d] text-sm font-black text-emerald-300 shadow-[inset_0_1px_0_rgb(255_255_255_/_0.08)]">
                          P
                        </span>
                        <div class="min-w-0">
                          <div class="flex flex-wrap items-center gap-2">
                            <strong class="text-base font-semibold text-white">科创点AI</strong>
                            <span class="h-4 w-px bg-white/12" />
                            <span class="rounded-md border border-white/8 bg-white/[0.045] px-2 py-0.5 text-xs font-medium text-white/62">
                              {{ item.modality }}
                            </span>
                            <span class="rounded-md border border-white/8 bg-white/[0.045] px-2 py-0.5 text-xs font-medium text-white/62">
                              {{ taskModelTag(item.task) }}
                            </span>
                          </div>
                          <p class="mt-1 text-xs text-white/32">{{ item.task.taskNo }}</p>
                        </div>
                      </div>
                      <div class="flex shrink-0 items-center gap-2 text-xs text-white/38">
                        <span
                          class="rounded-full px-2 py-1"
                          :class="canRetryTask(item.task.status) ? 'bg-red-500/12 text-red-100' : 'bg-white/[0.045] text-white/52'"
                        >
                          {{ taskStatusLabel(item.task.status) }}
                        </span>
                        <Clock class="h-3.5 w-3.5" />
                        {{ formatTaskTime(item.task.createdAt) }}
                      </div>
                    </header>

                    <section class="mt-4">
                      <div v-if="taskPrompt(item.task)" class="flex items-start gap-3">
                        <MessageSquareText class="mt-1 h-4 w-4 shrink-0 text-white/32" />
                        <div class="min-w-0 flex-1">
                          <p
                            class="whitespace-pre-wrap text-sm font-medium leading-7 text-white/72"
                            :class="isPromptExpanded(item.task.taskId) ? '' : 'line-clamp-2'"
                          >
                            {{ taskPrompt(item.task) }}
                          </p>
                          <button
                            v-if="taskPrompt(item.task).length > 88"
                            type="button"
                            class="dashboard-feed-prompt-toggle"
                            @click.stop="togglePrompt(item.task.taskId)"
                          >
                            {{ isPromptExpanded(item.task.taskId) ? "收起提示词" : "展开提示词" }}
                            <ChevronDown class="h-3.5 w-3.5 transition-transform" :class="{ 'rotate-180': isPromptExpanded(item.task.taskId) }" />
                          </button>
                        </div>
                      </div>
                      <p v-else class="text-sm text-white/38">本次任务未记录提示词。</p>
                    </section>

                    <section class="mt-5">
                      <!-- 生成中的任务卡片 - 圆环进度样式 -->
                      <div
                        v-if="isTaskRunning(item.task.status) || canRetryTask(item.task.status) || (!item.task.result?.contentText && item.task.status !== 'SUCCESS')"
                        class="relative overflow-hidden rounded-2xl border border-white/8 bg-black/40 transition hover:border-primary/30"
                        @click.stop="scrollToTop"
                      >
                        <!-- 黑色占位背景 -->
                        <div
                          class="relative aspect-[4/3] w-full bg-gradient-to-br from-gray-900 via-black to-gray-900 p-6"
                          :class="canRetryTask(item.task.status) ? 'ring-1 ring-red-400/25' : ''"
                        >
                          <!-- 渐变光晕效果 -->
                          <div class="absolute inset-0 bg-gradient-to-t from-black/80 via-transparent to-transparent" />
                          
                          <!-- 圆环进度指示器 -->
                          <div class="relative z-10 flex h-full flex-col items-center justify-center gap-4">
                            <!-- SVG圆环进度条 -->
                            <div class="relative h-28 w-28">
                              <svg class="h-full w-full -rotate-90" viewBox="0 0 100 100">
                                <!-- 背景圆环 -->
                                <circle
                                  cx="50"
                                  cy="50"
                                  r="42"
                                  fill="none"
                                  stroke="rgba(255, 255, 255, 0.1)"
                                  stroke-width="6"
                                />
                                <!-- 进度圆环 -->
                                <circle
                                  cx="50"
                                  cy="50"
                                  r="42"
                                  fill="none"
                                  :stroke="canRetryTask(item.task.status) ? '#f87171' : '#b05cff'"
                                  stroke-width="6"
                                  stroke-linecap="round"
                                  :stroke-dasharray="`${Math.max(6, taskProgressView(item.task).percent) * 2.64} 264`"
                                  class="transition-all duration-500 ease-out"
                                />
                              </svg>
                              <!-- 中心百分比文字 -->
                              <div class="absolute inset-0 flex flex-col items-center justify-center">
                                <span class="text-2xl font-bold text-white tabular-nums">{{ taskProgressView(item.task).percent }}%</span>
                                <Loader2 v-if="isTaskRunning(item.task.status)" class="mt-1 h-4 w-4 animate-spin text-primary/60" />
                                <X v-else-if="canRetryTask(item.task.status)" class="mt-1 h-4 w-4 text-red-400" />
                                <Clock v-else class="mt-1 h-4 w-4 text-white/40" />
                              </div>
                            </div>
                            
                            <!-- 状态标签 -->
                            <span
                              class="inline-flex items-center gap-2 rounded-full px-4 py-1.5 text-sm font-medium shadow-lg backdrop-blur-sm"
                              :class="
                                canRetryTask(item.task.status)
                                  ? 'bg-red-500/20 text-red-100 ring-1 ring-red-400/30'
                                  : 'bg-primary/20 text-primary ring-1 ring-primary/30'
                              "
                            >
                              <Loader2 v-if="isTaskRunning(item.task.status)" class="h-4 w-4 animate-spin" />
                              <X v-else-if="canRetryTask(item.task.status)" class="h-4 w-4" />
                              <Clock v-else class="h-4 w-4" />
                              {{ taskStatusLabel(item.task.status) }}
                            </span>
                            
                            <!-- 进度说明文字 -->
                            <p class="max-w-xs text-center text-sm text-white/50">
                              {{ taskProgressSubtitle(item.task, canRetryTask(item.task.status) ? "任务生成失败，可以复用本次参数重试。" : "任务正在生成，完成后会追加到信息流底部。") }}
                            </p>
                          </div>
                        </div>
                        
                        <!-- 底部操作栏 -->
                        <div class="flex items-center justify-between border-t border-white/8 px-4 py-3">
                          <p class="truncate text-xs text-white/30">{{ item.task.taskNo }}</p>
                          <div class="flex shrink-0 items-center gap-2">
                            <button
                              v-if="canCancelTask(item.task.status)"
                              type="button"
                              class="rounded-full bg-white/8 px-3 py-1.5 text-xs font-medium text-white/60 transition hover:bg-white/14 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                              :disabled="
                                cancellingTaskIds.has(item.task.taskId) ||
                                deletingTaskIds.has(item.task.taskId) ||
                                retryingTaskIds.has(item.task.taskId)
                              "
                              @click.stop="cancelQueuedTask(item.task)"
                            >
                              {{ cancellingTaskIds.has(item.task.taskId) ? "取消中" : "取消" }}
                            </button>
                            <button
                              v-if="canRetryTask(item.task.status)"
                              type="button"
                              class="rounded-full bg-red-500/15 px-3 py-1.5 text-xs font-medium text-red-100 transition hover:bg-red-500 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                              :disabled="
                                retryingTaskIds.has(item.task.taskId) ||
                                deletingTaskIds.has(item.task.taskId) ||
                                cancellingTaskIds.has(item.task.taskId)
                              "
                              @click.stop="retryTask(item.task)"
                            >
                              {{ retryingTaskIds.has(item.task.taskId) ? "重试中" : "重试" }}
                            </button>
                            <button
                              v-else-if="!canCancelTask(item.task.status)"
                              type="button"
                              class="rounded-full bg-primary/15 px-3 py-1.5 text-xs font-medium text-primary transition hover:bg-primary hover:text-white"
                              @click.stop="replayTask(item.task)"
                            >
                              再次生成
                            </button>
                            <button
                              v-if="canDeleteTask(item.task.status)"
                              type="button"
                              class="inline-flex h-8 w-8 items-center justify-center rounded-full bg-white/8 text-white/45 transition hover:bg-white/14 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                              :disabled="
                                deletingTaskIds.has(item.task.taskId) ||
                                retryingTaskIds.has(item.task.taskId) ||
                                cancellingTaskIds.has(item.task.taskId)
                              "
                              @click.stop="removeTask(item.task)"
                            >
                              <Loader2 v-if="deletingTaskIds.has(item.task.taskId)" class="h-4 w-4 animate-spin" />
                              <Trash2 v-else class="h-4 w-4" />
                            </button>
                          </div>
                        </div>
                      </div>

                      <div
                        v-else-if="imageItemsForBlocks(item.blocks).length"
                        class="dashboard-feed-gallery"
                        @click.stop="openAssetPreview(item)"
                      >
                        <img
                          v-for="image in imageItemsForBlocks(item.blocks)"
                          :key="image.url"
                          :src="resolveCommunityDerivativeUrl(image.url, 'image-thumb') || image.url"
                          :alt="image.label || item.task.toolName"
                          class="dashboard-feed-image"
                          loading="lazy"
                          decoding="async"
                          @load="handleHistoryFeedMediaLoaded"
                        />
                      </div>

                      <video
                        v-else-if="videoUrlForBlocks(item.blocks)"
                        :src="videoUrlForBlocks(item.blocks)"
                        :poster="resolveOssVideoPosterUrl(videoUrlForBlocks(item.blocks))"
                        controls
                        playsinline
                        preload="none"
                        class="max-h-[420px] w-full rounded-2xl bg-black object-contain"
                        @loadedmetadata="handleHistoryFeedMediaLoaded"
                      />

                      <div v-else-if="primaryBlock(item.blocks)?.type === 'audio'" class="grid gap-3 sm:grid-cols-2">
                        <div
                          v-for="(track, trackIndex) in audioTracksForItem(item.blocks)"
                          :key="`feed-audio-${track.url}-${trackIndex}`"
                          class="rounded-2xl border border-white/8 bg-black/22 p-4"
                        >
                          <p class="truncate text-sm font-medium text-white">{{ track.title || `版本 ${trackIndex + 1}` }}</p>
                          <p class="mt-1 text-xs text-white/38">{{ formatAudioDuration(track.duration) || "生成音频" }}</p>
                          <audio :src="track.url" controls preload="metadata" class="mt-3 w-full" />
                        </div>
                      </div>

                      <div v-else class="rounded-2xl border border-white/8 bg-black/22 p-4">
                        <p class="whitespace-pre-wrap text-sm leading-7 text-white/70">{{ textPreview(item.blocks, item.task) }}</p>
                      </div>
                    </section>

                    <footer class="mt-5 flex flex-wrap items-center gap-2">
                      <RouterLink
                        v-if="isWorkflowToolCode(item.task.toolCode)"
                        :to="userRoutes.workflowStudio(String(item.task.taskId))"
                        class="dashboard-feed-action dashboard-feed-action--primary"
                        @click.stop
                      >
                        <Workflow class="h-3.5 w-3.5" />
                        进入工作台
                      </RouterLink>
                      <button
                        v-if="canCancelTask(item.task.status)"
                        type="button"
                        class="dashboard-feed-action"
                        :disabled="
                          cancellingTaskIds.has(item.task.taskId) ||
                          deletingTaskIds.has(item.task.taskId) ||
                          retryingTaskIds.has(item.task.taskId)
                        "
                        @click.stop="cancelQueuedTask(item.task)"
                      >
                        <X class="h-3.5 w-3.5" />
                        {{ cancellingTaskIds.has(item.task.taskId) ? "取消中" : "取消任务" }}
                      </button>
                      <button
                        type="button"
                        class="dashboard-feed-action"
                        :disabled="retryingTaskIds.has(item.task.taskId) || deletingTaskIds.has(item.task.taskId)"
                        @click.stop="canRetryTask(item.task.status) ? retryTask(item.task) : replayTask(item.task)"
                      >
                        <Loader2 v-if="retryingTaskIds.has(item.task.taskId)" class="h-3.5 w-3.5 animate-spin" />
                        <WandSparkles v-else class="h-3.5 w-3.5" />
                        {{ canRetryTask(item.task.status) ? "重试" : "重新生成" }}
                      </button>
                      <button type="button" class="dashboard-feed-action opacity-55" disabled>
                        <ImageIcon class="h-3.5 w-3.5" />
                        局部重绘
                      </button>
                      <button
                        v-if="firstDownloadUrl(item.blocks)"
                        type="button"
                        class="dashboard-feed-action"
                        @click.stop="forceDownload(firstDownloadUrl(item.blocks), firstDownloadFilename(item))"
                      >
                        <Download class="h-3.5 w-3.5" />
                        下载
                      </button>
                      <button
                        v-if="canDeleteTask(item.task.status)"
                        type="button"
                        class="dashboard-feed-action dashboard-feed-action--danger"
                        :disabled="
                          deletingTaskIds.has(item.task.taskId) ||
                          retryingTaskIds.has(item.task.taskId) ||
                          cancellingTaskIds.has(item.task.taskId)
                        "
                        @click.stop="removeTask(item.task)"
                      >
                        <Loader2 v-if="deletingTaskIds.has(item.task.taskId)" class="h-3.5 w-3.5 animate-spin" />
                        <Trash2 v-else class="h-3.5 w-3.5" />
                        {{ deletingTaskIds.has(item.task.taskId) ? "删除中" : "删除" }}
                      </button>
                    </footer>
                  </article>
                  <div ref="historyFeedEndRef" class="h-2" />
                </div>

                <div v-else class="dashboard-history-grid">
                  <article
                    v-for="item in taskMaterials"
                    :key="item.task.taskId"
                    class="group overflow-hidden border border-white/8 bg-white/[0.045] shadow-[0_14px_34px_rgb(0_0_0_/_0.22)] transition hover:-translate-y-0.5 hover:border-primary/45 hover:bg-white/[0.06]"
                    :class="[item.task.status === 'SUCCESS' ? 'cursor-zoom-in' : '', item.historyCardClass]"
                    @click="openAssetPreview(item)"
                  >
                    <div class="relative overflow-hidden bg-[#101014]">
                    <section class="mt-5">
                      <!-- 生成中的任务卡片 - 圆环进度样式 -->
                      <div
                        v-if="isTaskRunning(item.task.status) || canRetryTask(item.task.status) || (!item.task.result?.contentText && item.task.status !== 'SUCCESS')"
                        class="relative overflow-hidden rounded-2xl border border-white/8 bg-black/40 transition hover:border-primary/30"
                        @click.stop="scrollToTop"
                      >
                        <!-- 黑色占位背景 -->
                        <div
                          class="relative aspect-[4/3] w-full bg-gradient-to-br from-gray-900 via-black to-gray-900 p-6"
                          :class="canRetryTask(item.task.status) ? 'ring-1 ring-red-400/25' : ''"
                        >
                          <!-- 渐变光晕效果 -->
                          <div class="absolute inset-0 bg-gradient-to-t from-black/80 via-transparent to-transparent" />
                          
                          <!-- 圆环进度指示器 -->
                          <div class="relative z-10 flex h-full flex-col items-center justify-center gap-4">
                            <!-- SVG圆环进度条 -->
                            <div class="relative h-28 w-28">
                              <svg class="h-full w-full -rotate-90" viewBox="0 0 100 100">
                                <!-- 背景圆环 -->
                                <circle
                                  cx="50"
                                  cy="50"
                                  r="42"
                                  fill="none"
                                  stroke="rgba(255, 255, 255, 0.1)"
                                  stroke-width="6"
                                />
                                <!-- 进度圆环 -->
                                <circle
                                  cx="50"
                                  cy="50"
                                  r="42"
                                  fill="none"
                                  :stroke="canRetryTask(item.task.status) ? '#f87171' : '#b05cff'"
                                  stroke-width="6"
                                  stroke-linecap="round"
                                  :stroke-dasharray="`${Math.max(6, taskProgressView(item.task).percent) * 2.64} 264`"
                                  class="transition-all duration-500 ease-out"
                                />
                              </svg>
                              <!-- 中心百分比文字 -->
                              <div class="absolute inset-0 flex flex-col items-center justify-center">
                                <span class="text-2xl font-bold text-white tabular-nums">{{ taskProgressView(item.task).percent }}%</span>
                                <Loader2 v-if="isTaskRunning(item.task.status)" class="mt-1 h-4 w-4 animate-spin text-primary/60" />
                                <X v-else-if="canRetryTask(item.task.status)" class="mt-1 h-4 w-4 text-red-400" />
                                <Clock v-else class="mt-1 h-4 w-4 text-white/40" />
                              </div>
                            </div>
                            
                            <!-- 状态标签 -->
                            <span
                              class="inline-flex items-center gap-2 rounded-full px-4 py-1.5 text-sm font-medium shadow-lg backdrop-blur-sm"
                              :class="
                                canRetryTask(item.task.status)
                                  ? 'bg-red-500/20 text-red-100 ring-1 ring-red-400/30'
                                  : 'bg-primary/20 text-primary ring-1 ring-primary/30'
                              "
                            >
                              <Loader2 v-if="isTaskRunning(item.task.status)" class="h-4 w-4 animate-spin" />
                              <X v-else-if="canRetryTask(item.task.status)" class="h-4 w-4" />
                              <Clock v-else class="h-4 w-4" />
                              {{ taskStatusLabel(item.task.status) }}
                            </span>
                            
                            <!-- 进度说明文字 -->
                            <p class="max-w-xs text-center text-sm text-white/50">
                              {{ taskProgressSubtitle(item.task, canRetryTask(item.task.status) ? "任务生成失败，可以复用本次参数重试。" : "任务正在生成，完成后结果会自动出现在这里。") }}
                            </p>
                          </div>
                        </div>
                        
                        <!-- 底部操作栏 -->
                        <div class="flex items-center justify-between border-t border-white/8 px-4 py-3">
                          <p class="truncate text-xs text-white/30">{{ item.task.taskNo }}</p>
                          <div class="flex shrink-0 items-center gap-2">
                            <button
                              v-if="canCancelTask(item.task.status)"
                              type="button"
                              class="rounded-full bg-white/8 px-3 py-1.5 text-xs font-medium text-white/60 transition hover:bg-white/14 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                              :disabled="
                                cancellingTaskIds.has(item.task.taskId) ||
                                deletingTaskIds.has(item.task.taskId) ||
                                retryingTaskIds.has(item.task.taskId)
                              "
                              @click.stop="cancelQueuedTask(item.task)"
                            >
                              {{ cancellingTaskIds.has(item.task.taskId) ? "取消中" : "取消" }}
                            </button>
                            <button
                              v-if="canRetryTask(item.task.status)"
                              type="button"
                              class="rounded-full bg-red-500/15 px-3 py-1.5 text-xs font-medium text-red-100 transition hover:bg-red-500 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                              :disabled="
                                retryingTaskIds.has(item.task.taskId) ||
                                deletingTaskIds.has(item.task.taskId) ||
                                cancellingTaskIds.has(item.task.taskId)
                              "
                              @click.stop="retryTask(item.task)"
                            >
                              {{ retryingTaskIds.has(item.task.taskId) ? "重试中" : "重试" }}
                            </button>
                            <button
                              v-else-if="!canCancelTask(item.task.status)"
                              type="button"
                              class="rounded-full bg-primary/15 px-3 py-1.5 text-xs font-medium text-primary transition hover:bg-primary hover:text-white"
                              @click.stop="replayTask(item.task)"
                            >
                              再次生成
                            </button>
                            <button
                              v-if="canDeleteTask(item.task.status)"
                              type="button"
                              class="inline-flex h-8 w-8 items-center justify-center rounded-full bg-white/8 text-white/45 transition hover:bg-white/14 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                              :disabled="
                                deletingTaskIds.has(item.task.taskId) ||
                                retryingTaskIds.has(item.task.taskId) ||
                                cancellingTaskIds.has(item.task.taskId)
                              "
                              @click.stop="removeTask(item.task)"
                            >
                              <Loader2 v-if="deletingTaskIds.has(item.task.taskId)" class="h-4 w-4 animate-spin" />
                              <Trash2 v-else class="h-4 w-4" />
                            </button>
                          </div>
                        </div>
                      </div>
                      <template v-else-if="primaryBlock(item.blocks)?.type === 'image'">
                        <div
                          class="dashboard-history-media-frame aspect-[4/3] w-full"
                          :class="{ 'dashboard-history-media-frame--stack': imageItemsForBlocks(item.blocks).length > 1 }"
                        >
                          <ImageStackPreview
                            :images="imageItemsForBlocks(item.blocks).map((image) => image.url)"
                            :alt="item.task.toolName"
                            fit="contain"
                            class="dashboard-history-image-preview"
                          />
                        </div>
                      </template>
                      <template v-else-if="primaryBlock(item.blocks)?.type === 'video'">
                        <video :src="primaryBlock(item.blocks)?.url" :poster="resolveOssVideoPosterUrl(primaryBlock(item.blocks)?.url)" controls playsinline preload="metadata" class="block aspect-[4/3] w-full bg-black object-contain" />
                      </template>
                      <template v-else-if="primaryBlock(item.blocks)?.type === 'audio'">
                        <div class="space-y-4 bg-white/[0.05] p-4 pt-12">
                          <div
                            v-for="(track, trackIndex) in audioTracksForItem(item.blocks)"
                            :key="`${track.url}-${trackIndex}`"
                            class="overflow-hidden rounded-2xl border border-white/8 bg-black/20"
                          >
                            <div class="relative aspect-[16/10] overflow-hidden">
                              <img
                                v-if="track.coverUrl"
                                :src="track.coverUrl"
                                :alt="track.title || item.task.toolName"
                                class="h-full w-full object-cover"
                                loading="lazy"
                              />
                              <div
                                v-else
                                class="flex h-full w-full items-center justify-center bg-gradient-to-br from-primary/25 via-black/40 to-black/70"
                              >
                                <Music class="h-10 w-10 text-primary" />
                              </div>
                              <span
                                v-if="formatAudioDuration(track.duration)"
                                class="absolute bottom-3 right-3 rounded-full bg-black/60 px-2 py-0.5 text-[11px] text-white/85"
                              >
                                {{ formatAudioDuration(track.duration) }}
                              </span>
                            </div>
                            <div class="space-y-3 p-4">
                              <div class="min-w-0">
                                <p class="truncate text-sm font-medium text-white">{{ track.title || `版本 ${trackIndex + 1}` }}</p>
                                <p class="text-xs text-white/45">{{ item.task.toolName }}</p>
                              </div>
                              <audio :src="track.url" controls preload="metadata" class="w-full" />
                            </div>
                          </div>
                        </div>
                      </template>
                      <template v-else>
                        <div class="space-y-4 bg-white/[0.05] p-5 pt-12">
                          <div class="flex items-center gap-3">
                            <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-white/10 text-white/55">
                              <FileText class="h-5 w-5" />
                            </div>
                            <div class="min-w-0">
                              <p class="truncate text-sm font-medium text-white">{{ primaryBlock(item.blocks)?.title || item.task.toolName }}</p>
                              <p class="text-xs text-white/45">文本作品</p>
                            </div>
                          </div>
                          <p class="line-clamp-10 whitespace-pre-line text-sm leading-6 text-white/70">
                            {{ textPreview(item.blocks, item.task) }}
                          </p>
                        </div>
                      </template>

                      <span class="absolute left-3 top-3 rounded-full bg-black/55 px-2.5 py-1 text-xs font-medium text-white shadow-sm backdrop-blur">
                        {{ item.modality }}
                      </span>
                    </div>

                    <div class="space-y-3 p-3.5">
                      <div class="flex items-start justify-between gap-3">
                        <div class="min-w-0">
                          <h3 class="truncate text-sm font-semibold text-white">{{ item.task.toolName }}</h3>
                          <p class="mt-1 truncate text-xs text-white/45">{{ item.task.taskNo }}</p>
                        </div>
                        <div class="flex shrink-0 items-center gap-1 text-xs text-white/35">
                          <Clock class="h-3 w-3" />
                          {{ formatTaskTime(item.task.createdAt) }}
                        </div>
                      </div>
                      <div class="flex items-center justify-between gap-3">
                        <div class="flex flex-wrap items-center gap-2">
                          <RouterLink
                            v-if="isWorkflowToolCode(item.task.toolCode)"
                            :to="userRoutes.workflowStudio(String(item.task.taskId))"
                            class="inline-flex items-center gap-1.5 rounded-full bg-primary/15 px-3 py-1.5 text-xs font-medium text-primary ring-1 ring-primary/25 transition hover:bg-primary hover:text-white"
                            @click.stop
                          >
                            <Workflow class="h-3.5 w-3.5" />
                            进入工作台
                          </RouterLink>
                          <button
                            v-if="canCancelTask(item.task.status)"
                            type="button"
                            class="inline-flex items-center gap-1 rounded-full bg-white/8 px-3 py-1.5 text-xs font-medium text-white/70 transition hover:bg-white/14 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                            :disabled="
                              cancellingTaskIds.has(item.task.taskId) ||
                              deletingTaskIds.has(item.task.taskId) ||
                              retryingTaskIds.has(item.task.taskId)
                            "
                            @click.stop="cancelQueuedTask(item.task)"
                          >
                            <Loader2
                              v-if="cancellingTaskIds.has(item.task.taskId)"
                              class="h-3.5 w-3.5 animate-spin"
                            />
                            <X v-else class="h-3.5 w-3.5" />
                            {{ cancellingTaskIds.has(item.task.taskId) ? "取消中" : "取消任务" }}
                          </button>
                          <button
                            v-if="canRetryTask(item.task.status)"
                            type="button"
                            class="rounded-full bg-red-500/15 px-3 py-1.5 text-xs font-medium text-red-100 transition hover:bg-red-500 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                            :disabled="
                              retryingTaskIds.has(item.task.taskId) ||
                              deletingTaskIds.has(item.task.taskId) ||
                              cancellingTaskIds.has(item.task.taskId)
                            "
                            @click.stop="retryTask(item.task)"
                          >
                            {{ retryingTaskIds.has(item.task.taskId) ? "重试中" : "重试" }}
                          </button>
                          <button
                            v-else-if="!canCancelTask(item.task.status)"
                            type="button"
                            class="rounded-full bg-primary/12 px-2.5 py-1 text-xs font-medium text-primary transition hover:bg-primary hover:text-white"
                            @click.stop="replayTask(item.task)"
                          >
                            再次生成
                          </button>
                          <button
                            v-if="canDeleteTask(item.task.status)"
                            type="button"
                            class="inline-flex items-center gap-1 rounded-full bg-white/8 px-3 py-1.5 text-xs font-medium text-white/55 transition hover:bg-white/14 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                            :disabled="
                              deletingTaskIds.has(item.task.taskId) ||
                              retryingTaskIds.has(item.task.taskId) ||
                              cancellingTaskIds.has(item.task.taskId)
                            "
                            @click.stop="removeTask(item.task)"
                          >
                            <Loader2 v-if="deletingTaskIds.has(item.task.taskId)" class="h-3.5 w-3.5 animate-spin" />
                            <Trash2 v-else class="h-3.5 w-3.5" />
                            {{ deletingTaskIds.has(item.task.taskId) ? "删除中" : "删除" }}
                          </button>
                        </div>
                      </div>
                    </div>
                  </article>
                </div>
                <div v-if="historyView === 'cards'" ref="historySentinelRef" class="flex min-h-16 items-center justify-center py-6 text-sm text-white/45">
                  <template v-if="tasksLoadingMore">
                    <Loader2 class="mr-2 h-4 w-4 animate-spin" />
                    正在加载更多工作历史...
                  </template>
                  <template v-else-if="taskHasNext">
                    继续下滑加载更多
                  </template>
                  <template v-else-if="recentTasks.length > 0">
                    已加载全部工作历史
                  </template>
                </div>
              </div>
            </section>
            </div>
          </div>
        </main>

        <Transition name="dashboard-scroll-bottom">
          <button
            v-if="showHistoryScrollBottom"
            type="button"
            class="dashboard-scroll-bottom-button"
            aria-label="回到最新任务"
            @click="scrollHistoryFeedToBottom('smooth')"
          >
            <ChevronDown class="h-5 w-5" />
            <span>最新</span>
          </button>
        </Transition>

        <div
          ref="composerRootRef"
          class="pointer-events-none fixed bottom-6 left-[calc(var(--app-sidebar-width,268px)+(100vw-var(--app-sidebar-width,268px))/2)] z-50 grid w-[min(980px,calc(100vw-2rem))] -translate-x-1/2 transition-[left]"
        >
          <div
            v-show="!composerOpen"
            class="col-start-1 row-start-1 flex w-full items-center justify-center gap-3 self-end transition-all duration-200"
          >
            <div v-if="activePanel === 'tasks'" class="dashboard-floating-view-switch pointer-events-auto">
              <button
                type="button"
                class="dashboard-floating-view-button"
                :class="historyView === 'cards' ? 'is-active' : ''"
                @click="historyView = 'cards'"
              >
                <LayoutGrid class="h-3.5 w-3.5" />
                卡片
              </button>
              <button
                type="button"
                class="dashboard-floating-view-button"
                :class="historyView === 'feed' ? 'is-active' : ''"
                @click="historyView = 'feed'"
              >
                <Rows3 class="h-3.5 w-3.5" />
                信息流
              </button>
            </div>
            <button
              type="button"
              class="pointer-events-auto flex h-16 min-w-0 flex-1 items-center gap-4 rounded-full border border-white/10 bg-[#1e1e24]/0.5 px-5 text-left text-white shadow-[0_24px_90px_rgb(0_0_0_/_0.3)] backdrop-blur-2xl transition hover:border-primary/45 hover:bg-[#252631]/0.7"
              @click="expandComposer"
            >
              <span class="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-black/30 text-primary">
                <MessageSquareText class="h-5 w-5" />
              </span>
              <span class="min-w-0 flex-1">
                <span class="block text-sm font-semibold">你想创作什么？</span>
                <span class="block truncate text-xs text-white/40">
                  {{ selectedTool?.toolName || `${modalityLabel(selectedModality)}模型` }} · 点击展开创作参数
                </span>
              </span>
              <span class="hidden rounded-full bg-[linear-gradient(180deg,rgb(199_128_255),rgb(143_73_226))] px-5 py-2 text-sm font-semibold shadow-[0_10px_28px_rgb(176_92_255_/_0.35),inset_0_1px_0_rgb(255_255_255_/_0.16)] sm:inline-flex">
                创作
              </span>
            </button>
          </div>

          <div
            class="pointer-events-auto col-start-1 row-start-1 w-full self-end transition-all duration-200"
            :class="composerOpen ? 'translate-y-0 scale-100 opacity-100' : 'pointer-events-none translate-y-8 scale-[0.98] opacity-0'"
          >
            <div class="mb-3 flex flex-wrap items-center justify-between gap-3">
              <button
                type="button"
                class="inline-flex h-11 items-center gap-2 rounded-full border border-primary/35 bg-primary/15 px-5 text-sm font-medium text-white shadow-[0_0_32px_rgb(176_92_255_/_0.24)]"
              >
                <WandSparkles class="h-4 w-4" />
                玩法
              </button>
              <div v-if="activePanel === 'tasks'" class="dashboard-floating-view-switch">
                <button
                  type="button"
                  class="dashboard-floating-view-button"
                  :class="historyView === 'cards' ? 'is-active' : ''"
                  @click="historyView = 'cards'"
                >
                  <LayoutGrid class="h-3.5 w-3.5" />
                  卡片
                </button>
                <button
                  type="button"
                  class="dashboard-floating-view-button"
                  :class="historyView === 'feed' ? 'is-active' : ''"
                  @click="historyView = 'feed'"
                >
                  <Rows3 class="h-3.5 w-3.5" />
                  信息流
                </button>
              </div>
            </div>

            <div class="relative rounded-3xl border border-white/[0.06] bg-[#0f0f14]/70 p-4 shadow-[0_20px_60px_rgb(0_0_0_/_0.45)] backdrop-blur-lg">
              <button
                type="button"
                class="absolute right-3 top-3 inline-flex h-8 w-8 items-center justify-center rounded-full text-white/45 transition hover:bg-white/8 hover:text-white"
                aria-label="收起创作窗"
                @click="collapseComposerForPreview"
              >
                <X class="h-4 w-4" />
              </button>
              <div class="relative rounded-2xl bg-black/30">
                <div class="pointer-events-none absolute left-0 top-1 z-10 flex h-10 w-10 items-center justify-center text-white/48">
                  <MessageSquareText v-if="!primaryReferenceInfo.available" class="h-6 w-6" />
                </div>
                <button
                  v-if="primaryReferenceInfo.available"
                  type="button"
                  class="absolute left-0 top-1 z-20 flex h-10 w-10 items-center justify-center rounded-xl border border-dashed border-white/16 bg-black/24 text-white/46 shadow-[0_8px_28px_rgb(0_0_0_/_0.2)] transition hover:border-primary/55 hover:bg-primary/10 hover:text-white"
                  :class="primaryReferenceInfo.count > 0 ? 'border-solid border-primary/35 bg-primary/10' : ''"
                  :title="primaryReferenceInfo.fieldName || '选择参考素材'"
                  @click="openPrimaryReferencePicker"
                >
                  <Loader2 v-if="primaryReferenceInfo.uploading" class="h-4 w-4 animate-spin text-primary" />
                  <Plus v-else class="h-5 w-5" />
                  <span class="sr-only">选择参考素材</span>
                </button>
                <textarea
                  v-model="promptText"
                  rows="2"
                  class="min-h-[72px] w-full resize-none bg-transparent pb-1 pr-10 pt-1 text-base leading-7 text-white outline-none placeholder:text-white/28"
                  :class="primaryReferenceInfo.available ? 'pl-14' : 'pl-10'"
                  :placeholder="coreFieldPlaceholder"
                  @focus="expandComposer"
                />
                <div
                  v-if="primaryReferenceInfo.previewUrls.length > 0"
                  class="mt-3 flex flex-wrap gap-3 px-1"
                >
                  <div
                    v-for="(url, index) in primaryReferenceInfo.previewUrls"
                    :key="`${url}-${index}`"
                    class="group relative h-14 w-14 overflow-visible"
                  >
                    <img
                      :src="url"
                      alt="参考图"
                      class="h-14 w-14 rounded-lg border border-white/10 object-cover"
                    />
                    <button
                      type="button"
                      class="absolute -right-1.5 -top-1.5 flex h-4 w-4 items-center justify-center rounded-full bg-black/80 text-[10px] text-white opacity-0 transition-colors hover:bg-red-500 group-hover:opacity-100"
                      aria-label="移除参考图"
                      @click="removePrimaryReferenceAt(index, $event)"
                    >
                      ×
                    </button>
                  </div>
                  <button
                    v-if="primaryReferenceInfo.count > primaryReferenceInfo.previewUrls.length"
                    type="button"
                    class="flex h-14 min-w-14 items-center justify-center rounded-lg border border-white/10 bg-white/[0.04] px-3 text-xs font-medium text-white/45 transition hover:border-primary/40 hover:text-white"
                    @click="openPrimaryReferencePicker"
                  >
                    +{{ primaryReferenceInfo.count - primaryReferenceInfo.previewUrls.length }}
                  </button>
                </div>
              </div>

              <div
                v-if="selectedToolDetailLoading"
                class="mt-3 flex items-center gap-2 rounded-2xl bg-black/20 px-3 py-2 text-xs text-white/45"
              >
                <Loader2 class="h-3.5 w-3.5 animate-spin" />
                正在读取后台字段配置...
              </div>
              <CapabilityControls
                v-else-if="selectedChatTool"
                ref="capabilityRef"
                :capabilities="selectedChatTool.capabilities || []"
                :fields="selectedChatTool.fields || []"
                :core-field-key="coreField?.fieldKey"
                :tool-id="selectedChatTool.id"
                :initial-params="replayParams"
                class="mt-3 rounded-2xl bg-white/[0.02] px-3 py-2"
                @primary-reference-change="updatePrimaryReferenceInfo"
                @params-change="onCapabilityParamsChange"
              />

              <p v-if="submitError" class="mt-3 rounded-2xl border border-red-500/25 bg-red-500/10 px-3 py-2 text-xs text-red-200">
                {{ submitError }}
              </p>
              <p v-if="submitNotice" class="mt-3 rounded-2xl border border-emerald-500/25 bg-emerald-500/10 px-3 py-2 text-xs text-emerald-200">
                {{ submitNotice }}
              </p>

              <div class="mt-3 flex flex-wrap items-center gap-2">
                <div class="relative">
                  <button
                    type="button"
                    class="flex h-11 max-w-[280px] items-center gap-2 rounded-xl bg-black/25 px-3 text-sm text-white ring-1 ring-white/8 transition hover:bg-white/8"
                    @click="modelPickerOpen = !modelPickerOpen"
                  >
                    <span class="flex h-7 w-7 shrink-0 items-center justify-center overflow-hidden rounded-lg bg-white/8 text-primary">
                      <video
                        v-if="isVideoPreviewUrl(selectedTool?.coverUrl)"
                        :src="normalizeMediaUrl(selectedTool?.coverUrl)"
                        class="h-full w-full object-cover"
                        muted
                        loop
                        autoplay
                        playsinline
                        preload="metadata"
                      />
                      <img
                        v-else-if="selectedTool?.coverUrl"
                        :src="normalizeMediaUrl(selectedTool.coverUrl)"
                        :alt="selectedTool.toolName"
                        class="h-full w-full object-cover"
                      />
                      <Bot v-else class="h-4 w-4" />
                    </span>
                    <span class="min-w-0 flex-1 truncate text-left">
                      {{ selectedTool?.toolName || `${modalityLabel(selectedModality)}模型` }}
                    </span>
                    <ChevronDown class="h-4 w-4 shrink-0 text-white/45" />
                  </button>

                  <div
                    v-if="modelPickerOpen"
                    class="absolute bottom-full left-0 z-40 mb-3 w-[min(760px,calc(100vw-48px))] overflow-hidden rounded-3xl border border-white/12 bg-[#08090d] shadow-[0_28px_80px_rgb(0_0_0_/_0.72)]"
                  >
                    <div class="flex items-center justify-between border-b border-white/8 px-5 py-4">
                      <div class="flex gap-8 text-lg font-semibold">
                        <span class="border-b-2 border-primary pb-3 text-white">{{ modalityLabel(selectedModality) }}</span>
                        <span class="pb-3 text-white/35">收藏</span>
                        <span class="pb-3 text-white/35">我的</span>
                        <span class="pb-3 text-white/35">最近使用</span>
                      </div>
                      <div class="flex h-10 w-64 items-center gap-2 rounded-xl bg-white/10 px-3">
                        <Search class="h-4 w-4 text-white/45" />
                        <input
                          v-model="modelSearch"
                          class="min-w-0 flex-1 bg-transparent text-sm text-white outline-none placeholder:text-white/35"
                          placeholder="搜索模型"
                        />
                      </div>
                    </div>

                    <div class="max-h-[480px] overflow-y-auto p-5">
                      <div class="mb-4 flex flex-wrap gap-2">
                        <button
                          v-for="tab in modalityTabs"
                          :key="`picker-${tab.key}`"
                          type="button"
                          class="rounded-xl border px-4 py-2 text-sm transition"
                          :class="
                            selectedModality === tab.key
                              ? 'border-primary bg-primary/15 text-white'
                              : 'border-white/8 text-white/45 hover:text-white'
                          "
                          @click="selectModality(tab.key); modelPickerOpen = true"
                        >
                          {{ tab.label }} {{ tab.count }}
                        </button>
                      </div>

                      <div v-if="filteredCurrentTools.length === 0" class="rounded-2xl border border-dashed border-white/10 py-10 text-center text-sm text-white/45">
                        当前模态暂无模型
                      </div>
                      <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                        <button
                          v-for="tool in filteredCurrentTools"
                          :key="tool.id"
                          type="button"
                          class="marketplace-tool-card"
                          :class="selectedToolCode === tool.toolCode ? 'marketplace-tool-card--selected' : ''"
                          @click="selectTool(tool)"
                        >
                          <div class="marketplace-tool-media">
                            <video
                              v-if="isVideoPreviewUrl(tool.coverUrl)"
                              :src="normalizeMediaUrl(tool.coverUrl)"
                              class="marketplace-tool-image"
                              muted
                              loop
                              autoplay
                              playsinline
                              preload="metadata"
                            />
                            <img
                              v-else-if="tool.coverUrl"
                              :src="normalizeMediaUrl(tool.coverUrl)"
                              :alt="tool.toolName"
                              class="marketplace-tool-image"
                            />
                            <div v-else class="marketplace-tool-empty">
                              <Sparkles class="h-10 w-10 text-white/48" />
                            </div>
                            <span class="marketplace-modality-badge">{{ modalityLabel(tool.outputModality) }}</span>
                          </div>
                          <div class="marketplace-tool-overlay">
                            <div class="marketplace-tool-content">
                              <h3 class="marketplace-tool-title">{{ tool.toolName }}</h3>
                              <div class="marketplace-hover-reveal">
                                <p class="marketplace-tool-desc">{{ toolDisplayDescription(tool, "模型工具") }}</p>
                                <div class="marketplace-start-button">
                                  开始创作
                                  <ExternalLink class="h-3.5 w-3.5" />
                                </div>
                              </div>
                            </div>
                          </div>
                        </button>
                      </div>
                    </div>
                  </div>
                </div>

                <span class="rounded-xl bg-black/25 px-3 py-2 text-sm text-white/55 ring-1 ring-white/8">
                  {{ modalityLabel(selectedModality) }}
                </span>
                <span class="rounded-xl bg-black/25 px-3 py-2 text-sm text-white/55 ring-1 ring-white/8">
                  免费体验
                </span>

                <span
                  v-if="liveCreditView.label"
                  class="dashboard-credit-estimate ml-auto"
                  :class="{ 'dashboard-credit-estimate--insufficient': creditInsufficient }"
                  :title="liveCreditView.hint"
                >
                  <Zap class="h-3.5 w-3.5 shrink-0 text-amber-300/90" />
                  {{ liveCreditView.label }}
                </span>

                <button
                  type="button"
                  class="inline-flex h-11 min-w-32 items-center justify-center gap-2 rounded-xl bg-[linear-gradient(180deg,rgb(199_128_255),rgb(143_73_226))] px-5 text-sm font-semibold text-white shadow-[0_12px_32px_rgb(176_92_255_/_0.34),inset_0_1px_0_rgb(255_255_255_/_0.16)] transition hover:brightness-110 disabled:cursor-not-allowed disabled:bg-white/12 disabled:text-white/35"
                  :class="liveCreditView.label ? '' : 'ml-auto'"
                  :disabled="!selectedTool || submitting || selectedToolDetailLoading"
                  @click.stop="createWithSelectedTool"
                >
                  <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />
                  <Send v-else class="h-4 w-4" />
                  {{ submitting ? "创建中" : "创作" }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </section>
      <AssetPreviewModal
        :asset="previewAsset"
        :recommendations="previewRecommendations"
        @close="previewAsset = null"
        @use-tool="useAssetWithTool"
        @open-task="openPreviewTask"
        @publish="publishPreviewAsset"
        @unpublish="unpublishPreviewAsset"
      />
    </div>
  </AppShell>
</template>

<style scoped>
/* dashboard 局部样式 */
.dashboard-credit-estimate {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 44px;
  padding: 0 14px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.06);
  font-size: 13px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.82);
  white-space: nowrap;
}

.dashboard-credit-estimate--insufficient {
  color: #fca5a5;
  background: rgba(239, 68, 68, 0.14);
}

.audio-wave-hit {
  min-height: 32px;
  cursor: pointer;
}

.dashboard-history-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  align-items: start;
  gap: clamp(14px, 1.6vw, 20px);
}

.dashboard-history-grid > article {
  border-radius: 18px;
}

.dashboard-chat-feed {
  display: flex;
  max-width: min(1040px, 100%);
  margin: 0 auto;
  padding-bottom: 36px;
  flex-direction: column;
  gap: 18px;
}

.dashboard-chat-row {
  border: 1px solid rgb(255 255 255 / 0.055);
  border-radius: 12px;
  background:
    linear-gradient(180deg, rgb(255 255 255 / 0.045), rgb(255 255 255 / 0.032)),
    #111116;
  padding: 22px 26px 24px;
  box-shadow: 0 22px 60px rgb(0 0 0 / 0.22);
  transition: border-color 180ms ease, background-color 180ms ease, transform 180ms ease;
}

.dashboard-chat-row:hover {
  border-color: rgb(255 255 255 / 0.085);
  background:
    linear-gradient(180deg, rgb(255 255 255 / 0.058), rgb(255 255 255 / 0.038)),
    #121218;
  transform: translateY(-1px);
}

.dashboard-feed-prompt-toggle {
  display: inline-flex;
  margin-top: 6px;
  align-items: center;
  gap: 4px;
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.42);
  padding: 0;
  font-size: 12px;
  font-weight: 500;
  transition: color 160ms ease;
}

.dashboard-feed-prompt-toggle:hover {
  color: rgb(196 181 253 / 0.95);
}

.dashboard-feed-gallery {
  display: flex;
  max-width: 100%;
  align-items: flex-start;
  gap: 12px;
  overflow-x: auto;
  overscroll-behavior-inline: contain;
  padding-bottom: 6px;
  scrollbar-width: thin;
  scrollbar-color: rgb(255 255 255 / 0.16) transparent;
}

.dashboard-feed-gallery::-webkit-scrollbar {
  height: 6px;
}

.dashboard-feed-gallery::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgb(255 255 255 / 0.16);
}

.dashboard-feed-image {
  width: auto;
  max-width: min(360px, 72vw);
  max-height: 420px;
  flex: 0 0 auto;
  object-fit: contain;
  border: 1px solid rgb(255 255 255 / 0.06);
  border-radius: 12px;
  background: #050507;
  box-shadow: 0 14px 36px rgb(0 0 0 / 0.18);
}

.dashboard-feed-action {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 10px;
  background: transparent;
  padding: 0 12px;
  color: rgb(255 255 255 / 0.58);
  font-size: 12px;
  font-weight: 500;
  transition: border-color 160ms ease, background-color 160ms ease, color 160ms ease, transform 160ms ease;
}

.dashboard-feed-action:hover:not(:disabled) {
  border-color: rgb(255 255 255 / 0.16);
  background: rgb(255 255 255 / 0.06);
  color: #fff;
  transform: translateY(-1px);
}

.dashboard-feed-action:disabled {
  cursor: not-allowed;
  opacity: 0.46;
}

.dashboard-feed-action--primary {
  border-color: rgb(168 85 247 / 0.3);
  background: rgb(168 85 247 / 0.12);
  color: rgb(192 132 252);
  text-decoration: none;
}

.dashboard-feed-action--primary:hover {
  border-color: rgb(168 85 247 / 0.5);
  background: rgb(168 85 247 / 0.25);
  color: #fff;
  transform: translateY(-1px);
}

.dashboard-feed-action--danger {
  color: rgb(255 114 136 / 0.92);
}

.dashboard-feed-action--danger:hover:not(:disabled) {
  border-color: rgb(255 92 122 / 0.28);
  background: rgb(255 72 112 / 0.08);
  color: rgb(255 132 154);
}

.dashboard-scroll-bottom-button {
  position: fixed;
  right: 30px;
  bottom: 108px;
  z-index: 52;
  display: inline-flex;
  height: 44px;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 999px;
  background: rgb(24 24 30 / 0.82);
  padding: 0 15px;
  color: rgb(255 255 255 / 0.78);
  font-size: 12px;
  font-weight: 600;
  box-shadow: 0 18px 54px rgb(0 0 0 / 0.38);
  backdrop-filter: blur(18px);
  transition: transform 160ms ease, border-color 160ms ease, background-color 160ms ease, color 160ms ease;
}

.dashboard-scroll-bottom-button:hover {
  transform: translateY(-2px);
  border-color: rgb(255 63 121 / 0.34);
  background: rgb(255 63 121 / 0.18);
  color: #fff;
}

.dashboard-scroll-bottom-enter-active,
.dashboard-scroll-bottom-leave-active {
  transition: opacity 180ms ease, transform 180ms ease;
}

.dashboard-scroll-bottom-enter-from,
.dashboard-scroll-bottom-leave-to {
  opacity: 0;
  transform: translateY(10px) scale(0.96);
}

.dashboard-floating-view-switch {
  display: inline-flex;
  flex-shrink: 0;
  gap: 3px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 999px;
  background: rgb(20 20 26 / 0.78);
  padding: 4px;
  box-shadow: 0 18px 54px rgb(0 0 0 / 0.32);
  backdrop-filter: blur(20px);
}

.dashboard-floating-view-button {
  display: inline-flex;
  height: 38px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: 999px;
  padding: 0 12px;
  color: rgb(255 255 255 / 0.52);
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  transition: background-color 160ms ease, color 160ms ease, box-shadow 160ms ease;
}

.dashboard-floating-view-button:hover {
  color: #fff;
  background: rgb(255 255 255 / 0.06);
}

.dashboard-floating-view-button.is-active {
  color: #fff;
  background: rgb(255 63 121 / 0.18);
  box-shadow:
    inset 0 0 0 1px rgb(255 63 121 / 0.18),
    0 8px 22px rgb(255 63 121 / 0.12);
}

.dashboard-history-grid > .history-card-image {
  min-width: 0;
  min-height: 300px;
}

.dashboard-history-grid > .history-card-standard {
  min-height: 300px;
}

.dashboard-history-media-frame {
  position: relative;
  overflow: hidden;
  background: #101014;
}

.dashboard-history-media-frame--stack {
  display: flex;
}

.dashboard-history-image-preview {
  flex: 1;
  min-height: 0;
  height: 100%;
}

.dashboard-history-media-frame--stack :deep(.image-grid-preview.grid) {
  height: 100%;
  grid-auto-rows: minmax(0, 1fr);
}

.dashboard-history-media-frame--stack :deep(.image-grid-cell) {
  aspect-ratio: auto;
  min-height: 0;
}

.dashboard-history-grid > .history-card-pending {
  min-height: 0;
}

@media (max-width: 1280px) {
  .dashboard-history-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .dashboard-history-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .dashboard-history-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .dashboard-floating-view-button {
    width: 38px;
    padding: 0;
  }

  .dashboard-floating-view-button svg {
    margin: 0;
  }

  .dashboard-floating-view-button {
    font-size: 0;
  }
}

.audio-wave-bar {
  display: block;
  width: 3px;
  min-height: 18%;
  flex: 1 1 0;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.22);
  transition: background-color 160ms ease, transform 160ms ease;
}

.audio-wave-hit:hover .audio-wave-bar {
  background: rgba(255, 255, 255, 0.34);
}

.audio-wave-bar.is-played {
  background: rgb(176, 92, 255);
}

.audio-wave-bar.stage {
  width: 5px;
  min-height: 12%;
  box-shadow: 0 0 18px rgba(176, 92, 255, 0.08);
}

.audio-viz-bar {
  display: block;
  width: 2px;
  min-height: 3px;
  border-radius: 999px;
  background: rgb(255, 255, 255);
  animation: audio-viz 760ms ease-in-out infinite;
}

.audio-viz-bar:nth-child(2) {
  animation-delay: 120ms;
}

.audio-viz-bar:nth-child(3) {
  animation-delay: 240ms;
}

.audio-viz-bar:nth-child(4) {
  animation-delay: 360ms;
}

.audio-volume-slider {
  min-width: 120px;
  height: 24px;
  flex: 1 1 auto;
  cursor: pointer;
  appearance: none;
  background: transparent;
}

.audio-volume-slider::-webkit-slider-runnable-track {
  height: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.16);
}

.audio-volume-slider::-webkit-slider-thumb {
  width: 14px;
  height: 14px;
  margin-top: -5px;
  appearance: none;
  border-radius: 999px;
  background: rgb(176, 92, 255);
  box-shadow: 0 0 0 4px rgba(176, 92, 255, 0.16);
}

.audio-volume-slider::-moz-range-track {
  height: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.16);
}

.audio-volume-slider::-moz-range-thumb {
  width: 14px;
  height: 14px;
  border: 0;
  border-radius: 999px;
  background: rgb(176, 92, 255);
  box-shadow: 0 0 0 4px rgba(176, 92, 255, 0.16);
}

@keyframes audio-viz {
  0%,
  100% {
    height: 3px;
    opacity: 0.55;
  }

  50% {
    height: 10px;
    opacity: 1;
  }
}
</style>
