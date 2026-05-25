import { http } from './http'
import type { BillingOverview, BillingUsageLog, PageResponse } from './types'

export interface BillingQuery {
  pageNo?: number
  pageSize?: number
  userId?: number
  modelConfigId?: number
  provider?: string
  modelName?: string
  sourceType?: string
  sourceId?: number
  startDate?: string
  endDate?: string
}

export function fetchBillingOverview(query?: Omit<BillingQuery, 'pageNo' | 'pageSize'>) {
  return http.get<BillingOverview>('/api/admin/v1/billing/overview', query)
}

export function fetchBillingUsageLogs(query?: BillingQuery) {
  return http.get<PageResponse<BillingUsageLog>>('/api/admin/v1/billing/usage-logs', query)
}
