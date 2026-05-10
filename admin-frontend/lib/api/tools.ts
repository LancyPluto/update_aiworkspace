import { http } from './http'
import type {
  PageResponse,
  ToolCategory,
  ToolSummary,
  UpsertToolCategoryPayload,
  UpsertToolPayload,
} from './types'

export function fetchToolCategories() {
  return http.get<ToolCategory[]>('/api/v1/tool-categories')
}

export function fetchAdminToolCategories() {
  return http.get<ToolCategory[]>('/api/admin/v1/tool-categories')
}

export function createToolCategory(payload: UpsertToolCategoryPayload) {
  return http.post<ToolCategory>('/api/admin/v1/tool-categories', payload)
}

export function updateToolCategory(categoryId: number, payload: UpsertToolCategoryPayload) {
  return http.put<ToolCategory>(`/api/admin/v1/tool-categories/${categoryId}`, payload)
}

export function updateToolCategoryStatus(categoryId: number, status: string) {
  return http.patch<ToolCategory>(`/api/admin/v1/tool-categories/${categoryId}/status`, { status })
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
