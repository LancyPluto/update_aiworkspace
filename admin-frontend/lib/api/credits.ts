import { http } from './http'
import type { CreditAccount } from './types'

export function fetchCreditAccount() {
  return http.get<CreditAccount>('/api/v1/credits/account')
}
