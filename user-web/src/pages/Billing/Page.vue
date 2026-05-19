<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { Activity, RefreshCw, Wallet } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import { fetchCreditAccount, fetchCreditLogs } from "@/api/creditApi"
import type { CreditAccount, CreditLog } from "@/api/types"
import { useAuthStore } from "@/store/authStore"

const auth = useAuthStore()

const loading = ref(false)
const error = ref("")
const account = ref<CreditAccount | null>(null)
const logs = ref<CreditLog[]>([])

const availablePercent = computed(() => {
  if (!account.value || account.value.totalGranted <= 0) return 0
  return Math.round((account.value.available / account.value.totalGranted) * 100)
})

function formatDate(value: string) {
  return new Date(value).toLocaleString("zh-CN")
}

function logTypeLabel(type: CreditLog["logType"]) {
  const labels: Record<string, string> = {
    FREEZE: "冻结",
    DEDUCT: "扣减",
    RELEASE: "释放",
    MANUAL_ADD: "手动增加",
    MANUAL_DEDUCT: "手动扣减",
  }
  return labels[type] || type
}

async function loadBilling() {
  loading.value = true
  error.value = ""
  try {
    const [accountRes, logRes] = await Promise.all([
      fetchCreditAccount({ token: auth.token }),
      fetchCreditLogs({ token: auth.token, query: { pageNo: 1, pageSize: 30 } }),
    ])
    account.value = accountRes
    logs.value = logRes.list
  } catch (err) {
    error.value = err instanceof Error ? err.message : "算力数据加载失败，请稍后重试"
  } finally {
    loading.value = false
  }
}

onMounted(loadBilling)
</script>

<template>
  <AppShell title="算力中心" description="查看当前算力余额、冻结算力和消费流水">
    <div class="min-h-[calc(100vh-4rem)] bg-gradient-to-br from-slate-50 via-sky-50/60 to-white px-4 py-6 sm:px-6">
      <div class="mx-auto max-w-6xl space-y-6">
        <div v-if="error" class="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {{ error }}
        </div>

        <section class="grid gap-4 md:grid-cols-4">
          <article class="rounded-3xl border border-white/70 bg-white/85 p-5 shadow-sm backdrop-blur">
            <div class="flex items-center gap-2 text-sm text-slate-500">
              <Wallet class="h-4 w-4 text-sky-600" />
              当前余额
            </div>
            <p class="mt-3 text-3xl font-semibold text-slate-950">{{ account?.balance ?? "--" }}</p>
          </article>
          <article class="rounded-3xl border border-white/70 bg-white/85 p-5 shadow-sm backdrop-blur">
            <p class="text-sm text-slate-500">可用算力</p>
            <p class="mt-3 text-3xl font-semibold text-slate-950">{{ account?.available ?? "--" }}</p>
          </article>
          <article class="rounded-3xl border border-white/70 bg-white/85 p-5 shadow-sm backdrop-blur">
            <p class="text-sm text-slate-500">冻结算力</p>
            <p class="mt-3 text-3xl font-semibold text-slate-950">{{ account?.frozen ?? "--" }}</p>
          </article>
          <article class="rounded-3xl border border-white/70 bg-white/85 p-5 shadow-sm backdrop-blur">
            <p class="text-sm text-slate-500">累计消耗</p>
            <p class="mt-3 text-3xl font-semibold text-slate-950">{{ account?.totalConsumed ?? "--" }}</p>
          </article>
        </section>

        <section class="rounded-3xl border border-white/70 bg-white p-6 shadow-sm">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <div>
              <p class="text-sm font-medium text-sky-700">统一算力</p>
              <h2 class="mt-1 text-xl font-semibold text-slate-950">平台 AI 功能统一按算力扣减</h2>
              <p class="mt-2 text-sm text-slate-500">
                AI 工具、Agent 和后续工作流都会走同一套算力账户，不再区分模型渠道套餐或节点池。
              </p>
            </div>
            <button
              type="button"
              class="inline-flex items-center gap-2 rounded-xl border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
              :disabled="loading"
              @click="loadBilling"
            >
              <RefreshCw class="h-4 w-4" :class="loading ? 'animate-spin' : ''" />
              刷新
            </button>
          </div>

          <div class="mt-6">
            <div class="mb-2 flex justify-between text-xs text-slate-500">
              <span>可用占比</span>
              <span>{{ availablePercent }}%</span>
            </div>
            <div class="h-3 overflow-hidden rounded-full bg-slate-100">
              <div class="h-full rounded-full bg-sky-500 transition-all" :style="{ width: Math.min(availablePercent, 100) + '%' }" />
            </div>
          </div>
        </section>

        <section class="overflow-hidden rounded-3xl border border-white/70 bg-white shadow-sm">
          <div class="flex items-center gap-2 border-b border-slate-100 px-6 py-4">
            <Activity class="h-4 w-4 text-sky-600" />
            <h2 class="text-sm font-semibold text-slate-950">算力流水</h2>
          </div>
          <div class="overflow-x-auto">
            <table class="w-full min-w-[760px] text-sm">
              <thead class="bg-slate-50 text-xs text-slate-500">
                <tr>
                  <th class="px-4 py-3 text-left font-medium">类型</th>
                  <th class="px-4 py-3 text-left font-medium">变动</th>
                  <th class="px-4 py-3 text-left font-medium">冻结变动</th>
                  <th class="px-4 py-3 text-left font-medium">变动后余额</th>
                  <th class="px-4 py-3 text-left font-medium">原因</th>
                  <th class="px-4 py-3 text-left font-medium">时间</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                <tr v-for="log in logs" :key="log.id">
                  <td class="px-4 py-3 font-medium text-slate-950">{{ logTypeLabel(log.logType) }}</td>
                  <td class="px-4 py-3" :class="log.amount >= 0 ? 'text-emerald-600' : 'text-red-600'">
                    {{ log.amount >= 0 ? '+' : '' }}{{ log.amount }}
                  </td>
                  <td class="px-4 py-3 text-slate-600">{{ log.frozenAmount }}</td>
                  <td class="px-4 py-3 text-slate-600">{{ log.balanceAfter }}</td>
                  <td class="px-4 py-3 text-slate-600">{{ log.reason || "-" }}</td>
                  <td class="px-4 py-3 text-slate-500">{{ formatDate(log.createdAt) }}</td>
                </tr>
              </tbody>
            </table>
            <div v-if="!loading && logs.length === 0" class="px-6 py-10 text-center text-sm text-slate-500">
              暂无算力流水。
            </div>
            <div v-if="loading" class="px-6 py-10 text-center text-sm text-slate-500">
              加载中...
            </div>
          </div>
        </section>
      </div>
    </div>
  </AppShell>
</template>
