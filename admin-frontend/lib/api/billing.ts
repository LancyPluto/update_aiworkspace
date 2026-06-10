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

function toBillingQueryRecord(query?: BillingQuery): Record<string, string | number | boolean | null | undefined> | undefined {
  return query ? { ...query } : undefined
}

export function fetchBillingOverview(query?: Omit<BillingQuery, 'pageNo' | 'pageSize'>) {
  return http.get<BillingOverview>('/api/admin/v1/billing/overview', toBillingQueryRecord(query))
}

export function fetchBillingUsageLogs(query?: BillingQuery) {
  return http.get<PageResponse<BillingUsageLog>>('/api/admin/v1/billing/usage-logs', toBillingQueryRecord(query))
}
