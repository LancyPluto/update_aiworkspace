import { http } from './http'
import type { ToolSummary } from './types'

export type AgentToolAccess = ToolSummary & {
  agentEnabled: boolean
  healthStatus?: 'UNKNOWN' | 'HEALTHY' | 'FAILED' | string
  healthMessage?: string | null
  healthCheckedAt?: string | null
  modelProvider?: string | null
  modelBaseUrl?: string | null
  modelTimeoutSeconds?: number | null
  modelConnectTimeoutSeconds?: number | null
  modelReadTimeoutSeconds?: number | null
  modelProxyConfigured?: boolean | null
}

export type AgentRouteDebugTool = {
  toolCode: string
  toolName: string
  autoCallable?: boolean | null
}

export type AgentRouteDebugFilteredTool = {
  toolCode: string
  toolName: string
  reason: string
}

export type AgentRouteDebugResult = {
  intent: string
  confidence?: number | null
  selectedToolCode?: string | null
  candidateToolCodes?: string[] | null
  clarifyingQuestion?: string | null
  decisionSource?: string | null
  reason?: string | null
  requestedOutputModality?: string | null
  visibleToolCount?: number | null
  visibleTools?: AgentRouteDebugTool[] | null
  filteredTools?: AgentRouteDebugFilteredTool[] | null
  readiness?: "ready" | "warning" | "blocked" | string | null
  modelConnectivity?: boolean | null
  availableToolCount?: number | null
  disclosedToolCount?: number | null
  estimatedRouterPromptBytes?: number | null
  nextActions?: string[] | null
  llmRouterEnabled?: boolean | null
  productToolLoopEnabled?: boolean | null
  toolDisclosureEnabled?: boolean | null
}

export type BulkAgentToolAccessResult = {
  updatedTools: AgentToolAccess[]
  failedToolCodes: Array<{ toolCode: string; reason: string }>
}

export function fetchAdminAgentTools() {
  return http.get<AgentToolAccess[]>('/api/admin/v1/agent/tools')
}

export function updateAdminAgentToolAccess(toolCode: string, agentEnabled: boolean) {
  return http.put<AgentToolAccess>(`/api/admin/v1/agent/tools/${toolCode}`, { agentEnabled })
}

export function bulkUpdateAdminAgentToolAccess(toolCodes: string[], agentEnabled: boolean) {
  return http.put<BulkAgentToolAccessResult>('/api/admin/v1/agent/tools/bulk-access', { toolCodes, agentEnabled })
}

export function debugAdminAgentRoute(message: string) {
  return http.post<AgentRouteDebugResult>('/api/admin/v1/agent/tools/route-debug', { message })
}
