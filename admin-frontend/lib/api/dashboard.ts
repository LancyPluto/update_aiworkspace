import { http } from './http'
import type { DashboardOverview } from './types'

export function fetchDashboardOverview() {
  return http.get<DashboardOverview>('/api/admin/v1/dashboard/overview')
}
