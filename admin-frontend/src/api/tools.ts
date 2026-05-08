import { http, unwrap } from './http'
import type { PageResponse, ToolCategory, ToolSummary, UpsertToolPayload } from '@/types'

export function fetchToolCategories() {
  return unwrap<ToolCategory[]>(http.get('/api/v1/tool-categories'))
}

export function fetchAdminTools() {
  return unwrap<PageResponse<ToolSummary>>(http.get('/api/admin/v1/tools'))
}

export function createTool(payload: UpsertToolPayload) {
  return unwrap<ToolSummary>(http.post('/api/admin/v1/tools', payload))
}

export function updateTool(toolId: number, payload: UpsertToolPayload) {
  return unwrap<ToolSummary>(http.put(`/api/admin/v1/tools/${toolId}`, payload))
}

export function publishTool(toolId: number) {
  return unwrap<ToolSummary>(http.post(`/api/admin/v1/tools/${toolId}/publish`))
}

export function offlineTool(toolId: number) {
  return unwrap<ToolSummary>(http.post(`/api/admin/v1/tools/${toolId}/offline`))
}
