import { http, unwrap } from './http'
import type { AdminTask, AdminTaskDetail, PageResponse } from '@/types'

export function fetchTasks() {
  return unwrap<PageResponse<AdminTask>>(http.get('/api/v1/tasks'))
}

export function fetchTaskDetail(taskId: number) {
  return unwrap<AdminTaskDetail>(http.get(`/api/v1/tasks/${taskId}`))
}

export function fetchTaskStatus(taskId: number) {
  return unwrap<Pick<AdminTask, 'taskId' | 'taskNo' | 'status' | 'progress' | 'progressMessage'>>(
    http.get(`/api/v1/tasks/${taskId}/status`)
  )
}
