<script setup lang="ts">
import { computed, ref } from "vue"
import {
  CreditCard,
  Crown,
  Diamond,
  Gift,
  Lock,
  Minus,
  Plus,
  ShoppingCart,
  Sparkles,
} from "lucide-vue-next"
import CreditPowerIcon from "@/components/CreditPowerIcon/CreditPowerIcon.vue"
import CreditPowerIconWatermark from "@/components/CreditPowerIcon/CreditPowerIconWatermark.vue"
import type { GiftCardPackage } from "@/api/types"
import {
  MEMBER_GIFT_TIERS,
  canBuyGiftCard,
  formatChineseDiscount,
  getCreditCardDiscount as calcCreditCardDiscount,
  normalizeMemberTier,
  type MemberTierKey,
} from "@/utils/giftCardTierConfig"

const props = defineProps<{
  giftCardPackages: GiftCardPackage[]
  loading: boolean
  ordering: boolean
  userMemberLevel?: number // 用户当前会员等级（0-3），-1表示未开通会员
}>()

const emit = defineEmits<{
  buyCreditGift: [items: Array<{ pkg: GiftCardPackage; quantity: number }>]
}>()

const TIER_ICONS = {
  flagship: Crown,
  pro: Diamond,
  growth: Sparkles,
  starter: Crown,
} as const

const TIER_STYLES = {
  flagship: {
    shell: "border-amber-500/35 bg-gradient-to-br from-amber-500/10 via-slate-900 to-slate-950",
    icon: "bg-gradient-to-br from-amber-400 to-yellow-600 shadow-amber-500/30",
    glow: "shadow-[0_0_40px_rgb(245_158_11_/_0.12)]",
  },
  pro: {
    shell: "border-sky-400/30 bg-gradient-to-br from-sky-500/10 via-slate-900 to-slate-950",
    icon: "bg-gradient-to-br from-sky-400 to-blue-600 shadow-sky-500/30",
    glow: "shadow-[0_0_40px_rgb(56_189_248_/_0.10)]",
  },
  growth: {
    shell: "border-purple-400/25 bg-gradient-to-br from-purple-500/10 via-slate-900 to-slate-950",
    icon: "bg-gradient-to-br from-purple-400 to-indigo-600 shadow-purple-500/20",
    glow: "",
  },
  starter: {
    shell: "border-cyan-400/30 bg-gradient-to-br from-cyan-500/10 via-slate-900 to-slate-950",
    icon: "bg-gradient-to-br from-cyan-400 to-blue-600 shadow-cyan-500/25",
    glow: "",
  },
} as const

const giftSteps = [
  { icon: ShoppingCart, title: "购买礼品卡", desc: "不限时有效，随时赠送兑换" },
  { icon: Gift, title: "会员中心 · 礼品卡", desc: "选择并赠送好友" },
  { icon: CreditCard, title: "获取卡密兑换", desc: "兑换后进入永久礼品卡余额" },
]

const memberQty = ref<Partial<Record<MemberTierKey, number>>>({})
const creditQty = ref<Record<number, number>>({})

function formatMoney(value: number | string | undefined | null) {
  const amount = Number(value ?? 0)
  return Number.isInteger(amount) ? String(amount) : amount.toFixed(2)
}

function cardType(pkg: GiftCardPackage) {
  return pkg.cardType === "MEMBER_CREDIT" ? "MEMBER_CREDIT" : "CREDIT"
}

function packageMemberTier(pkg: GiftCardPackage): MemberTierKey | null {
  const structuredTier = normalizeMemberTier(pkg.requiredMemberTier)
  if (structuredTier) return structuredTier
  return MEMBER_GIFT_TIERS.find((tier) => pkg.packageCode.toLowerCase().includes(tier.key))?.key ?? null
}

const memberGiftPackages = computed(() =>
  props.giftCardPackages.filter((pkg) => cardType(pkg) === "MEMBER_CREDIT" && pkg.priceAmount > 0),
)

function tierPackage(tierKey: MemberTierKey): GiftCardPackage | null {
  return memberGiftPackages.value.find((pkg) => packageMemberTier(pkg) === tierKey) ?? null
}

// 简化数量调整逻辑
function adjustMemberQty(tierKey: MemberTierKey, delta: number) {
  const next = Math.max(0, (memberQty.value[tierKey] ?? 0) + delta)
  memberQty.value = { ...memberQty.value, [tierKey]: next }
}

function adjustCreditQty(packageId: number, delta: number) {
  const next = Math.max(0, (creditQty.value[packageId] ?? 0) + delta)
  creditQty.value = { ...creditQty.value, [packageId]: next }
}

const sortedCreditPackages = computed(() =>
  [...props.giftCardPackages]
    .filter((pkg) => cardType(pkg) === "CREDIT" && pkg.priceAmount > 0)
    .sort((a, b) => a.credits - b.credits),
)

function normalizeDiscountRate(value: number | null | undefined) {
  const rate = Number(value)
  if (!Number.isFinite(rate) || rate <= 0) return null
  return rate > 1 && rate <= 100 ? rate / 100 : rate
}

function giftCardDiscount(pkg: GiftCardPackage, fallback?: number): number {
  const structuredRate = normalizeDiscountRate(pkg.discountRate)
  if (structuredRate) return structuredRate
  if (pkg.listPriceAmount && pkg.listPriceAmount > 0) {
    return pkg.priceAmount / pkg.listPriceAmount
  }
  return fallback ?? calcCreditCardDiscount(pkg.priceAmount, pkg.credits)
}

const cartSummary = computed(() => {
  let count = 0
  let amount = 0

  for (const [tierIndex, tier] of MEMBER_GIFT_TIERS.entries()) {
    const qty = memberQty.value[tier.key] ?? 0
    if (qty <= 0 || !canBuyTier(tierIndex)) continue
    const pkg = tierPackage(tier.key)
    if (!pkg) continue
    count += qty
    amount += pkg.priceAmount * qty
  }

  for (const pkg of sortedCreditPackages.value) {
    const qty = creditQty.value[pkg.id] ?? 0
    if (qty <= 0) continue
    count += qty
    amount += pkg.priceAmount * qty
  }

  return { count, amount }
})

function checkout() {
  const purchases: Array<{ pkg: GiftCardPackage; quantity: number }> = []

  for (const [tierIndex, tier] of MEMBER_GIFT_TIERS.entries()) {
    const qty = memberQty.value[tier.key] ?? 0
    if (qty <= 0 || !canBuyTier(tierIndex)) continue
    const pkg = tierPackage(tier.key)
    if (!pkg) continue
    purchases.push({ pkg, quantity: qty })
  }

  for (const pkg of sortedCreditPackages.value) {
    const qty = creditQty.value[pkg.id] ?? 0
    if (qty <= 0) continue
    purchases.push({ pkg, quantity: qty })
  }

  if (purchases.length === 0) return
  emit("buyCreditGift", purchases)
  memberQty.value = {}
  creditQty.value = {}
}

function tierBadge(tierKey: MemberTierKey) {
  const tier = MEMBER_GIFT_TIERS.find((item) => item.key === tierKey)
  return tier?.badge ?? null
}

// 检查是否可以购买该等级的礼品卡
function canBuyTier(tierIndex: number): boolean {
  const userLevel = props.userMemberLevel ?? -1
  return canBuyGiftCard(userLevel, tierIndex)
}
</script>

<template>
  <div class="gift-card-page space-y-10 pb-6">
    <div class="gift-steps rounded-2xl border border-slate-800/80 bg-slate-950/70 px-4 py-5 md:px-8">
      <div class="grid gap-6 md:grid-cols-3">
        <div
          v-for="(step, index) in giftSteps"
          :key="step.title"
          class="flex items-start gap-3"
        >
          <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-sky-500/15 text-sky-400 ring-1 ring-sky-500/20">
            <component :is="step.icon" class="h-4 w-4" aria-hidden="true" />
          </div>
          <div>
            <p class="text-xs font-medium text-sky-400/90">第 {{ index + 1 }} 步</p>
            <p class="mt-0.5 text-sm font-semibold text-slate-100">{{ step.title }}</p>
            <p class="mt-1 text-xs text-slate-500">{{ step.desc }}</p>
          </div>
        </div>
      </div>
    </div>

    <section class="space-y-6">
      <div>
        <h2 class="text-lg font-semibold text-sky-400">会员专属礼品卡</h2>
        <p class="mt-1 text-xs text-slate-500">兑换为永久算力，不升级或延长会员；双方均需达到卡片要求的会员等级</p>
      </div>

      <div v-if="loading" class="rounded-2xl border border-slate-800 bg-slate-900/50 px-5 py-12 text-center text-sm text-slate-400">
        正在加载礼品卡...
      </div>

      <div v-else class="grid gap-4 lg:grid-cols-2 xl:grid-cols-4">
        <article
          v-for="(tier, index) in MEMBER_GIFT_TIERS"
          :key="tier.key"
          class="member-gift-card relative flex flex-col overflow-hidden rounded-2xl border p-5 shadow-xl"
          :class="[
            TIER_STYLES[tier.key as keyof typeof TIER_STYLES].shell,
            TIER_STYLES[tier.key as keyof typeof TIER_STYLES].glow,
            !canBuyTier(index) ? 'opacity-60' : ''
          ]"
        >
          <!-- 权限锁提示 -->
          <div v-if="!canBuyTier(index)" class="absolute inset-0 z-20 flex items-center justify-center bg-black/30 backdrop-blur-[1px]">
            <div class="flex flex-col items-center gap-2 text-white">
              <Lock class="h-8 w-8" />
              <span class="text-xs font-medium">需{{ tier.label }}及以上会员</span>
            </div>
          </div>

          <div
            v-if="tierBadge(tier.key)?.text"
            class="member-gift-badge"
            :class="`member-gift-badge--${tierBadge(tier.key)?.variant || 'muted'}`"
          >
            {{ tierBadge(tier.key)?.text }}
          </div>

          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0">
              <h3 class="text-lg font-bold text-white">{{ tier.label }}</h3>
              <p class="mt-1 text-xs text-slate-400">{{ tier.subtitle }}</p>
            </div>
            <div
              class="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl shadow-lg"
              :class="TIER_STYLES[tier.key as keyof typeof TIER_STYLES].icon"
            >
              <component :is="TIER_ICONS[tier.key as keyof typeof TIER_ICONS]" class="h-5 w-5 text-white" aria-hidden="true" />
            </div>
          </div>

          <template v-if="tierPackage(tier.key)">
            <p class="mt-4 text-xs text-slate-500">
              {{ tierPackage(tier.key)!.credits.toLocaleString() }} 永久算力
            </p>
            <div class="mt-2 space-y-1 text-[11px] leading-relaxed text-slate-400">
              <p>
                <span class="text-slate-500">使用期限：</span>
                <span class="font-medium text-white/85">永久有效</span>
              </p>
              <p>
                <span class="text-slate-500">兑换要求：</span>
                <span class="font-medium text-white/85">{{ tier.label }}及以上有效会员</span>
              </p>
              <p>
                <span class="text-slate-500">到账方式：</span>
                <span class="font-medium text-white/85">兑换后进入永久礼品卡余额</span>
              </p>
            </div>

            <div class="mt-5 flex items-end justify-between gap-3 border-t border-white/8 pt-4">
              <div>
                <div class="flex items-baseline gap-1.5">
                  <span class="text-3xl font-bold tracking-tight text-white">
                    ¥{{ formatMoney(tierPackage(tier.key)!.priceAmount) }}
                  </span>
                  <span class="text-xs font-semibold text-emerald-400">
                    {{ formatChineseDiscount(giftCardDiscount(tierPackage(tier.key)!, tier.giftDiscount)) }}
                  </span>
                </div>
              </div>

              <div class="qty-control">
                <button
                  type="button"
                  class="qty-btn"
                  :disabled="ordering || (memberQty[tier.key] ?? 0) <= 0 || !canBuyTier(index)"
                  @click="adjustMemberQty(tier.key, -1)"
                >
                  <Minus class="h-3.5 w-3.5" />
                </button>
                <span class="qty-value">{{ memberQty[tier.key] ?? 0 }}</span>
                <button
                  type="button"
                  class="qty-btn"
                  :disabled="ordering || !canBuyTier(index)"
                  @click="adjustMemberQty(tier.key, 1)"
                >
                  <Plus class="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
          </template>

          <p v-else class="mt-6 text-sm text-slate-500">暂未上架</p>
        </article>
      </div>
    </section>

    <section class="space-y-5">
      <div>
        <h2 class="text-lg font-semibold text-sky-400">算力礼品卡</h2>
        <p class="mt-1 text-xs text-slate-500">固定永久算力面额，即买即送；面额越大越优惠</p>
      </div>

      <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <article
          v-for="pkg in sortedCreditPackages"
          :key="pkg.id"
          class="credit-gift-card"
        >
          <div class="credit-gift-card__body">
            <CreditPowerIconWatermark class="credit-gift-card__watermark" :size="128" />

            <div class="credit-gift-card__head">
              <CreditPowerIcon :size="34" class="credit-gift-card__icon" aria-hidden="true" />
              <span class="credit-gift-card__credits">{{ pkg.credits.toLocaleString() }} <small>永久算力</small></span>
            </div>

            <div class="credit-gift-card__meta">
              <p>
                <span class="credit-gift-card__meta-label">使用期限：</span>
                <span class="credit-gift-card__meta-value">不限时使用</span>
              </p>
              <p>
                <span class="credit-gift-card__meta-label">扣减规则：</span>
                <span class="credit-gift-card__meta-value">兑换后优先扣减会员套餐算力，用尽后再扣礼品卡余额</span>
              </p>
            </div>
          </div>

          <div class="credit-gift-card__footer">
            <div>
              <div class="flex items-baseline gap-1">
                <span class="text-2xl font-bold text-white">¥{{ formatMoney(pkg.priceAmount) }}</span>
                <span v-if="giftCardDiscount(pkg) < 1" class="ml-2 text-xs text-emerald-400">
                  {{ formatChineseDiscount(giftCardDiscount(pkg)) }}
                </span>
              </div>
            </div>
          
            <div class="qty-control">
              <button
                type="button"
                class="qty-btn"
                :disabled="ordering || (creditQty[pkg.id] ?? 0) <= 0"
                @click="adjustCreditQty(pkg.id, -1)"
              >
                <Minus class="h-3.5 w-3.5" />
              </button>
              <span class="qty-value">{{ creditQty[pkg.id] ?? 0 }}</span>
              <button
                type="button"
                class="qty-btn"
                :disabled="ordering"
                @click="adjustCreditQty(pkg.id, 1)"
              >
                <Plus class="h-3.5 w-3.5" />
              </button>
            </div>
          </div>
        </article>
      </div>
    </section>

    <div
      v-if="cartSummary.count > 0"
      class="gift-checkout-bar sticky bottom-0 z-10 -mx-4 border-t border-slate-800 bg-slate-950/95 px-4 py-3 backdrop-blur-md md:-mx-0 md:rounded-b-2xl"
    >
      <div class="flex items-center justify-between gap-4">
        <div class="text-sm text-slate-300">
          已选 <span class="font-semibold text-white">{{ cartSummary.count }}</span> 件
          <span class="mx-2 text-slate-600">·</span>
          合计 <span class="text-lg font-bold text-sky-400">¥{{ formatMoney(cartSummary.amount) }}</span>
        </div>
        <button
          type="button"
          class="rounded-full bg-gradient-to-r from-sky-400 to-blue-500 px-6 py-2.5 text-sm font-semibold text-slate-950 shadow-lg shadow-sky-500/20 transition hover:brightness-105 disabled:opacity-60"
          :disabled="ordering"
          @click="checkout"
        >
          {{ ordering ? "处理中..." : "去结算" }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.member-gift-badge {
  position: absolute;
  top: 12px;
  right: 12px;
  border-radius: 6px;
  padding: 3px 8px;
  font-size: 10px;
  font-weight: 700;
  line-height: 1.2;
}

.member-gift-badge--orange {
  background: linear-gradient(135deg, #f97316, #ea580c);
  color: #fff7ed;
}

.member-gift-badge--teal {
  background: linear-gradient(135deg, #38bdf8, #2563eb);
  color: #eff6ff;
}

.member-gift-badge--muted {
  background: rgb(255 255 255 / 0.08);
  color: rgb(255 255 255 / 0.62);
}

.qty-control {
  display: inline-flex;
  align-items: center;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.04);
  padding: 2px;
}

.qty-btn {
  display: flex;
  width: 30px;
  height: 30px;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: rgb(255 255 255 / 0.78);
  transition: background 0.15s ease, color 0.15s ease;
}

.qty-btn:hover:not(:disabled) {
  background: rgb(255 255 255 / 0.08);
  color: #fff;
}

.qty-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.qty-value {
  min-width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: #000;
  padding: 0 4px;
  text-align: center;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
}

.credit-gift-card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 14px;
  border: 1px solid rgb(255 255 255 / 0.06);
  background: #121317;
  box-shadow: 0 18px 40px rgb(0 0 0 / 0.28);
}

.credit-gift-card__body {
  position: relative;
  min-height: 148px;
  padding: 18px 18px 16px;
  overflow: hidden;
}

.credit-gift-card__watermark {
  position: absolute;
  right: -18px;
  top: 50%;
  opacity: 0.92;
  transform: translateY(-52%);
}

.credit-gift-card__head {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 10px;
}

.credit-gift-card__icon {
  filter: drop-shadow(0 8px 18px rgb(10 118 253 / 0.35));
}

.credit-gift-card__credits {
  font-size: 34px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.03em;
  color: #fff;
}

.credit-gift-card__credits small {
  margin-left: 4px;
  color: rgb(148 163 184 / 0.88);
  font-size: 11px;
  font-weight: 600;
}

.credit-gift-card__meta {
  position: relative;
  z-index: 1;
  margin-top: 14px;
  display: grid;
  gap: 6px;
  padding-right: 72px;
  font-size: 11px;
  line-height: 1.55;
  color: rgb(148 163 184 / 0.92);
}

.credit-gift-card__meta-label {
  color: rgb(148 163 184 / 0.88);
}

.credit-gift-card__meta-value {
  color: rgb(255 255 255 / 0.92);
  font-weight: 600;
}

.credit-gift-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-top: 1px solid rgb(255 255 255 / 0.05);
  background: rgb(255 255 255 / 0.03);
  padding: 14px 16px;
}

.credit-gift-card__price {
  display: flex;
  align-items: baseline;
  gap: 4px;
  font-size: 28px;
  font-weight: 700;
  line-height: 1;
  color: #fff;
}

.credit-gift-card__price-symbol {
  font-size: 18px;
  font-weight: 600;
  color: rgb(255 255 255 / 0.88);
}
</style>
