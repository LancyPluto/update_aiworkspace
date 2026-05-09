import { http } from './http'
import type {
  AdminMember,
  ManualAddCreditsPayload,
  ManualAddCreditsResult,
  PageResponse,
} from './types'

export function fetchAdminUsers() {
  return http.get<PageResponse<AdminMember>>('/api/admin/v1/users')
}

export function manualAddCredits(userId: number, payload: ManualAddCreditsPayload) {
  return http.post<ManualAddCreditsResult>(
    `/api/admin/v1/users/${userId}/credits/manual-add`,
    payload,
  )
}
