<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue"
import { Check, Crown, CreditCard, Loader2, MessageCircle, QrCode, Sparkles, X } from "lucide-vue-next"
import type { CreditAccount, RechargeOrder, RechargePackage } from "@/api/types"
import { createCustomRechargeOrder, createRechargeOrder, fetchRechargeOrder, fetchRechargePackages, mockPayRechargeOrder } from "@/api/creditApi"
import { useAuthStore } from "@/store/authStore"
import {
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
const customAmount = ref("")
const customCredits = computed(() => {
  const amount = parseFloat(customAmount.value)
  return Number.isFinite(amount) && amount > 0 ? Math.floor(amount * 100) : 0
})
const pendingPackage = ref<RechargePackage | null>(null)
const isCustomRecharge = ref(false)
const loadingPackages = ref(false)
const ordering = ref(false)
const orderingPackageId = ref<number | null>(null)
const error = ref("")
const showChannelModal = ref(false)
const showPayModal = ref(false)
const activeOrder = ref<RechargeOrder | null>(null)
const paymentResult = ref<"success" | "fail" | null>(null)
let pollingTimer: ReturnType<typeof setInterval> | null = null

const membership = ref<{ planName: string; expiryDate: string | null }>({
  planName: "免费版",
  expiryDate: null,
})

const availableDisplay = computed(() => (props.account ? props.account.available.toLocaleString() : "--"))

const paymentOptions: Array<{
  channel: PaymentChannel
  title: string
  description: string
  icon: typeof QrCode
}> = [
  {
    channel: "WECHAT_NATIVE",
    title: "微信扫码支付",
    description: "使用微信扫一扫完成付款",
    icon: MessageCircle,
  },
  {
    channel: "ALIPAY_PAGE",
    title: "支付宝电脑支付",
    description: "跳转支付宝官方收银台完成付款",
    icon: CreditCard,
  },
  {
    channel: "MOCK",
    title: "模拟支付",
    description: "用于本地测试充值一致性",
    icon: Sparkles,
  },
]

const payModalTitle = computed(() => {
  if (!activeOrder.value) return "扫码支付"
  const amount = formatMoney(activeOrder.value.priceAmount)
  if (activeOrder.value.paymentChannel === "MOCK") return `模拟支付 ${amount} 元`
  return `扫码支付 ${amount} 元`
})

const activePaymentName = computed(() => {
  if (activeOrder.value?.paymentChannel === "ALIPAY_PAGE") return "支付宝"
  if (activeOrder.value?.paymentChannel === "WECHAT_NATIVE") return "微信"
  return "模拟支付"
})

const isAlipayPageRedirect = computed(() =>
  activeOrder.value ? isAlipayPageRedirectOrder(activeOrder.value) : false,
)

function redirectToAlipayCheckout(order: RechargeOrder) {
  if (!isAlipayPageRedirectOrder(order) || !order.payUrl) return false
  window.location.assign(resolveAlipayLaunchUrl(order.payUrl))
  return true
}

function normalizeCustomAmountInput(raw: string): string {
  const value = raw.trim()
  if (!value) return ""
  const matched = value.match(/^\d*(?:\.\d{0,2})?/)
  return matched?.[0] ?? ""
}

function onCustomAmountInput(event: Event) {
  const input = event.target as HTMLInputElement
  const normalized = normalizeCustomAmountInput(input.value)
  if (normalized !== input.value) {
    input.value = normalized
  }
  customAmount.value = normalized
}

function formatMoney(value: number | string | undefined | null) {
  const amount = Number(value ?? 0)
  return Number.isInteger(amount) ? String(amount) : amount.toFixed(2)
}

function localizeBenefit(benefit: string) {
  return benefit
    .replace(/valid\s+for\s+(\d+)\s+days?/gi, "有效期 $1 天")
    .replace(/(\d+)\s+days?/gi, "$1 天")
    .replace(/\bcredits?\b/gi, "算力")
    .replace(/\bbonus\b/gi, "赠送")
    .replace(/\bpackage\b/gi, "套餐")
    .replace(/\brecharge\b/gi, "充值")
    .replace(/\bvalidity\b/gi, "有效期")
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
  isCustomRecharge.value = false
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
    selectedId.value = list.find((item) => item.recommended)?.id ?? list[0]?.id ?? null
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

function openCustomPaymentChoice() {
  const amount = Math.round(parseFloat(customAmount.value) * 100) / 100
  if (!Number.isFinite(amount) || amount < 0.01) {
    error.value = "请输入有效的充值金额（最低 0.01 元）"
    return
  }
  isCustomRecharge.value = true
  pendingPackage.value = null
  selectedId.value = null
  paymentResult.value = null
  error.value = ""
  showChannelModal.value = true
}

async function submitCustomRecharge(channel: PaymentChannel) {
  const amount = Math.round(parseFloat(customAmount.value) * 100) / 100
  if (!Number.isFinite(amount) || amount < 0.01) {
    error.value = "请输入有效的充值金额（最低 0.01 元）"
    return
  }
  ordering.value = true
  orderingPackageId.value = -1
  error.value = ""
  try {
    const order = await createCustomRechargeOrder(
      {
        amount,
        paymentChannel: channel,
        clientRequestId: `custom-recharge-${channel}-${Date.now()}`,
      },
      { token: auth.token },
    )
    if (!openPayModalForOrder(order, channel)) {
      return
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : "创建自定义充值订单失败"
  } finally {
    ordering.value = false
    orderingPackageId.value = null
  }
}

function openPayModalForOrder(order: RechargeOrder, channel: PaymentChannel): boolean {
  const hasAlipayLaunch = isAlipayPageRedirectOrder(order, channel)
  if (channel !== "MOCK" && !order.qrCodeUrl && !hasAlipayLaunch) {
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

async function confirmMockPayment() {
  if (!activeOrder.value) return
  ordering.value = true
  error.value = ""
  try {
    const order = await mockPayRechargeOrder(activeOrder.value.id, { token: auth.token })
    activeOrder.value = order
    if (order.status === "CREDITED") {
      paymentResult.value = "success"
      clearPolling()
      emit("creditsUpdated")
    }
  } catch (err) {
    paymentResult.value = "fail"
    error.value = err instanceof Error ? err.message : "确认支付失败"
  } finally {
    ordering.value = false
  }
}

onMounted(loadPackages)
onUnmounted(clearPolling)
</script>

<template>
  <section class="space-y-6">
    <div class="relative overflow-hidden rounded-xl border border-border bg-card p-6 shadow-sm md:p-8">
      <div class="relative flex flex-col gap-6 md:flex-row md:items-center md:justify-between">
        <div class="flex flex-col gap-6 sm:flex-row sm:gap-12">
          <div class="flex flex-col gap-1">
            <span class="text-sm text-muted-foreground">可用算力</span>
            <p class="text-3xl font-semibold tracking-tight text-foreground">
              {{ availableDisplay }}
              <span class="text-base font-normal text-muted-foreground">点</span>
            </p>
          </div>
          <div class="hidden h-12 w-px bg-border sm:block" aria-hidden="true" />
          <div class="flex flex-col gap-1">
            <span class="text-sm text-muted-foreground">会员状态</span>
            <p class="flex flex-wrap items-baseline gap-2 text-xl font-semibold">
              <Crown class="h-5 w-5 shrink-0 text-primary" aria-hidden="true" />
              {{ membership.planName }}
              <span v-if="membership.expiryDate" class="text-sm font-normal text-muted-foreground">
                有效期至 {{ membership.expiryDate }}
              </span>
              <span v-else class="text-sm font-normal text-muted-foreground">未开通或永久有效</span>
            </p>
          </div>
        </div>
        <button
          type="button"
          class="inline-flex items-center justify-center rounded-md border border-primary bg-transparent px-4 py-2 text-sm font-medium text-primary transition-colors hover:bg-primary/10"
        >
          升级会员
        </button>
      </div>
    </div>

    <div v-if="error" class="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
      {{ error }}
    </div>

    <div>
      <div class="mb-6 flex items-start gap-3">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <Sparkles class="h-5 w-5" aria-hidden="true" />
        </div>
        <div>
          <h2 class="text-lg font-semibold tracking-tight">选择算力套餐</h2>
          <p class="mt-1 text-sm text-muted-foreground">下单、支付确认、到账都由充值订单状态机驱动</p>
        </div>
      </div>

      <div v-if="loadingPackages" class="rounded-lg border border-border bg-card px-5 py-8 text-center text-sm text-muted-foreground">
        正在加载套餐...
      </div>
      <div v-else class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <div
          v-for="pkg in packages"
          :key="pkg.id"
          role="button"
          tabindex="0"
          class="group relative flex min-h-[286px] flex-col rounded-xl border bg-card p-6 shadow-sm transition-all duration-200 hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-primary/30"
          :class="selectedId === pkg.id ? 'border-primary ring-1 ring-primary/20' : 'border-border'"
          @click="selectPackage(pkg.id)"
          @keydown.enter="selectPackage(pkg.id)"
          @keydown.space.prevent="selectPackage(pkg.id)"
        >
          <span
            v-if="pkg.recommended"
            class="absolute right-4 top-4 rounded-full bg-primary px-2.5 py-0.5 text-xs font-semibold text-primary-foreground"
          >
            推荐
          </span>
          <p class="text-3xl font-bold tabular-nums">
            {{ pkg.credits.toLocaleString() }}
            <span class="text-sm font-normal text-muted-foreground">算力</span>
          </p>
          <p class="mt-2 text-2xl font-bold text-primary tabular-nums">
            <span class="text-lg font-semibold">¥</span>{{ formatMoney(pkg.priceAmount) }}
          </p>
          <p class="mt-2 border-b border-border pb-4 text-xs text-muted-foreground">
            套餐权益周期 {{ pkg.validityDays }} 天
          </p>
          <ul class="mt-4 flex flex-1 flex-col gap-2">
            <li v-for="benefit in packageBenefits(pkg)" :key="benefit" class="flex items-center gap-2 text-sm text-muted-foreground">
              <Check class="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
              {{ benefit }}
            </li>
          </ul>
          <button
            type="button"
            class="mt-6 w-full rounded-md bg-primary py-2.5 text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-60"
            :disabled="ordering"
            @click.stop="openPaymentChoice(pkg)"
          >
            {{ ordering && orderingPackageId === pkg.id ? "下单中..." : "立即购买" }}
          </button>
        </div>

      <!-- 自定义充值卡片 - 金额自选 -->
        <div
          class="group relative flex min-h-[286px] flex-col rounded-xl border bg-card p-6 shadow-sm transition-all duration-200 hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md"
          :class="customAmount && parseFloat(customAmount) >= 0.01 ? 'border-primary ring-1 ring-primary/20' : 'border-border'"
        >
          <p class="text-3xl font-bold tabular-nums">
            {{ customCredits.toLocaleString() }}
            <span class="text-sm font-normal text-muted-foreground">算力</span>
          </p>
          <div class="mt-2">
            <div class="flex items-center gap-1 text-2xl font-bold text-primary tabular-nums">
              <span class="text-lg font-semibold">¥</span>
              <input
                v-model="customAmount"
                type="text"
                inputmode="decimal"
                placeholder="输入金额"
                class="w-full border-0 border-b border-border bg-transparent px-0 py-0.5 text-2xl font-bold tabular-nums text-primary outline-none placeholder:text-muted-foreground/40 focus:border-primary focus:ring-0"
                @click.stop
                @input="onCustomAmountInput"
              />
            </div>
          </div>
          <p class="mt-2 border-b border-border pb-4 text-xs text-muted-foreground">
            自定义金额，1 元 = 100 算力
          </p>
          <ul class="mt-4 flex flex-1 flex-col gap-2">
            <li class="flex items-center gap-2 text-sm text-muted-foreground">
              <Check class="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
              任意金额随心充
            </li>
            <li class="flex items-center gap-2 text-sm text-muted-foreground">
              <Check class="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
              最低 ¥0.01 起
            </li>
          </ul>
          <button
            type="button"
            class="mt-6 w-full rounded-md bg-primary py-2.5 text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-60"
            :disabled="ordering || !customAmount || parseFloat(customAmount) < 0.01"
            @click.stop="openCustomPaymentChoice"
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
              <h3 id="payment-channel-title" class="text-lg font-semibold text-foreground">选择支付方式</h3>
              <p class="mt-1 text-sm text-muted-foreground">
                <template v-if="isCustomRecharge">自定义充值 · {{ customCredits.toLocaleString() }} 算力</template>
              <template v-else>{{ pendingPackage?.packageName }} · {{ pendingPackage?.credits.toLocaleString() }} 算力</template>
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
              :disabled="ordering || (!isCustomRecharge && !pendingPackage)"
              @click="isCustomRecharge ? submitCustomRecharge(option.channel) : (pendingPackage && createOrder(pendingPackage, option.channel))"
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
            <span class="font-semibold text-primary">¥{{ isCustomRecharge ? customAmount || "0.00" : formatMoney(pendingPackage?.priceAmount) }}</span>
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
              v-if="activeOrder?.paymentChannel !== 'MOCK' && isAlipayPageRedirect"
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
              v-else-if="activeOrder?.paymentChannel !== 'MOCK'"
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

            <div v-else class="mx-auto mt-10 rounded-2xl border border-primary/20 bg-primary/5 p-5">
              <Sparkles class="mx-auto h-10 w-10 text-primary" aria-hidden="true" />
              <p class="mt-3 text-sm text-muted-foreground">本地模拟支付不会调用第三方平台</p>
              <button
                type="button"
                class="mt-5 w-full rounded-full bg-primary py-2.5 text-sm font-semibold text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-60"
                :disabled="ordering"
                @click="confirmMockPayment"
              >
                {{ ordering ? "确认中..." : "模拟支付成功" }}
              </button>
            </div>

            <p v-if="activeOrder?.paymentChannel !== 'MOCK' && !isAlipayPageRedirect" class="mt-7 flex items-center justify-center gap-1.5 text-sm text-muted-foreground">
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
            <p class="mt-5 text-sm font-medium text-foreground">支付成功，算力已到账</p>
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
