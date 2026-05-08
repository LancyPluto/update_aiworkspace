import { http, unwrap } from './http'
import type { AdminTaskDetail, AdminTaskQuery, AdminTaskRow, PageResponse } from '@/types'

export function fetchAdminTasks(query: AdminTaskQuery = {}) {
  return unwrap<PageResponse<AdminTaskRow>>(
    http.get('/api/admin/v1/tasks', {
      params: {
        status: emptyToUndefined(query.status),
        toolCode: emptyToUndefined(query.toolCode),
        userId: query.userId
      }
    })
  )
}

export function fetchAdminTaskDetail(taskId: number) {
  return unwrap<AdminTaskDetail>(http.get(`/api/admin/v1/tasks/${taskId}`))
}

export function retryAdminTask(taskId: number) {
  return unwrap<AdminTaskRow>(http.post(`/api/admin/v1/tasks/${taskId}/retry`))
}

export function cancelAdminTask(taskId: number) {
  return unwrap<AdminTaskRow>(http.post(`/api/admin/v1/tasks/${taskId}/cancel`))
}

function emptyToUndefined(value: string | undefined) {
  if (value == null) return undefined
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : undefined
}
