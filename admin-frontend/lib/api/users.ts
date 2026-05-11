import { http } from './http'
import type {
  AdminMember,
  CreditAccount,
  CreditLogItem,
  ManualAddCreditsPayload,
  ManualAddCreditsResult,
  PageResponse,
  UpdateUserStatusPayload,
} from './types'

export function fetchAdminUsers() {
  return http.get<PageResponse<AdminMember>>('/api/admin/v1/users')
}

export function updateUserStatus(userId: number, payload: UpdateUserStatusPayload) {
  return http.patch<AdminMember>(`/api/admin/v1/users/${userId}/status`, payload)
}

export function manualAddCredits(userId: number, payload: ManualAddCreditsPayload) {
  return http.post<ManualAddCreditsResult>(
    `/api/admin/v1/users/${userId}/credits/manual-add`,
    payload,
  )
}

export function manualDeductCredits(userId: number, payload: ManualAddCreditsPayload) {
  return http.post<ManualAddCreditsResult>(
    `/api/admin/v1/users/${userId}/credits/manual-deduct`,
    payload,
  )
}

export function fetchUserCreditAccount(userId: number) {
  return http.get<CreditAccount>(`/api/admin/v1/users/${userId}/credits/account`)
}

export function fetchUserCreditLogs(userId: number) {
  return http.get<PageResponse<CreditLogItem>>(
    `/api/admin/v1/users/${userId}/credits/logs`,
    { pageNo: 1, pageSize: 20 },
  )
}
