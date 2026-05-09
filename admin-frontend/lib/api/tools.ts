import { http } from './http'
import type {
  PageResponse,
  ToolCategory,
  ToolSummary,
  UpsertToolPayload,
} from './types'

export function fetchToolCategories() {
  return http.get<ToolCategory[]>('/api/v1/tool-categories')
}

export function fetchAdminTools() {
  return http.get<PageResponse<ToolSummary>>('/api/admin/v1/tools')
}

export function createTool(payload: UpsertToolPayload) {
  return http.post<ToolSummary>('/api/admin/v1/tools', payload)
}

export function updateTool(toolId: number, payload: UpsertToolPayload) {
  return http.put<ToolSummary>(`/api/admin/v1/tools/${toolId}`, payload)
}

export function publishTool(toolId: number) {
  return http.post<ToolSummary>(`/api/admin/v1/tools/${toolId}/publish`)
}

export function offlineTool(toolId: number) {
  return http.post<ToolSummary>(`/api/admin/v1/tools/${toolId}/offline`)
}
