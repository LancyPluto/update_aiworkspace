export type AgentAccessTool = {
  executionMode?: string | null
  agentEnabled?: boolean | null
  modelConfigId?: number | null
  modelName?: string | null
  healthStatus?: string | null
}

export function hasIndependentAgentAccess(tool: AgentAccessTool): boolean {
  return tool.executionMode?.trim().toUpperCase() !== "WORKFLOW"
}

export function isAgentEffectivelyEnabled(tool: AgentAccessTool): boolean {
  return hasIndependentAgentAccess(tool) ? tool.agentEnabled === true : true
}

export function filterIndependentAgentAccessTools<T extends AgentAccessTool>(tools: readonly T[]): T[] {
  return tools.filter(hasIndependentAgentAccess)
}

export function matchesAgentToolStatus(tool: AgentAccessTool, statusFilter: string): boolean {
  if (statusFilter === "enabled") return isAgentEffectivelyEnabled(tool)
  if (statusFilter === "disabled") return !isAgentEffectivelyEnabled(tool)
  if (statusFilter === "all") return true
  if (!hasIndependentAgentAccess(tool)) return false

  const hasModel = tool.modelConfigId != null || Boolean(tool.modelName)
  const healthStatus = (tool.healthStatus || "UNKNOWN").toUpperCase()
  if (statusFilter === "unbound") return !hasModel
  if (statusFilter === "failed") return healthStatus === "FAILED"
  if (statusFilter === "unknown") return healthStatus === "UNKNOWN"
  return true
}
