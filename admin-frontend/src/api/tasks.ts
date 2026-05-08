import { http, unwrap } from './http'
import type { AdminTask, AdminTaskDetail, PageResponse } from '@/types'

export function fetchTasks() {
  return unwrap<PageResponse<AdminTask>>(http.get('/api/admin/v1/tasks'))
}

export function fetchTaskDetail(taskId: number) {
  return unwrap<AdminTaskDetail>(http.get(`/api/admin/v1/tasks/${taskId}`))
}
