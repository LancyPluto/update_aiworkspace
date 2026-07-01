<script setup lang="ts">
import { computed, ref } from "vue"
import {
  CreditCard,
  Crown,
  Diamond,
  Flame,
  Gift,
  Minus,
  Plus,
  ShoppingCart,
  Sparkles,
} from "lucide-vue-next"
import type { GiftCardPackage, RechargePackage } from "@/api/types"

type BillingCycle = "monthly" | "quarterly" | "yearly"

const props = defineProps<{
  packages: RechargePackage[]
  giftCardPackages: GiftCardPackage[]
  loading: boolean
  ordering: boolean
}>()

const emit = defineEmits<{
  buyMemberPackage: [pkg: RechargePackage]
  buyCreditGift: [pkg: GiftCardPackage, quantity: number]
}>()

const billingCycles: Array<{ value: BillingCycle; label: string; prefix: string; discountRate: number | null }> = [
  { value: "yearly", label: "/年", prefix: "yearly_", discountRate: 0.63 },
  { value: "quarterly", label: "/季", prefix: "quarterly_", discountRate: 0.9 },
  { value: "monthly", label: "/月", prefix: "monthly_", discountRate: null },
]

const MEMBER_TIERS = [
  {
    key: "flagship",
    label: "黑金会员",
    icon: Crown,
    accent: "from-amber-200/20 to-amber-500/10 border-amber-500/30 text-amber-200",
    iconBg: "bg-gradient-to-br from-amber-400 to-yellow-600",
  },
  {
    key: "pro",
    label: "钻石会员",
    icon: Diamond,
    accent: "from-sky-200/15 to-blue-500/10 border-sky-400/25 text-sky-200",
    iconBg: "bg-gradient-to-br from-sky-400 to-blue-600",
  },
  {
    key: "growth",
    label: "铂金会员",
    icon: Sparkles,
    accent: "from-slate-200/15 to-slate-400/10 border-slate-300/25 text-slate-200",
    iconBg: "bg-gradient-to-br from-slate-300 to-slate-500",
  },
  {
    key: "starter",
    label: "黄金会员",
    icon: Crown,
    accent: "from-orange-200/15 to-orange-500/10 border-orange-400/25 text-orange-200",
    iconBg: "bg-gradient-to-br from-orange-400 to-amber-600",
  },
] as const

const giftSteps = [
  { icon: ShoppingCart, title: "购买礼品卡", desc: "购买后 1 年内有效" },
  { icon: Gift, title: "会员中心 · 礼品卡", desc: "选择并赠送好友" },
  { icon: CreditCard, title: "获取卡密兑换", desc: "在会员中心完成兑换" },
]

const memberQty = ref<Record<string, number>>({})
const creditQty = ref<Record<number, number>>({})

function formatMoney(value: number | string | undefined | null) {
  const amount = Number(value ?? 0)
  return Number.isInteger(amount) ? String(amount) : amount.toFixed(2)
}

function tierPackage(tierKey: string, cycle: BillingCycle) {
  const prefix = billingCycles.find((item) => item.value === cycle)?.prefix ?? "monthly_"
  return props.packages.find((pkg) => pkg.packageCode === `${prefix}${tierKey}`) ?? null
}

function originalPrice(pkg: RechargePackage, cycle: BillingCycle) {
  const rate = billingCycles.find((item) => item.value === cycle)?.discountRate
  if (!rate) return null
  return pkg.priceAmount / rate
}

function creditsPerMonth(pkg: RechargePackage, cycle: BillingCycle) {
  const divisor = ({ monthly: 1, quarterly: 3, yearly: 12 } as const)[cycle]
  return Math.round(pkg.credits / divisor)
}

function memberCartKey(tierKey: string, cycle: BillingCycle) {
  return `${tierKey}_${cycle}`
}

function adjustMemberQty(tierKey: string, cycle: BillingCycle, delta: number) {
  const key = memberCartKey(tierKey, cycle)
  const next = Math.max(0, (memberQty.value[key] ?? 0) + delta)
  memberQty.value = { ...memberQty.value, [key]: next }
}

function adjustCreditQty(packageId: number, delta: number) {
  const next = Math.max(0, (creditQty.value[packageId] ?? 0) + delta)
  creditQty.value = { ...creditQty.value, [packageId]: next }
}

function creditOriginalPrice(pkg: GiftCardPackage) {
  if (pkg.credits >= 1000) return pkg.priceAmount * 1.12
  return null
}

const sortedCreditPackages = computed(() =>
  [...props.giftCardPackages].sort((a, b) => a.credits - b.credits),
)

const cartSummary = computed(() => {
  let count = 0
  let amount = 0

  for (const tier of MEMBER_TIERS) {
    for (const cycle of billingCycles) {
      const qty = memberQty.value[memberCartKey(tier.key, cycle.value)] ?? 0
      if (qty <= 0) continue
      const pkg = tierPackage(tier.key, cycle.value)
      if (!pkg) continue
      count += qty
      amount += pkg.priceAmount * qty
    }
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
  for (const tier of MEMBER_TIERS) {
    for (const cycle of billingCycles) {
      const qty = memberQty.value[memberCartKey(tier.key, cycle.value)] ?? 0
      if (qty <= 0) continue
      const pkg = tierPackage(tier.key, cycle.value)
      if (!pkg) continue
      emit("buyMemberPackage", pkg)
      memberQty.value = { ...memberQty.value, [memberCartKey(tier.key, cycle.value)]: 0 }
      return
    }
  }

  for (const pkg of sortedCreditPackages.value) {
    const qty = creditQty.value[pkg.id] ?? 0
    if (qty <= 0) continue
    emit("buyCreditGift", pkg, qty)
    creditQty.value = { ...creditQty.value, [pkg.id]: 0 }
    return
  }
}
</script>

<template>
  <div class="space-y-10 pb-24">
    <!-- 三步流程 -->
    <div class="rounded-2xl border border-slate-800 bg-slate-950/60 px-4 py-5 md:px-8">
      <div class="grid gap-6 md:grid-cols-3">
        <div
          v-for="(step, index) in giftSteps"
          :key="step.title"
          class="flex items-start gap-3"
        >
          <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-emerald-500/15 text-emerald-400">
            <component :is="step.icon" class="h-4 w-4" aria-hidden="true" />
          </div>
          <div>
            <p class="text-xs font-medium text-emerald-400/90">第 {{ index + 1 }} 步</p>
            <p class="mt-0.5 text-sm font-semibold text-slate-100">{{ step.title }}</p>
            <p class="mt-1 text-xs text-slate-500">{{ step.desc }}</p>
          </div>
        </div>
      </div>
    </div>

    <!-- 会员礼品卡 -->
    <section class="space-y-5">
      <div>
        <h2 class="text-lg font-semibold text-emerald-400">会员礼品卡</h2>
        <p class="mt-1 text-xs text-slate-500">按会员档位购买算力礼包，支持包年 / 包季 / 包月组合选购</p>
      </div>

      <div v-if="loading" class="rounded-2xl border border-slate-800 bg-slate-900/50 px-5 py-12 text-center text-sm text-slate-400">
        正在加载礼品卡...
      </div>

      <div v-else class="grid gap-4 lg:grid-cols-2 xl:grid-cols-4">
        <article
          v-for="tier in MEMBER_TIERS"
          :key="tier.key"
          class="flex flex-col rounded-2xl border bg-slate-900/80 p-4 shadow-lg"
          :class="tier.accent"
        >
          <div class="flex items-start justify-between gap-3">
            <div>
              <h3 class="text-base font-bold text-white">{{ tier.label }}</h3>
              <p v-if="tierPackage(tier.key, 'monthly')" class="mt-2 text-xs leading-relaxed text-slate-400">
                每月 {{ creditsPerMonth(tierPackage(tier.key, 'monthly')!, 'monthly').toLocaleString() }} 算力
                · 约 {{ Math.floor(creditsPerMonth(tierPackage(tier.key, 'monthly')!, 'monthly') / 10) }} 张图
              </p>
              <p v-else class="mt-2 text-xs text-slate-500">套餐加载中</p>
            </div>
            <div class="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl shadow-inner" :class="tier.iconBg">
              <component :is="tier.icon" class="h-5 w-5 text-white/90" aria-hidden="true" />
            </div>
          </div>

          <div class="mt-4 space-y-2">
            <div
              v-for="cycle in billingCycles"
              :key="cycle.value"
              class="flex items-center gap-2 rounded-xl border border-slate-800/80 bg-slate-950/50 px-3 py-2.5"
            >
              <template v-if="tierPackage(tier.key, cycle.value)">
                <div class="min-w-0 flex-1">
                  <div class="flex items-baseline gap-1.5">
                    <span class="text-lg font-bold text-white">¥{{ formatMoney(tierPackage(tier.key, cycle.value)!.priceAmount) }}</span>
                    <span class="text-xs text-slate-500">{{ cycle.label }}</span>
                  </div>
                  <p
                    v-if="originalPrice(tierPackage(tier.key, cycle.value)!, cycle.value)"
                    class="text-[11px] text-slate-600 line-through"
                  >
                    ¥{{ formatMoney(originalPrice(tierPackage(tier.key, cycle.value)!, cycle.value)) }}
                  </p>
                </div>

                <div class="flex items-center rounded-full border border-slate-700 bg-slate-900 p-0.5">
                  <button
                    type="button"
                    class="flex h-7 w-7 items-center justify-center rounded-full text-slate-400 transition hover:bg-slate-800 hover:text-white disabled:opacity-30"
                    :disabled="ordering || (memberQty[memberCartKey(tier.key, cycle.value)] ?? 0) <= 0"
                    @click="adjustMemberQty(tier.key, cycle.value, -1)"
                  >
                    <Minus class="h-3.5 w-3.5" />
                  </button>
                  <span class="w-6 text-center text-sm font-medium text-white">
                    {{ memberQty[memberCartKey(tier.key, cycle.value)] ?? 0 }}
                  </span>
                  <button
                    type="button"
                    class="flex h-7 w-7 items-center justify-center rounded-full text-slate-400 transition hover:bg-slate-800 hover:text-white disabled:opacity-30"
                    :disabled="ordering"
                    @click="adjustMemberQty(tier.key, cycle.value, 1)"
                  >
                    <Plus class="h-3.5 w-3.5" />
                  </button>
                </div>
              </template>
            </div>
          </div>
        </article>
      </div>
    </section>

    <!-- 算力礼品卡 -->
    <section class="space-y-5">
      <h2 class="text-lg font-semibold text-emerald-400">算力礼品卡</h2>

      <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <article
          v-for="pkg in sortedCreditPackages"
          :key="pkg.id"
          class="relative overflow-hidden rounded-2xl border border-emerald-500/20 bg-gradient-to-br from-slate-900 to-slate-950 p-5"
        >
          <Flame
            class="pointer-events-none absolute -right-4 -top-4 h-28 w-28 text-emerald-500/10"
            aria-hidden="true"
          />

          <div class="relative flex items-center gap-2">
            <Flame class="h-6 w-6 text-emerald-400" aria-hidden="true" />
            <span class="text-3xl font-bold text-white">{{ pkg.credits.toLocaleString() }}</span>
          </div>

          <p class="relative mt-3 text-[11px] leading-relaxed text-slate-500">
            购买后 1 年内可兑换；兑换后算力有效期以平台规则为准
          </p>

          <div class="relative mt-5 flex items-center justify-between gap-3 border-t border-slate-800 pt-4">
            <div>
              <div class="flex items-baseline gap-2">
                <span class="text-xl font-bold text-white">¥{{ formatMoney(pkg.priceAmount) }}</span>
                <span
                  v-if="creditOriginalPrice(pkg)"
                  class="text-xs text-slate-600 line-through"
                >
                  ¥{{ formatMoney(creditOriginalPrice(pkg)) }}
                </span>
              </div>
            </div>

            <div class="flex items-center rounded-full border border-slate-700 bg-slate-900 p-0.5">
              <button
                type="button"
                class="flex h-7 w-7 items-center justify-center rounded-full text-slate-400 transition hover:bg-slate-800 hover:text-white disabled:opacity-30"
                :disabled="ordering || (creditQty[pkg.id] ?? 0) <= 0"
                @click="adjustCreditQty(pkg.id, -1)"
              >
                <Minus class="h-3.5 w-3.5" />
              </button>
              <span class="w-6 text-center text-sm font-medium text-white">{{ creditQty[pkg.id] ?? 0 }}</span>
              <button
                type="button"
                class="flex h-7 w-7 items-center justify-center rounded-full text-slate-400 transition hover:bg-slate-800 hover:text-white disabled:opacity-30"
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

    <!-- 结算栏 -->
    <Teleport to="body">
      <div
        v-if="cartSummary.count > 0"
        class="fixed inset-x-0 bottom-0 z-[90] border-t border-slate-800 bg-slate-950/95 px-4 py-3 backdrop-blur-md"
      >
        <div class="mx-auto flex max-w-4xl items-center justify-between gap-4">
          <div class="text-sm text-slate-300">
            已选 <span class="font-semibold text-white">{{ cartSummary.count }}</span> 件
            <span class="mx-2 text-slate-600">·</span>
            合计 <span class="text-lg font-bold text-emerald-400">¥{{ formatMoney(cartSummary.amount) }}</span>
          </div>
          <button
            type="button"
            class="rounded-full bg-emerald-500 px-6 py-2.5 text-sm font-semibold text-slate-950 transition hover:bg-emerald-400 disabled:opacity-60"
            :disabled="ordering"
            @click="checkout"
          >
            {{ ordering ? "处理中..." : "去结算" }}
          </button>
        </div>
      </div>
    </Teleport>
  </div>
</template>
