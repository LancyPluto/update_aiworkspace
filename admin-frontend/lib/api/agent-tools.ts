import { http } from './http'
import type { ToolSummary } from './types'

export type AgentToolAccess = ToolSummary & {
  agentEnabled: boolean
  healthStatus?: 'UNKNOWN' | 'HEALTHY' | 'FAILED' | string
  healthMessage?: string | null
  healthCheckedAt?: string | null
}

export function fetchAdminAgentTools() {
  return http.get<AgentToolAccess[]>('/api/admin/v1/agent/tools')
}

export function updateAdminAgentToolAccess(toolCode: string, agentEnabled: boolean) {
  return http.put<AgentToolAccess>(`/api/admin/v1/agent/tools/${toolCode}`, { agentEnabled })
}
