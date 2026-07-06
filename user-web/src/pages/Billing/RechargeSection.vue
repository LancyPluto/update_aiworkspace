<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue"
import { Check, CreditCard, Crown, Loader2, MessageCircle, QrCode, X } from "lucide-vue-next"
import type { CreditAccount, GiftCardPackage, RechargeOrder, RechargePackage } from "@/api/types"
import {
  createRechargeOrder,
  fetchGiftCardPackages,
  fetchRechargeOrder,
  fetchRechargePackages,
} from "@/api/creditApi"
import { useAuthStore } from "@/store/authStore"
import {
  DEFAULT_RECHARGE_PAYMENT_CHANNELS,
  isAlipayPageRedirectOrder,
  rechargePaymentFailureMessage,
  resolveAlipayLaunchUrl,
  type RechargePaymentChannel,
} from "@/utils/rechargePayment"
import GiftCardSection from "@/pages/Billing/GiftCardSection.vue"
import {
  BILLING_CYCLES,
  cycleMonthDivisor,
  cyclePeriodLabel,
  type BillingCycle,
} from "@/utils/billingCycleConfig"
import { getUserMemberLevel } from "@/utils/giftCardTierConfig"

const props = defineProps<{
  account: CreditAccount | null
}>()

const emit = defineEmits<{
  creditsUpdated: []
}>()

type PaymentChannel = RechargePaymentChannel

const activeTab = ref<BillingCycle>("quarterly")

const auth = useAuthStore()
const packages = ref<RechargePackage[]>([])
const selectedId = ref<number | null>(null)
const pendingPackage = ref<RechargePackage | null>(null)
const loadingPackages = ref(false)
const ordering = ref(false)
const orderingPackageId = ref<number | null>(null)
const error = ref("")
const showChannelModal = ref(false)
const showPayModal = ref(false)
const activeOrder = ref<RechargeOrder | null>(null)
const paymentResult = ref<"success" | "fail" | null>(null)
let pollingTimer: ReturnType<typeof setInterval> | null = null

// 模式切换：会员计划 / 礼品卡
const mode = ref<'credits' | 'giftcard'>('credits')
const giftCardPackages = ref<GiftCardPackage[]>([])
const loadingGiftCards = ref(false)
const pendingGiftCardPackage = ref<GiftCardPackage | null>(null)
const pendingGiftCardItems = ref<Array<{ pkg: GiftCardPackage; quantity: number }>>([])

// 用户当前会员等级（0-3），-1表示未开通会员
// 基于后端返回的 membershipPlan（用户最近一次CREDITED订单的套餐代码）
const userMemberLevel = computed(() => getUserMemberLevel(auth.user?.membershipPlan))

const TRIAL_PLAN_NAME = "体验版"
const TRIAL_GRANTED_CREDITS = 200

const membershipStatus = computed(() => ({
  planName: TRIAL_PLAN_NAME,
  availableDisplay: props.account ? props.account.available.toLocaleString() : "--",
}))

const TIER_META: Record<string, { label: string; subtitle: string; featured?: boolean }> = {
  starter: { label: "标准版", subtitle: "适合轻度创作者" },
  growth: { label: "进阶版", subtitle: "适合日常创作" },
  pro: { label: "高级版", subtitle: "适合专业团队" },
  flagship: { label: "豪华版", subtitle: "旗舰尊享", featured: true },
}

const TIER_ORDER = ["starter", "growth", "pro", "flagship"]

const filteredPackages = computed(() => {
  const prefix = BILLING_CYCLES.find((tab) => tab.value === activeTab.value)?.prefix ?? "monthly_"
  return packages.value
    .filter((pkg) => pkg.packageCode.startsWith(prefix))
    .sort((a, b) => TIER_ORDER.indexOf(tierKey(a.packageCode)) - TIER_ORDER.indexOf(tierKey(b.packageCode)))
})

const activeCycleMeta = computed(() => BILLING_CYCLES.find((tab) => tab.value === activeTab.value) ?? BILLING_CYCLES[2])

const paymentOptions = computed(() =>
  DEFAULT_RECHARGE_PAYMENT_CHANNELS.map((option) => ({
    ...option,
    icon: option.channel === "WECHAT_NATIVE" ? MessageCircle : CreditCard,
  })),
)

const payModalTitle = computed(() => {
  if (!activeOrder.value) return "扫码支付"
  const amount = formatMoney(activeOrder.value.priceAmount)
  return `扫码支付 ${amount} 元`
})

const activePaymentName = computed(() => {
  if (activeOrder.value?.paymentChannel === "ALIPAY_PAGE") return "支付宝"
  return "微信"
})

const isAlipayPageRedirect = computed(() =>
  activeOrder.value ? isAlipayPageRedirectOrder(activeOrder.value) : false,
)

const isGiftCardOrder = computed(() => activeOrder.value?.orderType === 'GIFT_CARD')

const successMessage = computed(() =>
  isGiftCardOrder.value ? '礼品卡购买成功，请在个人中心查看' : '支付成功，算力已到账',
)

const pendingDisplay = computed(() => {
  if (pendingGiftCardItems.value.length > 0) {
    const itemCount = pendingGiftCardItems.value.reduce((sum, item) => sum + item.quantity, 0)
    const skuCount = pendingGiftCardItems.value.length
    return {
      name: skuCount === 1
        ? `${pendingGiftCardItems.value[0].pkg.packageName} x ${pendingGiftCardItems.value[0].quantity}`
        : `算力礼品卡 ${itemCount} 张`,
      credits: pendingGiftCardItems.value.reduce((sum, item) => sum + item.pkg.credits * item.quantity, 0),
      price: pendingGiftCardItems.value.reduce((sum, item) => sum + item.pkg.priceAmount * item.quantity, 0),
    }
  }
  if (pendingPackage.value) {
    return {
      name: localizePackageName(pendingPackage.value.packageName),
      credits: pendingPackage.value.credits,
      price: pendingPackage.value.priceAmount,
    }
  }
  return null
})

function redirectToAlipayCheckout(order: RechargeOrder) {
  if (!isAlipayPageRedirectOrder(order) || !order.payUrl) return false
  window.location.assign(resolveAlipayLaunchUrl(order.payUrl))
  return true
}



function formatMoney(value: number | string | undefined | null) {
  const amount = Number(value ?? 0)
  return Number.isInteger(amount) ? String(amount) : amount.toFixed(2)
}

const GIFT_CARD_THEMES: Record<string, string> = {
  blue: 'from-blue-600 to-blue-800 border-blue-400/30',
  purple: 'from-purple-600 to-purple-800 border-purple-400/30',
  gold: 'from-amber-600 to-yellow-800 border-amber-400/30',
  dark: 'from-slate-800 to-slate-950 border-slate-600/30',
}

function giftCardThemeClass(theme: string | undefined | null) {
  return GIFT_CARD_THEMES[theme || 'dark'] || GIFT_CARD_THEMES.dark
}

const PACKAGE_NAME_ZH: Record<string, string> = {
  "Starter credits": "入门套餐",
  "Growth credits": "成长套餐",
  "Pro credits": "专业套餐",
  "Test credits": "测试套餐",
}

const BENEFIT_ZH: Record<string, string> = {
  "Priority queue": "优先排队",
  "API acceleration": "API 加速",
  "Model consulting": "模型咨询服务",
}

function localizePackageName(name: string | undefined | null) {
  if (!name) return ""
  const trimmed = name.trim()
  return PACKAGE_NAME_ZH[trimmed] ?? trimmed
}

function localizeBenefit(benefit: string) {
  const trimmed = benefit.trim()
  if (BENEFIT_ZH[trimmed]) return BENEFIT_ZH[trimmed]
  return trimmed
    .replace(/valid\s+for\s+(\d+)\s+days?/gi, "有效期 $1 天")
    .replace(/(\d+)\s+days?/gi, "$1 天")
    .replace(/\bcredits?\b/gi, "算力")
    .replace(/\bbonus\b/gi, "赠送")
    .replace(/\bpackage\b/gi, "套餐")
    .replace(/\brecharge\b/gi, "充值")
    .replace(/\bvalidity\b/gi, "有效期")
    .replace(/\bpriority\s+queue\b/gi, "优先排队")
    .replace(/\bapi\s+acceleration\b/gi, "API 加速")
    .replace(/\bmodel\s+consulting\b/gi, "模型咨询服务")
}

function tierKey(packageCode: string) {
  const parts = packageCode.split("_")
  return parts[parts.length - 1] ?? packageCode
}

function tierMeta(packageCode: string) {
  return TIER_META[tierKey(packageCode)] ?? { label: localizePackageName(packageCode), subtitle: "" }
}

function periodLabel(cycle: BillingCycle) {
  return cyclePeriodLabel(cycle)
}

function originalPrice(pkg: RechargePackage, cycle: BillingCycle) {
  const rate = BILLING_CYCLES.find((tab) => tab.value === cycle)?.discountRate
  if (!rate) return null
  return pkg.priceAmount / rate
}

function creditsPerMonth(pkg: RechargePackage, cycle: BillingCycle) {
  return Math.round(pkg.credits / cycleMonthDivisor(cycle))
}

function creditUnitPrice(pkg: RechargePackage) {
  if (!pkg.credits) return "0"
  const unit = pkg.priceAmount / pkg.credits
  return unit < 0.01 ? unit.toFixed(4) : unit.toFixed(3)
}

function renewalHint(pkg: RechargePackage, cycle: BillingCycle) {
  const amount = formatMoney(pkg.priceAmount)
  if (cycle === "monthly") return `次月续费 ¥${amount}，可随时取消`
  if (cycle === "quarterly") return `次季续费 ¥${amount}，可随时取消`
  return `次年续费 ¥${amount}，可随时取消`
}

function monthlyEquivalentPrice(pkg: RechargePackage, cycle: BillingCycle) {
  return pkg.priceAmount / cycleMonthDivisor(cycle)
}

function isFeaturedCard(pkg: RechargePackage) {
  if (pkg.recommended) return true
  return tierMeta(pkg.packageCode).featured === true && activeTab.value === "yearly"
}

function selectDefaultPackage(list: RechargePackage[]) {
  const prefix = activeCycleMeta.value.prefix
  const recommended = list.find((item) => item.recommended && item.packageCode.startsWith(prefix))
  const first = list.find((item) => item.packageCode.startsWith(prefix))
  selectedId.value = recommended?.id ?? first?.id ?? list[0]?.id ?? null
}

function onBillingCycleChange(cycle: BillingCycle) {
  activeTab.value = cycle
  selectDefaultPackage(packages.value)
}

function packageBenefits(pkg: RechargePackage) {
  const benefits = pkg.benefits?.filter(Boolean) ?? []
  if (benefits.length > 0) return benefits.map(localizeBenefit)
  return [`到账 ${pkg.credits.toLocaleString()} 点算力`, `有效期 ${pkg.validityDays} 天`]
}

function selectPackage(id: number) {
  selectedId.value = id
}

function clearPolling() {
  if (pollingTimer) {
    clearInterval(pollingTimer)
    pollingTimer = null
  }
}

function closeChannelModal() {
  if (ordering.value) return
  showChannelModal.value = false
  pendingPackage.value = null
  pendingGiftCardPackage.value = null
  pendingGiftCardItems.value = []
}

function closePayModal() {
  clearPolling()
  showPayModal.value = false
  activeOrder.value = null
  paymentResult.value = null
}

async function loadPackages() {
  loadingPackages.value = true
  error.value = ""
  try {
    const list = await fetchRechargePackages({ token: auth.token })
    packages.value = list
    selectDefaultPackage(list)
  } catch (err) {
    error.value = err instanceof Error ? err.message : "加载充值套餐失败"
  } finally {
    loadingPackages.value = false
  }
}

async function pollOrder(orderId: number) {
  try {
    const order = await fetchRechargeOrder(orderId, { token: auth.token })
    activeOrder.value = order
    if (order.status === "CREDITED") {
      paymentResult.value = "success"
      clearPolling()
      emit("creditsUpdated")
    } else if (order.status === "FAILED" || order.status === "CLOSED") {
      paymentResult.value = "fail"
      clearPolling()
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : "查询充值订单失败"
    clearPolling()
  }
}

function startPolling(orderId: number) {
  clearPolling()
  pollingTimer = setInterval(() => {
    void pollOrder(orderId)
  }, 1500)
}

function openPaymentChoice(pkg: RechargePackage) {
  pendingPackage.value = pkg
  selectedId.value = pkg.id
  paymentResult.value = null
  error.value = ""
  showChannelModal.value = true
}



function openPayModalForOrder(order: RechargeOrder, channel: PaymentChannel): boolean {
  const hasAlipayLaunch = isAlipayPageRedirectOrder(order, channel)
  if (!order.qrCodeUrl && !hasAlipayLaunch) {
    error.value = rechargePaymentFailureMessage(order, channel)
    return false
  }
  activeOrder.value = order
  paymentResult.value = null
  showChannelModal.value = false
  if (hasAlipayLaunch) {
    startPolling(order.id)
    redirectToAlipayCheckout(order)
    return true
  }
  showPayModal.value = true
  startPolling(order.id)
  return true
}

async function createOrder(pkg: RechargePackage, channel: PaymentChannel) {
  ordering.value = true
  orderingPackageId.value = pkg.id
  error.value = ""
  try {
    const order = await createRechargeOrder(
      {
        packageId: pkg.id,
        paymentChannel: channel,
        clientRequestId: `recharge-${pkg.id}-${channel}-${Date.now()}`,
      },
      { token: auth.token },
    )
    if (!openPayModalForOrder(order, channel)) {
      return
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : "创建充值订单失败"
  } finally {
    ordering.value = false
    orderingPackageId.value = null
  }
}

async function loadGiftCardPackages() {
  loadingGiftCards.value = true
  try {
    giftCardPackages.value = await fetchGiftCardPackages({ token: auth.token })
  } catch (err) {
    error.value = err instanceof Error ? err.message : "加载礼品卡套餐失败"
  } finally {
    loadingGiftCards.value = false
  }
}

function openGiftCardPayment(items: Array<{ pkg: GiftCardPackage; quantity: number }>) {
  const normalized = items.filter((item) => item.quantity > 0)
  if (normalized.length === 0) return
  pendingPackage.value = null
  pendingGiftCardPackage.value = normalized[0].pkg
  pendingGiftCardItems.value = normalized
  paymentResult.value = null
  error.value = ""
  showChannelModal.value = true
}

async function createGiftCardOrder(items: Array<{ pkg: GiftCardPackage; quantity: number }>, channel: PaymentChannel) {
  if (items.length === 0) return
  ordering.value = true
  error.value = ""
  try {
    const order = await createRechargeOrder(
      {
        packageId: null,
        paymentChannel: channel,
        clientRequestId: `giftcard-${items.map((item) => `${item.pkg.id}x${item.quantity}`).join("-")}-${channel}-${Date.now()}`,
        orderType: 'GIFT_CARD',
        giftCardPackageId: items[0].pkg.id,
        giftCardItems: items.map((item) => ({
          giftCardPackageId: item.pkg.id,
          quantity: item.quantity,
        })),
      },
      { token: auth.token },
    )
    if (!openPayModalForOrder(order, channel)) {
      return
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : "创建礼品卡订单失败"
  } finally {
    ordering.value = false
  }
}

onMounted(() => {
  loadPackages()
  loadGiftCardPackages()
})
onUnmounted(clearPolling)
</script>

<template>
  <section class="space-y-6">
    <article class="membership-status-card">
      <div class="membership-status-card__main">
        <div class="membership-status-card__label">当前会员</div>
        <div class="membership-status-card__plan">
          <Crown class="h-5 w-5 shrink-0 text-cyan-300" aria-hidden="true" />
          <span>{{ membershipStatus.planName }}</span>
        </div>
        <p class="membership-status-card__meta">
          可用算力
          <strong>{{ membershipStatus.availableDisplay }}</strong>
          <span class="membership-status-card__divider">·</span>
          体验额度 {{ TRIAL_GRANTED_CREDITS }}
        </p>
        <p class="membership-status-card__hint">未开通连续订阅，选择下方套餐即可升级会员</p>
      </div>
    </article>

    <div v-if="error" class="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
      {{ error }}
    </div>

    <div class="billing-mode-tabs" role="tablist" aria-label="会员与礼品卡">
      <button
        type="button"
        role="tab"
        class="billing-mode-tab"
        :class="{ 'billing-mode-tab--active': mode === 'credits' }"
        :aria-selected="mode === 'credits'"
        @click="mode = 'credits'"
      >
        会员计划
      </button>
      <button
        type="button"
        role="tab"
        class="billing-mode-tab"
        :class="{ 'billing-mode-tab--active': mode === 'giftcard' }"
        :aria-selected="mode === 'giftcard'"
        @click="mode = 'giftcard'"
      >
        礼品卡
      </button>
    </div>

    <!-- 计费周期切换：按年/按季/按月（仅会员计划） -->
    <div v-if="mode === 'credits'" class="billing-cycle-tabs" role="tablist" aria-label="订阅周期">
      <button
        v-for="tab in BILLING_CYCLES"
        :key="tab.value"
        type="button"
        role="tab"
        class="billing-cycle-tab"
        :class="{ 'billing-cycle-tab--active': activeTab === tab.value }"
        :aria-selected="activeTab === tab.value"
        @click="onBillingCycleChange(tab.value)"
      >
        <span>{{ tab.label }}</span>
        <span v-if="tab.badge" class="billing-cycle-badge" :class="`billing-cycle-badge--${tab.badgeVariant}`">
          {{ tab.badge }}
        </span>
        <span v-if="tab.hint" class="billing-cycle-hint">{{ tab.hint }}</span>
      </button>
    </div>

    <div v-if="mode === 'credits'" class="space-y-8">
      <div v-if="loadingPackages" class="rounded-2xl border border-border bg-card px-5 py-12 text-center text-sm text-muted-foreground">
        正在加载套餐...
      </div>

      <div
        v-else-if="filteredPackages.length === 0"
        class="rounded-2xl border border-dashed border-border bg-card px-5 py-12 text-center text-sm text-muted-foreground"
      >
        当前周期暂无可用套餐，请切换其他订阅周期
      </div>

      <div
        v-else
        class="-mx-1 flex gap-4 overflow-x-auto px-1 pb-4 snap-x snap-mandatory scrollbar-thin lg:grid lg:grid-cols-4 lg:overflow-visible"
      >
        <article
          v-for="pkg in filteredPackages"
          :key="pkg.id"
          role="button"
          tabindex="0"
          class="relative flex min-w-[260px] shrink-0 snap-center flex-col rounded-2xl border bg-gradient-to-b from-slate-900 via-slate-900 to-slate-950 p-5 shadow-xl transition-all duration-300 hover:-translate-y-0.5 lg:min-w-0"
          :class="{
            'border-cyan-400/70 ring-2 ring-cyan-400/30 shadow-cyan-500/10': isFeaturedCard(pkg),
            'border-primary/60 ring-1 ring-primary/20': selectedId === pkg.id && !isFeaturedCard(pkg),
            'border-slate-800': selectedId !== pkg.id && !isFeaturedCard(pkg),
          }"
          @click="selectPackage(pkg.id)"
          @keydown.enter="selectPackage(pkg.id)"
          @keydown.space.prevent="selectPackage(pkg.id)"
        >
          <div
            v-if="isFeaturedCard(pkg)"
            class="absolute -top-px left-0 right-0 rounded-t-2xl bg-gradient-to-r from-cyan-500 to-blue-500 px-4 py-1.5 text-center text-xs font-semibold text-slate-950"
          >
            {{ activeTab === 'yearly' ? '特惠上新 · 比月卡立省 37%' : '🔥 推荐套餐' }}
          </div>

          <div :class="isFeaturedCard(pkg) ? 'mt-6' : 'mt-1'">
            <p class="text-sm font-medium text-slate-400">{{ tierMeta(pkg.packageCode).subtitle }}</p>
            <h3 class="mt-1 text-2xl font-bold tracking-tight text-white">
              {{ tierMeta(pkg.packageCode).label }}
            </h3>
          </div>

          <div class="mt-5 flex flex-wrap items-end gap-2">
            <span class="text-4xl font-bold leading-none text-white">¥{{ formatMoney(pkg.priceAmount) }}</span>
            <span class="pb-1 text-sm text-slate-400">/{{ periodLabel(activeTab) }}</span>
            <span
              v-if="originalPrice(pkg, activeTab)"
              class="pb-1 text-sm text-slate-500 line-through"
            >
              ¥{{ formatMoney(originalPrice(pkg, activeTab)) }}
            </span>
          </div>

          <p class="mt-2 text-xs text-slate-500">{{ renewalHint(pkg, activeTab) }}</p>

          <p class="mt-3 text-xs text-slate-400">
            约 ¥{{ formatMoney(monthlyEquivalentPrice(pkg, activeTab)) }}/月
            <span class="mx-1 text-slate-600">·</span>
            ¥{{ creditUnitPrice(pkg) }}/算力
          </p>

          <div class="mt-5 rounded-xl border border-slate-700/80 bg-slate-800/40 px-4 py-4">
            <p class="text-3xl font-bold text-white">
              {{ creditsPerMonth(pkg, activeTab).toLocaleString() }}
              <span class="text-sm font-medium text-slate-400">算力/月</span>
            </p>
            <p class="mt-2 text-xs text-slate-500">
              约可生成 {{ Math.floor(creditsPerMonth(pkg, activeTab) / 10).toLocaleString() }} 张图
              <span class="mx-1">|</span>
              {{ Math.floor(creditsPerMonth(pkg, activeTab) / 50).toLocaleString() }} 个视频
            </p>
            <p class="mt-1 text-[11px] text-slate-600">
              本周期共 {{ pkg.credits.toLocaleString() }} 算力 · 有效期 {{ pkg.validityDays }} 天
            </p>
          </div>

          <button
            type="button"
            class="mt-5 w-full rounded-xl bg-gradient-to-r from-sky-400 to-blue-500 py-3 text-sm font-bold text-slate-950 shadow-lg shadow-sky-500/20 transition-all hover:brightness-105 disabled:cursor-not-allowed disabled:opacity-60"
            :disabled="ordering"
            @click.stop="openPaymentChoice(pkg)"
          >
            {{ ordering && orderingPackageId === pkg.id ? "下单中..." : "立即开通" }}
          </button>

          <ul class="mt-5 flex-1 space-y-2 border-t border-slate-800 pt-4">
            <li
              v-for="benefit in packageBenefits(pkg)"
              :key="benefit"
              class="flex items-start gap-2 text-xs leading-relaxed text-slate-300"
            >
              <Check class="mt-0.5 h-3.5 w-3.5 shrink-0 text-cyan-400" aria-hidden="true" />
              <span>{{ benefit }}</span>
            </li>
          </ul>
        </article>
      </div>
    </div>

    <GiftCardSection
      v-if="mode === 'giftcard'"
      :packages="packages"
      :gift-card-packages="giftCardPackages"
      :loading="loadingGiftCards || loadingPackages"
      :ordering="ordering"
      :user-member-level="userMemberLevel"
      @buy-member-package="openPaymentChoice"
      @buy-credit-gift="openGiftCardPayment"
    />
    <Teleport to="body">
      <div
        v-if="showChannelModal"
        class="fixed inset-0 z-[100] flex items-center justify-center bg-black/45 p-4 backdrop-blur-[2px]"
        role="dialog"
        aria-modal="true"
        aria-labelledby="payment-channel-title"
        @click.self="closeChannelModal"
      >
        <div class="w-full max-w-md rounded-2xl border border-border bg-card p-6 shadow-xl" @click.stop>
          <div class="flex items-start justify-between gap-4">
            <div>
              <h3 id="payment-channel-title" class="text-lg font-semibold text-foreground">确认订单</h3>
              <p class="mt-1 text-sm text-muted-foreground">
                {{ pendingDisplay?.name }} · {{ pendingDisplay?.credits.toLocaleString() }} 算力
              </p>
            </div>
            <button
              type="button"
              class="rounded-full p-1.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
              aria-label="关闭"
              @click="closeChannelModal"
            >
              <X class="h-5 w-5" aria-hidden="true" />
            </button>
          </div>

          <div class="mt-5 grid gap-3">
            <button
              v-for="option in paymentOptions"
              :key="option.channel"
              type="button"
              class="flex w-full items-center gap-4 rounded-xl border border-border bg-background px-4 py-3 text-left transition hover:border-primary/50 hover:bg-primary/5 disabled:cursor-not-allowed disabled:opacity-60"
              :disabled="ordering || !pendingDisplay"
              @click="pendingPackage ? createOrder(pendingPackage, option.channel) : createGiftCardOrder(pendingGiftCardItems, option.channel)"
            >
              <span class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                <component :is="option.icon" class="h-5 w-5" aria-hidden="true" />
              </span>
              <span class="min-w-0 flex-1">
                <span class="block text-sm font-semibold text-foreground">{{ option.title }}</span>
                <span class="mt-0.5 block text-xs text-muted-foreground">{{ option.description }}</span>
              </span>
              <Loader2 v-if="ordering" class="h-4 w-4 animate-spin text-primary" aria-hidden="true" />
            </button>
          </div>

          <p class="mt-5 text-center text-xs text-muted-foreground">
            应付金额
            <span class="font-semibold text-primary">¥{{ formatMoney(pendingDisplay?.price) }}</span>
          </p>
        </div>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="showPayModal"
        class="fixed inset-0 z-[100] flex items-center justify-center bg-black/45 p-4 backdrop-blur-[2px]"
        role="dialog"
        aria-modal="true"
        aria-labelledby="pay-modal-title"
        @click.self="closePayModal"
      >
        <div class="relative w-full max-w-[408px] rounded-2xl border border-border bg-card px-8 py-9 text-center shadow-xl" @click.stop>
          <button
            type="button"
            class="absolute right-5 top-5 rounded-full p-1.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
            aria-label="关闭"
            @click="closePayModal"
          >
            <X class="h-5 w-5" aria-hidden="true" />
          </button>

          <template v-if="!paymentResult">
            <h3 id="pay-modal-title" class="text-xl font-semibold tracking-tight text-foreground">
              {{ payModalTitle }}
            </h3>

            <div
              v-if="isAlipayPageRedirect"
              class="mx-auto mt-10 max-w-sm rounded-2xl border border-border bg-secondary/35 p-5"
            >
              <p class="text-sm text-muted-foreground">当前为电脑网站支付模式，将在新窗口打开支付宝收银台。</p>
              <button
                type="button"
                class="mt-4 w-full rounded-full bg-primary py-2.5 text-sm font-semibold text-primary-foreground hover:bg-primary/90"
                @click="activeOrder && redirectToAlipayCheckout(activeOrder)"
              >
                打开支付宝收银台
              </button>
            </div>

            <div
              v-else
              class="mx-auto mt-14 flex h-40 w-40 items-center justify-center bg-white p-1"
            >
              <img
                v-if="activeOrder?.qrCodeUrl"
                :src="activeOrder.qrCodeUrl"
                :alt="`${activePaymentName}支付二维码`"
                class="h-full w-full object-contain"
              />
              <QrCode v-else class="h-24 w-24 text-slate-900" aria-hidden="true" />
            </div>

            <p v-if="!isAlipayPageRedirect" class="mt-7 flex items-center justify-center gap-1.5 text-sm text-muted-foreground">
              请扫码完成支付
              <span class="inline-flex h-4 w-4 items-center justify-center rounded bg-sky-500 text-[10px] font-bold text-white">
                {{ activeOrder?.paymentChannel === "ALIPAY_PAGE" ? "支" : "微" }}
              </span>
            </p>

            <div class="mt-8 flex items-center justify-center gap-2 rounded-full border border-border bg-secondary/45 px-4 py-2.5 text-xs text-muted-foreground">
              <Loader2 class="h-4 w-4 animate-spin text-primary" aria-hidden="true" />
              <span>正在确认订单状态</span>
            </div>
          </template>

          <div v-else-if="paymentResult === 'success'" class="pt-4">
            <div class="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-sky-500/15 text-sky-500 dark:text-sky-400">
              <Check class="h-8 w-8" stroke-width="2.5" aria-hidden="true" />
            </div>
            <p class="mt-5 text-sm font-medium text-foreground">{{ successMessage }}</p>
            <button type="button" class="mt-6 rounded-full bg-primary px-6 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90" @click="closePayModal">
              关闭
            </button>
          </div>

          <div v-else class="pt-4">
            <div class="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-destructive/15 text-destructive">
              <X class="h-8 w-8" stroke-width="2.5" aria-hidden="true" />
            </div>
            <p class="mt-5 text-sm font-medium text-foreground">支付未完成，请稍后重试</p>
            <button type="button" class="mt-6 rounded-full bg-primary px-6 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90" @click="closePayModal">
              关闭
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.membership-status-card {
  overflow: hidden;
  border-radius: 16px;
  border: 1px solid rgb(255 255 255 / 0.08);
  background: linear-gradient(135deg, rgb(15 23 42 / 0.95), rgb(30 41 59 / 0.88));
  padding: 20px 24px;
  box-shadow: 0 16px 40px rgb(0 0 0 / 0.22);
}

.membership-status-card__label {
  font-size: 13px;
  font-weight: 500;
  color: rgb(255 255 255 / 0.45);
}

.membership-status-card__plan {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  font-size: 24px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 0.01em;
}

.membership-status-card__meta {
  margin-top: 12px;
  font-size: 14px;
  color: rgb(255 255 255 / 0.55);
}

.membership-status-card__meta strong {
  margin-left: 6px;
  font-size: 18px;
  font-weight: 700;
  color: #fff;
}

.membership-status-card__divider {
  margin: 0 8px;
  color: rgb(255 255 255 / 0.22);
}

.membership-status-card__hint {
  margin-top: 8px;
  font-size: 12px;
  color: rgb(255 255 255 / 0.38);
}

.billing-mode-tabs {
  display: flex;
  justify-content: center;
  gap: 48px;
  border-bottom: 1px solid rgb(255 255 255 / 0.06);
  padding-bottom: 0;
}

.billing-mode-tab {
  position: relative;
  border: 0;
  background: transparent;
  padding: 0 4px 14px;
  color: rgb(255 255 255 / 0.42);
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.02em;
  cursor: pointer;
  transition: color 0.2s ease;
}

.billing-mode-tab:hover {
  color: rgb(255 255 255 / 0.72);
}

.billing-mode-tab--active {
  color: #fff;
}

.billing-mode-tab--active::after {
  content: "";
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 3px;
  border-radius: 999px 999px 0 0;
  background: #fff;
}

/* 计费周期切换标签 */
.billing-cycle-tabs {
  display: flex;
  justify-content: center;
  gap: 6px;
  padding: 5px;
  border-radius: 12px;
  background: rgb(255 255 255 / 0.04);
  border: 1px solid rgb(255 255 255 / 0.06);
  max-width: 420px;
  margin: 0 auto;
}

.billing-cycle-tab {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  border: 0;
  background: transparent;
  padding: 8px 12px;
  border-radius: 10px;
  color: rgb(255 255 255 / 0.48);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
}

.billing-cycle-tab:hover {
  color: rgb(255 255 255 / 0.75);
  background: rgb(255 255 255 / 0.04);
}

.billing-cycle-tab--active {
  background: rgb(255 255 255 / 0.09);
  color: #fff;
  font-weight: 600;
}

.billing-cycle-badge {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 5px;
  border-radius: 4px;
  line-height: 1.4;
}

.billing-cycle-badge--orange {
  background: linear-gradient(135deg, #f97316, #ea580c);
  color: #fff7ed;
}

.billing-cycle-badge--teal {
  background: linear-gradient(135deg, #38bdf8, #2563eb);
  color: #eff6ff;
}

.billing-cycle-hint {
  font-size: 10px;
  color: rgb(255 255 255 / 0.35);
}

@media (max-width: 560px) {
  .billing-mode-tabs {
    gap: 32px;
  }

  .billing-mode-tab {
    font-size: 15px;
  }
}
</style>


