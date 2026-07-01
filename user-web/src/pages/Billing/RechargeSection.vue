<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue"
import { Check, Crown, CreditCard, Gift, Loader2, MessageCircle, QrCode, Sparkles, X } from "lucide-vue-next"
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

const props = defineProps<{
  account: CreditAccount | null
}>()

const emit = defineEmits<{
  creditsUpdated: []
}>()

type PaymentChannel = RechargePaymentChannel

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

// 模式切换：算力充值 / 礼品卡
const mode = ref<'credits' | 'giftcard'>('credits')
const giftCardPackages = ref<GiftCardPackage[]>([])
const loadingGiftCards = ref(false)
const pendingGiftCardPackage = ref<GiftCardPackage | null>(null)

const DEFAULT_GRANTED_CREDITS = 200

// Tab 切换逻辑
const tabs = [
  { value: 'monthly' as const, label: '连续包月', discount: null },
  { value: 'quarterly' as const, label: '连续包季', discount: '限时9折' },
]
const activeTab = ref<'monthly' | 'quarterly'>('monthly')

const filteredPackages = computed(() => {
  return packages.value.filter(pkg => {
    if (activeTab.value === 'monthly') {
      return pkg.packageCode.startsWith('monthly_')
    } else {
      return pkg.packageCode.startsWith('quarterly_')
    }
  })
})

const membership = ref<{ planName: string; expiryDate: string | null }>({
  planName: "体验版",
  expiryDate: null,
})

const availableDisplay = computed(() => (props.account ? props.account.available.toLocaleString() : "--"))

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
  if (pendingGiftCardPackage.value) {
    return {
      name: pendingGiftCardPackage.value.packageName,
      credits: pendingGiftCardPackage.value.credits,
      price: pendingGiftCardPackage.value.priceAmount,
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
    // 默认选中推荐套餐或第一个月度套餐
    const recommended = list.find((item) => item.recommended)
    const monthlyFirst = list.find((item) => item.packageCode.startsWith('monthly_'))
    selectedId.value = recommended?.id ?? monthlyFirst?.id ?? list[0]?.id ?? null
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
  isCustomRecharge.value = false
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

function openGiftCardPayment(pkg: GiftCardPackage) {
  pendingGiftCardPackage.value = pkg
  paymentResult.value = null
  error.value = ""
  showChannelModal.value = true
}

async function createGiftCardOrder(pkg: GiftCardPackage, channel: PaymentChannel) {
  ordering.value = true
  error.value = ""
  try {
    const order = await createRechargeOrder(
      {
        packageId: 0,
        paymentChannel: channel,
        clientRequestId: `giftcard-${pkg.id}-${channel}-${Date.now()}`,
        orderType: 'GIFT_CARD',
        giftCardPackageId: pkg.id,
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
    <div class="relative overflow-hidden rounded-2xl border border-slate-700 bg-gradient-to-br from-slate-900 to-slate-800 p-6 shadow-lg md:p-8">
      <div class="relative flex flex-col gap-6 md:flex-row md:items-center md:justify-between">
        <div class="flex flex-col gap-6 sm:flex-row sm:gap-12">
          <div class="flex flex-col gap-1">
            <span class="text-sm font-medium text-slate-400">体验版</span>
            <p class="text-3xl font-semibold tracking-tight text-white">
              {{ availableDisplay }}
              <span class="text-base font-normal text-slate-400">/ {{ DEFAULT_GRANTED_CREDITS }}</span>
            </p>
            <p class="mt-1 text-xs text-slate-500">当前使用：体验版</p>
          </div>
          <div class="hidden h-12 w-px bg-slate-700 sm:block" aria-hidden="true" />
          <div class="flex flex-col gap-1">
            <span class="text-sm text-slate-400">会员状态</span>
            <p class="flex flex-wrap items-baseline gap-2 text-xl font-semibold">
              <Crown class="h-5 w-5 shrink-0 text-primary" aria-hidden="true" />
              <span class="text-white">{{ membership.planName }}</span>
              <span v-if="membership.expiryDate" class="text-sm font-normal text-slate-400">
                有效期至 {{ membership.expiryDate }}
              </span>
              <span v-else class="text-sm font-normal text-slate-400">未开通或永久有效</span>
            </p>
          </div>
        </div>
        <button
          type="button"
          class="inline-flex items-center justify-center rounded-lg border border-primary bg-primary/10 px-4 py-2 text-sm font-medium text-primary transition-colors hover:bg-primary/20"
        >
          升级会员
        </button>
      </div>
    </div>

    <div v-if="error" class="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
      {{ error }}
    </div>

    <!-- 模式切换 -->
    <div class="flex justify-center">
      <div class="inline-flex rounded-lg bg-secondary p-1">
        <button
          @click="mode = 'credits'"
          :class="[
            'px-8 py-2.5 text-sm font-medium rounded-md transition-all',
            mode === 'credits'
              ? 'bg-primary text-primary-foreground shadow-sm'
              : 'text-muted-foreground hover:text-foreground'
          ]"
        >
          算力充值
        </button>
        <button
          @click="mode = 'giftcard'"
          :class="[
            'px-8 py-2.5 text-sm font-medium rounded-md transition-all',
            mode === 'giftcard'
              ? 'bg-primary text-primary-foreground shadow-sm'
              : 'text-muted-foreground hover:text-foreground'
          ]"
        >
          礼品卡
        </button>
      </div>
    </div>

    <div v-if="mode === 'credits'">
      <div class="mb-6 flex items-start gap-3">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <Sparkles class="h-5 w-5" aria-hidden="true" />
        </div>
        <div>
          <h2 class="text-lg font-semibold tracking-tight">选择算力套餐</h2>
          <p class="mt-1 text-sm text-muted-foreground">下单、支付确认、到账都由充值订单状态机驱动</p>
        </div>
      </div>

      <!-- Tab 切换 -->
      <div class="flex justify-center mb-8">
        <div class="inline-flex rounded-lg bg-secondary p-1">
          <button
            v-for="tab in tabs"
            :key="tab.value"
            @click="activeTab = tab.value; selectedId = null"
            :class="[
              'px-6 py-2.5 text-sm font-medium rounded-md transition-all',
              activeTab === tab.value 
                ? 'bg-primary text-primary-foreground shadow-sm' 
                : 'text-muted-foreground hover:text-foreground'
            ]"
          >
            {{ tab.label }}
            <span v-if="tab.discount" class="ml-2 text-xs bg-destructive text-white px-1.5 py-0.5 rounded">
              {{ tab.discount }}
            </span>
          </button>
        </div>
      </div>

      <div v-if="loadingPackages" class="rounded-lg border border-border bg-card px-5 py-8 text-center text-sm text-muted-foreground">
        正在加载套餐...
      </div>
      <div v-else class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <div
          v-for="pkg in filteredPackages"
          :key="pkg.id"
          role="button"
          tabindex="0"
          class="relative group overflow-hidden rounded-2xl border bg-gradient-to-br from-slate-900 to-slate-800 p-6 shadow-lg transition-all duration-300 hover:-translate-y-1 hover:border-primary/50 hover:shadow-xl"
          :class="{
            'border-primary ring-2 ring-primary/30': selectedId === pkg.id,
            'border-slate-700': selectedId !== pkg.id,
            'ring-2 ring-blue-500/50': pkg.recommended
          }"
          @click="selectPackage(pkg.id)"
          @keydown.enter="selectPackage(pkg.id)"
          @keydown.space.prevent="selectPackage(pkg.id)"
        >
          <!-- 推荐标签 -->
          <span
            v-if="pkg.recommended"
            class="absolute right-4 top-4 rounded-full bg-blue-500 px-3 py-1 text-xs font-bold text-white shadow-lg z-10"
          >
            🔥 推荐
          </span>

          <!-- 限时折扣标签 -->
          <span
            v-if="activeTab === 'quarterly'"
            class="absolute left-4 top-4 rounded-full bg-emerald-500/20 px-2.5 py-0.5 text-xs font-semibold text-emerald-400 border border-emerald-500/30 z-10"
          >
            限时9折
          </span>

          <!-- 套餐名称 -->
          <h3 class="mt-8 text-lg font-semibold text-slate-100">
            {{ localizePackageName(pkg.packageName) }}
          </h3>

          <!-- 价格区域 -->
          <div class="mt-4 flex items-baseline gap-2">
            <span class="text-4xl font-bold text-white">
              ¥{{ formatMoney(pkg.priceAmount) }}
            </span>
            <span class="text-sm text-slate-400">
              /{{ activeTab === 'monthly' ? '月' : '季' }}
            </span>
            <span v-if="activeTab === 'quarterly'" class="text-sm text-slate-500 line-through">
              ¥{{ formatMoney(pkg.priceAmount / 0.9) }}
            </span>
          </div>

          <!-- 算力显示 -->
          <div class="mt-4 rounded-lg bg-slate-800/50 p-3 border border-slate-700">
            <p class="text-2xl font-bold text-primary">
              {{ pkg.credits.toLocaleString() }}
              <span class="text-sm font-normal text-slate-400">算力</span>
            </p>
            <p class="mt-1 text-xs text-slate-500">
              约可生成 {{ Math.floor(pkg.credits / 10) }} 张图片 · {{ Math.floor(pkg.credits / 50) }} 个视频
            </p>
          </div>

          <!-- 权益列表 -->
          <ul class="mt-5 space-y-2.5">
            <li v-for="benefit in packageBenefits(pkg)" :key="benefit" class="flex items-start gap-2.5 text-sm text-slate-300">
              <Check class="h-4 w-4 shrink-0 mt-0.5 text-emerald-400" aria-hidden="true" />
              <span>{{ benefit }}</span>
            </li>
          </ul>

          <!-- 按钮 -->
          <button
            type="button"
            class="mt-6 w-full rounded-lg bg-primary py-3 text-sm font-bold text-primary-foreground transition-all hover:bg-primary/90 hover:shadow-lg disabled:cursor-not-allowed disabled:opacity-60"
            :disabled="ordering"
            @click.stop="openPaymentChoice(pkg)"
          >
            {{ ordering && orderingPackageId === pkg.id ? "下单中..." : "立即开通" }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="mode === 'giftcard'">
      <div class="mb-6 flex items-start gap-3">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <Gift class="h-5 w-5" aria-hidden="true" />
        </div>
        <div>
          <h2 class="text-lg font-semibold tracking-tight">选择礼品卡</h2>
          <p class="mt-1 text-sm text-muted-foreground">购买后可在个人中心使用或赠送给好友</p>
        </div>
      </div>

      <div v-if="loadingGiftCards" class="rounded-lg border border-border bg-card px-5 py-8 text-center text-sm text-muted-foreground">
        正在加载礼品卡...
      </div>
      <div v-else class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <div
          v-for="pkg in giftCardPackages"
          :key="pkg.id"
          class="relative overflow-hidden rounded-2xl border bg-gradient-to-br p-6 shadow-lg transition-all duration-300 hover:-translate-y-1 hover:shadow-xl"
          :class="giftCardThemeClass(pkg.cardTheme)"
        >
          <h3 class="text-lg font-semibold text-white">
            {{ pkg.packageName }}
          </h3>

          <div class="mt-4 flex items-baseline gap-2">
            <span class="text-4xl font-bold text-white">
              ¥{{ formatMoney(pkg.priceAmount) }}
            </span>
          </div>

          <div class="mt-4 rounded-lg bg-white/10 p-3 border border-white/10">
            <p class="text-2xl font-bold text-white">
              {{ pkg.credits.toLocaleString() }}
              <span class="text-sm font-normal text-white/70">算力</span>
            </p>
          </div>

          <button
            type="button"
            class="mt-6 w-full rounded-lg bg-white/20 py-3 text-sm font-bold text-white transition-all hover:bg-white/30 disabled:cursor-not-allowed disabled:opacity-60"
            :disabled="ordering"
            @click.stop="openGiftCardPayment(pkg)"
          >
            {{ ordering ? "下单中..." : "立即购买" }}
          </button>
        </div>
      </div>
    </div>
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
              @click="pendingPackage ? createOrder(pendingPackage, option.channel) : pendingGiftCardPackage && createGiftCardOrder(pendingGiftCardPackage, option.channel)"
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
            <p class="mt-6 text-xs text-muted-foreground">
              您已同意《未来云AI付费服务协议》
            </p>
          </template>

          <div v-else-if="paymentResult === 'success'" class="pt-4">
            <div class="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-emerald-500/15 text-emerald-600 dark:text-emerald-400">
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
// HMR check
