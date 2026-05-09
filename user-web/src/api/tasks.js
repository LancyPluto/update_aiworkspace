import { http } from './http'

export function createTask(body) {
  return http.post('/tasks', body).then((r) => unwrapData(r.data))
}

export function listTasks(params = {}) {
  return http.get('/tasks', { params }).then((r) => unwrapList(r.data))
}

export function getTaskStatus(taskId) {
  return http
    .get(`/tasks/${encodeURIComponent(taskId)}/status`)
    .then((r) => unwrapData(r.data))
}

export function getTask(taskId) {
  return http.get(`/tasks/${encodeURIComponent(taskId)}`).then((r) => unwrapData(r.data))
}

function unwrapData(data) {
  return data?.data ?? data
}

function unwrapList(data) {
  const payload = unwrapData(data)
  if (Array.isArray(payload)) return payload
  if (Array.isArray(payload?.list)) return payload.list
  if (Array.isArray(payload?.items)) return payload.items
  if (Array.isArray(payload?.records)) return payload.records
  return []
}
