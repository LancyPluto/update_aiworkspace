import type { TaskDetail, TaskStatus } from "@/api/types"

export interface TaskProgressView {
  percent: number
  percentLabel: string
  caption: string
  estimated: boolean
}

const ACTIVE_STATUSES = new Set<TaskStatus>(["CREATED", "QUEUED", "PROCESSING", "RETRYING"])
const REALTIME_PROGRESS_PATTERN = /实时进度|真实进度|厂商进度/i

function clampPercent(value?: number | null): number {
  if (typeof value !== "number" || !Number.isFinite(value)) return 0
  return Math.max(0, Math.min(100, Math.round(value)))
}

function expectedDurationMs(task: Pick<TaskDetail, "outputModality" | "toolType">): number {
  const text = `${task.outputModality || ""} ${task.toolType || ""}`.toLowerCase()
  if (text.includes("image") || text.includes("图片")) return 24_000
  if (text.includes("audio") || text.includes("音频")) return 35_000
  return 50_000
}

function isImageTask(task: Pick<TaskDetail, "outputModality" | "toolType">): boolean {
  const text = `${task.outputModality || ""} ${task.toolType || ""}`.toLowerCase()
  return text.includes("image") || text.includes("图片")
}

function timestamp(value?: string | null): number | null {
  if (!value) return null
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : null
}

function activeStartedAt(task: Pick<TaskDetail, "startedAt" | "queuedAt" | "createdAt">): number {
  return timestamp(task.startedAt) ?? timestamp(task.queuedAt) ?? timestamp(task.createdAt) ?? Date.now()
}

function estimateProgress(task: TaskDetail, now: number): number {
  const base = clampPercent(task.progress)
  if (base >= 90) return base
  const elapsed = Math.max(0, now - activeStartedAt(task))
  const expected = expectedDurationMs(task)
  const estimated = 8 + Math.floor((elapsed / expected) * 80)
  return Math.max(base, Math.min(88, estimated))
}

function estimateImageProgress(task: TaskDetail, now: number): number {
  const elapsedSeconds = Math.floor(Math.max(0, now - activeStartedAt(task)) / 1000)
  return Math.max(1, Math.min(99, elapsedSeconds))
}

function taskStatusCaption(status: TaskStatus): string {
  if (status === "CREATED") return "任务已创建"
  if (status === "QUEUED") return "排队中"
  if (status === "RETRYING") return "生成遇到问题，正在重试"
  if (status === "SUCCESS") return "生成完成"
  if (status === "FAILED") return "生成失败"
  if (status === "TIMEOUT") return "任务超时"
  if (status === "CANCELLED") return "任务已取消"
  return "AI 正在生成"
}

function progressCaption(task: TaskDetail, estimated: boolean): string {
  const message = task.progressMessage?.trim()
  if (message) return message
  if (estimated && task.status === "PROCESSING") return "模型正在生成内容，完成后会保存到资产中。"
  return taskStatusCaption(task.status)
}

export function buildTaskProgressView(task: TaskDetail, now = Date.now()): TaskProgressView {
  if (task.status === "SUCCESS") {
    return {
      percent: 100,
      percentLabel: "100%",
      caption: "生成完成",
      estimated: false,
    }
  }

  const exactProgress = REALTIME_PROGRESS_PATTERN.test(task.progressMessage || "")
  const active = ACTIVE_STATUSES.has(task.status)
  const estimated = active && !exactProgress
  const percent = estimated
    ? isImageTask(task) ? estimateImageProgress(task, now) : estimateProgress(task, now)
    : clampPercent(task.progress)

  return {
    percent,
    percentLabel: `${estimated ? "预计 " : ""}${percent}%`,
    caption: progressCaption(task, estimated),
    estimated,
  }
}
