//工具接口封装
import { http } from './http'

export function listCategories() {
  return http.get('/tool-categories').then((r) => unwrapList(r.data))
}

export function listTools(params = {}) {
  return http.get('/tools', { params }).then((r) => unwrapList(r.data))
}

export function getTool(toolCode) {
  return http.get(`/tools/${encodeURIComponent(toolCode)}`).then((r) => unwrapData(r.data))
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
