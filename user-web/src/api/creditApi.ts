import { apiRequest } from "./client"
import type { CreditAccount } from "./types"

/** GET /api/v1/credits/account */
export async function fetchCreditAccount(options?: { token?: string | null }): Promise<CreditAccount> {
  return apiRequest<CreditAccount>("GET", "/api/v1/credits/account", { token: options?.token })
}
