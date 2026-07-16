import type { WorkflowRunStatus } from "@/api/workflowApi"

export type WorkflowStreamOutcome = "idle" | "signal" | "eof" | "error"

export interface WorkflowRefreshInput {
  status: WorkflowRunStatus
  streamOutcome: WorkflowStreamOutcome
  failures: number
}

export interface WorkflowRefreshPlan {
  stop: boolean
  pollAfterMs: number | null
  reconnectAfterMs: number | null
}

const TERMINAL_STATUSES = new Set<WorkflowRunStatus>(["SUCCESS", "FAILED", "TIMEOUT", "CANCELLED"])

export function isWorkflowTerminal(status: WorkflowRunStatus | null | undefined): boolean {
  return status != null && TERMINAL_STATUSES.has(status)
}

export function pollDelayMs(failures: number): number {
  if (failures <= 0) return 4000
  return Math.min(30_000, Math.round(4000 * 1.5 ** failures))
}

export function nextRefreshPlan(input: WorkflowRefreshInput): WorkflowRefreshPlan {
  if (isWorkflowTerminal(input.status)) {
    return { stop: true, pollAfterMs: null, reconnectAfterMs: null }
  }
  const pollAfterMs = pollDelayMs(input.failures)
  if (input.streamOutcome === "eof" || input.streamOutcome === "error") {
    return { stop: false, pollAfterMs, reconnectAfterMs: Math.max(3000, pollAfterMs - 1000) }
  }
  return { stop: false, pollAfterMs, reconnectAfterMs: null }
}

export function shouldApplyWorkflowResponse(currentRequest: number, responseRequest: number): boolean {
  return currentRequest === responseRequest
}
