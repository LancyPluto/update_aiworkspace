import { http } from './http'
import type {
  AdminAgentRunDetail,
  AdminAgentRunListItem,
  AdminAgentRunQuery,
  AdminAgentRunStats,
  AgentRun,
  PageResponse,
} from './types'

export function fetchAdminAgentRuns(query: AdminAgentRunQuery = {}) {
  return http.get<PageResponse<AdminAgentRunListItem>>(
    '/api/admin/v1/agent/runs',
    query as Record<string, string | number | boolean | undefined>,
  )
}

export function fetchAdminAgentRunStats() {
  return http.get<AdminAgentRunStats>('/api/admin/v1/agent/runs/stats')
}

export function fetchAdminAgentRunDetail(runId: number) {
  return http.get<AdminAgentRunDetail>(`/api/admin/v1/agent/runs/${runId}`)
}

export function fetchAdminAgentRunEvents(
  runId: number,
  query: { afterEventId?: number; pageSize?: number } = {},
) {
  return http.get<import("./types").AgentRunEvent[]>(
    `/api/admin/v1/agent/runs/${runId}/events`,
    query as Record<string, string | number | boolean | undefined>,
  )
}

export function cancelAdminAgentRun(runId: number) {
  return http.post<AgentRun>(`/api/admin/v1/agent/runs/${runId}/cancel`)
}
