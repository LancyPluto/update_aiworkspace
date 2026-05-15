<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { Boxes, CheckCircle2, Clock3, CreditCard, ReceiptText, RotateCw, Wallet } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import { fetchCreditAccount, fetchCreditLogs } from "@/api/creditApi"
import {
  createPaymentOrder,
  fetchModelPlans,
  fetchModelSubscriptions,
  fetchPaymentOrders,
  mockPayOrder,
} from "@/api/modelWorkbenchApi"
import type { CreditAccount, CreditLog, PaymentOrder, SubscriptionPlan, UserSubscription } from "@/api/types"
import { useAuthStore } from "@/store/authStore"

const auth = useAuthStore()
const loading = ref(false)
const buyingPlanId = ref<number | null>(null)
const error = ref("")
const success = ref("")
const account = ref<CreditAccount | null>(null)
const logs = ref<CreditLog[]>([])
const plans = ref<SubscriptionPlan[]>([])
const subscriptions = ref<UserSubscription[]>([])
const orders = ref<PaymentOrder[]>([])
const selectedOrderId = ref<number | null>(null)

const activeSubscriptions = computed(() =>
  subscriptions.value.filter((subscription) => subscriptionStatusKind(subscription) === "active"),
)

const expiredSubscriptions = computed(() =>
  subscriptions.value.filter((subscription) => subscriptionStatusKind(subscription) === "expired"),
)

const selectedOrder = computed(() => orders.value.find((order) => order.id === selectedOrderId.value) || null)

const latestOrder = computed(() => orders.value[0] || null)

function planName(plan: SubscriptionPlan | UserSubscription) {
  return plan.planName || plan.plan_name || plan.planCode || plan.plan_code || `套餐 #${plan.id}`
}

function planPrice(plan: SubscriptionPlan) {
  return Number(plan.priceCents ?? plan.price_cents ?? 0)
}

function planDuration(plan: SubscriptionPlan) {
  return Number(plan.durationDays ?? plan.duration_days ?? 0)
}

function planIdOf(subscription: UserSubscription) {
  return subscription.planId ?? subscription.plan_id
}

function orderProductId(order: PaymentOrder) {
  return order.productId ?? order.product_id
}

function orderProductType(order: PaymentOrder) {
  const type = order.productType || order.product_type || "-"
  if (type === "MODEL_CHANNEL") return "模型通道套餐"
  if (type === "COMPUTE_CREDIT") return "算力包"
  if (type === "PLAN") return "套餐"
  return type
}

function planById(planId?: number | null) {
  if (!planId) return null
  return plans.value.find((plan) => plan.id === planId) || null
}

function orderPlan(order: PaymentOrder) {
  return planById(orderProductId(order))
}

function subscriptionPlan(subscription: UserSubscription) {
  return planById(planIdOf(subscription))
}

function entitlementPools(item: SubscriptionPlan | UserSubscription) {
  return item.entitledPools || item.entitled_pools || "全部可用模型池"
}

function quotaText(item: SubscriptionPlan | UserSubscription) {
  const hourly = item.hourlyLimit ?? item.hourly_limit
  const daily = item.dailyLimit ?? item.daily_limit
  const parts = [
    hourly == null ? "每小时不限次" : `每小时 ${hourly} 次`,
    daily == null ? "每日不限次" : `每日 ${daily} 次`,
  ]
  return parts.join(" / ")
}

function orderNo(order: PaymentOrder) {
  return order.orderNo || order.order_no || `#${order.id}`
}

function orderAmount(order: PaymentOrder) {
  return Number(order.amountCents ?? order.amount_cents ?? 0)
}

function formatMoney(cents: number) {
  return `¥${(cents / 100).toFixed(2)}`
}

function formatDate(value?: string | null) {
  if (!value) return "-"
  return new Date(value).toLocaleString("zh-CN")
}

function daysUntil(value?: string | null) {
  if (!value) return null
  const diff = new Date(value).getTime() - Date.now()
  return Math.ceil(diff / 86_400_000)
}

function subscriptionStatusKind(subscription: UserSubscription) {
  const expiresAt = subscription.expiresAt || subscription.expires_at
  if (subscription.status !== "ACTIVE") return "inactive"
  if (expiresAt && new Date(expiresAt).getTime() <= Date.now()) return "expired"
  return "active"
}

function subscriptionHint(subscription: UserSubscription) {
  const expiresAt = subscription.expiresAt || subscription.expires_at
  const days = daysUntil(expiresAt)
  if (subscriptionStatusKind(subscription) === "expired") return "套餐已到期，续费后可恢复模型通道访问。"
  if (days !== null && days <= 3) return `套餐将在 ${Math.max(days, 0)} 天内到期，建议提前续费避免中断。`
  return "权益正常，可在模型工作台使用已授权模型池。"
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    ACTIVE: "生效中",
    CREATED: "待支付",
    PAID: "已支付",
    CLOSED: "已关闭",
    EXPIRED: "已过期",
    FULFILLED: "已交付",
  }
  return labels[status] || status
}

function statusClass(status: string) {
  if (status === "PAID" || status === "ACTIVE" || status === "FULFILLED") return "bg-emerald-100 text-emerald-700"
  if (status === "CREATED") return "bg-amber-100 text-amber-700"
  return "bg-slate-100 text-slate-700"
}

function orderStatusHint(order: PaymentOrder) {
  if (order.status === "CREATED") return "订单已创建，完成支付后系统会自动开通权益。"
  if (order.status === "PAID") return "订单已支付，套餐权益已发放，可在当前套餐中查看。"
  if (order.status === "EXPIRED") return "订单已过期，可重新购买同款套餐。"
  if (order.status === "CLOSED") return "订单已关闭，如仍需使用请再次购买。"
  return "订单状态已同步。"
}

async function loadBilling() {
  loading.value = true
  error.value = ""
  try {
    const [accountRes, logRes, planRes, subscriptionRes, orderRes] = await Promise.all([
      fetchCreditAccount({ token: auth.token }),
      fetchCreditLogs({ token: auth.token, query: { pageNo: 1, pageSize: 20 } }),
      fetchModelPlans({ token: auth.token }),
      fetchModelSubscriptions({ token: auth.token }),
      fetchPaymentOrders({ token: auth.token }),
    ])
    account.value = accountRes
    logs.value = logRes.list
    plans.value = planRes
    subscriptions.value = subscriptionRes
    orders.value = orderRes
    if (!selectedOrderId.value && orderRes.length > 0) selectedOrderId.value = orderRes[0].id
  } catch (err) {
    error.value = err instanceof Error ? err.message : "加载订单和套餐数据失败"
  } finally {
    loading.value = false
  }
}

async function buyPlan(plan: SubscriptionPlan, action = "购买") {
  buyingPlanId.value = plan.id
  error.value = ""
  success.value = ""
  try {
    const order = await createPaymentOrder(
      { productType: "MODEL_CHANNEL", productId: plan.id, channel: "MOCK" },
      { token: auth.token },
    )
    const paidOrder = await mockPayOrder(order.id, { token: auth.token })
    selectedOrderId.value = paidOrder.id
    success.value = `${planName(plan)} ${action}成功，订单 ${orderNo(paidOrder)} 已支付，权益已生效。`
    await loadBilling()
  } catch (err) {
    error.value = err instanceof Error ? err.message : "套餐购买失败，请稍后重试。"
  } finally {
    buyingPlanId.value = null
  }
}

async function renewSubscription(subscription: UserSubscription) {
  const plan = subscriptionPlan(subscription)
  if (!plan) {
    error.value = "未找到原套餐，无法续费。请从可购买套餐中重新选择。"
    return
  }
  await buyPlan(plan, "续费")
}

async function repurchaseOrder(order: PaymentOrder) {
  const plan = orderPlan(order)
  if (!plan) {
    error.value = "未找到订单对应的套餐，无法再次购买。"
    return
  }
  await buyPlan(plan, "再次购买")
}

onMounted(loadBilling)
</script>

<template>
  <AppShell title="订单与套餐" description="查看当前套餐权益、订单记录，并购买或续费模型通道套餐">
    <div class="min-h-[calc(100vh-4rem)] bg-gradient-to-br from-slate-50 via-sky-50/60 to-white px-4 py-6 sm:px-6">
      <div class="mx-auto max-w-7xl space-y-6">
        <div v-if="error" class="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {{ error }}
        </div>
        <div v-if="success" class="rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">
          {{ success }}
        </div>

        <section class="grid gap-4 md:grid-cols-4">
          <article class="rounded-3xl border border-white/70 bg-white/80 p-5 shadow-sm backdrop-blur">
            <div class="flex items-center gap-2 text-sm text-slate-500">
              <Wallet class="h-4 w-4 text-sky-600" /> 当前余额
            </div>
            <p class="mt-3 text-3xl font-semibold text-slate-950">{{ account?.balance ?? "--" }}</p>
          </article>
          <article class="rounded-3xl border border-white/70 bg-white/80 p-5 shadow-sm backdrop-blur">
            <p class="text-sm text-slate-500">可用算力</p>
            <p class="mt-3 text-3xl font-semibold text-slate-950">{{ account?.available ?? "--" }}</p>
          </article>
          <article class="rounded-3xl border border-white/70 bg-white/80 p-5 shadow-sm backdrop-blur">
            <p class="text-sm text-slate-500">生效套餐</p>
            <p class="mt-3 text-3xl font-semibold text-slate-950">{{ activeSubscriptions.length }}</p>
          </article>
          <article class="rounded-3xl border border-white/70 bg-white/80 p-5 shadow-sm backdrop-blur">
            <p class="text-sm text-slate-500">最近订单</p>
            <p class="mt-3 truncate text-lg font-semibold text-slate-950">{{ latestOrder ? statusLabel(latestOrder.status) : "暂无" }}</p>
          </article>
        </section>

        <section v-if="expiredSubscriptions.length > 0" class="rounded-3xl border border-amber-200 bg-amber-50 p-5 text-sm text-amber-800">
          有 {{ expiredSubscriptions.length }} 个套餐已到期。你可以在“当前套餐与权益”中续费，或在右侧重新购买新套餐。
        </section>

        <section class="grid gap-6 xl:grid-cols-[1.05fr_1fr]">
          <article class="rounded-3xl border border-white/70 bg-white p-6 shadow-sm">
            <div class="flex items-center justify-between gap-3">
              <div>
                <p class="text-sm font-medium text-sky-700">Current Benefits</p>
                <h2 class="mt-1 text-xl font-semibold text-slate-950">当前套餐与权益</h2>
              </div>
              <CheckCircle2 class="h-6 w-6 text-emerald-500" />
            </div>
            <div v-if="loading" class="py-10 text-center text-sm text-slate-500">加载中...</div>
            <div v-else-if="subscriptions.length === 0" class="mt-5 rounded-2xl border border-dashed border-slate-200 bg-slate-50 p-6 text-sm text-slate-500">
              暂无模型通道套餐。可在右侧选择套餐，Mock Pay 后立即开通。
            </div>
            <div v-else class="mt-5 space-y-4">
              <article
                v-for="subscription in subscriptions"
                :key="subscription.id"
                class="rounded-2xl border p-5"
                :class="subscriptionStatusKind(subscription) === 'active' ? 'border-slate-900 bg-gradient-to-br from-slate-950 to-slate-800 text-white' : 'border-amber-200 bg-amber-50 text-amber-950'"
              >
                <div class="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <h3 class="text-lg font-semibold">{{ planName(subscription) }}</h3>
                    <p class="mt-1 text-sm opacity-80">{{ subscription.description || "模型通道访问权益" }}</p>
                    <p class="mt-2 text-xs opacity-80">{{ subscriptionHint(subscription) }}</p>
                  </div>
                  <span class="rounded-full px-3 py-1 text-xs font-semibold" :class="subscriptionStatusKind(subscription) === 'active' ? 'bg-emerald-400/15 text-emerald-200' : 'bg-amber-100 text-amber-700'">
                    {{ subscriptionStatusKind(subscription) === "expired" ? "已到期" : statusLabel(subscription.status) }}
                  </span>
                </div>
                <div class="mt-5 grid gap-3 text-sm sm:grid-cols-3">
                  <div class="rounded-xl p-3" :class="subscriptionStatusKind(subscription) === 'active' ? 'bg-white/10' : 'bg-white/70'">
                    <p class="opacity-70">可用模型池</p>
                    <p class="mt-1 font-medium">{{ entitlementPools(subscription) }}</p>
                  </div>
                  <div class="rounded-xl p-3" :class="subscriptionStatusKind(subscription) === 'active' ? 'bg-white/10' : 'bg-white/70'">
                    <p class="opacity-70">调用额度</p>
                    <p class="mt-1 font-medium">{{ quotaText(subscription) }}</p>
                  </div>
                  <div class="rounded-xl p-3" :class="subscriptionStatusKind(subscription) === 'active' ? 'bg-white/10' : 'bg-white/70'">
                    <p class="opacity-70">有效期至</p>
                    <p class="mt-1 font-medium">{{ formatDate(subscription.expiresAt || subscription.expires_at) }}</p>
                  </div>
                </div>
                <button
                  type="button"
                  class="mt-5 inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-semibold transition disabled:cursor-not-allowed disabled:opacity-60"
                  :class="subscriptionStatusKind(subscription) === 'active' ? 'bg-white text-slate-950 hover:bg-sky-50' : 'bg-slate-950 text-white hover:bg-sky-700'"
                  :disabled="buyingPlanId !== null || !subscriptionPlan(subscription)"
                  @click="renewSubscription(subscription)"
                >
                  <RotateCw class="h-4 w-4" />
                  {{ buyingPlanId === planIdOf(subscription) ? "续费中..." : "续费同款套餐" }}
                </button>
              </article>
            </div>
          </article>

          <article class="rounded-3xl border border-white/70 bg-white p-6 shadow-sm">
            <div class="flex items-center justify-between gap-3">
              <div>
                <p class="text-sm font-medium text-sky-700">Upgrade</p>
                <h2 class="mt-1 text-xl font-semibold text-slate-950">可购买套餐</h2>
              </div>
              <Boxes class="h-6 w-6 text-sky-600" />
            </div>
            <div v-if="plans.length === 0" class="mt-5 rounded-2xl border border-dashed border-slate-200 bg-slate-50 p-6 text-sm text-slate-500">
              暂无可售套餐。
            </div>
            <div v-else class="mt-5 grid gap-4 sm:grid-cols-2">
              <article v-for="plan in plans" :key="plan.id" class="rounded-2xl border border-slate-200 p-5">
                <div class="flex items-start justify-between gap-3">
                  <div>
                    <h3 class="font-semibold text-slate-950">{{ planName(plan) }}</h3>
                    <p class="mt-1 text-sm text-slate-500">{{ plan.description || "开通模型通道访问权限" }}</p>
                  </div>
                  <span class="rounded-full bg-sky-100 px-2.5 py-1 text-xs font-semibold text-sky-700">
                    {{ planDuration(plan) }} 天
                  </span>
                </div>
                <p class="mt-4 text-3xl font-semibold text-slate-950">{{ formatMoney(planPrice(plan)) }}</p>
                <div class="mt-4 space-y-2 text-sm text-slate-600">
                  <p>模型池：{{ entitlementPools(plan) }}</p>
                  <p>额度：{{ quotaText(plan) }}</p>
                </div>
                <button
                  type="button"
                  class="mt-5 w-full rounded-xl bg-slate-950 px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-sky-700 disabled:cursor-not-allowed disabled:opacity-60"
                  :disabled="buyingPlanId !== null"
                  @click="buyPlan(plan)"
                >
                  {{ buyingPlanId === plan.id ? "开通中..." : "Mock Pay 购买" }}
                </button>
              </article>
            </div>
          </article>
        </section>

        <section class="grid gap-6 xl:grid-cols-[1.1fr_0.9fr]">
          <article class="overflow-hidden rounded-3xl border border-white/70 bg-white shadow-sm">
            <div class="flex items-center gap-2 border-b border-slate-100 px-6 py-4">
              <CreditCard class="h-4 w-4 text-sky-600" />
              <h2 class="text-sm font-semibold text-slate-950">订单记录</h2>
            </div>
            <div class="overflow-x-auto">
              <table class="w-full min-w-[860px] text-sm">
                <thead class="bg-slate-50 text-xs text-slate-500">
                  <tr>
                    <th class="px-4 py-3 text-left font-medium">订单号</th>
                    <th class="px-4 py-3 text-left font-medium">商品</th>
                    <th class="px-4 py-3 text-left font-medium">渠道</th>
                    <th class="px-4 py-3 text-right font-medium">金额</th>
                    <th class="px-4 py-3 text-left font-medium">状态</th>
                    <th class="px-4 py-3 text-left font-medium">创建时间</th>
                    <th class="px-4 py-3 text-right font-medium">操作</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-slate-100">
                  <tr v-for="order in orders" :key="order.id" :class="selectedOrderId === order.id ? 'bg-sky-50/60' : ''">
                    <td class="px-4 py-3 font-mono text-xs text-slate-600">{{ orderNo(order) }}</td>
                    <td class="px-4 py-3 text-slate-700">{{ orderPlan(order) ? planName(orderPlan(order)!) : orderProductType(order) }}</td>
                    <td class="px-4 py-3 text-slate-700">{{ order.channel }}</td>
                    <td class="px-4 py-3 text-right font-medium text-slate-950">{{ formatMoney(orderAmount(order)) }}</td>
                    <td class="px-4 py-3">
                      <span class="rounded-full px-2.5 py-1 text-xs font-semibold" :class="statusClass(order.status)">
                        {{ statusLabel(order.status) }}
                      </span>
                    </td>
                    <td class="px-4 py-3 text-slate-500">{{ formatDate(order.createdAt || order.created_at) }}</td>
                    <td class="px-4 py-3 text-right">
                      <button type="button" class="text-sm font-semibold text-sky-700 hover:text-sky-900" @click="selectedOrderId = order.id">详情</button>
                      <button type="button" class="ml-3 text-sm font-semibold text-slate-700 hover:text-slate-950 disabled:opacity-40" :disabled="buyingPlanId !== null || !orderPlan(order)" @click="repurchaseOrder(order)">再次购买</button>
                    </td>
                  </tr>
                </tbody>
              </table>
              <div v-if="orders.length === 0" class="px-5 py-8 text-center text-sm text-slate-500">暂无订单记录</div>
            </div>
          </article>

          <article class="rounded-3xl border border-white/70 bg-white p-6 shadow-sm">
            <div class="flex items-center gap-2">
              <ReceiptText class="h-4 w-4 text-sky-600" />
              <h2 class="text-sm font-semibold text-slate-950">订单详情 / 状态提示</h2>
            </div>
            <div v-if="!selectedOrder" class="mt-5 rounded-2xl border border-dashed border-slate-200 bg-slate-50 p-6 text-sm text-slate-500">
              选择一笔订单查看详情。
            </div>
            <div v-else class="mt-5 space-y-4 text-sm">
              <div class="rounded-2xl border border-slate-200 bg-slate-50 p-4">
                <div class="flex items-center justify-between gap-3">
                  <p class="font-mono text-xs text-slate-600">{{ orderNo(selectedOrder) }}</p>
                  <span class="rounded-full px-2.5 py-1 text-xs font-semibold" :class="statusClass(selectedOrder.status)">
                    {{ statusLabel(selectedOrder.status) }}
                  </span>
                </div>
                <p class="mt-3 text-slate-700">{{ orderStatusHint(selectedOrder) }}</p>
              </div>
              <dl class="grid gap-3 sm:grid-cols-2">
                <div class="rounded-xl border border-slate-100 p-3">
                  <dt class="text-xs text-slate-500">商品</dt>
                  <dd class="mt-1 font-medium text-slate-900">{{ orderPlan(selectedOrder) ? planName(orderPlan(selectedOrder)!) : orderProductType(selectedOrder) }}</dd>
                </div>
                <div class="rounded-xl border border-slate-100 p-3">
                  <dt class="text-xs text-slate-500">金额</dt>
                  <dd class="mt-1 font-medium text-slate-900">{{ formatMoney(orderAmount(selectedOrder)) }}</dd>
                </div>
                <div class="rounded-xl border border-slate-100 p-3">
                  <dt class="text-xs text-slate-500">创建时间</dt>
                  <dd class="mt-1 font-medium text-slate-900">{{ formatDate(selectedOrder.createdAt || selectedOrder.created_at) }}</dd>
                </div>
                <div class="rounded-xl border border-slate-100 p-3">
                  <dt class="text-xs text-slate-500">支付时间</dt>
                  <dd class="mt-1 font-medium text-slate-900">{{ formatDate(selectedOrder.paidAt || selectedOrder.paid_at) }}</dd>
                </div>
              </dl>
              <button
                type="button"
                class="w-full rounded-xl bg-slate-950 px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-sky-700 disabled:cursor-not-allowed disabled:opacity-60"
                :disabled="buyingPlanId !== null || !orderPlan(selectedOrder)"
                @click="repurchaseOrder(selectedOrder)"
              >
                {{ orderPlan(selectedOrder) && buyingPlanId === orderProductId(selectedOrder) ? "再次购买中..." : "再次购买同款套餐" }}
              </button>
            </div>
          </article>
        </section>

        <section class="overflow-hidden rounded-3xl border border-white/70 bg-white shadow-sm">
          <div class="flex items-center gap-2 border-b border-slate-100 px-6 py-4">
            <ReceiptText class="h-4 w-4 text-sky-600" />
            <h2 class="text-sm font-semibold text-slate-950">算力流水</h2>
          </div>
          <div v-if="loading" class="px-5 py-8 text-center text-sm text-slate-500">加载中...</div>
          <div v-else class="max-h-[420px] overflow-y-auto">
            <article v-for="log in logs" :key="log.id" class="flex items-start gap-3 border-b border-slate-100 px-5 py-4 last:border-0">
              <Clock3 class="mt-0.5 h-4 w-4 text-slate-400" />
              <div class="min-w-0 flex-1">
                <div class="flex items-center justify-between gap-3">
                  <p class="truncate text-sm font-medium text-slate-800">{{ log.reason || log.logType }}</p>
                  <p class="text-sm font-semibold" :class="log.amount >= 0 ? 'text-emerald-600' : 'text-red-600'">
                    {{ log.amount > 0 ? "+" : "" }}{{ log.amount }}
                  </p>
                </div>
                <p class="mt-1 text-xs text-slate-500">{{ formatDate(log.createdAt) }} · 余额 {{ log.balanceAfter }}</p>
              </div>
            </article>
            <div v-if="logs.length === 0" class="px-5 py-8 text-center text-sm text-slate-500">暂无算力流水</div>
          </div>
        </section>
      </div>
    </div>
  </AppShell>
</template>
