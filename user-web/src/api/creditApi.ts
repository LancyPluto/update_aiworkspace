import { apiRequest } from "./client"
import type { BillingUsageLog, CreditAccount, PageResult, CreditLog, GiftCard, GiftCardPackage, RechargeOrder, RechargePackage } from "./types"

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

export async function fetchCreditUsageLogs(options?: {
  token?: string | null
  query?: Record<string, string | number | boolean | undefined>
}): Promise<PageResult<BillingUsageLog>> {
  return apiRequest<PageResult<BillingUsageLog>>("GET", "/api/v1/credits/usage-logs", {
    token: options?.token,
    query: options?.query,
  })
}

export async function fetchRechargePackages(options?: { token?: string | null }): Promise<RechargePackage[]> {
  return apiRequest<RechargePackage[]>("GET", "/api/v1/credits/recharge-packages", { token: options?.token })
}

export async function createRechargeOrder(
  body: { packageId: number; paymentChannel?: string; clientRequestId?: string; orderType?: string; giftCardPackageId?: number },
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

/** GET /api/v1/credits/gift-card-packages */
export async function fetchGiftCardPackages(options?: { token?: string | null }): Promise<GiftCardPackage[]> {
  return apiRequest<GiftCardPackage[]>("GET", "/api/v1/credits/gift-card-packages", { token: options?.token })
}

/** GET /api/v1/credits/gift-cards */
export async function fetchMyGiftCards(options?: { status?: string; token?: string | null }): Promise<GiftCard[]> {
  return apiRequest<GiftCard[]>("GET", "/api/v1/credits/gift-cards", {
    token: options?.token,
    query: options?.status ? { status: options.status } : undefined,
  })
}

/** POST /api/v1/credits/gift-cards/:id/redeem */
export async function redeemGiftCard(id: number, options?: { token?: string | null }): Promise<GiftCard> {
  return apiRequest<GiftCard>("POST", `/api/v1/credits/gift-cards/${id}/redeem`, { token: options?.token })
}

/** POST /api/v1/credits/gift-cards/:id/gift */
export async function transferGiftCard(
  id: number,
  body: { account: string },
  options?: { token?: string | null },
): Promise<GiftCard> {
  return apiRequest<GiftCard>("POST", `/api/v1/credits/gift-cards/${id}/gift`, {
    token: options?.token,
    body,
  })
}
