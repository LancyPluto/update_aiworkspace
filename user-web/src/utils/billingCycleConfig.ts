export type BillingCycle = "monthly" | "quarterly" | "yearly"

export type BillingCycleBadgeVariant = "orange" | "teal"

export interface BillingCycleOption {
  value: BillingCycle
  label: string
  badge: string | null
  hint: string | null
  badgeVariant: BillingCycleBadgeVariant | null
  prefix: string
  /** 相对原价的折扣率，用于展示划线价 */
  discountRate: number | null
}

/** 连续订阅周期配置（与充值套餐 package_code 前缀一致） */
export const BILLING_CYCLES: BillingCycleOption[] = [
  {
    value: "yearly",
    label: "连续包年",
    badge: "限时37折",
    hint: null,
    badgeVariant: "orange",
    prefix: "yearly_",
    discountRate: 0.63,
  },
  {
    value: "quarterly",
    label: "连续包季",
    badge: "季卡9折",
    hint: null,
    badgeVariant: "teal",
    prefix: "quarterly_",
    discountRate: 0.9,
  },
  {
    value: "monthly",
    label: "连续包月",
    badge: null,
    hint: "灵活月付",
    badgeVariant: null,
    prefix: "monthly_",
    discountRate: null,
  },
]

const MARKUP = 1.5

/**
 * 在平台加价 markup（默认 1.50）下，用户用完套餐算力时的最低毛利率。
 * credits 面值按 ¥0.01/点计，实际成本 = credits / markup。
 */
export function subscriptionMarginPercent(credits: number, priceYuan: number, markup = MARKUP) {
  if (!priceYuan || priceYuan <= 0) return 0
  const costYuan = credits / (markup * 100)
  return ((priceYuan - costYuan) / priceYuan) * 100
}

export function cyclePeriodLabel(cycle: BillingCycle) {
  return ({ monthly: "月", quarterly: "季", yearly: "年" } as const)[cycle]
}

export function cycleMonthDivisor(cycle: BillingCycle) {
  return ({ monthly: 1, quarterly: 3, yearly: 12 } as const)[cycle]
}
