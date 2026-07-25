<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from "vue"
import { useRoute, useRouter } from "vue-router"
import {
  AlertCircle, ArrowLeft, CheckCircle2, Download, FileImage, FileText,
  Eye, LoaderCircle, PanelRightClose, Presentation, RefreshCw, Settings2, X,
} from "lucide-vue-next"
import {
  pptApi, pptClientRequestId, type PptJob, type PptModelBinding,
  type PptModelOptions, type PptSlideView,
} from "@/api/pptApi"
import { usePptWorkspaceStore } from "@/store/pptWorkspaceStore"
import { usePptJobPolling } from "./composables/usePptJobPolling"
import { userRoutes } from "@/router/userRoutes"
import {
  derivePptStageStates,
  latestReadyPptExport,
  pptStageActionLabel,
  pptStageErrorSummary,
  pptStageStatusLabel,
  preferredPptStage,
  type PptStageType,
  type PptStageViewState,
} from "./pptStageViewState"

const route = useRoute()
const router = useRouter()
const workspace = usePptWorkspaceStore()
const selectedSlide = ref(0)
const actionError = ref("")
const submitting = ref("")
const modelBinding = ref<PptModelBinding | null>(null)
const modelOptions = ref<PptModelOptions>({ textModels: [], imageModels: [] })
const modelSettingsOpen = ref(false)
const modelSettingsSaving = ref(false)
const selectedTextModelId = ref<number | null>(null)
const selectedImageModelId = ref<number | null>(null)
const workbenchEnabled = ref(false)
const workbenchMessage = ref("")
const selectedStageType = ref<PptStageType>("GENERATE_OUTLINE")
const stageSelectionTouched = ref(false)
const generationConfirmOpen = ref(false)
const pendingGenerationStage = ref<PptStageType | null>(null)
const projectId = computed(() => Number(route.params.projectId))
const project = computed(() => workspace.currentProject)
const slides = computed<PptSlideView[]>(() => project.value?.latestDeck?.slides || [])
const activeJob = computed(() => workspace.activeJob)

const stageDefinitions = [
  { type: "GENERATE_OUTLINE" as const, label: "生成大纲", contentLabel: "大纲", icon: FileText },
  { type: "GENERATE_DESCRIPTIONS" as const, label: "完善页面", contentLabel: "页面描述", icon: Presentation },
  { type: "GENERATE_IMAGES" as const, label: "生成视觉", contentLabel: "视觉预览", icon: FileImage },
  { type: "EXPORT_PPTX" as const, label: "生成 PPTX", contentLabel: "PPTX 文件", icon: Download },
]
const stageStates = computed(() => derivePptStageStates(project.value))
const stages = computed(() => stageDefinitions.map((stage) => ({
  ...stage,
  state: stageStates.value[stage.type],
})))
const selectedStageState = computed(() => stageStates.value[selectedStageType.value])
const selectedStageDefinition = computed(() =>
  stageDefinitions.find((stage) => stage.type === selectedStageType.value) || stageDefinitions[0],
)
const selectedSlideView = computed(() => slides.value[selectedSlide.value] || null)
const latestExport = computed(() => latestReadyPptExport(project.value))
const pendingStageState = computed(() =>
  pendingGenerationStage.value ? stageStates.value[pendingGenerationStage.value] : null,
)
const pendingStageDefinition = computed(() =>
  stageDefinitions.find((stage) => stage.type === pendingGenerationStage.value) || null,
)

const { poll, stop, polling } = usePptJobPolling(
  (job) => workspace.patchJob(job),
  async () => {
    const refreshed = await workspace.loadProject(projectId.value, true)
    const next = refreshed.recentJobs.find((job) =>
      ["CREATED", "CREDIT_RESERVED", "SUBMITTED", "QUEUED", "RUNNING", "RECONCILING"].includes(job.status),
    )
    if (next) void poll(next.jobId)
  },
)

onMounted(async () => {
  await load()
})

watch(projectId, () => {
  stop()
  selectedSlide.value = 0
  selectedStageType.value = "GENERATE_OUTLINE"
  stageSelectionTouched.value = false
  generationConfirmOpen.value = false
  pendingGenerationStage.value = null
  void load()
})

watch(slides, (nextSlides) => {
  if (selectedSlide.value >= nextSlides.length) selectedSlide.value = Math.max(0, nextSlides.length - 1)
})

onBeforeUnmount(() => workspace.clearCurrentProject())

async function load() {
  if (!Number.isFinite(projectId.value)) return
  try {
    const [loaded, binding, status, options] = await Promise.all([
      workspace.loadProject(projectId.value),
      pptApi.modelBinding(projectId.value),
      pptApi.status(),
      pptApi.modelOptions(),
    ])
    modelBinding.value = binding
    modelOptions.value = options
    selectedTextModelId.value = binding.textModel.modelConfigId
    selectedImageModelId.value = binding.imageModel.modelConfigId
    workbenchEnabled.value = status.enabled
    workbenchMessage.value = status.message
    if (!stageSelectionTouched.value) {
      selectedStageType.value = preferredPptStage(derivePptStageStates(loaded))
    }
    const running = loaded.recentJobs.find((job) =>
      ["CREATED", "CREDIT_RESERVED", "SUBMITTED", "QUEUED", "RUNNING", "RECONCILING"].includes(job.status),
    )
    if (running) void poll(running.jobId)
  } catch {
    // Store exposes the actionable message.
  }
}

function openModelSettings() {
  if (!modelBinding.value) return
  selectedTextModelId.value = modelBinding.value.textModel.modelConfigId
  selectedImageModelId.value = modelBinding.value.imageModel.modelConfigId
  modelSettingsOpen.value = true
}

async function saveModelSettings() {
  if (activeJob.value || modelSettingsSaving.value) return
  modelSettingsSaving.value = true
  actionError.value = ""
  try {
    modelBinding.value = await pptApi.updateModelBinding(projectId.value, {
      textModelConfigId: selectedTextModelId.value,
      imageModelConfigId: selectedImageModelId.value,
    })
    modelSettingsOpen.value = false
  } catch (cause) {
    actionError.value = cause instanceof Error ? cause.message : "模型设置保存失败"
  } finally {
    modelSettingsSaving.value = false
  }
}

function stageTone(state: PptStageViewState) {
  if (state.status === "RUNNING") return "text-cyan-300"
  if (state.status === "AVAILABLE") return "text-emerald-300"
  if (["AVAILABLE_WITH_WARNING", "STALE"].includes(state.status)) return "text-amber-300"
  if (state.status === "FAILED") return "text-rose-300"
  return "text-white/40"
}

function selectStage(type: PptStageType) {
  selectedStageType.value = type
  stageSelectionTouched.value = true
}

function requestGeneration(type: PptStageType) {
  if (!workbenchEnabled.value || activeJob.value || submitting.value) return
  pendingGenerationStage.value = type
  generationConfirmOpen.value = true
}

function closeGenerationConfirm() {
  if (submitting.value) return
  generationConfirmOpen.value = false
  pendingGenerationStage.value = null
}

async function confirmGeneration() {
  const type = pendingGenerationStage.value
  if (!type) return
  generationConfirmOpen.value = false
  pendingGenerationStage.value = null
  await submitJob(type)
}

async function submitJob(jobType: PptStageType) {
  if (!workbenchEnabled.value || submitting.value || activeJob.value) return
  submitting.value = jobType
  actionError.value = ""
  try {
    const job = await pptApi.submitJob(projectId.value, {
      jobType,
      clientRequestId: pptClientRequestId(jobType.toLowerCase()),
      payload: { pageCount: project.value?.pageCount, topic: project.value?.topic },
    })
    workspace.patchJob(job)
    if (["CREATED", "CREDIT_RESERVED", "SUBMITTED", "QUEUED", "RUNNING", "RECONCILING"].includes(job.status)) void poll(job.jobId)
    else await workspace.loadProject(projectId.value, true)
  } catch (cause) {
    actionError.value = cause instanceof Error ? cause.message : "任务提交失败"
  } finally {
    submitting.value = ""
  }
}

function download(url?: string | null) {
  if (url) window.location.assign(url)
}

function handlePrimaryExport() {
  if (latestExport.value?.downloadUrl) download(latestExport.value.downloadUrl)
  else requestGeneration("EXPORT_PPTX")
}

function stageModelLabel(type: PptStageType | null) {
  if (!modelBinding.value || !type) return "平台托管模型"
  if (type === "GENERATE_IMAGES") return modelBinding.value.imageModel.displayName
  if (type === "EXPORT_PPTX") return "使用当前视觉稿，无需调用模型"
  return modelBinding.value.textModel.displayName
}

function formatDate(value?: string | null) {
  if (!value) return "时间未知"
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime())
    ? value
    : new Intl.DateTimeFormat("zh-CN", {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    }).format(parsed)
}

function formatFileSize(size?: number | null) {
  if (!size || size < 1) return "大小未知"
  if (size < 1024 * 1024) return `${Math.max(1, Math.round(size / 1024))} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function stageErrorDetail(state: PptStageViewState) {
  return state.latestAttempt?.status === "FAILED" ? state.latestAttempt.error?.message || "" : ""
}

function jobStatusLabel(job?: PptJob | null) {
  if (!job) return "尚未开始"
  return {
    CREATED: "正在初始化",
    CREDIT_RESERVED: "算力已预留",
    SUBMITTED: "已提交引擎",
    QUEUED: "排队中",
    RUNNING: "生成中",
    RECONCILING: "正在协调引擎",
    SUCCEEDED: "已完成",
    FAILED: "失败",
    CANCELLED: "已取消",
  }[job.status] || job.status
}
</script>

<template>
  <main class="flex h-full min-h-0 flex-col bg-[#06090e] text-white">
    <header class="flex h-16 shrink-0 items-center justify-between border-b border-white/8 bg-[#0a0e14] px-4 lg:px-6">
      <div class="flex min-w-0 items-center gap-3">
        <button class="grid h-9 w-9 shrink-0 place-items-center rounded-lg text-white/50 hover:bg-white/5 hover:text-white" type="button" @click="router.push(userRoutes.ppt)">
          <ArrowLeft class="h-4 w-4" />
        </button>
        <div class="min-w-0">
          <h1 class="truncate text-sm font-semibold">{{ project?.title || "PPT 工作台" }}</h1>
          <p class="truncate text-[11px] text-white/35">{{ project?.aspectRatio }} · {{ project?.pageCount }} 页 · {{ project?.engineStrategy }}</p>
        </div>
      </div>
      <div class="flex items-center gap-2">
        <span v-if="activeJob" class="hidden items-center gap-2 rounded-lg bg-cyan-300/10 px-3 py-2 text-xs text-cyan-200 sm:flex">
          <LoaderCircle class="h-3.5 w-3.5 animate-spin" /> {{ jobStatusLabel(activeJob) }} {{ activeJob.progress }}%
        </span>
        <button
          class="grid h-9 w-9 place-items-center rounded-lg border border-white/10 text-white/55 hover:border-cyan-300/40 hover:text-white disabled:cursor-not-allowed disabled:opacity-40"
          type="button"
          title="模型设置"
          :disabled="!workbenchEnabled || Boolean(activeJob)"
          @click="openModelSettings"
        >
          <Settings2 class="h-4 w-4" />
        </button>
        <button
          class="flex h-9 items-center gap-2 rounded-lg border border-white/10 px-3 text-xs text-white/65 hover:border-cyan-300/40 hover:text-white disabled:cursor-not-allowed disabled:opacity-45"
          type="button"
          :disabled="!latestExport && (!workbenchEnabled || Boolean(activeJob))"
          @click="handlePrimaryExport"
        >
          <Download class="h-3.5 w-3.5" /> {{ latestExport ? "下载最新 PPTX" : "生成 PPTX" }}
        </button>
      </div>
    </header>

    <div v-if="workspace.loading && !project" class="grid flex-1 place-items-center">
      <LoaderCircle class="h-7 w-7 animate-spin text-cyan-300" />
    </div>
    <div v-else-if="workspace.error && !project" class="grid flex-1 place-items-center px-6 text-center">
      <div><AlertCircle class="mx-auto mb-3 h-8 w-8 text-rose-300" /><p>{{ workspace.error }}</p></div>
    </div>

    <div v-else class="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-[230px_minmax(0,1fr)_300px]">
      <aside class="hidden min-h-0 overflow-y-auto border-r border-white/8 bg-[#090d13] p-3 lg:block">
        <div class="mb-3 flex items-center justify-between px-2 py-1 text-xs text-white/40">
          <span>页面</span><span>{{ slides.length || project?.pageCount || 0 }}</span>
        </div>
        <button
          v-for="(slide, index) in slides"
          :key="slide.slideId || index"
          type="button"
          class="mb-3 w-full rounded-xl border p-2 text-left transition"
          :class="selectedSlide === index ? 'border-cyan-300/60 bg-cyan-300/[0.06]' : 'border-white/8 bg-white/[0.02] hover:border-white/20'"
          @click="selectedSlide = index"
        >
          <div class="aspect-video overflow-hidden rounded-lg bg-slate-900">
            <img v-if="slide.previewUrl" :src="slide.previewUrl" alt="" class="h-full w-full object-cover" />
            <div v-else class="grid h-full place-items-center text-white/20"><Presentation class="h-6 w-6" /></div>
          </div>
          <p class="mt-2 truncate px-1 text-xs text-white/65">{{ index + 1 }}. {{ slide.title || "待生成页面" }}</p>
        </button>
        <div v-if="!slides.length" class="rounded-xl border border-dashed border-white/10 px-4 py-10 text-center text-xs leading-5 text-white/35">
          大纲完成后，这里会出现每一页。
        </div>
      </aside>

      <section class="relative flex min-h-0 flex-col overflow-y-auto p-5 lg:p-8">
        <div v-if="!workbenchEnabled && workbenchMessage" class="mb-4 rounded-xl border border-amber-300/20 bg-amber-300/10 px-4 py-3 text-sm text-amber-100">
          {{ workbenchMessage }} 已生成的文件仍可下载。
        </div>
        <div v-if="actionError" class="mb-4 rounded-xl border border-rose-400/25 bg-rose-400/10 px-4 py-3 text-sm text-rose-200">{{ actionError }}</div>

        <header class="mx-auto mb-5 flex w-full max-w-5xl flex-wrap items-start justify-between gap-4">
          <div>
            <div class="flex items-center gap-2">
              <h2 class="text-lg font-semibold">{{ selectedStageDefinition.contentLabel }}</h2>
              <span
                class="rounded-full border px-2 py-0.5 text-[10px]"
                :class="{
                  'border-emerald-300/20 bg-emerald-300/10 text-emerald-200': selectedStageState.status === 'AVAILABLE',
                  'border-cyan-300/20 bg-cyan-300/10 text-cyan-200': selectedStageState.status === 'RUNNING',
                  'border-amber-300/20 bg-amber-300/10 text-amber-200': ['AVAILABLE_WITH_WARNING','STALE'].includes(selectedStageState.status),
                  'border-rose-300/20 bg-rose-300/10 text-rose-200': selectedStageState.status === 'FAILED',
                  'border-white/10 bg-white/5 text-white/40': selectedStageState.status === 'NOT_STARTED',
                }"
              >
                {{ pptStageStatusLabel(selectedStageState) }}
              </span>
            </div>
            <p class="mt-1 text-xs text-white/35">点击右侧阶段只会查看内容，不会触发生成或扣费。</p>
          </div>
          <button
            class="flex h-9 items-center gap-2 rounded-lg bg-cyan-400 px-3 text-xs font-semibold text-slate-950 hover:bg-cyan-300 disabled:cursor-not-allowed disabled:opacity-40"
            type="button"
            :disabled="!workbenchEnabled || Boolean(activeJob) || Boolean(submitting)"
            @click="requestGeneration(selectedStageType)"
          >
            <RefreshCw class="h-3.5 w-3.5" /> {{ pptStageActionLabel(selectedStageState) }}
          </button>
        </header>

        <div
          v-if="selectedStageState.status === 'AVAILABLE_WITH_WARNING' || selectedStageState.status === 'FAILED'"
          class="mx-auto mb-5 w-full max-w-5xl rounded-xl border p-4"
          :class="selectedStageState.artifactAvailable ? 'border-amber-300/20 bg-amber-300/[0.07]' : 'border-rose-300/20 bg-rose-300/[0.07]'"
        >
          <div class="flex gap-3">
            <AlertCircle class="mt-0.5 h-4 w-4 shrink-0" :class="selectedStageState.artifactAvailable ? 'text-amber-300' : 'text-rose-300'" />
            <div class="min-w-0">
              <p class="text-sm" :class="selectedStageState.artifactAvailable ? 'text-amber-100' : 'text-rose-100'">
                {{ pptStageErrorSummary(selectedStageState.latestAttempt) }}
                <span v-if="selectedStageState.artifactAvailable">，当前仍展示上一次成功内容。</span>
              </p>
              <details v-if="stageErrorDetail(selectedStageState)" class="mt-2 text-xs text-white/35">
                <summary class="cursor-pointer select-none hover:text-white/55">技术详情</summary>
                <p class="mt-2 break-words rounded-lg bg-black/20 p-2 font-mono">{{ stageErrorDetail(selectedStageState) }}</p>
              </details>
            </div>
          </div>
        </div>
        <div v-else-if="selectedStageState.stale" class="mx-auto mb-5 w-full max-w-5xl rounded-xl border border-amber-300/20 bg-amber-300/[0.07] px-4 py-3 text-sm text-amber-100">
          上游内容已更新，本阶段仍可查看和下载，但建议重新生成以保持内容一致。
        </div>

        <div class="mx-auto flex w-full max-w-5xl flex-1 items-center justify-center">
          <div v-if="selectedStageType === 'GENERATE_OUTLINE' && slides.length" class="w-full self-start">
            <div class="space-y-3">
              <button
                v-for="(slide, index) in slides"
                :key="slide.slideId || `outline-${index}`"
                type="button"
                class="flex w-full items-start gap-4 rounded-xl border p-4 text-left transition"
                :class="selectedSlide === index ? 'border-cyan-300/45 bg-cyan-300/[0.06]' : 'border-white/8 bg-white/[0.02] hover:border-white/20'"
                @click="selectedSlide = index"
              >
                <span class="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-white/5 text-xs text-cyan-200">{{ index + 1 }}</span>
                <span class="min-w-0">
                  <strong class="block text-sm text-white/85">{{ slide.title || `第 ${index + 1} 页` }}</strong>
                  <small class="mt-1 block text-xs text-white/35">{{ slide.description ? "已有页面描述" : "等待完善页面内容" }}</small>
                </span>
              </button>
            </div>
          </div>

          <div v-else-if="selectedStageType === 'GENERATE_DESCRIPTIONS' && selectedSlideView" class="w-full">
            <div class="rounded-2xl border border-white/10 bg-[#101722] p-6 shadow-2xl shadow-black/30 lg:p-10">
              <p class="text-xs font-medium uppercase tracking-[0.2em] text-cyan-300/70">第 {{ selectedSlide + 1 }} 页</p>
              <h3 class="mt-4 text-2xl font-semibold">{{ selectedSlideView.title || `第 ${selectedSlide + 1} 页` }}</h3>
              <p v-if="selectedSlideView.description" class="mt-6 whitespace-pre-wrap text-sm leading-8 text-white/65">{{ selectedSlideView.description }}</p>
              <div v-else class="mt-6 rounded-xl border border-dashed border-white/10 px-4 py-8 text-center text-sm text-white/35">
                这一页尚无页面描述。
              </div>
            </div>
          </div>

          <div v-else-if="selectedStageType === 'GENERATE_IMAGES' && selectedSlideView" class="w-full">
            <div class="aspect-video overflow-hidden rounded-2xl border border-white/10 bg-[#101722] shadow-2xl shadow-black/40">
              <img v-if="selectedSlideView.previewUrl" :src="selectedSlideView.previewUrl" :alt="selectedSlideView.title || `第 ${selectedSlide + 1} 页视觉预览`" class="h-full w-full object-contain" />
              <div v-else class="flex h-full flex-col justify-center p-[8%]">
                <span class="mb-5 h-1 w-14 rounded bg-cyan-300" />
                <h3 class="max-w-3xl text-2xl font-semibold lg:text-4xl">{{ selectedSlideView.title || `第 ${selectedSlide + 1} 页` }}</h3>
                <p class="mt-5 max-w-2xl text-sm leading-7 text-white/45">{{ selectedSlideView.description || "视觉内容尚未生成，可以先检查这一页是否讲清楚了一个观点。" }}</p>
              </div>
            </div>
          </div>

          <div v-else-if="selectedStageType === 'EXPORT_PPTX' && project?.exports?.length" class="w-full self-start">
            <div class="space-y-3">
              <button
                v-for="item in project.exports"
                :key="item.exportId"
                class="flex w-full items-center gap-4 rounded-xl border border-white/8 bg-white/[0.025] p-4 text-left transition hover:border-cyan-300/30 disabled:cursor-not-allowed disabled:opacity-40"
                type="button"
                :disabled="item.status !== 'READY' || !item.downloadUrl"
                @click="download(item.downloadUrl)"
              >
                <span class="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-cyan-300/10 text-cyan-200"><Download class="h-4 w-4" /></span>
                <span class="min-w-0 flex-1">
                  <strong class="block truncate text-sm">{{ item.fileName || item.exportType }}</strong>
                  <small class="mt-1 block text-xs text-white/35">{{ formatDate(item.createdAt) }} · {{ formatFileSize(item.fileSize) }}</small>
                </span>
                <span class="text-xs text-cyan-300">{{ item.status === "READY" ? "下载" : item.status }}</span>
              </button>
            </div>
          </div>

          <div v-else class="max-w-lg text-center">
            <span class="mx-auto mb-5 grid h-16 w-16 place-items-center rounded-2xl bg-cyan-300/10 text-cyan-300"><Presentation class="h-8 w-8" /></span>
            <h3 class="text-xl font-semibold">{{ activeJob?.jobType === selectedStageType ? jobStatusLabel(activeJob) : `${selectedStageDefinition.contentLabel}尚无内容` }}</h3>
            <p class="mt-3 text-sm leading-6 text-white/40">
              {{ activeJob?.jobType === selectedStageType ? activeJob.progressMessage : "可以继续查看其他阶段，或使用上方按钮明确生成当前阶段。" }}
            </p>
          </div>
        </div>
      </section>

      <aside class="min-h-0 overflow-y-auto border-l border-white/8 bg-[#090d13] p-5">
        <div class="mb-6 flex items-center justify-between">
          <div><p class="text-sm font-semibold">生成流程</p><p class="mt-1 text-xs text-white/35">选择阶段查看，生成需确认</p></div>
          <PanelRightClose class="h-4 w-4 text-white/25" />
        </div>
        <div class="space-y-3">
          <button
            v-for="stage in stages"
            :key="stage.type"
            type="button"
            class="flex w-full items-center gap-3 rounded-xl border p-3 text-left transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-cyan-300/60"
            :class="selectedStageType === stage.type ? 'border-cyan-300/45 bg-cyan-300/[0.07]' : 'border-white/8 bg-white/[0.025] hover:border-cyan-300/25'"
            :aria-pressed="selectedStageType === stage.type"
            @click="selectStage(stage.type)"
          >
            <span class="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-white/5" :class="stageTone(stage.state)">
              <LoaderCircle v-if="stage.state.status === 'RUNNING'" class="h-4 w-4 animate-spin" />
              <CheckCircle2 v-else-if="stage.state.status === 'AVAILABLE'" class="h-4 w-4" />
              <AlertCircle v-else-if="['FAILED','AVAILABLE_WITH_WARNING','STALE'].includes(stage.state.status)" class="h-4 w-4" />
              <component :is="stage.icon" v-else class="h-4 w-4" />
            </span>
            <span class="min-w-0 flex-1">
              <strong class="block text-sm">{{ stage.label }}</strong>
              <small class="mt-0.5 block truncate" :class="['FAILED','AVAILABLE_WITH_WARNING','STALE'].includes(stage.state.status) ? 'text-amber-200/60' : 'text-white/35'">
                {{ pptStageStatusLabel(stage.state) }}
              </small>
            </span>
            <Eye class="h-3.5 w-3.5" :class="selectedStageType === stage.type ? 'text-cyan-300' : 'text-white/25'" />
          </button>
        </div>

        <div class="mt-5 rounded-xl border border-cyan-300/10 bg-cyan-300/[0.04] px-3 py-2 text-[11px] leading-5 text-cyan-100/55">
          <Eye class="mr-1 inline h-3 w-3" /> 浏览阶段不会提交任务，也不会产生算力消耗。
        </div>

        <div class="mt-6 rounded-xl border border-white/8 bg-black/20 p-3 text-[11px] leading-5 text-white/35">
          <div class="flex items-center gap-2"><span class="h-2 w-2 rounded-full" :class="polling ? 'bg-cyan-300' : 'bg-white/20'" />任务恢复监测</div>
          <template v-if="modelBinding">
            <div class="mt-2 flex items-center justify-between gap-2">
              <p class="text-cyan-200/70">平台模型 · 无需单独配置</p>
              <button
                class="text-cyan-300 hover:text-cyan-200 disabled:cursor-not-allowed disabled:opacity-40"
                type="button"
                :disabled="!workbenchEnabled || Boolean(activeJob)"
                @click="openModelSettings"
              >更换</button>
            </div>
            <p>文本：{{ modelBinding.textModel.displayName }}</p>
            <p>视觉：{{ modelBinding.imageModel.displayName }}</p>
          </template>
          <p class="mt-1">扣费状态：{{ activeJob?.creditState || project?.recentJobs?.[0]?.creditState || "未冻结" }}</p>
        </div>
      </aside>
    </div>

    <div
      v-if="generationConfirmOpen && pendingStageState && pendingStageDefinition"
      class="fixed inset-0 z-50 grid place-items-center bg-black/70 px-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby="ppt-generation-confirm-title"
      @click.self="closeGenerationConfirm"
    >
      <section class="w-full max-w-lg rounded-2xl border border-white/10 bg-[#0c1119] p-5 shadow-2xl shadow-black/60">
        <header class="flex items-start justify-between gap-4">
          <div>
            <p class="text-xs font-medium uppercase tracking-[0.18em] text-cyan-300/65">算力操作确认</p>
            <h2 id="ppt-generation-confirm-title" class="mt-2 text-lg font-semibold">{{ pptStageActionLabel(pendingStageState) }}</h2>
          </div>
          <button class="grid h-8 w-8 shrink-0 place-items-center rounded-lg text-white/45 hover:bg-white/5 hover:text-white" type="button" aria-label="关闭生成确认" @click="closeGenerationConfirm">
            <X class="h-4 w-4" />
          </button>
        </header>

        <div class="mt-5 space-y-3 rounded-xl border border-white/8 bg-white/[0.025] p-4 text-sm">
          <div class="flex justify-between gap-4"><span class="text-white/40">执行阶段</span><strong>{{ pendingStageDefinition.contentLabel }}</strong></div>
          <div class="flex justify-between gap-4"><span class="text-white/40">使用模型</span><strong class="text-right">{{ stageModelLabel(pendingGenerationStage) }}</strong></div>
          <div class="flex justify-between gap-4">
            <span class="text-white/40">算力参考</span>
            <strong>
              {{ pendingStageState.latestAttempt?.reservedCredits ? `约 ${pendingStageState.latestAttempt.reservedCredits} 算力` : "按平台实际调用结算" }}
            </strong>
          </div>
        </div>

        <div class="mt-4 rounded-xl border border-amber-300/15 bg-amber-300/[0.06] px-4 py-3 text-xs leading-5 text-amber-100/80">
          本次只生成当前阶段，不会自动更新后续阶段。已有成功内容会保留到新任务成功，不会因失败被覆盖。
        </div>

        <footer class="mt-6 flex justify-end gap-3">
          <button class="h-10 rounded-xl border border-white/10 px-4 text-sm text-white/60 hover:text-white" type="button" @click="closeGenerationConfirm">取消</button>
          <button
            class="flex h-10 items-center gap-2 rounded-xl bg-cyan-400 px-4 text-sm font-semibold text-slate-950 hover:bg-cyan-300 disabled:cursor-not-allowed disabled:opacity-40"
            type="button"
            :disabled="Boolean(activeJob) || Boolean(submitting)"
            @click="confirmGeneration"
          >
            <RefreshCw class="h-4 w-4" /> 确认生成
          </button>
        </footer>
      </section>
    </div>

    <div v-if="modelSettingsOpen" class="fixed inset-0 z-50 grid place-items-center bg-black/70 px-4 backdrop-blur-sm" @click.self="modelSettingsOpen = false">
      <section class="w-full max-w-lg rounded-2xl border border-white/10 bg-[#0c1119] p-5 shadow-2xl shadow-black/60">
        <header class="flex items-start justify-between gap-4">
          <div>
            <h2 class="text-base font-semibold">PPT 生成模型</h2>
            <p class="mt-1 text-xs leading-5 text-white/40">选择平台已启用的模型。这里只保存模型 ID，不会向 PPT 引擎传递任何密钥。</p>
          </div>
          <button class="grid h-8 w-8 shrink-0 place-items-center rounded-lg text-white/45 hover:bg-white/5 hover:text-white" type="button" aria-label="关闭模型设置" @click="modelSettingsOpen = false">
            <X class="h-4 w-4" />
          </button>
        </header>
        <div class="mt-6 space-y-4">
          <label class="block">
            <span class="mb-2 block text-xs font-medium text-white/55">文本模型 · 大纲与页面描述</span>
            <select v-model="selectedTextModelId" class="h-11 w-full rounded-xl border border-white/10 bg-[#101720] px-3 text-sm outline-none focus:border-cyan-300/60">
              <option v-for="model in modelOptions.textModels" :key="model.modelConfigId" :value="model.modelConfigId">
                {{ model.displayName }}{{ model.recommended ? " · 推荐" : "" }}
              </option>
            </select>
          </label>
          <label class="block">
            <span class="mb-2 block text-xs font-medium text-white/55">图像模型 · 页面视觉</span>
            <select v-model="selectedImageModelId" class="h-11 w-full rounded-xl border border-white/10 bg-[#101720] px-3 text-sm outline-none focus:border-cyan-300/60">
              <option v-for="model in modelOptions.imageModels" :key="model.modelConfigId" :value="model.modelConfigId">
                {{ model.displayName }}{{ model.recommended ? " · 推荐" : "" }}
              </option>
            </select>
          </label>
          <p v-if="activeJob" class="rounded-xl border border-amber-300/20 bg-amber-300/10 px-3 py-2 text-xs text-amber-100">当前任务完成后才能更换，避免同一阶段混用模型。</p>
        </div>
        <footer class="mt-6 flex justify-end gap-3">
          <button class="h-10 rounded-xl border border-white/10 px-4 text-sm text-white/60 hover:text-white" type="button" @click="modelSettingsOpen = false">取消</button>
          <button
            class="flex h-10 items-center gap-2 rounded-xl bg-cyan-400 px-4 text-sm font-semibold text-slate-950 hover:bg-cyan-300 disabled:cursor-not-allowed disabled:opacity-40"
            type="button"
            :disabled="Boolean(activeJob) || modelSettingsSaving || selectedTextModelId === null || selectedImageModelId === null"
            @click="saveModelSettings"
          >
            <LoaderCircle v-if="modelSettingsSaving" class="h-4 w-4 animate-spin" />保存设置
          </button>
        </footer>
      </section>
    </div>
  </main>
</template>
