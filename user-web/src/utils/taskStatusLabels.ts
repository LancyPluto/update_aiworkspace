import type { TaskStatus } from "@/api/types"

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
