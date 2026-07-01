import type { BillingCycle } from "@/utils/billingCycleConfig"

export type GiftBadgeVariant = "orange" | "teal" | "muted"

export interface MemberGiftTierConfig {
  key: string
  label: string
  subtitle: string
  /** 相对月卡×周期原价的礼品卡展示折扣（仅影响划线价，实际成交价仍取套餐接口） */
  giftDiscountByCycle: Record<BillingCycle, number | null>
  badgeByCycle: Record<BillingCycle, { text: string; variant: GiftBadgeVariant }>
}

export const MEMBER_GIFT_TIERS: MemberGiftTierConfig[] = [
  {
    key: "flagship",
    label: "黑金会员",
    subtitle: "旗舰尊享 · 年卡最划算",
    giftDiscountByCycle: { yearly: 0.63, quarterly: 0.88, monthly: 0.95 },
    badgeByCycle: {
      yearly: { text: "限时37折", variant: "orange" },
      quarterly: { text: "立省12%", variant: "teal" },
      monthly: { text: "黑金95折", variant: "muted" },
    },
  },
  {
    key: "pro",
    label: "钻石会员",
    subtitle: "专业团队 · 季卡优选",
    giftDiscountByCycle: { yearly: 0.65, quarterly: 0.9, monthly: 0.97 },
    badgeByCycle: {
      yearly: { text: "年卡65折", variant: "orange" },
      quarterly: { text: "季卡9折", variant: "teal" },
      monthly: { text: "钻石97折", variant: "muted" },
    },
  },
  {
    key: "growth",
    label: "铂金会员",
    subtitle: "日常创作 · 均衡之选",
    giftDiscountByCycle: { yearly: 0.68, quarterly: 0.91, monthly: null },
    badgeByCycle: {
      yearly: { text: "年卡68折", variant: "orange" },
      quarterly: { text: "季卡91折", variant: "teal" },
      monthly: { text: "灵活月付", variant: "muted" },
    },
  },
  {
    key: "starter",
    label: "黄金会员",
    subtitle: "轻度创作 · 入门优选",
    giftDiscountByCycle: { yearly: 0.7, quarterly: 0.92, monthly: null },
    badgeByCycle: {
      yearly: { text: "年卡7折", variant: "orange" },
      quarterly: { text: "季卡92折", variant: "teal" },
      monthly: { text: "灵活月付", variant: "muted" },
    },
  },
]

export function memberGiftOriginalPrice(price: number, cycle: BillingCycle, tierKey: string) {
  const tier = MEMBER_GIFT_TIERS.find((item) => item.key === tierKey)
  const rate = tier?.giftDiscountByCycle[cycle]
  if (!rate || rate >= 1) return null
  return price / rate
}
