import { http } from './http'
import type {
  AdminAgentRunDetail,
  AdminAgentRunListItem,
  AdminAgentRunQuery,
  AdminAgentRunStats,
  AgentRun,
  AgentAuditCategory,
  AgentModelRequestSnapshot,
  AgentRunAudit,
  AgentRunAuditReview,
  AgentSkillCoverage,
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

export function fetchAdminAgentRunAudit(runId: number) {
  return http.get<AgentRunAudit>(`/api/admin/v1/agent/runs/${runId}/audit`)
}

export function fetchAdminAgentModelRequests(runId: number, afterId?: number, pageSize = 50) {
  return http.get<AgentModelRequestSnapshot[]>(`/api/admin/v1/agent/runs/${runId}/model-requests`, {
    afterId,
    pageSize,
  })
}

export function updateAdminAgentAuditReview(
  runId: number,
  review: { expectedToolCode?: string; finalCategory?: AgentAuditCategory; reviewNote?: string },
) {
  return http.put<AgentRunAuditReview>(`/api/admin/v1/agent/runs/${runId}/audit-review`, review)
}

export function fetchAdminAgentSkillCoverage() {
  return http.get<AgentSkillCoverage[]>("/api/admin/v1/agent/skills/coverage")
}
