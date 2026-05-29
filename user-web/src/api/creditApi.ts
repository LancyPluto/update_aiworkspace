import { apiRequest } from "./client"
import type { CreditAccount, PageResult, CreditLog, RechargeOrder, RechargePackage } from "./types"

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

export async function fetchRechargePackages(options?: { token?: string | null }): Promise<RechargePackage[]> {
  return apiRequest<RechargePackage[]>("GET", "/api/v1/credits/recharge-packages", { token: options?.token })
}

export async function createRechargeOrder(
  body: { packageId: number; paymentChannel?: string; clientRequestId?: string },
  options?: { token?: string | null },
): Promise<RechargeOrder> {
  return apiRequest<RechargeOrder>("POST", "/api/v1/credits/recharge-orders", {
    token: options?.token,
    body,
  })
}

export async function createCustomRechargeOrder(
  body: { amount: number; paymentChannel?: string; clientRequestId?: string },
  options?: { token?: string | null },
): Promise<RechargeOrder> {
  return apiRequest<RechargeOrder>("POST", "/api/v1/credits/recharge-orders/custom", {
    token: options?.token,
    body,
  })
}

export async function fetchRechargeOrder(orderId: number, options?: { token?: string | null }): Promise<RechargeOrder> {
  return apiRequest<RechargeOrder>("GET", `/api/v1/credits/recharge-orders/${orderId}`, { token: options?.token })
}

export async function mockPayRechargeOrder(orderId: number, options?: { token?: string | null }): Promise<RechargeOrder> {
  return apiRequest<RechargeOrder>("POST", `/api/v1/credits/recharge-orders/${orderId}/mock-pay-success`, {
    token: options?.token,
  })
}
