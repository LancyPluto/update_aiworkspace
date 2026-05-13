import { http } from './http'
import type { BillingOverview, BillingUsageLog, PageResponse } from './types'

export function fetchBillingOverview() {
  return http.get<BillingOverview>('/api/admin/v1/billing/overview')
}

export function fetchBillingUsageLogs(query?: { pageNo?: number; pageSize?: number }) {
  return http.get<PageResponse<BillingUsageLog>>('/api/admin/v1/billing/usage-logs', query)
}
