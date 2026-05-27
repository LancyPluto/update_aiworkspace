<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue"
import { Check, Crown, Loader2, QrCode, Sparkles, X } from "lucide-vue-next"
import type { CreditAccount, RechargeOrder, RechargePackage } from "@/api/types"
import { createRechargeOrder, fetchRechargeOrder, fetchRechargePackages, mockPayRechargeOrder } from "@/api/creditApi"
import { useAuthStore } from "@/store/authStore"

const props = defineProps<{
  account: CreditAccount | null
}>()

const emit = defineEmits<{
  creditsUpdated: []
}>()

const auth = useAuthStore()
const packages = ref<RechargePackage[]>([])
const selectedId = ref<number | null>(null)
const loadingPackages = ref(false)
const ordering = ref(false)
const error = ref("")
const showPayModal = ref(false)
const activeOrder = ref<RechargeOrder | null>(null)
const paymentResult = ref<"success" | "fail" | null>(null)
let pollingTimer: ReturnType<typeof setInterval> | null = null

const membership = ref<{ planName: string; expiryDate: string | null }>({
  planName: "免费版",
  expiryDate: null,
})

const selectedPackage = computed(() => packages.value.find((item) => item.id === selectedId.value) ?? null)
const availableDisplay = computed(() => (props.account ? props.account.available.toLocaleString() : "--"))

function selectPackage(id: number) {
  selectedId.value = id
}

function clearPolling() {
  if (pollingTimer) {
    clearInterval(pollingTimer)
    pollingTimer = null
  }
}

function closeModal() {
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

async function handleBuy(pkgId: number) {
  ordering.value = true
  error.value = ""
  try {
    const order = await createRechargeOrder(
      {
        packageId: pkgId,
        paymentChannel: "WECHAT_NATIVE",
        clientRequestId: `recharge-${pkgId}-${Date.now()}`,
      },
      { token: auth.token },
    )
    activeOrder.value = order
    paymentResult.value = null
    showPayModal.value = true
    startPolling(order.id)
  } catch (err) {
    error.value = err instanceof Error ? err.message : "创建充值订单失败"
  } finally {
    ordering.value = false
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
            <span class="text-lg font-semibold">¥</span>{{ pkg.priceAmount }}
          </p>
          <p class="mt-2 border-b border-border pb-4 text-xs text-muted-foreground">
            套餐权益周期 {{ pkg.validityDays }} 天
          </p>
          <ul class="mt-4 flex flex-1 flex-col gap-2">
            <li v-for="benefit in pkg.benefits" :key="benefit" class="flex items-center gap-2 text-sm text-muted-foreground">
              <Check class="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
              {{ benefit }}
            </li>
          </ul>
          <button
            type="button"
            class="mt-6 w-full rounded-md bg-primary py-2.5 text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-60"
            :disabled="ordering"
            @click.stop="handleBuy(pkg.id)"
          >
            {{ ordering && selectedPackage?.id === pkg.id ? "下单中..." : "立即购买" }}
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
        <div class="w-full max-w-md rounded-2xl border border-border bg-card p-8 shadow-xl" @click.stop>
          <template v-if="!paymentResult">
            <h3 id="pay-modal-title" class="text-center text-base font-semibold">扫码支付</h3>
            <p class="mt-2 text-center text-sm text-muted-foreground">订单 {{ activeOrder?.orderNo }}</p>
            <div class="mx-auto mt-5 flex h-44 w-44 items-center justify-center rounded-xl border border-border bg-white p-3 text-muted-foreground">
              <img
                v-if="activeOrder?.qrCodeUrl"
                :src="activeOrder.qrCodeUrl"
                alt="微信支付二维码"
                class="h-full w-full"
              />
              <QrCode v-else class="h-16 w-16" aria-hidden="true" />
            </div>
            <p class="mt-4 text-center text-sm font-medium">
              支付金额：
              <span class="text-primary tabular-nums">¥{{ activeOrder?.priceAmount }}</span>
            </p>
            <div class="mt-4 flex items-center justify-center gap-2 rounded-full border border-border bg-secondary/50 px-4 py-2.5 text-xs text-muted-foreground">
              <Loader2 class="h-4 w-4 animate-spin text-primary" aria-hidden="true" />
              <span>正在确认订单状态</span>
            </div>
            <button
              v-if="activeOrder?.paymentChannel === 'MOCK'"
              type="button"
              class="mt-5 w-full rounded-full bg-primary py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-60"
              :disabled="ordering"
              @click="confirmMockPayment"
            >
              {{ ordering ? "确认中..." : "模拟支付成功" }}
            </button>
            <button
              type="button"
              class="mt-3 w-full rounded-full border border-border py-2 text-sm text-muted-foreground transition-colors hover:bg-secondary"
              @click="closeModal"
            >
              稍后支付
            </button>
          </template>

          <div v-else-if="paymentResult === 'success'" class="text-center">
            <div class="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-emerald-500/15 text-emerald-600 dark:text-emerald-400">
              <Check class="h-8 w-8" stroke-width="2.5" aria-hidden="true" />
            </div>
            <p class="mt-5 text-sm font-medium text-foreground">支付成功，算力已到账</p>
            <button type="button" class="mt-6 rounded-full bg-primary px-6 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90" @click="closeModal">
              关闭
            </button>
          </div>

          <div v-else class="text-center">
            <div class="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-destructive/15 text-destructive">
              <X class="h-8 w-8" stroke-width="2.5" aria-hidden="true" />
            </div>
            <p class="mt-5 text-sm font-medium text-foreground">支付未完成，请稍后重试</p>
            <button type="button" class="mt-6 rounded-full bg-primary px-6 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90" @click="closeModal">
              关闭
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>
