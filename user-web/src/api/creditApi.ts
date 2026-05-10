import { apiRequest } from "./client"
import type { CreditAccount, PageResult, CreditLog } from "./types"

/** GET /api/v1/credits/account */
export async function fetchCreditAccount(options?: { token?: string | null }): Promise<CreditAccount> {
  return apiRequest<CreditAccount>("GET", "/api/v1/credits/account", { token: options?.token })
}

/** GET /api/v1/credits/logs —— 算力流水（分页） */
export async function fetchCreditLogs(options?: {
  token?: string | null
  query?: Record<string, string | number | boolean | undefined>
}): Promise<PageResult<CreditLog>> {
  return apiRequest<PageResult<CreditLog>>("GET", "/api/v1/credits/logs", {
    token: options?.token,
    query: options?.query,
  })
}
