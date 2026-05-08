//工具接口封装
import { http } from './http'

export function listCategories() {
  return http.get('/tool-categories').then((r) => unwrap(r.data))
}

export function listTools(params = {}) {
  return http.get('/tools', { params }).then((r) => unwrap(r.data))
}

export function getTool(toolCode) {
  return http.get(`/tools/${encodeURIComponent(toolCode)}`).then((r) => r.data)
}

function unwrap(data) {
  if (Array.isArray(data)) return data
  if (Array.isArray(data?.data)) return data.data
  if (Array.isArray(data?.items)) return data.items
  if (data?.list && Array.isArray(data.list)) return data.list
  return data ?? []
}
