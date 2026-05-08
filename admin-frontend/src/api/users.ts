import { http, unwrap } from './http'
import type {
  AdminMember,
  CreditAccount,
  ManualAddCreditsPayload,
  ManualAddCreditsResult,
  PageResponse
} from '@/types'

export function fetchUsers() {
  return unwrap<PageResponse<AdminMember>>(http.get('/api/admin/v1/users'))
}

export function manualAddCredits(userId: number, payload: ManualAddCreditsPayload) {
  return unwrap<ManualAddCreditsResult>(
    http.post(`/api/admin/v1/users/${userId}/credits/manual-add`, payload)
  )
}

export function fetchCreditAccount() {
  return unwrap<CreditAccount>(http.get('/api/v1/credits/account'))
}
