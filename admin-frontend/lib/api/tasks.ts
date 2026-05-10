import { http } from './http'
import type {
  AdminTaskDetail,
  AdminTaskQuery,
  AdminTaskRow,
  PageResponse,
} from './types'

export function fetchAdminTasks(query: AdminTaskQuery = {}) {
  return http.get<PageResponse<AdminTaskRow>>('/api/admin/v1/tasks', {
    status: query.status,
    toolCode: query.toolCode,
    userId: query.userId,
  })
}

export function fetchAdminTaskDetail(taskId: number) {
  return http.get<AdminTaskDetail>(`/api/admin/v1/tasks/${taskId}`)
}

export function retryAdminTask(taskId: number) {
  return http.post<AdminTaskRow>(`/api/admin/v1/tasks/${taskId}/retry`)
}

export function cancelAdminTask(taskId: number) {
  return http.post<AdminTaskRow>(`/api/admin/v1/tasks/${taskId}/cancel`)
}
