import { http, unwrap } from './http'
import type { AdminMember, CreditAccount, PageResponse } from '@/types'

export function fetchUsers() {
  return unwrap<PageResponse<AdminMember>>(http.get('/api/admin/v1/users'))
}

export function manualAddCredits(userId: number, payload: { amount: number; reason: string }) {
  return unwrap<void>(http.post(`/api/admin/v1/users/${userId}/credits/manual-add`, payload))
}

export function fetchCreditAccount() {
  return unwrap<CreditAccount>(http.get('/api/v1/credits/account'))
}
