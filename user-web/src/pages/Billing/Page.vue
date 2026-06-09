<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { ReceiptText, Wallet } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import RechargeSection from "@/pages/Billing/RechargeSection.vue"
import { fetchCreditAccount, fetchCreditLogs, fetchCreditUsageLogs } from "@/api/creditApi"
import type { BillingUsageLog, CreditAccount, CreditLog, PageResult } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { sortCreditLogsByCreatedAtDesc } from "@/utils/creditLogSort"

const auth = useAuthStore()
const loading = ref(false)
const error = ref("")
const account = ref<CreditAccount | null>(null)
type BillingRow = {
  id: string
  createdAt: string
  reason: string
  changeText: string
  negative: boolean
}

const logs = ref<BillingRow[]>([])
const currentPage = ref(1)
const pageSize = 20
const pagedLogs = computed(() => logs.value.slice((currentPage.value - 1) * pageSize, currentPage.value * pageSize))
const totalPages = computed(() => Math.max(1, Math.ceil(logs.value.length / pageSize)))

function emptyUsageLogs(): PageResult<BillingUsageLog> {
  return {
    list: [],
    total: 0,
    pageNo: 1,
    pageSize: 100,
    hasNext: false,
  }
}

function creditSourceKey(log: CreditLog) {
  if (log.agentRunId != null) return `AGENT_RUN:${log.agentRunId}`
  if (log.taskId != null) return `TASK:${log.taskId}`
  return ""
}

function usageSourceKey(log: BillingUsageLog) {
  return `${log.sourceType}:${log.sourceId}`
}

function parseShanghaiDate(value?: string | null) {
  if (!value) return null
  const normalized = value.trim()
  if (!normalized) return null
  if (/[zZ]$|[+-]\d{2}:?\d{2}$/.test(normalized)) return new Date(normalized)
  return new Date(`${normalized.replace(" ", "T")}+08:00`)
}

function formatShanghaiTime(value?: string | null) {
  const date = parseShanghaiDate(value)
  if (!date || Number.isNaN(date.getTime())) return "-"
  const parts = new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).formatToParts(date)
  const pick = (type: Intl.DateTimeFormatPartTypes) => parts.find((part) => part.type === type)?.value || ""
  return `${pick("year")}-${pick("month")}-${pick("day")} ${pick("hour")}:${pick("minute")}:${pick("second")}`
}

function usageReason(log: BillingUsageLog) {
  const source = log.sourceType === "AGENT_RUN" ? `Agent \u4f1a\u8bdd #${log.sourceId}` : `\u4efb\u52a1 ${log.taskNo || `#${log.sourceId}`}`
  const model = log.modelName || log.provider || "\u6a21\u578b"
  const tokens = log.totalTokens > 0
    ? `\uff0c\u6d88\u8017 ${log.promptTokens.toLocaleString()} \u8f93\u5165 / ${log.completionTokens.toLocaleString()} \u8f93\u51fa tokens`
    : ""
  const unitLabel = (log.billingUnit || "").toUpperCase() === "PER_SECOND" ? "\u79d2" : "\u6b21"
  const units = (log.billableUnits ?? 0) > 0 ? `\uff0c\u8ba1\u8d39 ${log.billableUnits} ${unitLabel}` : ""
  return `${source} \u8c03\u7528 ${model}${tokens}${units}`
}

function creditReason(log: CreditLog, negative: boolean) {
  const raw = (log.reason || "").trim()
  if (log.logType === "RECHARGE") return raw && raw !== "async recharge credit dispatch" ? raw : "\u5145\u503c\u5230\u8d26"
  if (log.logType === "MANUAL_ADD") return raw ? `\u540e\u53f0\u589e\u52a0\u7b97\u529b\uff1a${raw}` : "\u540e\u53f0\u589e\u52a0\u7b97\u529b"
  if (log.logType === "MANUAL_DEDUCT") return raw ? `\u540e\u53f0\u6263\u51cf\u7b97\u529b\uff1a${raw}` : "\u540e\u53f0\u6263\u51cf\u7b97\u529b"
  if (log.agentRunId != null) return raw ? `Agent \u4f1a\u8bdd #${log.agentRunId}\uff1a${raw}` : `Agent \u4f1a\u8bdd #${log.agentRunId} \u6263\u8d39`
  if (log.taskId != null) return raw ? `\u4efb\u52a1 #${log.taskId}\uff1a${raw}` : `\u4efb\u52a1 #${log.taskId} \u6263\u8d39`
  return raw || (negative ? "\u7b97\u529b\u6263\u8d39" : "\u7b97\u529b\u5165\u8d26")
}

function mergeBillingRows(creditLogs: CreditLog[], usageLogs: BillingUsageLog[]) {
  const usageKeys = new Set(usageLogs.map(usageSourceKey))
  const creditRows = creditLogs
    .filter((log) => !(log.logType === "DEDUCT" && usageKeys.has(creditSourceKey(log))))
    .filter((log) => log.amount !== 0)
    .map((log): BillingRow => {
      const negative = log.logType === "DEDUCT" || log.logType === "MANUAL_DEDUCT"
      return {
        id: `credit-${log.id}`,
        createdAt: log.createdAt,
        reason: creditReason(log, negative),
        changeText: `${negative ? "-" : "+"}${log.amount}`,
        negative,
      }
    })
  const usageRows = usageLogs
    .filter((log) => log.chargedCredits !== 0)
    .map((log): BillingRow => ({
      id: `usage-${log.id}`,
      createdAt: log.createdAt,
      reason: usageReason(log),
      changeText: `-${log.chargedCredits}`,
      negative: true,
    }))
  return [...creditRows, ...usageRows].sort((a, b) => {
    const diff = (parseShanghaiDate(b.createdAt)?.getTime() ?? 0) - (parseShanghaiDate(a.createdAt)?.getTime() ?? 0)
    return Number.isNaN(diff) || diff === 0 ? b.id.localeCompare(a.id) : diff
  })
}

async function loadBilling() {
  loading.value = true
  error.value = ""
  try {
    const [accountRes, creditLogRes, usageLogRes] = await Promise.all([
      fetchCreditAccount({ token: auth.token }),
      fetchCreditLogs({ token: auth.token, query: { pageNo: 1, pageSize: 100 } }),
      fetchCreditUsageLogs({ token: auth.token, query: { pageNo: 1, pageSize: 100 } }).catch(emptyUsageLogs),
    ])
    account.value = accountRes
    logs.value = mergeBillingRows(sortCreditLogsByCreatedAtDesc(creditLogRes.list), usageLogRes.list)
    currentPage.value = 1
    window.dispatchEvent(new CustomEvent("credits:updated", { detail: accountRes }))
  } catch (err) {
    error.value = err instanceof Error ? err.message : "加载算力数据失败"
  } finally {
    loading.value = false
  }
}

onMounted(loadBilling)
</script>

<template>
  <AppShell title="会员与算力" description="充值套餐、算力账户与流水">
    <div class="space-y-6 px-6 py-6">
      <div v-if="error" class="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
        {{ error }}
      </div>

      <RechargeSection :account="account" @credits-updated="loadBilling" />

      <div class="grid gap-4 md:grid-cols-4">
        <div class="rounded-lg border border-border bg-card p-4">
          <div class="flex items-center gap-2 text-sm text-muted-foreground">
            <Wallet class="h-4 w-4 text-primary" /> 当前余额
          </div>
          <p class="mt-3 text-2xl font-semibold">{{ account?.balance ?? "--" }}</p>
        </div>
        <div class="rounded-lg border border-border bg-card p-4">
          <p class="text-sm text-muted-foreground">冻结算力</p>
          <p class="mt-3 text-2xl font-semibold">{{ account?.frozen ?? "--" }}</p>
        </div>
        <div class="rounded-lg border border-border bg-card p-4">
          <p class="text-sm text-muted-foreground">可用算力</p>
          <p class="mt-3 text-2xl font-semibold">{{ account?.available ?? "--" }}</p>
        </div>
        <div class="rounded-lg border border-border bg-card p-4">
          <p class="text-sm text-muted-foreground">累计消耗</p>
          <p class="mt-3 text-2xl font-semibold">{{ account?.totalConsumed ?? "--" }}</p>
        </div>
      </div>

      <section class="rounded-lg border border-border bg-card">
        <div class="flex items-center gap-2 border-b border-border px-5 py-4">
          <ReceiptText class="h-4 w-4 text-primary" />
          <h2 class="text-sm font-semibold">算力流水</h2>
        </div>
        <div v-if="loading" class="px-5 py-8 text-center text-sm text-muted-foreground">加载中...</div>
        <div v-else class="overflow-x-auto">
          <table class="w-full min-w-[560px] text-sm">
            <thead class="bg-secondary/70 text-xs text-muted-foreground">
              <tr>
                <th class="px-4 py-3 text-left font-medium">{{ "\u65f6\u95f4" }}</th>
                <th class="px-4 py-3 text-left font-medium">原因</th>
                <th class="px-4 py-3 text-right font-medium">{{ "\u53d8\u52a8" }}</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-border">
              <tr v-for="log in pagedLogs" :key="log.id">
                <td class="px-4 py-3 text-muted-foreground">
                  {{ formatShanghaiTime(log.createdAt) }}
                </td>
                <td class="px-4 py-3">{{ log.reason || "-" }}</td>
                <td
                  class="px-4 py-3 text-right"
                  :class="log.negative ? 'text-destructive' : 'text-primary'"
                >
                  {{ log.changeText }}
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="logs.length > 0" class="flex items-center justify-between border-t border-border px-5 py-4 text-sm text-muted-foreground">
            <span>共 {{ logs.length }} 条，每页 {{ pageSize }} 条</span>
            <div class="flex items-center gap-2">
              <button
                type="button"
                class="rounded-md border border-border px-3 py-1.5 transition-colors hover:bg-secondary disabled:cursor-not-allowed disabled:opacity-50"
                :disabled="currentPage <= 1"
                @click="currentPage -= 1"
              >
                上一页
              </button>
              <span>{{ currentPage }} / {{ totalPages }}</span>
              <button
                type="button"
                class="rounded-md border border-border px-3 py-1.5 transition-colors hover:bg-secondary disabled:cursor-not-allowed disabled:opacity-50"
                :disabled="currentPage >= totalPages"
                @click="currentPage += 1"
              >
                下一页
              </button>
            </div>
          </div>
          <div v-if="logs.length === 0" class="px-5 py-8 text-center text-sm text-muted-foreground">暂无算力流水</div>
        </div>
      </section>
    </div>
  </AppShell>
</template>
