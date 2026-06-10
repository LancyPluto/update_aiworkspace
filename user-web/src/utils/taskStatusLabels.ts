import type { TaskStatus } from "@/api/types"

export type TaskStatusViewKind = "running" | "success" | "failed" | "queued" | "cancelled"

/** 与交付文档《统一前端状态文案》一致 */
export const TASK_STATUS_DOC_LABELS: Record<TaskStatus, string> = {
  CREATED: "任务已创建",
  QUEUED: "排队中",
  PROCESSING: "AI 正在生成",
  RETRYING: "生成遇到问题，正在重试",
  SUCCESS: "生成完成",
  FAILED: "生成失败",
  TIMEOUT: "任务超时",
  CANCELLED: "任务已取消",
}

export function taskStatusDocLabel(status: TaskStatus): string {
  return TASK_STATUS_DOC_LABELS[status] ?? status
}

export function isFailedTaskStatus(status: TaskStatus): boolean {
  return status === "FAILED" || status === "TIMEOUT"
}

export function taskStatusViewKind(status: TaskStatus): TaskStatusViewKind {
  if (status === "PROCESSING" || status === "RETRYING") return "running"
  if (status === "SUCCESS") return "success"
  if (status === "CANCELLED") return "cancelled"
  if (isFailedTaskStatus(status)) return "failed"
  return "queued"
}

const DEFAULT_FAILURE_HINTS: Partial<Record<TaskStatus, string>> = {
  FAILED: "生成失败，请检查参数后重试。",
  TIMEOUT: "任务执行超时，请稍后重试或联系支持。",
  CANCELLED: "任务已取消。",
}

export function taskFailureHint(status: TaskStatus, progressMessages: Array<string | null | undefined> = []): string {
  if (status === "CANCELLED") return DEFAULT_FAILURE_HINTS.CANCELLED!
  const message = progressMessages.map((item) => item?.trim()).find(Boolean)
  return message || DEFAULT_FAILURE_HINTS[status] || "任务未成功完成。"
}

export function taskProgressMessage(status: TaskStatus, progressMessage?: string | null): string {
  if (status === "CANCELLED") return TASK_STATUS_DOC_LABELS.CANCELLED
  return progressMessage?.trim() || "处理中"
}
