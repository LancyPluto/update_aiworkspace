<script setup lang="ts">
import { ref, computed, onUnmounted } from "vue"
import type { CreditAccount } from "@/api/types"
import { Crown, Sparkles, Check, Loader2, X } from "lucide-vue-next"

const props = defineProps<{
  account: CreditAccount | null
}>()

const emit = defineEmits<{
  /** 支付成功或需要刷新算力账户时由父级重新拉取 */
  creditsUpdated: []
}>()

/** 本地套餐展示（与预览页一致）；上线后改为接口数据 */
interface CreditPackageItem {
  id: number
  credits: number
  price: number
  validityDays: number
  benefits: string[]
  recommended?: boolean
}

// TODO[API]: GET /api/v1/credits/recharge-packages（或 openapi 中实际路径）
// 期望字段示例：{ id, credits, priceCny, validityDays, benefits[], recommended? }
const packages = ref<CreditPackageItem[]>([
  {
    id: 1,
    credits: 1000,
    price: 10,
    validityDays: 30,
    benefits: ["专属客服", "优先排队"],
  },
  {
    id: 2,
    credits: 5000,
    price: 45,
    validityDays: 90,
    benefits: ["专属客服", "优先排队", "API 加速"],
    recommended: true,
  },
  {
    id: 3,
    credits: 12000,
    price: 99,
    validityDays: 180,
    benefits: ["专属客服", "优先排队", "API 加速", "模型定制咨询"],
  },
])

// TODO[API]: GET /api/v1/membership/me（或 user/plan 等）— 当前会员等级、到期时间
const membership = ref<{ planName: string; expiryDate: string | null }>({
  planName: "免费版",
  expiryDate: null,
})

const selectedId = ref<number | null>(null)
function selectPackage(id: number) {
  selectedId.value = id
}

const showPayModal = ref(false)
const payingPackage = ref<CreditPackageItem | null>(null)
const paymentResult = ref<"success" | "fail" | null>(null)
let pollingTimer: ReturnType<typeof setInterval> | null = null

function clearPolling() {
  if (pollingTimer) {
    clearInterval(pollingTimer)
    pollingTimer = null
  }
}

function closeModal() {
  clearPolling()
  showPayModal.value = false
  payingPackage.value = null
  paymentResult.value = null
}

/** 模拟轮询；接支付后改为请求订单状态 */
function startPolling() {
  clearPolling()
  let attempts = 0
  pollingTimer = setInterval(() => {
    attempts++
    // TODO[API]: GET /api/v1/credits/orders/{orderId}/status — 直到 PAID / FAILED / CLOSED
    if (attempts >= 3) {
      clearPolling()
      const isSuccess = Math.random() > 0.3
      paymentResult.value = isSuccess ? "success" : "fail"
      if (isSuccess) emit("creditsUpdated")
    }
  }, 1000)
}

function handleBuy(pkgId: number) {
  const pkg = packages.value.find((p) => p.id === pkgId)
  if (!pkg) return
  // TODO[API]: POST /api/v1/credits/recharge-orders — body: { packageId }，返回 { orderId, payUrl?, qrCodeDataUrl? }
  payingPackage.value = pkg
  paymentResult.value = null
  showPayModal.value = true
  startPolling()
}

const availableDisplay = computed(() => {
  if (!props.account) return "—"
  return props.account.available.toLocaleString()
})

onUnmounted(() => {
  clearPolling()
})
</script>

<template>
  <section class="space-y-6">
    <!-- 概览：与侧栏「智擎 AI」风格统一的算力 + 会员 -->
    <div
      class="relative overflow-hidden rounded-xl border border-border bg-card p-6 shadow-sm md:p-8"
    >
      <div
        class="pointer-events-none absolute -right-16 -top-16 h-48 w-48 rounded-full bg-primary/5 blur-2xl"
        aria-hidden="true"
      />
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
        <div class="flex shrink-0 flex-col gap-2 sm:flex-row">
          <!-- TODO[API]: 跳转会员订购页或 POST 升级意向；此处仅占位 -->
          <button
            type="button"
            class="inline-flex items-center justify-center rounded-md border border-primary bg-transparent px-4 py-2 text-sm font-medium text-primary transition-colors hover:bg-primary/10"
          >
            升级会员
          </button>
        </div>
      </div>
    </div>

    <!-- 套餐 -->
    <div>
      <div class="mb-6 flex items-start gap-3">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"
        >
          <Sparkles class="h-5 w-5" aria-hidden="true" />
        </div>
        <div>
          <h2 class="text-lg font-semibold tracking-tight">选择算力套餐</h2>
          <p class="mt-1 text-sm text-muted-foreground">多买多惠 · 支付成功后算力将尽快到账</p>
        </div>
      </div>

      <div class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <div
          v-for="pkg in packages"
          :key="pkg.id"
          role="button"
          tabindex="0"
          class="group relative flex flex-col rounded-xl border bg-card p-6 shadow-sm transition-all duration-200 hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-primary/30"
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
            <span class="text-lg font-semibold">¥</span>{{ pkg.price }}
          </p>
          <p class="mt-2 border-b border-border pb-4 text-xs text-muted-foreground">
            套餐权益周期 {{ pkg.validityDays }} 天
          </p>
          <ul class="mt-4 flex flex-1 flex-col gap-2">
            <li
              v-for="b in pkg.benefits"
              :key="b"
              class="flex items-center gap-2 text-sm text-muted-foreground"
            >
              <Check class="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
              {{ b }}
            </li>
          </ul>
          <button
            type="button"
            class="mt-6 w-full rounded-md bg-primary py-2.5 text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90"
            @click.stop="handleBuy(pkg.id)"
          >
            立即购买
          </button>
        </div>
      </div>
    </div>

    <Teleport to="body">
      <div
        v-if="showPayModal"
        class="fixed inset-0 z-[100] flex items-center justify-center bg-black/50 p-4 backdrop-blur-[2px]"
        role="dialog"
        aria-modal="true"
        aria-labelledby="pay-modal-title"
        @click.self="closeModal"
      >
        <div
          class="w-full max-w-md rounded-2xl border border-border bg-card p-8 shadow-xl"
          @click.stop
        >
          <template v-if="!paymentResult">
            <h3 id="pay-modal-title" class="sr-only">扫码支付</h3>
            <p class="text-center text-sm text-muted-foreground">请使用微信 / 支付宝扫码支付</p>
            <!-- TODO[API]: 使用下单接口返回的二维码 URL 或 base64：<img :src="order.qrCodeUrl" alt="支付码" /> -->
            <div
              class="mx-auto mt-5 flex h-44 w-44 items-center justify-center rounded-xl border border-dashed border-border bg-muted/30 text-xs text-muted-foreground"
            >
              二维码占位
            </div>
            <p class="mt-4 text-center text-sm font-medium">
              支付金额：
              <span class="text-primary tabular-nums">¥{{ payingPackage?.price }}</span>
            </p>
            <div
              class="mt-4 flex items-center justify-center gap-2 rounded-full border border-border bg-secondary/50 px-4 py-2.5 text-xs text-muted-foreground"
            >
              <Loader2 class="h-4 w-4 animate-spin text-primary" aria-hidden="true" />
              <span>正在确认支付结果，请稍候…</span>
            </div>
            <button
              type="button"
              class="mt-6 w-full rounded-full border border-border py-2 text-sm text-muted-foreground transition-colors hover:bg-secondary"
              @click="closeModal"
            >
              取消支付
            </button>
          </template>

          <div v-else-if="paymentResult === 'success'" class="text-center">
            <div
              class="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-emerald-500/15 text-3xl text-emerald-600 dark:text-emerald-400"
            >
              ✓
            </div>
            <p class="mt-5 text-sm font-medium text-foreground">支付成功，订单处理中</p>
            <button
              type="button"
              class="mt-6 rounded-full bg-primary px-6 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
              @click="closeModal"
            >
              关闭
            </button>
          </div>

          <div v-else class="text-center">
            <div
              class="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-destructive/15 text-destructive"
            >
              <X class="h-8 w-8" stroke-width="2.5" aria-hidden="true" />
            </div>
            <p class="mt-5 text-sm font-medium text-foreground">支付失败，请稍后重试</p>
            <button
              type="button"
              class="mt-6 rounded-full bg-primary px-6 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
              @click="closeModal"
            >
              关闭
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>
