import { http } from './http'
import type {
  AdminMember,
  CreditAccount,
  CreditLogItem,
  ManualAddCreditsPayload,
  PageResponse,
  UpdateUserStatusPayload,
} from './types'

/** 算力余额（嵌套在 AdminUserResponse.creditAccount） */
export function memberAccountBalance(user: AdminMember): number {
  return user.creditAccount?.balance ?? 0
}

export function fetchAdminUsers(query?: Record<string, string | number | boolean | undefined>) {
  return http.get<PageResponse<AdminMember>>('/api/admin/v1/users', query)
}

export function updateUserStatus(userId: number, payload: UpdateUserStatusPayload) {
  return http.patch<AdminMember>(`/api/admin/v1/users/${userId}/status`, payload)
}

export function manualAddCredits(userId: number, payload: ManualAddCreditsPayload) {
  return http.post<CreditAccount>(
    `/api/admin/v1/users/${userId}/credits/manual-add`,
    payload,
  )
}

export function manualDeductCredits(userId: number, payload: ManualAddCreditsPayload) {
  return http.post<CreditAccount>(
    `/api/admin/v1/users/${userId}/credits/manual-deduct`,
    payload,
  )
}

export function fetchUserCreditAccount(userId: number) {
  return http.get<CreditAccount>(`/api/admin/v1/users/${userId}/credits/account`)
}

export function fetchUserCreditLogs(userId: number, pageSize = 200) {
  return http.get<PageResponse<CreditLogItem>>(
    `/api/admin/v1/users/${userId}/credits/logs`,
    { pageNo: 1, pageSize },
  )
}
