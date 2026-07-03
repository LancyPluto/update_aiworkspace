export type GiftBadgeVariant = "orange" | "teal" | "muted"

// 会员等级配置（按从低到高排序）
export const MEMBER_TIERS = [
  { key: "starter", label: "标准版", minPricePerCredit: 0.015 },
  { key: "growth", label: "进阶版", minPricePerCredit: 0.015 },
  { key: "pro", label: "高级版", minPricePerCredit: 0.015 },
  { key: "flagship", label: "豪华版", minPricePerCredit: 0.015 },
] as const

// 算力礼品卡的列表价（每算力的原价，用于计算折扣）
export const GIFT_CARD_LIST_PRICE_PER_CREDIT = 0.020

// 算力礼品卡最优折扣下限（不能比标准版会员礼品卡98折更好）
export const GIFT_CARD_MIN_DISCOUNT = 0.98

/**
 * 根据用户的 membershipPlan（套餐代码）判断会员等级索引
 * @param membershipPlan 用户当前会员套餐代码，如 "monthly_starter"、"yearly_pro"
 * @returns 0-3 表示等级索引，-1 表示未开通会员
 */
export function getUserMemberLevel(membershipPlan: string | null | undefined): number {
  if (!membershipPlan) return -1
  const code = membershipPlan.toLowerCase()
  // 从高到低检查，返回最高匹配等级
  for (let i = MEMBER_TIERS.length - 1; i >= 0; i--) {
    if (code.includes(MEMBER_TIERS[i].key)) {
      return i
    }
  }
  return -1 // 未开通会员
}

// 判断用户是否可以购买某个等级的礼品卡
export function canBuyGiftCard(userLevel: number, targetTierIndex: number): boolean {
  // 只能购买等于或低于自己等级的礼品卡
  return userLevel >= targetTierIndex && userLevel >= 0
}

export interface MemberGiftTierConfig {
  key: string
  label: string
  subtitle: string
  /** 礼品卡折扣（基于会员套餐原价） */
  giftDiscount: number
  badge?: { text: string; variant: GiftBadgeVariant }
}

export const MEMBER_GIFT_TIERS: MemberGiftTierConfig[] = [
  {
    key: "starter",
    label: "标准版",
    subtitle: "适合轻度创作者",
    giftDiscount: 0.98, // 98折
    badge: { text: "入门首选", variant: "muted" },
  },
  {
    key: "growth",
    label: "进阶版",
    subtitle: "适合日常创作",
    giftDiscount: 0.96, // 96折
    badge: { text: "人气推荐", variant: "teal" },
  },
  {
    key: "pro",
    label: "高级版",
    subtitle: "适合专业团队",
    giftDiscount: 0.94, // 94折
    badge: { text: "专业之选", variant: "teal" },
  },
  {
    key: "flagship",
    label: "豪华版",
    subtitle: "旗舰尊享",
    giftDiscount: 0.92, // 92折
    badge: { text: "至尊特权", variant: "orange" },
  },
]

/**
 * 计算礼品卡原价（基于会员套餐价格）
 * @param memberPackagePrice 会员套餐价格
 * @param discount 礼品卡折扣
 */
export function memberGiftOriginalPrice(memberPackagePrice: number, discount: number): number | null {
  if (!discount || discount >= 1) return null
  return memberPackagePrice / discount
}

/**
 * 计算算力礼品卡的折扣率（基于固定列表价）
 * 折扣 = 实际单价 / 列表价单价
 * 最低不低于 GIFT_CARD_MIN_DISCOUNT（即不能比标准版会员礼品卡98折更好）
 *
 * @param priceAmount 礼品卡售价
 * @param credits 礼品卡算力数
 * @returns 折扣率（1.0 = 原价，0.98 = 98折）
 */
export function getCreditCardDiscount(priceAmount: number, credits: number): number {
  if (!credits) return 1.0
  const currentPricePerCredit = priceAmount / credits
  const discount = currentPricePerCredit / GIFT_CARD_LIST_PRICE_PER_CREDIT
  return Math.max(discount, GIFT_CARD_MIN_DISCOUNT)
}
