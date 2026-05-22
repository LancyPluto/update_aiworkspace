import { http } from './http'
import type { AITool, UpsertAIToolPayload } from '../ai-tool-types'
import {
  isMockMode,
  mockCreateAITool,
  mockDeleteAITool,
  mockFetchAdminAITools,
  mockUpdateAITool,
  mockUploadAIToolIcon,
} from './ai-tools-mock'

export interface IconUploadResult {
  url: string
}

async function withMockFallback<T>(request: () => Promise<T>, mock: () => Promise<T>): Promise<T> {
  if (isMockMode()) return mock()
  return request()
}

/** GET /api/admin/ai-tools */
export function fetchAdminAITools() {
  return withMockFallback(
    () => http.get<AITool[]>('/api/admin/ai-tools'),
    mockFetchAdminAITools,
  )
}

/** POST /api/admin/ai-tools */
export function createAITool(payload: UpsertAIToolPayload) {
  return withMockFallback(
    () => http.post<AITool>('/api/admin/ai-tools', payload),
    () => mockCreateAITool(payload),
  )
}

/** PUT /api/admin/ai-tools/{id} */
export function updateAITool(id: string, payload: UpsertAIToolPayload) {
  return withMockFallback(
    () => http.put<AITool>(`/api/admin/ai-tools/${encodeURIComponent(id)}`, payload),
    () => mockUpdateAITool(id, payload),
  )
}

/** DELETE /api/admin/ai-tools/{id} */
export function deleteAITool(id: string) {
  return withMockFallback(
    () => http.delete<void>(`/api/admin/ai-tools/${encodeURIComponent(id)}`),
    () => mockDeleteAITool(id),
  )
}

/** POST /api/admin/upload-icon */
export function uploadAIToolIcon(file: File) {
  return withMockFallback(
    () => {
      const formData = new FormData()
      formData.append('file', file)
      return http.postForm<IconUploadResult>('/api/admin/upload-icon', formData)
    },
    () => mockUploadAIToolIcon(file),
  )
}
