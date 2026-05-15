<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { RouterLink } from "vue-router"
import AppShell from "@/components/AppShell.vue"
import { ApiBusinessError } from "@/api/client"
import {
  createModelChatSession,
  fetchModelChatMessages,
  fetchModelChatSessions,
  fetchModelNodes,
  fetchModelPlans,
  fetchModelPools,
  fetchModelSubscriptions,
  createPaymentOrder,
  mockPayOrder,
  reportModelIssue,
  streamModelChatMessage,
} from "@/api/modelWorkbenchApi"
import type { ModelChatMessage, ModelChatSession, ModelNode, ModelPool, SubscriptionPlan, UserSubscription } from "@/api/types"
import { useAuthStore } from "@/store/authStore"

const auth = useAuthStore()
const loading = ref(false)
const sending = ref(false)
const buyingPlanId = ref<number | null>(null)
const error = ref("")
const success = ref("")
const pools = ref<ModelPool[]>([])
const plans = ref<SubscriptionPlan[]>([])
const subscriptions = ref<UserSubscription[]>([])
const nodes = ref<ModelNode[]>([])
const sessions = ref<ModelChatSession[]>([])
const messages = ref<ModelChatMessage[]>([])
const activePool = ref<ModelPool | null>(null)
const activeNode = ref<ModelNode | null>(null)
const activeSession = ref<ModelChatSession | null>(null)
const input = ref("")
const streamController = ref<AbortController | null>(null)

const hasActiveSubscription = computed(() =>
  subscriptions.value.some((subscription) => {
    const expiresAt = subscription.expiresAt || subscription.expires_at
    return subscription.status === "ACTIVE" && (!expiresAt || new Date(expiresAt).getTime() > Date.now())
  }),
)

const hasExpiredSubscription = computed(() =>
  subscriptions.value.some((subscription) => {
    const expiresAt = subscription.expiresAt || subscription.expires_at
    return subscription.status === "ACTIVE" && !!expiresAt && new Date(expiresAt).getTime() <= Date.now()
  }),
)

function poolName(pool: ModelPool) {
  return pool.poolName || pool.pool_name || pool.provider
}

function specLabel(pool: ModelPool) {
  return pool.specLabel || pool.spec_label || "PRO"
}

function nodeCode(node: ModelNode) {
  return node.nodeCode || node.node_code || `node-${node.id}`
}

function nodeLabel(node: ModelNode) {
  return node.displayLabel || node.display_label || nodeCode(node)
}

function nodeStatusLabel(node: ModelNode) {
  if (node.status === "AVAILABLE") return "可用"
  if (node.status === "BUSY") return "繁忙"
  if (node.status === "DISABLED") return "停用"
  return node.status
}

function nodeUsagePercent(node: ModelNode) {
  const current = node.currentConcurrency ?? node.current_concurrency ?? 0
  const max = node.maxConcurrency ?? node.max_concurrency ?? 1
  return Math.min(100, Math.round(((current + 1) / Math.max(1, max)) * 100))
}

function nodeHint(node: ModelNode) {
  const warning = node.warningReason || node.warning_reason
  if (node.status === "BUSY" || nodeUsagePercent(node) >= 90) return "节点繁忙，建议切换到负载更低的节点。"
  if (node.healthStatus === "UNHEALTHY" || node.health_status === "UNHEALTHY") return "节点健康检查异常，发送失败时会尝试切换。"
  if (warning && warning !== "capacity normal") return warning
  return "节点状态正常。"
}

function messageText(message: ModelChatMessage) {
  return message.contentText || message.content_text || ""
}

function planName(plan: SubscriptionPlan) {
  return plan.planName || plan.plan_name || plan.planCode || plan.plan_code || `套餐 #${plan.id}`
}

function planPrice(plan: SubscriptionPlan) {
  return Number(plan.priceCents ?? plan.price_cents ?? 0)
}

function formatModelWorkbenchError(err: unknown, fallback: string) {
  if (err instanceof ApiBusinessError) {
    if (err.code === "SUBSCRIPTION_REQUIRED") {
      return hasExpiredSubscription.value
        ? "模型套餐已到期。请续费或重新购买后再使用模型通道。"
        : "当前账号暂无可用模型订阅。开通模型套餐后即可开始对话。"
    }
    if (err.code === "CHANNEL_UNAVAILABLE") return "当前模型节点繁忙或不可用。请切换节点后重试，或稍后再发送。"
    if (err.code === "CREDIT_NOT_ENOUGH" || err.code === "AGENT_CREDIT_NOT_ENOUGH") return "当前算力额度不足。请先购买算力包或升级套餐后再使用。"
    if (err.code === "AGENT_RATE_LIMITED") return "套餐调用额度已用完。请稍后重试，或续费/升级更高额度套餐。"
    return err.message || fallback
  }
  return err instanceof Error ? err.message : fallback
}

function formatStreamError(message: string) {
  const text = message || ""
  if (/quota|limit|rate|exceeded/i.test(text)) return "套餐调用额度已用完。请稍后重试，或前往会员与算力续费/升级。"
  if (/subscription|entitled/i.test(text)) return "模型套餐不可用或已到期。请续费后再发送。"
  if (/node|channel|available|busy/i.test(text)) return "当前模型节点繁忙或不可用。请切换节点后重试。"
  return text || "模型响应失败，请稍后重试。"
}

async function loadInitial() {
  loading.value = true
  error.value = ""
  try {
    const [planRes, subscriptionRes, poolRes, sessionRes] = await Promise.all([
      fetchModelPlans({ token: auth.token }),
      fetchModelSubscriptions({ token: auth.token }),
      fetchModelPools({ token: auth.token }),
      fetchModelChatSessions({ token: auth.token }),
    ])
    plans.value = planRes
    subscriptions.value = subscriptionRes
    pools.value = poolRes
    sessions.value = sessionRes
    if (poolRes.length > 0) {
      await selectPool(poolRes[0])
    } else {
      nodes.value = []
      activePool.value = null
      activeNode.value = null
    }
    if (sessionRes.length > 0) {
      await selectSession(sessionRes[0])
    }
  } catch (err) {
    error.value = formatModelWorkbenchError(err, "模型工作台加载失败")
  } finally {
    loading.value = false
  }
}

async function buyPlan(plan: SubscriptionPlan) {
  buyingPlanId.value = plan.id
  error.value = ""
  success.value = ""
  try {
    const order = await createPaymentOrder(
      { productType: "MODEL_CHANNEL", productId: plan.id, channel: "MOCK" },
      { token: auth.token },
    )
    await mockPayOrder(order.id, { token: auth.token })
    success.value = `${planName(plan)} 已开通，正在刷新模型通道。`
    await loadInitial()
  } catch (err) {
    error.value = formatModelWorkbenchError(err, "套餐开通失败")
  } finally {
    buyingPlanId.value = null
  }
}

async function selectPool(pool: ModelPool) {
  activePool.value = pool
  nodes.value = await fetchModelNodes(pool.id, { token: auth.token })
  activeNode.value = nodes.value.find((node) => node.status === "AVAILABLE" && nodeUsagePercent(node) < 90) || nodes.value[0] || null
}

async function selectSession(session: ModelChatSession) {
  activeSession.value = session
  messages.value = await fetchModelChatMessages(session.id, { token: auth.token })
}

async function startChat(pool = activePool.value, node = activeNode.value) {
  if (!pool) {
    error.value = hasExpiredSubscription.value
      ? "套餐已到期，续费后即可新建模型对话。"
      : "请先开通模型套餐，再新建模型对话。"
    return
  }
  error.value = ""
  const session = await createModelChatSession(
    { poolId: pool.id, nodeId: node?.id, title: `${poolName(pool)} chat` },
    { token: auth.token },
  )
  sessions.value = [session, ...sessions.value]
  await selectSession(session)
}

async function send() {
  if (!input.value.trim()) return
  if (!activeSession.value) {
    await startChat()
  }
  if (!activeSession.value) return
  const text = input.value.trim()
  input.value = ""
  sending.value = true
  error.value = ""
  success.value = ""
  messages.value.push({
    id: Date.now(),
    role: "USER",
    contentText: text,
  })
  const assistantDraft: ModelChatMessage = {
    id: Date.now() + 1,
    role: "ASSISTANT",
    contentText: "",
  }
  messages.value.push(assistantDraft)
  streamController.value = new AbortController()
  try {
    await streamModelChatMessage(
      activeSession.value.id,
      { content: text, nodeId: activeNode.value?.id },
      {
        token: auth.token,
        signal: streamController.value.signal,
        onDelta: (chunk) => {
          assistantDraft.contentText = `${assistantDraft.contentText || ""}${chunk}`
        },
        onCompleted: (message) => {
          Object.assign(assistantDraft, message)
        },
        onError: (message) => {
          error.value = formatStreamError(message)
        },
      },
    )
    sessions.value = await fetchModelChatSessions({ token: auth.token })
  } catch (err) {
    if (!(err instanceof Error && err.name === "AbortError")) {
      error.value = formatModelWorkbenchError(err, "消息发送失败")
      if (!messageText(assistantDraft)) {
        messages.value = messages.value.filter((message) => message !== assistantDraft)
      }
    }
  } finally {
    sending.value = false
    streamController.value = null
  }
}

function stopGenerating() {
  streamController.value?.abort()
  sending.value = false
}

async function retryLastUserMessage() {
  const lastUserMessage = [...messages.value].reverse().find((message) => message.role === "USER")
  const text = lastUserMessage ? messageText(lastUserMessage).trim() : ""
  if (!text) return
  input.value = text
  await send()
}

async function continueGenerating() {
  input.value = "Continue"
  await send()
}

async function reportIssue() {
  if (!activeSession.value) return
  error.value = ""
  success.value = ""
  try {
    await reportModelIssue(
      activeSession.value.id,
      {
        poolId: activePool.value?.id,
        nodeId: activeNode.value?.id,
        message: "User reported an issue from model workbench",
      },
      { token: auth.token },
    )
    success.value = "已提交节点反馈，我们会持续优化通道稳定性。"
  } catch (err) {
    error.value = formatModelWorkbenchError(err, "反馈提交失败")
  }
}

onMounted(loadInitial)
</script>

<template>
  <AppShell title="Global Models" description="使用已订阅的全球模型通道，进行稳定低延迟对话">
    <div class="grid min-h-[calc(100vh-4rem)] bg-slate-50 lg:grid-cols-[280px_1fr]">
      <aside class="border-r border-slate-200 bg-white p-4">
        <button
          type="button"
          class="mb-4 w-full rounded-xl bg-slate-950 px-4 py-3 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-50"
          :disabled="!hasActiveSubscription && pools.length === 0"
          @click="startChat()"
        >
          New chat
        </button>
        <div class="space-y-2">
          <p class="text-xs font-semibold uppercase tracking-widest text-slate-400">History</p>
          <button
            v-for="session in sessions"
            :key="session.id"
            type="button"
            class="w-full rounded-lg px-3 py-2 text-left text-sm"
            :class="activeSession?.id === session.id ? 'bg-slate-900 text-white' : 'hover:bg-slate-100'"
            @click="selectSession(session)"
          >
            <div class="truncate">{{ session.title }}</div>
            <div class="text-xs opacity-70">{{ session.nodeCode || session.node_code || "auto node" }}</div>
          </button>
          <p v-if="sessions.length === 0" class="rounded-xl bg-slate-50 p-3 text-xs text-slate-500">暂无对话记录。</p>
        </div>
      </aside>

      <main class="flex min-w-0 flex-col">
        <section class="border-b border-slate-200 bg-white p-4">
          <div v-if="error" class="mb-3 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
            <span>{{ error }}</span>
            <RouterLink to="/billing" class="font-semibold text-red-800 underline">去续费/购买</RouterLink>
          </div>
          <div v-if="success" class="mb-3 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">
            {{ success }}
          </div>
          <div v-if="loading" class="text-sm text-slate-500">Loading model pools...</div>
          <div v-else class="space-y-5">
            <div v-if="pools.length === 0" class="rounded-2xl border border-dashed border-blue-200 bg-blue-50 p-5">
              <h2 class="font-semibold text-blue-950">
                {{ hasExpiredSubscription ? "模型套餐已到期" : "暂无可用模型订阅" }}
              </h2>
              <p class="mt-1 text-sm text-blue-700">
                {{ hasExpiredSubscription ? "续费或再次购买套餐后，即可恢复全球模型通道。" : "开通模型套餐后，即可使用全球模型通道进行对话。" }}
              </p>
              <div class="mt-4 flex flex-wrap gap-3">
                <button
                  v-for="plan in plans"
                  :key="String(plan.id)"
                  type="button"
                  class="rounded-xl bg-blue-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
                  :disabled="buyingPlanId !== null"
                  @click="buyPlan(plan)"
                >
                  {{ buyingPlanId === plan.id ? "开通中..." : hasExpiredSubscription ? "续费" : "开通" }}
                  {{ planName(plan) }} - CNY {{ (planPrice(plan) / 100).toFixed(2) }}
                </button>
                <RouterLink to="/billing" class="rounded-xl border border-blue-200 bg-white px-4 py-2 text-sm font-semibold text-blue-700">
                  查看订单与套餐
                </RouterLink>
              </div>
            </div>
            <div v-for="pool in pools" :key="pool.id" class="rounded-2xl border border-slate-100 bg-slate-50/50 p-4">
              <div class="mb-3 flex flex-wrap items-center justify-between gap-3">
                <h2 class="font-semibold text-slate-900">
                  {{ poolName(pool) }}
                  <span class="text-sm text-slate-400">({{ pool.availableNodes || pool.available_nodes || 0 }}/{{ pool.nodeCount || pool.node_count || 0 }})</span>
                </h2>
                <span class="rounded-full bg-amber-100 px-2 py-1 text-xs font-semibold text-amber-700">{{ specLabel(pool) }}</span>
              </div>
              <div v-if="activePool?.id === pool.id && nodes.length === 0" class="rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
                当前模型池暂无可用节点，请稍后重试或切换其他模型池。
              </div>
              <div class="grid gap-3 md:grid-cols-3 xl:grid-cols-4">
                <button
                  v-for="node in activePool?.id === pool.id ? nodes : []"
                  :key="node.id"
                  type="button"
                  class="rounded-2xl border p-3 text-left shadow-sm transition"
                  :class="activeNode?.id === node.id ? 'border-blue-500 bg-blue-50' : 'border-slate-200 bg-white hover:border-blue-300'"
                  @click="activeNode = node"
                >
                  <div class="flex items-center justify-between gap-2">
                    <span class="truncate font-mono text-sm">{{ nodeLabel(node) }}</span>
                    <span
                      class="rounded-full px-2 py-0.5 text-xs"
                      :class="node.status === 'AVAILABLE' ? 'bg-green-100 text-green-700' : node.status === 'BUSY' ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-600'"
                    >
                      {{ nodeStatusLabel(node) }}
                    </span>
                  </div>
                  <div class="mt-3 h-1.5 overflow-hidden rounded-full bg-slate-100">
                    <div
                      class="h-full rounded-full"
                      :class="nodeUsagePercent(node) >= 90 ? 'bg-amber-500' : 'bg-green-500'"
                      :style="{ width: `${nodeUsagePercent(node)}%` }"
                    />
                  </div>
                  <p class="mt-2 text-xs text-slate-500">{{ nodeHint(node) }}</p>
                </button>
              </div>
              <div class="mt-3 flex flex-wrap gap-3">
                <button
                  v-if="activePool?.id !== pool.id"
                  type="button"
                  class="rounded-xl border border-slate-200 px-4 py-2 text-sm text-slate-600 hover:border-blue-300"
                  @click="selectPool(pool)"
                >
                  Show nodes
                </button>
                <button
                  v-else-if="activeSession"
                  type="button"
                  class="rounded-xl border border-slate-200 px-4 py-2 text-sm text-slate-600 hover:border-red-300 hover:text-red-600"
                  @click="reportIssue"
                >
                  Report issue
                </button>
              </div>
            </div>
          </div>
        </section>

        <section class="flex-1 overflow-y-auto px-6 py-8">
          <div v-if="messages.length === 0" class="mx-auto mt-24 max-w-xl text-center">
            <h1 class="text-3xl font-semibold text-slate-900">What can we build today?</h1>
            <p class="mt-3 text-slate-500">选择已订阅的模型节点，开始稳定低延迟对话。</p>
            <RouterLink v-if="!hasActiveSubscription" to="/billing" class="mt-5 inline-flex rounded-xl bg-slate-950 px-5 py-2.5 text-sm font-semibold text-white">
              开通模型套餐
            </RouterLink>
          </div>
          <div v-else class="mx-auto max-w-3xl space-y-5">
            <article
              v-for="message in messages"
              :key="message.id"
              class="rounded-2xl px-5 py-4"
              :class="message.role === 'USER' ? 'ml-auto max-w-[80%] bg-slate-900 text-white' : 'mr-auto bg-white text-slate-900 shadow-sm'"
            >
              <p class="whitespace-pre-wrap text-sm leading-7">{{ messageText(message) }}</p>
            </article>
            <div class="flex flex-wrap justify-center gap-3">
              <button
                type="button"
                class="rounded-xl border border-slate-200 bg-white px-4 py-2 text-sm font-semibold text-slate-600 shadow-sm hover:border-blue-300"
                :disabled="sending"
                @click="retryLastUserMessage"
              >
                Regenerate
              </button>
              <button
                type="button"
                class="rounded-xl border border-slate-200 bg-white px-4 py-2 text-sm font-semibold text-slate-600 shadow-sm hover:border-blue-300"
                :disabled="sending"
                @click="continueGenerating"
              >
                Continue
              </button>
            </div>
          </div>
        </section>

        <footer class="border-t border-slate-200 bg-white p-4">
          <form class="mx-auto flex max-w-3xl items-center gap-3 rounded-2xl border border-slate-200 bg-white p-3 shadow-lg" @submit.prevent="send">
            <input
              v-model="input"
              class="min-w-0 flex-1 bg-transparent px-3 py-2 text-sm outline-none"
              :placeholder="hasActiveSubscription ? 'Ask anything...' : '请先开通模型套餐后再发送'"
              :disabled="sending || (!hasActiveSubscription && pools.length === 0)"
            />
            <button
              v-if="sending"
              type="button"
              class="rounded-xl border border-slate-200 px-5 py-2 text-sm font-semibold text-slate-600"
              @click="stopGenerating"
            >
              Stop
            </button>
            <button
              type="submit"
              class="rounded-xl bg-blue-600 px-5 py-2 text-sm font-semibold text-white disabled:opacity-50"
              :disabled="sending || (!hasActiveSubscription && pools.length === 0)"
            >
              {{ sending ? "Sending" : "Send" }}
            </button>
          </form>
        </footer>
      </main>
    </div>
  </AppShell>
</template>
