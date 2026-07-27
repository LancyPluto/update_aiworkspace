import type { CreditStatementLog } from "@/api/types"

export type BillingRow = {
  id: string
  createdAt: string
  reason: string
  changeText: string
  negative: boolean
}

export function parseShanghaiDate(value?: string | null) {
  if (!value) return null
  const normalized = value.trim()
  if (!normalized) return null
  if (/[zZ]$|[+-]\d{2}:?\d{2}$/.test(normalized)) return new Date(normalized)
  return new Date(`${normalized.replace(" ", "T")}+08:00`)
}

function modelLabel(log: CreditStatementLog) {
  return log.modelName?.trim() || log.provider?.trim() || "模型"
}

function workflowReason(log: CreditStatementLog) {
  const workflowName = log.workflowName?.trim()
    || (log.workflowId != null ? `工作流 #${log.workflowId}` : "工作流")
  const stepName = log.workflowStepName?.trim()
    || (log.workflowNodeId?.trim() ? `步骤 ${log.workflowNodeId.trim()}` : "具体步骤")
  return `${workflowName} · ${stepName} · ${modelLabel(log)}`
}

export function statementReason(log: CreditStatementLog) {
  const sourceType = (log.sourceType || "").trim().toUpperCase()
  if (sourceType === "WORKFLOW_STEP") return workflowReason(log)
  if (sourceType === "TASK") {
    const task = log.taskNo?.trim() || (log.sourceRef != null ? `#${log.sourceRef}` : "任务")
    const tool = log.toolName?.trim()
    return `${tool ? `${tool} · ` : ""}任务 ${task} · ${modelLabel(log)}`
  }
  if (sourceType === "AGENT_RUN") {
    const runId = log.agentRunId ?? log.sourceRef
    return `Agent 会话${runId != null ? ` #${runId}` : ""} · ${modelLabel(log)}`
  }

  const raw = (log.reason || "").trim()
  if (log.logType === "RECHARGE") return raw && raw !== "async recharge credit dispatch" ? raw : "充值到账"
  if (log.logType === "MANUAL_ADD") return raw ? `后台增加算力：${raw}` : "后台增加算力"
  if (log.logType === "MANUAL_DEDUCT") return raw ? `后台扣减算力：${raw}` : "后台扣减算力"
  return raw || (log.logType === "DEDUCT" ? "算力扣费" : "算力入账")
}

export function statementLogToBillingRow(log: CreditStatementLog): BillingRow {
  const negative = log.logType === "DEDUCT" || log.logType === "MANUAL_DEDUCT"
  return {
    id: `statement-${log.id}`,
    createdAt: log.createdAt,
    reason: statementReason(log),
    changeText: `${negative ? "-" : "+"}${Math.abs(log.amount)}`,
    negative,
  }
}
