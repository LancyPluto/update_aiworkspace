<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue"
import { Check, CreditCard, Loader2, MessageCircle, QrCode, Sparkles, X } from "lucide-vue-next"
import type { RechargeOrder, RechargePackage } from "@/api/types"
import { createRechargeOrder, fetchRechargeOrder, fetchRechargePackages } from "@/api/creditApi"
import { useAuthStore } from "@/store/authStore"
import {
  DEFAULT_RECHARGE_PAYMENT_CHANNELS,
  isAlipayPageRedirectOrder,
  rechargePaymentFailureMessage,
  resolveAlipayLaunchUrl,
  type RechargePaymentChannel,
} from "@/utils/rechargePayment"

type PaymentChannel = RechargePaymentChannel

const emit = defineEmits<{
  close: []
  creditsUpdated: []
}>()

const auth = useAuthStore()
const packages = ref<RechargePackage[]>([])
const selectedId = ref<number | null>(null)
const loadingPackages = ref(false)
const loadError = ref("")
const ordering = ref(false)
const showChannelModal = ref(false)
const showPayModal = ref(false)
const pendingPackage = ref<RechargePackage | null>(null)
const activeOrder = ref<RechargeOrder | null>(null)
const paymentResult = ref<"success" | "fail" | null>(null)
let pollingTimer: ReturnType<typeof setInterval> | null = null

const selectedPackage = computed(() => packages.value.find((item) => item.id === selectedId.value) ?? null)
const displayedPackages = computed<RechargePackage[]>(() => pickDisplayPackages(packages.value))

const paymentOptions = computed(() =>
  DEFAULT_RECHARGE_PAYMENT_CHANNELS.map((option) => ({
    ...option,
    icon: option.channel === "WECHAT_NATIVE" ? MessageCircle : CreditCard,
  })),
)

function formatMoney(value: number | string | undefined | null) {
  const amount = Number(value ?? 0)
  return Number.isInteger(amount) ? String(amount) : amount.toFixed(2)
}

function normalizePackageName(name: string) {
  const raw = (name || "").trim()
  if (!raw) return "充值套餐"
  const lower = raw.toLowerCase()
  if (lower.includes("starter")) return "入门套餐"
  if (lower.includes("test")) return "体验套餐"
  if (lower.includes("growth")) return "成长套餐"
  if (lower.includes("pro")) return "专业套餐"
  if (lower.includes("ultra")) return "旗舰套餐"
  return raw
}

function translateBenefit(text: string) {
  const raw = (text || "").trim()
  if (!raw) return raw
  const lower = raw.toLowerCase()
  if (lower.includes("priority queue") || lower.includes("priority")) return "优先队列"
  if (lower.includes("api acceleration") || lower.includes("acceleration")) return "API 加速"
  if (lower.includes("commercial") || lower.includes("business")) return "商用授权"
  if (lower.includes("support")) return "专属支持"
  return raw
}

function packageBenefits(pkg: RechargePackage) {
  const benefits = pkg.benefits?.filter(Boolean) ?? []
  if (benefits.length > 0) {
    return benefits.map((item) =>
      translateBenefit(
        item
          .replace(/valid\s+for\s+(\d+)\s+days?/gi, "有效期 $1 天")
          .replace(/(\d+)\s+days?/gi, "$1 天")
          .replace(/\bcredits?\b/gi, "算力")
          .replace(/\bbonus\b/gi, "赠送"),
      ),
    )
  }
  return [`到账 ${pkg.credits.toLocaleString()} 点算力`, `有效期 ${pkg.validityDays} 天`]
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
}

function closePayModal() {
  clearPolling()
  showPayModal.value = false
  activeOrder.value = null
  paymentResult.value = null
}

function closeAll() {
  closePayModal()
  closeChannelModal()
  emit("close")
}

function pickDisplayPackages(list: RechargePackage[]) {
  const source = (list || []).filter(Boolean)
  if (source.length <= 3) return source
  const recommended = source.find((item) => item.recommended) ?? null
  const remaining = source.filter((item) => item.id !== recommended?.id)
  const byPrice = [...remaining].sort((a, b) => Number(a.priceAmount ?? 0) - Number(b.priceAmount ?? 0))
  const cheapest = byPrice[0] ?? null
  const mostExpensive = byPrice.length ? byPrice[byPrice.length - 1] : null
  const middle = byPrice.length >= 3 ? byPrice[Math.floor(byPrice.length / 2)] : (byPrice[1] ?? null)
  const picked = [recommended, cheapest, middle, mostExpensive].filter(Boolean) as RechargePackage[]
  const uniq: RechargePackage[] = []
  const seen = new Set<number>()
  for (const item of picked) {
    if (seen.has(item.id)) continue
    seen.add(item.id)
    uniq.push(item)
    if (uniq.length >= 3) break
  }
  return uniq.length === 3 ? uniq : source.slice(0, 3)
}

async function loadPackages() {
  loadingPackages.value = true
  loadError.value = ""
  try {
    const list = await fetchRechargePackages({ token: auth.token })
    packages.value = list
    const displayList = pickDisplayPackages(list)
    selectedId.value = displayList.find((item) => item.recommended)?.id ?? displayList[0]?.id ?? null
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : "加载充值套餐失败"
  } finally {
    loadingPackages.value = false
  }
}

async function pollOrder(orderId: number) {
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
}

function startPolling(orderId: number) {
  clearPolling()
  pollingTimer = setInterval(() => {
    void pollOrder(orderId).catch(() => clearPolling())
  }, 1500)
}

function openPaymentChoice(pkg: RechargePackage) {
  pendingPackage.value = pkg
  selectedId.value = pkg.id
  paymentResult.value = null
  showChannelModal.value = true
}

async function createOrder(channel: PaymentChannel) {
  const pkg = pendingPackage.value
  if (!pkg) return
  ordering.value = true
  try {
    const order = await createRechargeOrder(
      {
        packageId: pkg.id,
        paymentChannel: channel,
        clientRequestId: `agent-recharge-${pkg.id}-${channel}-${Date.now()}`,
      },
      { token: auth.token },
    )
    const hasAlipayLaunch = isAlipayPageRedirectOrder(order, channel)
    if (!order.qrCodeUrl && !hasAlipayLaunch) {
      loadError.value = rechargePaymentFailureMessage(order, channel)
      return
    }
    activeOrder.value = order
    showChannelModal.value = false
    paymentResult.value = null
    startPolling(order.id)
    if (hasAlipayLaunch && order.payUrl) {
      window.location.assign(resolveAlipayLaunchUrl(order.payUrl))
      return
    }
    showPayModal.value = true
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : "创建充值订单失败"
  } finally {
    ordering.value = false
  }
}

onMounted(() => {
  void loadPackages()
})

onUnmounted(clearPolling)
</script>

<template>
  <Teleport to="body">
    <div class="credit-modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="credit-modal-title" @click.self="closeAll">
      <div class="credit-modal-panel" @click.stop>
        <button type="button" class="credit-modal-close" aria-label="关闭" @click="closeAll">
          <X class="h-5 w-5" />
        </button>

        <header class="credit-modal-header">
          <h2 id="credit-modal-title">余额不足</h2>
          <p>当前可用算力不足以完成本次 Agent 工具调用，请选择套餐充值后继续。</p>
        </header>

        <p v-if="loadError" class="credit-modal-error">{{ loadError }}</p>

        <div v-if="loadingPackages" class="credit-modal-loading">
          <Loader2 class="h-6 w-6 animate-spin" />
          <span>正在加载套餐…</span>
        </div>

        <div v-else class="credit-package-list">
          <button
            v-for="pkg in displayedPackages"
            :key="pkg.id"
            type="button"
            class="credit-package-card"
            :class="{ active: selectedId === pkg.id, recommended: pkg.recommended }"
            @click="selectedId = pkg.id"
          >
            <span v-if="pkg.recommended" class="credit-package-badge">推荐</span>
            <p class="credit-package-name">{{ normalizePackageName(pkg.packageName) }}</p>
            <p class="credit-package-credits">
              {{ pkg.credits.toLocaleString() }}
              <span>算力</span>
            </p>
            <p class="credit-package-price">
              <span class="currency">¥</span>{{ formatMoney(pkg.priceAmount) }}
            </p>
            <ul class="credit-package-benefits">
              <li v-for="benefit in packageBenefits(pkg).slice(0, 2)" :key="benefit">
                <Check class="h-3.5 w-3.5 shrink-0" />
                {{ benefit }}
              </li>
            </ul>
          </button>
        </div>

        <button
          type="button"
          class="credit-modal-buy"
          :disabled="!selectedPackage || ordering || loadingPackages"
          @click="selectedPackage && openPaymentChoice(selectedPackage)"
        >
          {{ ordering ? "处理中…" : "立即购买" }}
        </button>
      </div>
    </div>

    <div
      v-if="showChannelModal"
      class="credit-modal-backdrop credit-modal-backdrop--nested"
      role="dialog"
      aria-modal="true"
      @click.self="closeChannelModal"
    >
      <div class="credit-modal-panel credit-modal-panel--sm" @click.stop>
        <h3 class="text-lg font-semibold text-white">选择支付方式</h3>
        <p class="mt-1 text-sm text-white/55">
          {{ pendingPackage?.packageName }} · {{ pendingPackage?.credits.toLocaleString() }} 算力
        </p>
        <div class="mt-4 grid gap-2">
          <button
            v-for="option in paymentOptions"
            :key="option.channel"
            type="button"
            class="credit-pay-option"
            :disabled="ordering || !pendingPackage"
            @click="createOrder(option.channel)"
          >
            <component :is="option.icon" class="h-5 w-5 shrink-0" />
            <span class="min-w-0 flex-1 text-left">
              <strong>{{ option.title }}</strong>
              <small>{{ option.description }}</small>
            </span>
            <Loader2 v-if="ordering" class="h-4 w-4 animate-spin" />
          </button>
        </div>
      </div>
    </div>

    <div
      v-if="showPayModal"
      class="credit-modal-backdrop credit-modal-backdrop--nested"
      role="dialog"
      aria-modal="true"
      @click.self="closePayModal"
    >
      <div class="credit-modal-panel credit-modal-panel--sm credit-modal-panel--pay" @click.stop>
        <button type="button" class="credit-modal-close" aria-label="关闭" @click="closePayModal">
          <X class="h-5 w-5" />
        </button>
        <template v-if="!paymentResult">
          <h3 class="text-xl font-semibold text-white">扫码支付 ¥{{ formatMoney(activeOrder?.priceAmount) }}</h3>
          <div class="credit-qr-wrap">
            <img
              v-if="activeOrder?.qrCodeUrl"
              :src="activeOrder.qrCodeUrl"
              alt="支付二维码"
              class="h-full w-full object-contain"
            />
            <QrCode v-else class="h-20 w-20 text-slate-900" />
          </div>
          <p class="mt-4 flex items-center justify-center gap-2 text-sm text-white/60">
            <Loader2 class="h-4 w-4 animate-spin" />
            正在确认支付结果…
          </p>
        </template>
        <div v-else-if="paymentResult === 'success'" class="credit-pay-result">
          <Check class="h-10 w-10 text-emerald-400" />
          <p>支付成功，算力已到账</p>
          <button type="button" class="credit-modal-buy" @click="closeAll">继续 Agent 会话</button>
        </div>
        <div v-else class="credit-pay-result">
          <X class="h-10 w-10 text-red-400" />
          <p>支付未完成，请稍后重试</p>
          <button type="button" class="credit-modal-buy" @click="closePayModal">返回</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.credit-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 120;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background: rgb(0 0 0 / 0.72);
  backdrop-filter: blur(6px);
}

.credit-modal-backdrop--nested {
  z-index: 130;
}

.credit-modal-panel {
  position: relative;
  width: min(720px, 100%);
  max-height: min(90vh, 640px);
  overflow-y: auto;
  padding: 24px 22px 20px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 16px;
  background: linear-gradient(165deg, rgb(32 32 38 / 0.98), rgb(22 22 26 / 0.98));
  box-shadow: 0 24px 80px rgb(0 0 0 / 0.55);
}

.credit-modal-panel--sm {
  width: min(400px, 100%);
  max-height: none;
}

.credit-modal-panel--pay {
  text-align: center;
}

.credit-modal-close {
  position: absolute;
  top: 14px;
  right: 14px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.06);
  color: rgb(255 255 255 / 0.7);
  padding: 6px;
  cursor: pointer;
}

.credit-modal-header h2 {
  color: #fff;
  font-size: 22px;
  font-weight: 700;
}

.credit-modal-header p {
  margin-top: 6px;
  color: rgb(255 255 255 / 0.55);
  font-size: 13px;
  line-height: 1.5;
}

.credit-modal-error {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgb(239 68 68 / 0.12);
  color: rgb(252 165 165);
  font-size: 13px;
}

.credit-modal-loading {
  margin: 28px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  color: rgb(255 255 255 / 0.55);
  font-size: 13px;
}

.credit-package-list {
  margin-top: 18px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.credit-package-card {
  position: relative;
  width: 100%;
  min-height: 200px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 16px 14px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 12px;
  background: rgb(255 255 255 / 0.03);
  color: rgb(255 255 255 / 0.88);
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.credit-package-card.recommended {
  background: linear-gradient(160deg, rgb(176 146 255 / 0.12), rgb(255 255 255 / 0.03));
}

@media (max-width: 720px) {
  .credit-package-list {
    grid-template-columns: 1fr;
  }
}

.credit-package-card:hover {
  border-color: rgb(255 255 255 / 0.18);
}

.credit-package-card.active {
  border-color: var(--agent-accent, #b092ff);
  background: rgb(176 146 255 / 0.08);
  box-shadow: 0 0 0 1px var(--agent-accent-soft, rgb(176 146 255 / 0.35));
}

.credit-package-badge {
  position: absolute;
  top: 10px;
  right: 10px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--agent-accent, #b092ff);
  color: #111;
  font-size: 10px;
  font-weight: 700;
}

.credit-package-name {
  font-size: 14px;
  font-weight: 600;
  color: rgb(255 255 255 / 0.72);
}

.credit-package-credits {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.1;
}

.credit-package-credits span {
  font-size: 12px;
  font-weight: 500;
  color: rgb(255 255 255 / 0.45);
}

.credit-package-price {
  font-size: 22px;
  font-weight: 700;
  color: var(--agent-accent, #c4a8ff);
}

.credit-package-price .currency {
  font-size: 14px;
}

.credit-package-benefits {
  margin-top: auto;
  padding-top: 8px;
  display: grid;
  gap: 4px;
  list-style: none;
}

.credit-package-benefits li {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: rgb(255 255 255 / 0.45);
}

.credit-modal-buy {
  margin-top: 16px;
  width: 100%;
  min-height: 44px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, var(--agent-accent, #b092ff), #8b5cf6);
  color: #111;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
}

.credit-modal-buy:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.credit-pay-option {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 10px;
  background: rgb(255 255 255 / 0.04);
  color: #fff;
  cursor: pointer;
}

.credit-pay-option strong {
  display: block;
  font-size: 13px;
}

.credit-pay-option small {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: rgb(255 255 255 / 0.45);
}

.credit-qr-wrap {
  margin: 20px auto 0;
  width: 160px;
  height: 160px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px;
  background: #fff;
  border-radius: 12px;
}

.credit-pay-result {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  color: rgb(255 255 255 / 0.8);
  font-size: 14px;
}
</style>
