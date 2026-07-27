import type { BillingUsageLog } from "@/lib/api/types"

export function isWorkflowStepUsage(log: BillingUsageLog) {
  return (log.sourceType || "").toUpperCase() === "WORKFLOW_STEP"
}

export function workflowUsageSourceLabel(log: BillingUsageLog) {
  const workflowName = log.workflowName?.trim()
    || (log.workflowId != null ? `工作流 #${log.workflowId}` : "工作流")
  const stepName = log.workflowStepName?.trim()
    || (log.workflowNodeId?.trim() ? `步骤 ${log.workflowNodeId.trim()}` : "具体步骤")
  return `${workflowName} · ${stepName}`
}

export function billingUsageSourceLabel(log: BillingUsageLog) {
  if (isWorkflowStepUsage(log)) return workflowUsageSourceLabel(log)
  if (log.taskNo) return log.taskNo
  if (log.sourceType === "TASK") return `#${log.sourceId}`
  if (log.sourceType === "AGENT_RUN") return `Agent运行 #${log.sourceId}`
  return log.sourceId ? `${log.sourceType} #${log.sourceId}` : "-"
}

export function billingUsageSearchText(log: BillingUsageLog) {
  return [
    billingUsageSourceLabel(log),
    log.sourceType,
    log.sourceId,
    log.workflowRunId,
    log.workflowId,
    log.workflowName,
    log.workflowNodeId,
    log.workflowStepName,
  ].filter((value) => value != null).join(" ").toLowerCase()
}
