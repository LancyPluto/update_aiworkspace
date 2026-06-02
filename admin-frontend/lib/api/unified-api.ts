import { http } from './http'
import type { UnifiedApiOverview } from './types'

const BASE = '/api/admin/v1/unified-api'

export function fetchUnifiedApiOverview() {
  return http.get<UnifiedApiOverview>(`${BASE}/overview`)
}
