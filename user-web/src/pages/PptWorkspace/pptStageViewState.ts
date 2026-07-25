import type { PptExport, PptJob, PptProject } from "@/api/pptApi"

export const PPT_STAGE_TYPES = [
  "GENERATE_OUTLINE",
  "GENERATE_DESCRIPTIONS",
  "GENERATE_IMAGES",
  "EXPORT_PPTX",
] as const

export type PptStageType = (typeof PPT_STAGE_TYPES)[number]
export type PptStageUiStatus =
  | "NOT_STARTED"
  | "RUNNING"
  | "AVAILABLE"
  | "AVAILABLE_WITH_WARNING"
  | "FAILED"
  | "STALE"

export interface PptStageViewState {
  type: PptStageType
  status: PptStageUiStatus
  artifactAvailable: boolean
  stale: boolean
  latestAttempt: PptJob | null
  latestSuccess: PptJob | null
  latestReadyExport: PptExport | null
}

const ACTIVE_STATUSES = new Set([
  "CREATED",
  "CREDIT_RESERVED",
  "SUBMITTED",
  "QUEUED",
  "RUNNING",
  "RECONCILING",
])

const STAGE_LABELS: Record<PptStageType, string> = {
  GENERATE_OUTLINE: "大纲",
  GENERATE_DESCRIPTIONS: "页面描述",
  GENERATE_IMAGES: "页面视觉",
  EXPORT_PPTX: "PPTX",
}

function timestamp(value?: string | null): number {
  if (!value) return 0
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function newestReadyExport(exports: PptExport[]): PptExport | null {
  return exports
    .filter((item) => item.status === "READY")
    .sort((left, right) => timestamp(right.createdAt) - timestamp(left.createdAt))[0] || null
}

function hasOutline(project: PptProject): boolean {
  const deck = project.latestDeck
  return Boolean(deck?.outline) || Boolean(deck?.slides?.some((slide) => slide.title?.trim()))
}

function hasDescriptions(project: PptProject): boolean {
  return Boolean(project.latestDeck?.slides?.some((slide) => slide.description?.trim()))
}

function hasImages(project: PptProject): boolean {
  return Boolean(project.latestDeck?.slides?.some((slide) => slide.previewUrl?.trim()))
}

function stageSuccessTime(
  type: PptStageType,
  latestSuccess: PptJob | null,
  readyExport: PptExport | null,
): number {
  if (type === "EXPORT_PPTX") return timestamp(readyExport?.createdAt)
  return timestamp(latestSuccess?.finishedAt || latestSuccess?.updatedAt)
}

export function derivePptStageStates(project: PptProject | null): Record<PptStageType, PptStageViewState> {
  const jobs = project?.recentJobs || []
  const exports = project?.exports || []
  const readyExport = newestReadyExport(exports)
  const availability: Record<PptStageType, boolean> = {
    GENERATE_OUTLINE: project ? hasOutline(project) : false,
    GENERATE_DESCRIPTIONS: project ? hasDescriptions(project) : false,
    GENERATE_IMAGES: project ? hasImages(project) : false,
    EXPORT_PPTX: Boolean(readyExport),
  }

  const latestAttempts = Object.fromEntries(
    PPT_STAGE_TYPES.map((type) => [type, jobs.find((job) => job.jobType === type) || null]),
  ) as Record<PptStageType, PptJob | null>
  const latestSuccesses = Object.fromEntries(
    PPT_STAGE_TYPES.map((type) => [
      type,
      jobs.find((job) => job.jobType === type && job.status === "SUCCEEDED") || null,
    ]),
  ) as Record<PptStageType, PptJob | null>

  const states = {} as Record<PptStageType, PptStageViewState>
  PPT_STAGE_TYPES.forEach((type, index) => {
    const latestAttempt = latestAttempts[type]
    const latestSuccess = latestSuccesses[type]
    const artifactAvailable = availability[type]
    const upstreamType = index > 0 ? PPT_STAGE_TYPES[index - 1] : null
    const upstreamTime = upstreamType
      ? stageSuccessTime(upstreamType, latestSuccesses[upstreamType], readyExport)
      : 0
    const currentTime = stageSuccessTime(type, latestSuccess, readyExport)
    const stale = artifactAvailable && upstreamTime > 0 && currentTime > 0 && upstreamTime > currentTime

    let status: PptStageUiStatus = "NOT_STARTED"
    if (latestAttempt && ACTIVE_STATUSES.has(latestAttempt.status)) status = "RUNNING"
    else if (artifactAvailable && latestAttempt?.status === "FAILED") status = "AVAILABLE_WITH_WARNING"
    else if (stale) status = "STALE"
    else if (artifactAvailable) status = "AVAILABLE"
    else if (latestAttempt?.status === "FAILED") status = "FAILED"

    states[type] = {
      type,
      status,
      artifactAvailable,
      stale,
      latestAttempt,
      latestSuccess,
      latestReadyExport: type === "EXPORT_PPTX" ? readyExport : null,
    }
  })
  return states
}

export function preferredPptStage(states: Record<PptStageType, PptStageViewState>): PptStageType {
  for (const type of ["GENERATE_IMAGES", "GENERATE_DESCRIPTIONS", "GENERATE_OUTLINE"] as PptStageType[]) {
    if (states[type].artifactAvailable) return type
  }
  return PPT_STAGE_TYPES.find((type) => states[type].status === "RUNNING") || "GENERATE_OUTLINE"
}

export function pptStageStatusLabel(state: PptStageViewState): string {
  const labels: Record<PptStageUiStatus, string> = {
    NOT_STARTED: "尚未开始",
    RUNNING: "正在生成",
    AVAILABLE: "内容可用",
    AVAILABLE_WITH_WARNING: "内容可用 · 上次尝试失败",
    FAILED: "生成失败",
    STALE: "建议更新",
  }
  return labels[state.status]
}

export function pptStageActionLabel(state: PptStageViewState): string {
  if (state.status === "FAILED") return "重试本阶段"
  if (state.status === "STALE") return "更新本阶段"
  if (state.artifactAvailable) return "重新生成本阶段"
  return "生成本阶段"
}

export function pptStageErrorSummary(job: PptJob | null): string {
  if (!job || job.status !== "FAILED") return ""
  const engineMessages: Record<string, string> = {
    PPT_ENGINE_UNAVAILABLE: "PPT 引擎暂时不可用，请稍后重试",
    PPT_ENGINE_BUSY: "当前生成任务较多，请稍后重试",
    ENGINE_BUSY: "当前生成任务较多，请稍后重试",
    ENGINE_INTERRUPTED: "生成服务重启导致任务中断，可从本阶段重试",
    PPT_ENGINE_OUTCOME_UNKNOWN: "引擎提交结果无法确认，预留算力已释放",
    PPT_STAGE_TIMEOUT: "本阶段生成超时，预留算力已释放",
  }
  const mapped = job.error?.code ? engineMessages[job.error.code] : undefined
  if (mapped) return mapped
  const type = PPT_STAGE_TYPES.includes(job.jobType as PptStageType)
    ? job.jobType as PptStageType
    : null
  const fallback = type ? `${STAGE_LABELS[type]}生成失败` : "任务生成失败"
  const message = job.error?.message || ""
  const pageCounts = message.match(/for\s+(\d+)\/(\d+)\s+pages/i)
  if (!pageCounts) return fallback
  return `${fallback}（${pageCounts[1]}/${pageCounts[2]} 页）`
}

export function latestReadyPptExport(project: PptProject | null): PptExport | null {
  return newestReadyExport(project?.exports || [])
}
