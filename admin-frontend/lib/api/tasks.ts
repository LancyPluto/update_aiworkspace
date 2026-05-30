import { http } from './http'
import type {
  AdminTaskApiPayload,
  AdminTaskQuery,
  PageResponse,
  TaskStatusPayload,
} from './types'

export function fetchAdminTasks(query: AdminTaskQuery = {}) {
  return http.get<PageResponse<AdminTaskApiPayload>>('/api/admin/v1/tasks', {
    status: query.status,
    toolCode: query.toolCode,
    userId: query.userId,
    taskId: query.taskId,
  })
}

export function fetchAdminTaskDetail(taskId: number) {
  return http.get<AdminTaskApiPayload>(`/api/admin/v1/tasks/${taskId}`)
}

export function retryAdminTask(taskId: number) {
  return http.post<TaskStatusPayload>(`/api/admin/v1/tasks/${taskId}/retry`)
}

export function cancelAdminTask(taskId: number) {
  return http.post<TaskStatusPayload>(`/api/admin/v1/tasks/${taskId}/cancel`)
}
