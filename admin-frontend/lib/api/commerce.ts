import { http } from './http'

export interface CommerceRecord {
  [key: string]: unknown
}

export interface QueueStats {
  backend: string
  exchange: string
  routingKey: string
  taskQueue: CommerceRecord
  deadQueue: CommerceRecord
}

export interface ModelCapacityStats extends CommerceRecord {
  total_nodes?: number
  available_nodes?: number
  disabled_nodes?: number
  healthy_nodes?: number
  unhealthy_nodes?: number
  max_concurrency?: number
  current_concurrency?: number
  today_used?: number
  daily_limit?: number
  concurrency_usage_percent?: number
  daily_usage_percent?: number
  pools?: CommerceRecord[]
}

export function fetchCommercePlans() {
  return http.get<CommerceRecord[]>('/api/admin/v1/commerce/plans')
}

export function upsertCommercePlan(body: CommerceRecord) {
  return http.post<CommerceRecord>('/api/admin/v1/commerce/plans', body)
}

export function fetchCommercePools() {
  return http.get<CommerceRecord[]>('/api/admin/v1/commerce/pools')
}

export function upsertCommercePool(body: CommerceRecord) {
  return http.post<CommerceRecord>('/api/admin/v1/commerce/pools', body)
}

export function fetchCommerceNodes(poolId?: number) {
  return http.get<CommerceRecord[]>('/api/admin/v1/commerce/nodes', poolId ? { poolId } : undefined)
}

export function upsertCommerceNode(body: CommerceRecord) {
  return http.post<CommerceRecord>('/api/admin/v1/commerce/nodes', body)
}

export function updateCommerceNodeStatus(nodeId: number, status: string) {
  return http.post<CommerceRecord>(`/api/admin/v1/commerce/nodes/${nodeId}/status?status=${encodeURIComponent(status)}`)
}

export function healthCheckCommerceNode(nodeId: number) {
  return http.post<CommerceRecord>(`/api/admin/v1/commerce/nodes/${nodeId}/health-check`)
}

export function healthCheckCommerceNodes(poolId?: number) {
  const query = poolId ? `?poolId=${encodeURIComponent(poolId)}` : ''
  return http.post<CommerceRecord>(`/api/admin/v1/commerce/nodes/health-check${query}`)
}

export function fetchModelCapacityStats() {
  return http.get<ModelCapacityStats>('/api/admin/v1/commerce/capacity-stats')
}

export function fetchCommerceOrders() {
  return http.get<CommerceRecord[]>('/api/admin/v1/commerce/orders')
}

export function fetchModelCallLogs() {
  return http.get<CommerceRecord[]>('/api/admin/v1/commerce/model-call-logs')
}

export function fetchModelFeedback() {
  return http.get<CommerceRecord[]>('/api/admin/v1/commerce/model-feedback')
}

export function fetchQueueStats() {
  return http.get<QueueStats>('/api/admin/v1/queues/stats')
}

export function requeueDeadMessages(limit = 10) {
  return http.post<CommerceRecord>(`/api/admin/v1/queues/dead/requeue?limit=${limit}`)
}
