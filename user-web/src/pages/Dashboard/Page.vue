<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue"
import { RouterLink, useRoute, useRouter } from "vue-router"
import {
  ArrowRight,
  Bot,
  Box,
  Braces,
  ChevronDown,
  Clock,
  Download,
  FileText,
  Files,
  Image as ImageIcon,
  Loader2,
  MessageSquareText,
  MoreHorizontal,
  Music,
  Pause,
  Play,
  Search,
  Send,
  Sparkles,
  Store,
  Trash2,
  Video,
  Volume2,
  WandSparkles,
  X,
} from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import AssetPreviewModal from "@/components/AssetPreviewModal.vue"
import CreditCostBadge from "@/components/CreditCostBadge/CreditCostBadge.vue"
import CapabilityControls from "@/pages/Chat/CapabilityControls.vue"
import DashboardModalityDock from "./DashboardModalityDock.vue"
import { fetchCreditAccount } from "@/api/creditApi"
import { ApiBusinessError, getApiOrigin } from "@/api/client"
import {
  cancelTask,
  createTask,
  deleteTask,
  fetchTaskById,
  fetchTasks,
  fetchTaskStatus,
  regenerateTask,
} from "@/api/taskApi"
import { publishCommunityPost, unpublishCommunityPost } from "@/api/communityApi"
import { fetchAIToolById, fetchTools } from "@/api/toolApi"
import type { AITool } from "@/api/aiToolTypes"
import type { CreditAccount, TaskDetail, TaskStatus, ToolField, ToolSummary } from "@/api/types"
import type { AssetPreviewItem, AssetPreviewRecommendation } from "@/types/assetPreview"
import type { AudioTrackItem, ResultBlock } from "@/types/result"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { buildTaskResultBlocks, formatAudioDuration, resolveAudioTracks } from "@/utils/taskResultBlocks"
import { isCoreField } from "@/utils/fieldUiMeta"
import { consumeDashboardPendingAsset } from "@/utils/assetReplay"
import { cleanToolDisplayText, toolDisplayDescription } from "@/utils/toolDisplayText"
import { randomUUID } from "@/utils/randomUUID"
import {
  dashboardAttributionFromRoute,
  mergePendingAssetAttribution,
  type DashboardAttributionContext,
} from "./dashboardAttribution"
import { buildDashboardTaskParams, buildOptimisticDashboardTask } from "./dashboardTaskFactory"

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

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
const composerOpen = ref(false)
const workbenchScrollRef = ref<HTMLElement | null>(null)
const replayParams = ref<Record<string, unknown> | null>(null)
const submitting = ref(false)
const submitError = ref("")
const submitNotice = ref("")
const activePanel = ref<"models" | "tasks">("models")
const modalityDockOpen = ref(true)
const historySentinelRef = ref<HTMLElement | null>(null)
let historyObserver: IntersectionObserver | null = null
const taskPollTimers = new Map<number, number>()
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
let lastWorkbenchScrollTop = 0
let lastWindowScrollTop = 0

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
  MULTIMODAL: "多模态",
  JSON: "数据",
  FILE: "文件",
}

const modalityDescriptions: Record<string, string> = {
  IMAGE: "海报、商品图、场景图",
  VIDEO: "短片、运镜、动态素材",
  AUDIO: "配音、音效、音乐",
  TEXT: "文案、脚本、营销内容",
  MULTIMODAL: "图文混合与理解",
  JSON: "结构化数据生成",
  FILE: "文件解析与生成",
}

const modalityIcons = {
  IMAGE: ImageIcon,
  VIDEO: Video,
  AUDIO: Music,
  TEXT: FileText,
  MULTIMODAL: Box,
  JSON: Braces,
  FILE: Files,
}

const gallerySeeds = [
  {
    title: "品牌新品主视觉",
    prompt: "高质感电商棚拍，柔和布光，细腻产品材质",
    gradient: "bg-[radial-gradient(circle_at_18%_18%,rgb(244_114_182_/_0.70),transparent_32%),radial-gradient(circle_at_82%_22%,rgb(251_191_36_/_0.36),transparent_34%),radial-gradient(circle_at_45%_90%,rgb(127_29_29_/_0.66),transparent_42%),linear-gradient(135deg,rgb(42_22_28),rgb(21_18_24))]",
  },
  {
    title: "社媒种草封面",
    prompt: "年轻化生活方式，明亮构图，强记忆点标题空间",
    gradient: "bg-[radial-gradient(circle_at_18%_20%,rgb(34_211_238_/_0.58),transparent_34%),radial-gradient(circle_at_82%_28%,rgb(129_140_248_/_0.48),transparent_38%),radial-gradient(circle_at_55%_92%,rgb(30_64_175_/_0.62),transparent_44%),linear-gradient(135deg,rgb(14_28_44),rgb(18_18_30))]",
  },
  {
    title: "短视频口播脚本",
    prompt: "三秒钩子，真实体验，轻转化结尾",
    gradient: "bg-[radial-gradient(circle_at_20%_24%,rgb(16_185_129_/_0.56),transparent_35%),radial-gradient(circle_at_84%_30%,rgb(45_212_191_/_0.34),transparent_36%),radial-gradient(circle_at_56%_90%,rgb(14_116_144_/_0.58),transparent_45%),linear-gradient(135deg,rgb(13_36_32),rgb(13_20_25))]",
  },
  {
    title: "直播间氛围素材",
    prompt: "暖色灯光，大促氛围，层次丰富的空间布景",
    gradient: "bg-[radial-gradient(circle_at_18%_22%,rgb(217_70_239_/_0.56),transparent_34%),radial-gradient(circle_at_82%_26%,rgb(168_85_247_/_0.42),transparent_36%),radial-gradient(circle_at_56%_92%,rgb(71_85_105_/_0.64),transparent_46%),linear-gradient(135deg,rgb(35_24_46),rgb(17_18_24))]",
  },
]

const toolsByModality = computed(() => {
  const groups = new Map<string, ToolSummary[]>()
  for (const tool of tools.value) {
    const key = normalizeModality(tool.outputModality)
    groups.set(key, [...(groups.get(key) || []), tool])
  }
  return groups
})

const modalityTabs = computed(() => {
  const order = ["IMAGE", "VIDEO", "AUDIO", "TEXT", "MULTIMODAL", "JSON", "FILE"]
  const available = new Set([...toolsByModality.value.keys(), ...order])
  return [...available]
    .sort((a, b) => {
      const ia = order.indexOf(a)
      const ib = order.indexOf(b)
      return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib)
    })
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
  return byCode && normalizeModality(byCode.outputModality) === selectedModality.value
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

const featuredTools = computed(() => {
  const list = currentTools.value.length > 0 ? currentTools.value : tools.value
  return list.slice(0, 6)
})

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
const previewRecommendations = computed<AssetPreviewRecommendation[]>(() =>
  previewAsset.value ? recommendToolsForAsset(previewAsset.value) : [],
)
const runningCount = computed(() =>
  tasks.value.filter((task) => ["CREATED", "QUEUED", "PROCESSING", "RETRYING"].includes(task.status)).length,
)
const audioTaskMaterials = computed(() =>
  taskMaterials.value.filter((item) => item.task.status === "SUCCESS" && primaryBlock(item.blocks)?.type === "audio"),
)
const audioStatusMaterials = computed(() =>
  taskMaterials.value.filter((item) => item.task.status !== "SUCCESS" || primaryBlock(item.blocks)?.type !== "audio"),
)
const audioWorkbenchVisible = computed(() => selectedModality.value === "AUDIO" && recentTasks.value.length > 0)
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
  () => route.query.sourcePost,
  () => {
    attribution.value = mergePendingAssetAttribution(dashboardAttributionFromRoute(route), pendingAssetReplay.value)
  },
)

watch(activePanel, async (panel) => {
  if (panel !== "tasks") return
  await nextTick()
  setupHistoryObserver()
})

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
    if (rawRouteModality) selectedModality.value = normalizeModality(rawRouteModality)
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
  const fallback = ["IMAGE", "VIDEO", "AUDIO", "TEXT", "MULTIMODAL", "JSON", "FILE"].find(
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
  selectedModality.value = normalizeModality(tool.outputModality)
  selectedToolCode.value = tool.toolCode
  if (openComposer) expandComposer()
}

function expandComposer() {
  composerOpen.value = true
}

function collapseComposerForPreview() {
  if (!composerOpen.value || submitting.value) return
  composerOpen.value = false
  modelPickerOpen.value = false
}

function handleWorkbenchScroll(event: Event) {
  const target = event.currentTarget as HTMLElement
  lastWorkbenchScrollTop = collapseComposerOnDownScroll(target.scrollTop, lastWorkbenchScrollTop)
}

function handleWindowWorkbenchScroll() {
  const nextTop = window.scrollY || document.documentElement.scrollTop || 0
  lastWindowScrollTop = collapseComposerOnDownScroll(nextTop, lastWindowScrollTop)
}

function collapseComposerOnDownScroll(nextTop: number, previousTop: number) {
  const scrollingDown = nextTop > previousTop + 18
  if (scrollingDown && nextTop > 120) collapseComposerForPreview()
  return nextTop
}

function handleWorkbenchWheel(event: WheelEvent) {
  if (event.deltaY > 12) collapseComposerForPreview()
}

async function createWithSelectedTool() {
  const tool = selectedTool.value
  if (!tool || submitting.value) return
  submitError.value = ""
  submitNotice.value = ""

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
    upsertTask(optimisticTask, true)
    activePanel.value = "tasks"
    submitNotice.value = `已进入工作历史：${response.taskNo}`
    replayParams.value = null
    startTaskPolling(response.taskId)
    await nextTick()
    setupHistoryObserver()
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
  } finally {
    tasksLoadingMore.value = false
  }
}

async function loadMoreTasks() {
  if (tasksLoadingMore.value || !taskHasNext.value) return
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
    startPollingVisibleTasks()
  } finally {
    tasksLoadingMore.value = false
  }
}

function taskMatchesSelectedModality(task: TaskDetail): boolean {
  return normalizeModality(task.outputModality || task.result?.resourceType) === selectedModality.value
}

function setupHistoryObserver() {
  historyObserver?.disconnect()
  historyObserver = new IntersectionObserver((entries) => {
    if (entries.some((entry) => entry.isIntersecting)) void loadMoreTasks()
  }, { rootMargin: "260px" })
  if (historySentinelRef.value) historyObserver.observe(historySentinelRef.value)
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
  }, 1800)
  taskPollTimers.set(taskId, timer)
}

function stopTaskPolling(taskId: number) {
  const timer = taskPollTimers.get(taskId)
  if (timer) window.clearInterval(timer)
  taskPollTimers.delete(taskId)
}

async function refreshTaskStatus(taskId: number) {
  try {
    const status = await fetchTaskStatus(taskId, { token: auth.token })
    const current = tasks.value.find((task) => task.taskId === taskId)
    if (current) {
      upsertTask({
        ...current,
        status: status.status,
        progress: status.progress ?? current.progress,
        progressMessage: status.progressMessage ?? current.progressMessage,
      })
    }
    if (isTaskTerminal(status.status)) {
      stopTaskPolling(taskId)
      try {
        const detail = await fetchTaskById(taskId, { token: auth.token })
        if (taskMatchesSelectedModality(detail)) upsertTask(detail)
      } catch {
        // The status card is still useful even if detail sync is briefly unavailable.
      }
    }
  } catch {
    stopTaskPolling(taskId)
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

function audioTracksForItem(blocks: ResultBlock[]) {
  const block = primaryBlock(blocks)
  return block?.type === "audio" ? resolveAudioTracks(block) : []
}

function historyCardClass(task: TaskDetail, blocks: ResultBlock[]): string {
  if (task.status !== "SUCCESS") return "history-card-pending"
  const block = primaryBlock(blocks)
  if (block?.type !== "image") return "history-card-standard"
  const ratio = inferImageAspectRatio(task)
  if (ratio >= 1.45) return "history-card-wide"
  if (ratio > 0 && ratio <= 0.78) return "history-card-tall"
  return "history-card-standard"
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
    subtitle: item.task.progressMessage || item.task.taskNo,
    prompt: taskPrompt(item.task),
    taskId: item.task.taskId,
    taskNo: item.task.taskNo,
    toolName: item.task.toolName,
    toolCode: item.task.toolCode,
    createdAt: item.task.createdAt,
  }
  if (block.type === "image") {
    return {
      ...base,
      kind: "image",
      url: block.images[0]?.url,
      urls: block.images.map((image) => image.url),
      title: block.title || base.title,
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

function consumePendingAssetFromStorage(): AssetPreviewItem | null {
  return consumeDashboardPendingAsset()
}

function openPreviewTask(asset: AssetPreviewItem) {
  if (!asset.taskId) return
  void router.push(userRoutes.taskResult(String(asset.taskId)))
}

async function publishPreviewAsset(asset: AssetPreviewItem) {
  if (!auth.token || !asset.taskId) return
  try {
    const post = await publishCommunityPost(
      {
        taskId: asset.taskId,
        title: asset.title,
        description: asset.subtitle || null,
        promptVisible: asset.promptVisible ?? auth.user?.promptPublicByDefault ?? false,
      },
      { token: auth.token },
    )
    previewAsset.value = { ...asset, communityPostId: post.id, promptVisible: post.promptVisible }
  } catch (err) {
    const message = err instanceof Error ? err.message : "发布失败"
    window.alert(message)
  }
}

async function unpublishPreviewAsset(asset: AssetPreviewItem) {
  if (!auth.token || !asset.communityPostId) return
  try {
    await unpublishCommunityPost(asset.communityPostId, { token: auth.token })
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
  replayParams.value = { ...(task.params || {}) }
  expandComposer()
}

async function loadSelectedToolDetail(toolCode: string) {
  selectedToolDetailLoading.value = true
  try {
    selectedChatTool.value = await fetchAIToolById(toolCode, { token: auth.token })
    if (pendingAssetReplay.value) {
      replayParams.value = buildAssetReplayParams(selectedChatTool.value.fields || [], pendingAssetReplay.value)
      if (pendingAssetReplay.value.prompt) promptText.value = pendingAssetReplay.value.prompt
    }
  } finally {
    selectedToolDetailLoading.value = false
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

function isVideoPreviewUrl(value?: string | null): boolean {
  const raw = value?.split(/[?#]/)[0]?.toLowerCase() || ""
  return [".mp4", ".webm", ".mov", ".m4v"].some((ext) => raw.endsWith(ext))
}

function modalityLabel(value?: string | null) {
  const key = normalizeModality(value)
  return modalityLabels[key] || key
}

onMounted(async () => {
  await loadDashboard()
  setupHistoryObserver()
  window.addEventListener("scroll", handleWindowWorkbenchScroll, { passive: true })
  window.addEventListener("wheel", handleWorkbenchWheel, { passive: true })
})

onUnmounted(() => {
  window.removeEventListener("scroll", handleWindowWorkbenchScroll)
  window.removeEventListener("wheel", handleWorkbenchWheel)
  historyObserver?.disconnect()
  for (const timer of taskPollTimers.values()) window.clearInterval(timer)
  taskPollTimers.clear()
})
</script>

<template>
  <AppShell title="工作台" description="像 SeaArt 一样选择模态、模型，然后开始创作">
    <div class="flex min-h-[calc(100vh-5rem)] bg-black text-white">
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

      <section class="relative flex min-w-0 flex-1 flex-col">
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
          ref="workbenchScrollRef"
          class="min-h-0 flex-1 overflow-y-auto px-5 pb-40 pt-6 lg:pl-[132px] xl:px-10 xl:pl-[132px]"
          @scroll="handleWorkbenchScroll"
          @wheel.passive="handleWorkbenchWheel"
        >
          <div class="mx-auto w-full max-w-[1380px]">
            <div class="sticky top-0 z-20 -mx-5 mb-8 border-b border-transparent bg-transparent px-5 py-4 backdrop-blur-0 xl:-mx-10 xl:px-10">
              <div class="flex flex-wrap items-center justify-between gap-4">
                <div class="flex rounded-full border border-white/10 bg-white/[0.04] p-1 shadow-[0_16px_40px_rgb(0_0_0_/_0.25)]">
                  <button
                    type="button"
                    class="rounded-full px-7 py-3 text-base font-semibold transition"
                    :class="activePanel === 'models' ? 'bg-primary text-white shadow-[0_10px_28px_rgb(176_92_255_/_0.3)]' : 'text-white/45 hover:text-white'"
                    @click="activePanel = 'models'"
                  >
                    模型推荐
                  </button>
                  <button
                    type="button"
                    class="rounded-full px-7 py-3 text-base font-semibold transition"
                    :class="activePanel === 'tasks' ? 'bg-primary text-white shadow-[0_10px_28px_rgb(176_92_255_/_0.3)]' : 'text-white/45 hover:text-white'"
                    @click="activePanel = 'tasks'"
                  >
                    工作历史
                    <span class="ml-1 text-xs opacity-70">{{ recentTasks.length }}</span>
                  </button>
                </div>
                <div class="hidden text-right font-mono text-[12px] leading-5 text-white/36 sm:block">
                  <p>{{ modalityLabel(selectedModality) }} · {{ currentTools.length }} 个可用模型 · 可用算力 {{ credit?.available ?? "--" }} · 进行中 {{ runningCount }}</p>
                </div>
              </div>
            </div>

            <section>
              <div v-if="activePanel === 'models'" class="space-y-8">
                <div class="flex items-end justify-between gap-5">
                  <div>
                    <p class="text-sm text-white/45">当前模态</p>
                    <h2 class="mt-1 text-4xl font-semibold">{{ modalityLabel(selectedModality) }}创作</h2>
                  </div>
                </div>

                <section class="grid gap-2 overflow-hidden rounded-[28px] bg-white/[0.025] p-2 ring-1 ring-white/6 lg:grid-cols-4">
                  <article
                    v-for="seed in gallerySeeds"
                    :key="seed.title"
                    class="group relative min-h-[220px] overflow-hidden rounded-[24px] bg-secondary"
                  >
                    <div class="absolute inset-0" :class="seed.gradient" />
                    <div class="absolute inset-0 opacity-[0.07] bg-[url('data:image/svg+xml,%3Csvg_viewBox=%220_0_120_120%22_xmlns=%22http://www.w3.org/2000/svg%22%3E%3Cfilter_id=%22n%22%3E%3CfeTurbulence_type=%22fractalNoise%22_baseFrequency=%220.9%22_numOctaves=%222%22_stitchTiles=%22stitch%22/%3E%3C/filter%3E%3Crect_width=%22120%22_height=%22120%22_filter=%22url(%23n)%22_opacity=%220.65%22/%3E%3C/svg%3E')]" />
                    <div class="absolute inset-0 bg-gradient-to-t from-black/78 via-black/8 to-white/5" />
                    <WandSparkles class="absolute -bottom-3 -right-2 h-28 w-28 text-white/[0.075] transition duration-500 group-hover:scale-105 group-hover:text-white/[0.11]" />
                    <div class="absolute bottom-5 left-5 right-5">
                      <p class="text-xl font-semibold text-white/90">{{ seed.title }}</p>
                      <p class="mt-2 line-clamp-2 text-sm font-light leading-6 text-white/62">{{ seed.prompt }}</p>
                    </div>
                    <button
                      type="button"
                      class="absolute right-4 top-4 flex h-9 w-9 items-center justify-center rounded-full border border-white/10 bg-black/24 text-white/74 opacity-0 backdrop-blur transition group-hover:opacity-100 hover:bg-white/10 hover:text-white"
                      @click="promptText = seed.prompt; expandComposer()"
                    >
                      <WandSparkles class="h-4 w-4" />
                    </button>
                  </article>
                </section>

                <div class="grid gap-5 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
                <article
                  v-for="tool in featuredTools"
                  :key="tool.id"
                  class="group flex min-h-[260px] cursor-pointer flex-col overflow-hidden rounded-3xl border border-white/8 bg-[#191919] transition hover:-translate-y-1 hover:border-primary/50"
                  @click="selectTool(tool)"
                >
                  <div class="relative h-36 overflow-hidden bg-white/[0.04]">
                    <video
                      v-if="isVideoPreviewUrl(tool.coverUrl)"
                      :src="normalizeMediaUrl(tool.coverUrl)"
                      class="h-full w-full object-cover transition duration-500 group-hover:scale-105"
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
                      class="h-full w-full object-cover transition duration-500 group-hover:scale-105"
                    />
                    <div
                      v-else
                      class="flex h-full w-full items-center justify-center bg-[radial-gradient(circle_at_35%_20%,rgb(176_92_255_/_0.42),transparent_35%),linear-gradient(135deg,rgb(31_41_55),rgb(17_17_17))]"
                    >
                      <Sparkles class="h-10 w-10 text-primary" />
                    </div>
                    <div class="absolute inset-0 bg-gradient-to-t from-black/85 via-black/5 to-transparent" />
                    <span class="absolute left-4 top-4 rounded-full bg-black/45 px-2.5 py-1 text-xs text-white/80 backdrop-blur">
                      {{ modalityLabel(tool.outputModality) }}
                    </span>
                  </div>
                  <div class="flex flex-1 flex-col p-5">
                    <h3 class="line-clamp-2 text-xl font-semibold">{{ tool.toolName }}</h3>
                    <p class="mt-2 line-clamp-2 text-sm text-white/50">
                      {{ toolDisplayDescription(tool, "点击选择模型后开始创作。") }}
                    </p>
                    <div class="mt-auto flex items-center justify-between pt-5">
                      <span class="text-xs text-white/45">{{ tool.modelConfigName || tool.modelName || tool.toolCode }}</span>
                      <CreditCostBadge :cost="tool.estimatedCreditCost" size="md" class="text-amber-300" />
                    </div>
                  </div>
                </article>
                </div>
              </div>

              <div v-else>
                <div v-if="loading" class="flex justify-center py-8 text-white/45">
                  <Loader2 class="h-5 w-5 animate-spin" />
                </div>
                <div v-else-if="recentTasks.length === 0" class="rounded-2xl border border-dashed border-white/10 py-10 text-center text-sm text-white/45">
                  暂无任务，选择模型后开始第一条创作。
                </div>
                <div v-else-if="audioWorkbenchVisible" class="grid gap-4 xl:grid-cols-[minmax(420px,0.9fr)_minmax(0,1.35fr)]">
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
                      >
                        <div class="grid grid-cols-[40px_minmax(0,1fr)_auto] items-center gap-3">
                          <div
                            class="flex h-10 w-10 items-center justify-center rounded-lg"
                            :class="canRetryTask(item.task.status) ? 'bg-red-500/12 text-red-200' : 'bg-primary/12 text-primary'"
                          >
                            <Loader2 v-if="isTaskRunning(item.task.status)" class="h-4 w-4 animate-spin" />
                            <X v-else-if="canRetryTask(item.task.status)" class="h-4 w-4" />
                            <Clock v-else class="h-4 w-4" />
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
                              {{ item.task.progressMessage || (canRetryTask(item.task.status) ? "任务生成失败，可以重试。" : "任务正在生成，完成后自动展开版本。") }}
                            </p>
                          </div>
                          <span class="text-xs tabular-nums text-white/40">{{ item.task.progress ?? 0 }}%</span>
                        </div>
                        <div class="mt-3 h-1.5 overflow-hidden rounded-full bg-white/10">
                          <div
                            class="h-full rounded-full transition-all"
                            :class="canRetryTask(item.task.status) ? 'bg-red-400' : 'bg-primary'"
                            :style="{ width: `${Math.max(6, Math.min(item.task.progress ?? (isTaskRunning(item.task.status) ? 12 : 100), 100))}%` }"
                          />
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
                          <a
                            :href="normalizeMediaUrl(track.url)"
                            :download="track.downloadName || `audio-${track.version}`"
                            class="inline-flex h-8 w-8 items-center justify-center rounded-full text-white/45 transition hover:bg-white/10 hover:text-white"
                            title="下载"
                            @click.stop
                          >
                            <Download class="h-4 w-4" />
                          </a>
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
                            {{ activeAudioTrack ? audioTaskSubtitle(activeAudioTrack) : (primaryAudioStatusItem?.task.progressMessage || "任务正在生成，完成后会自动出现在左侧列表。") }}
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
                        <div class="rounded-2xl border border-white/8 bg-black/22 p-6">
                          <div class="flex items-center gap-4">
                            <div
                              class="flex h-14 w-14 items-center justify-center rounded-2xl"
                              :class="canRetryTask(primaryAudioStatusItem?.task.status) ? 'bg-red-500/12 text-red-200' : 'bg-primary/12 text-primary'"
                            >
                              <Loader2 v-if="isTaskRunning(primaryAudioStatusItem?.task.status)" class="h-6 w-6 animate-spin" />
                              <X v-else-if="canRetryTask(primaryAudioStatusItem?.task.status)" class="h-6 w-6" />
                              <Clock v-else class="h-6 w-6" />
                            </div>
                            <div class="min-w-0 flex-1">
                              <p class="text-lg font-semibold text-white">{{ primaryAudioStatusItem?.task.toolName || "音乐生成任务" }}</p>
                              <p class="mt-1 text-sm text-white/45">
                                {{ primaryAudioStatusItem?.task.progressMessage || (canRetryTask(primaryAudioStatusItem?.task.status) ? "任务生成失败，可以复用参数重试。" : "音乐生成中，完成后会展示版本列表和波形播放器。") }}
                              </p>
                            </div>
                            <span
                              class="shrink-0 rounded-full px-3 py-1 text-xs font-medium"
                              :class="canRetryTask(primaryAudioStatusItem?.task.status) ? 'bg-red-500/15 text-red-100' : 'bg-primary/15 text-primary'"
                            >
                              {{ taskStatusLabel(primaryAudioStatusItem?.task.status) }}
                            </span>
                          </div>
                          <div class="mt-6 h-2 overflow-hidden rounded-full bg-white/10">
                            <div
                              class="h-full rounded-full transition-all"
                              :class="canRetryTask(primaryAudioStatusItem?.task.status) ? 'bg-red-400' : 'bg-primary'"
                              :style="{ width: `${Math.max(6, Math.min(primaryAudioStatusItem?.task.progress ?? (isTaskRunning(primaryAudioStatusItem?.task.status) ? 12 : 100), 100))}%` }"
                            />
                          </div>
                          <div class="mt-4 flex flex-wrap items-center justify-between gap-3">
                            <p class="text-xs text-white/35">{{ primaryAudioStatusItem?.task.taskNo }}</p>
                            <div v-if="primaryAudioStatusItem" class="flex gap-2">
                              <button
                                v-if="canCancelTask(primaryAudioStatusItem.task.status)"
                                type="button"
                                class="rounded-xl bg-white/8 px-3 py-2 text-sm font-medium text-white/70 transition hover:bg-white/14 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                                :disabled="
                                  cancellingTaskIds.has(primaryAudioStatusItem.task.taskId) ||
                                  deletingTaskIds.has(primaryAudioStatusItem.task.taskId) ||
                                  retryingTaskIds.has(primaryAudioStatusItem.task.taskId)
                                "
                                @click.stop="cancelQueuedTask(primaryAudioStatusItem.task)"
                              >
                                {{ cancellingTaskIds.has(primaryAudioStatusItem.task.taskId) ? "取消中" : "取消任务" }}
                              </button>
                              <button
                                v-if="canRetryTask(primaryAudioStatusItem.task.status)"
                                type="button"
                                class="rounded-xl bg-red-500/15 px-3 py-2 text-sm font-medium text-red-100 transition hover:bg-red-500 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
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
                                class="rounded-xl bg-primary/15 px-3 py-2 text-sm font-medium text-primary transition hover:bg-primary hover:text-white"
                                @click.stop="replayTask(primaryAudioStatusItem.task)"
                              >
                                再次生成
                              </button>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </section>
                </div>
                <div v-else class="dashboard-history-grid">
                  <article
                    v-for="item in taskMaterials"
                    :key="item.task.taskId"
                    class="group overflow-hidden rounded-2xl border border-white/8 bg-[#191919] shadow-[0_16px_36px_rgb(0_0_0_/_0.24)] transition hover:-translate-y-0.5 hover:border-primary/50"
                    :class="[item.task.status === 'SUCCESS' ? 'cursor-zoom-in' : '', item.historyCardClass]"
                    @click="openAssetPreview(item)"
                  >
                    <div class="relative bg-muted">
                      <template v-if="isTaskRunning(item.task.status) || canRetryTask(item.task.status) || (!item.task.result?.contentText && item.task.status !== 'SUCCESS')">
                        <div
                          class="relative min-h-[300px] overflow-hidden bg-[radial-gradient(circle_at_28%_20%,rgb(176_92_255_/_0.28),transparent_34%),linear-gradient(145deg,rgb(29_30_38),rgb(12_12_14))] p-5"
                          :class="canRetryTask(item.task.status) ? 'ring-1 ring-red-400/25' : ''"
                        >
                          <div class="absolute inset-0 bg-gradient-to-t from-black/75 via-transparent to-transparent" />
                          <div class="relative z-10 flex h-full min-h-[260px] flex-col">
                            <div class="flex items-center justify-between gap-2">
                              <span
                                class="inline-flex items-center gap-2 rounded-full px-3 py-1 text-xs font-medium"
                                :class="
                                  canRetryTask(item.task.status)
                                    ? 'bg-red-500/15 text-red-100 ring-1 ring-red-400/25'
                                    : 'bg-primary/15 text-primary ring-1 ring-primary/25'
                                "
                              >
                                <Loader2 v-if="isTaskRunning(item.task.status)" class="h-3.5 w-3.5 animate-spin" />
                                <X v-else-if="canRetryTask(item.task.status)" class="h-3.5 w-3.5" />
                                <Clock v-else class="h-3.5 w-3.5" />
                                {{ taskStatusLabel(item.task.status) }}
                              </span>
                              <div class="flex items-center gap-2">
                                <button
                                  v-if="canCancelTask(item.task.status)"
                                  type="button"
                                  class="inline-flex items-center gap-1 rounded-full bg-white/10 px-3 py-1 text-xs font-medium text-white/75 transition hover:bg-white/18 hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
                                  :disabled="
                                    cancellingTaskIds.has(item.task.taskId) ||
                                    deletingTaskIds.has(item.task.taskId) ||
                                    retryingTaskIds.has(item.task.taskId)
                                  "
                                  @click.stop="cancelQueuedTask(item.task)"
                                >
                                  <Loader2
                                    v-if="cancellingTaskIds.has(item.task.taskId)"
                                    class="h-3 w-3 animate-spin"
                                  />
                                  <X v-else class="h-3 w-3" />
                                  {{ cancellingTaskIds.has(item.task.taskId) ? "取消中" : "取消" }}
                                </button>
                                <span class="text-xs text-white/35">{{ item.task.progress ?? 0 }}%</span>
                              </div>
                            </div>

                            <div class="mt-auto">
                              <p class="line-clamp-2 text-xl font-semibold text-white">{{ item.task.toolName }}</p>
                              <p class="mt-2 line-clamp-3 text-sm leading-6 text-white/55">
                                {{ item.task.progressMessage || (canRetryTask(item.task.status) ? "任务生成失败，可以复用本次参数重试。" : "任务正在生成，完成后结果会自动出现在这里。") }}
                              </p>
                              <div class="mt-5 h-1.5 overflow-hidden rounded-full bg-white/10">
                                <div
                                  class="h-full rounded-full transition-all"
                                  :class="canRetryTask(item.task.status) ? 'bg-red-400' : 'bg-primary'"
                                  :style="{ width: `${Math.max(6, Math.min(item.task.progress ?? (isTaskRunning(item.task.status) ? 12 : 100), 100))}%` }"
                                />
                              </div>
                            </div>
                          </div>
                        </div>
                      </template>
                      <template v-else-if="primaryBlock(item.blocks)?.type === 'image'">
                        <img
                          :src="primaryBlock(item.blocks)?.images[0]?.url"
                          :alt="item.task.toolName"
                          class="block h-auto w-full"
                          loading="lazy"
                          decoding="async"
                        />
                      </template>
                      <template v-else-if="primaryBlock(item.blocks)?.type === 'video'">
                        <video :src="primaryBlock(item.blocks)?.url" controls playsinline preload="metadata" class="block h-auto w-full bg-black" />
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

                    <div class="space-y-3 p-4">
                      <div class="flex items-start justify-between gap-3">
                        <div class="min-w-0">
                          <h3 class="truncate text-base font-semibold text-white">{{ item.task.toolName }}</h3>
                          <p class="mt-1 truncate text-xs text-white/45">{{ item.task.taskNo }}</p>
                        </div>
                        <div class="flex shrink-0 items-center gap-1 text-xs text-white/35">
                          <Clock class="h-3 w-3" />
                          {{ formatTaskTime(item.task.createdAt) }}
                        </div>
                      </div>
                      <div class="flex items-center justify-between gap-3">
                        <div class="flex flex-wrap items-center gap-2">
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
                            class="rounded-full bg-primary/15 px-3 py-1.5 text-xs font-medium text-primary transition hover:bg-primary hover:text-white"
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
                        <RouterLink
                          :to="item.task.status === 'SUCCESS' ? userRoutes.taskResult(String(item.task.taskId)) : userRoutes.taskStatus(String(item.task.taskId))"
                          class="inline-flex shrink-0 items-center gap-1 text-xs font-medium text-white/45 transition hover:text-white"
                          @click.stop
                        >
                          查看完整内容
                          <ArrowRight class="h-3 w-3" />
                        </RouterLink>
                      </div>
                    </div>
                  </article>
                </div>
                <div ref="historySentinelRef" class="flex min-h-16 items-center justify-center py-6 text-sm text-white/45">
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
        </main>

        <div class="pointer-events-none fixed bottom-6 left-[calc(var(--app-sidebar-width,268px)+(100vw-var(--app-sidebar-width,268px))/2)] z-50 -translate-x-1/2 transition-[left]">
          <button
            v-if="!composerOpen"
            type="button"
            class="pointer-events-auto flex h-16 w-[min(720px,calc(100vw-2rem))] items-center gap-4 rounded-full border border-white/10 bg-[#1e1e24]/0.5 px-5 text-left text-white shadow-[0_24px_90px_rgb(0_0_0_/_0.3)] backdrop-blur-2xl transition hover:border-primary/45 hover:bg-[#252631]/0.7"
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

          <div v-else class="pointer-events-auto w-[min(980px,calc(100vw-2rem))]">
            <button
              type="button"
              class="mb-3 inline-flex h-11 items-center gap-2 rounded-full border border-primary/35 bg-primary/15 px-5 text-sm font-medium text-white shadow-[0_0_32px_rgb(176_92_255_/_0.24)]"
            >
              <WandSparkles class="h-4 w-4" />
              玩法
            </button>

            <div class="relative rounded-3xl border border-white/10 bg-[#1e1e24]/92 p-4 shadow-[0_24px_90px_rgb(0_0_0_/_0.58)] backdrop-blur-2xl">
              <button
                type="button"
                class="absolute right-3 top-3 inline-flex h-8 w-8 items-center justify-center rounded-full text-white/45 transition hover:bg-white/8 hover:text-white"
                aria-label="收起创作窗"
                @click="collapseComposerForPreview"
              >
                <X class="h-4 w-4" />
              </button>
              <div class="flex items-start gap-3">
                <MessageSquareText class="mt-2 h-6 w-6 shrink-0 text-white/50" />
                <textarea
                  v-model="promptText"
                  rows="2"
                  class="min-h-[72px] flex-1 resize-none bg-transparent text-base leading-7 text-white outline-none placeholder:text-white/28"
                  :placeholder="coreFieldPlaceholder"
                  @focus="expandComposer"
                />
              </div>

              <div
                v-if="selectedToolDetailLoading"
                class="mt-3 flex items-center gap-2 rounded-2xl border border-white/8 bg-black/20 px-3 py-2 text-xs text-white/45"
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
                class="mt-3 rounded-2xl border border-white/8 bg-black/18 px-3 py-2"
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
                          class="group overflow-hidden rounded-2xl border bg-white/[0.04] text-left transition hover:-translate-y-0.5 hover:border-primary/50"
                          :class="selectedToolCode === tool.toolCode ? 'border-primary/70' : 'border-white/8'"
                          @click="selectTool(tool)"
                        >
                          <div class="relative h-32 bg-secondary">
                            <video
                              v-if="isVideoPreviewUrl(tool.coverUrl)"
                              :src="normalizeMediaUrl(tool.coverUrl)"
                              class="h-full w-full object-cover"
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
                              class="h-full w-full object-cover"
                            />
                            <div v-else class="flex h-full items-center justify-center">
                              <Sparkles class="h-8 w-8 text-primary" />
                            </div>
                            <div class="absolute inset-0 bg-gradient-to-t from-black/75 to-transparent" />
                            <span class="absolute bottom-3 left-3 rounded-full bg-black/45 px-2 py-0.5 text-xs text-white/70">
                              {{ tool.modelConfigName || tool.modelName || modalityLabel(tool.outputModality) }}
                            </span>
                          </div>
                          <div class="p-4">
                            <h4 class="line-clamp-2 font-semibold text-white">{{ tool.toolName }}</h4>
                            <p class="mt-2 line-clamp-2 text-xs text-white/45">{{ toolDisplayDescription(tool, "模型工具") }}</p>
                            <p class="mt-3 inline-flex items-center gap-1 text-xs text-amber-300">
                              <CreditCostBadge :cost="tool.estimatedCreditCost" />
                            </p>
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

                <button
                  type="button"
                  class="ml-auto inline-flex h-11 min-w-32 items-center justify-center gap-2 rounded-xl bg-[linear-gradient(180deg,rgb(199_128_255),rgb(143_73_226))] px-5 text-sm font-semibold text-white shadow-[0_12px_32px_rgb(176_92_255_/_0.34),inset_0_1px_0_rgb(255_255_255_/_0.16)] transition hover:brightness-110 disabled:cursor-not-allowed disabled:bg-white/12 disabled:text-white/35"
                  :disabled="!selectedTool || submitting"
                  @click="createWithSelectedTool"
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
.audio-wave-hit {
  min-height: 32px;
  cursor: pointer;
}

.dashboard-history-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  grid-auto-flow: dense;
  align-items: start;
  gap: clamp(16px, 2vw, 24px);
}

.dashboard-history-grid > .history-card-wide {
  grid-column: span 2;
}

.dashboard-history-grid > .history-card-tall {
  grid-row: span 2;
}

.dashboard-history-grid > .history-card-pending {
  min-height: 300px;
}

@media (max-width: 900px) {
  .dashboard-history-grid {
    grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  }

  .dashboard-history-grid > .history-card-wide {
    grid-column: span 1;
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
