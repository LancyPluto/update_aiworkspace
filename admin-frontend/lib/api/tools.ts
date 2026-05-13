import { http } from './http'
import type {
  PageResponse,
  FieldSchemaAdmin,
  ToolCategory,
  ToolField,
  ToolFieldPayload,
  ToolSummary,
  UpsertFieldSchemaPayload,
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

export function fetchToolFieldSchemas(toolId: number) {
  return http.get<FieldSchemaAdmin[]>(`/api/admin/v1/tools/${toolId}/field-schemas`)
}

export function upsertToolFieldSchema(toolId: number, payload: UpsertFieldSchemaPayload) {
  return http.post<FieldSchemaAdmin>(`/api/admin/v1/tools/${toolId}/field-schemas`, payload)
}

export function fetchToolFields(toolId: number) {
  return http.get<ToolField[]>(`/api/admin/v1/tools/${toolId}/fields`)
}

export function updateToolFields(toolId: number, fields: ToolFieldPayload[]) {
  return http.put<ToolField[]>(`/api/admin/v1/tools/${toolId}/fields`, { fields })
}
