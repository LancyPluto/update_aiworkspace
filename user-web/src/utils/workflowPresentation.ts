import type {
  WorkflowArtifact,
  WorkflowRunStatus,
  WorkflowStepStatus,
} from "@/api/workflowApi"

export type WorkflowRunAction = "feedback" | "recharge" | "resume" | "cancel"

export interface WorkflowStatusView {
  label: string
  tone: "neutral" | "info" | "warning" | "success" | "danger"
  needsAction: boolean
}

const RUN_STATUS_VIEWS: Record<string, WorkflowStatusView> = {
  QUEUED: { label: "排队中", tone: "neutral", needsAction: false },
  RUNNING: { label: "执行中", tone: "info", needsAction: false },
  AWAITING_USER: { label: "等待确认", tone: "warning", needsAction: true },
  AWAITING_FUNDS: { label: "等待充值", tone: "warning", needsAction: true },
  CANCELLING: { label: "取消中", tone: "neutral", needsAction: false },
  SUCCESS: { label: "已完成", tone: "success", needsAction: false },
  FAILED: { label: "失败", tone: "danger", needsAction: false },
  TIMEOUT: { label: "已超时", tone: "danger", needsAction: false },
  CANCELLED: { label: "已取消", tone: "neutral", needsAction: false },
}

const STEP_STATUS_LABELS: Record<string, string> = {
  PENDING: "未开始",
  READY: "准备中",
  QUEUED: "排队中",
  RUNNING: "执行中",
  AWAITING_USER: "等待确认",
  SUCCESS: "已完成",
  FAILED: "失败",
  SKIPPED: "已跳过",
  CANCELLED: "已取消",
}

export function runStatusView(status: WorkflowRunStatus): WorkflowStatusView {
  return RUN_STATUS_VIEWS[status] ?? { label: status || "未知状态", tone: "neutral", needsAction: false }
}

export function stepStatusView(status: WorkflowStepStatus): Pick<WorkflowStatusView, "label" | "tone"> {
  const runView = RUN_STATUS_VIEWS[status]
  return {
    label: STEP_STATUS_LABELS[status] ?? (status || "未知状态"),
    tone: runView?.tone ?? "neutral",
  }
}

export function runActions(status: WorkflowRunStatus): WorkflowRunAction[] {
  if (status === "AWAITING_USER") return ["feedback", "cancel"]
  if (status === "AWAITING_FUNDS") return ["recharge", "resume", "cancel"]
  if (["QUEUED", "RUNNING"].includes(status)) return ["cancel"]
  return []
}

function formatCredits(value: number | null | undefined): string {
  return value == null ? "暂不可用" : `${value} 算力`
}

export function workflowCostSummary(cost: { totalCredits?: number | null; reservedCredits?: number | null }) {
  return {
    total: formatCredits(cost.totalCredits),
    reserved: formatCredits(cost.reservedCredits),
  }
}

export type WorkflowArtifactViewer = "text" | "json" | "image" | "audio" | "video" | "file" | "fallback"

export function artifactView(artifact: Pick<WorkflowArtifact, "type" | "name" | "title">): {
  viewer: WorkflowArtifactViewer
  label: string
} {
  const viewers: Record<string, WorkflowArtifactViewer> = {
    TEXT: "text",
    JSON: "json",
    IMAGE: "image",
    AUDIO: "audio",
    VIDEO: "video",
    FILE: "file",
  }
  return {
    viewer: viewers[artifact.type] ?? "fallback",
    label: artifact.title || artifact.name || "运行产物",
  }
}

export function workflowArtifactUrl(value: unknown): string {
  if (typeof value !== "string") return ""
  const url = value.trim()
  if (!url) return ""
  if (url.startsWith("/") && !url.startsWith("//")) return url
  if (/^(https?:|blob:)/i.test(url)) return url
  return ""
}
