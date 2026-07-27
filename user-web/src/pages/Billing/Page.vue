<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { ReceiptText } from "lucide-vue-next"
import RechargeSection from "@/pages/Billing/RechargeSection.vue"
import { fetchCreditAccount, fetchCreditStatementLogs } from "@/api/creditApi"
import type { CreditAccount, CreditStatementLog, PageResult } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { parseShanghaiDate, statementLogToBillingRow } from "@/utils/billingLogPresentation"

const auth = useAuthStore()
const loading = ref(false)
const error = ref("")
const account = ref<CreditAccount | null>(null)
const currentPage = ref(1)
const pageSize = 20
const statementPage = ref<PageResult<CreditStatementLog>>({
  list: [],
  total: 0,
  pageNo: 1,
  pageSize,
  hasNext: false,
})
const logs = computed(() => statementPage.value.list.map(statementLogToBillingRow))
const totalPages = computed(() => Math.max(1, Math.ceil(statementPage.value.total / pageSize)))

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

async function loadBilling() {
  loading.value = true
  error.value = ""
  try {
    const [accountRes, statementRes] = await Promise.all([
      fetchCreditAccount({ token: auth.token }),
      fetchCreditStatementLogs({ token: auth.token, query: { pageNo: 1, pageSize } }),
    ])
    account.value = accountRes
    statementPage.value = statementRes
    currentPage.value = statementRes.pageNo
    window.dispatchEvent(new CustomEvent("credits:updated", { detail: accountRes }))
  } catch (err) {
    error.value = err instanceof Error ? err.message : "加载算力数据失败"
  } finally {
    loading.value = false
  }
}

async function changePage(pageNo: number) {
  if (pageNo < 1 || pageNo > totalPages.value || pageNo === currentPage.value) return
  loading.value = true
  error.value = ""
  try {
    const response = await fetchCreditStatementLogs({
      token: auth.token,
      query: { pageNo, pageSize },
    })
    statementPage.value = response
    currentPage.value = response.pageNo
  } catch (err) {
    error.value = err instanceof Error ? err.message : "加载算力流水失败"
  } finally {
    loading.value = false
  }
}

onMounted(loadBilling)
</script>

<template>
    <div class="space-y-6 px-6 py-6">
      <div v-if="error" class="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
        {{ error }}
      </div>

      <RechargeSection :account="account" @credits-updated="loadBilling" />

      <section class="rounded-lg border border-border bg-card">
        <details class="group">
          <summary class="flex cursor-pointer items-center justify-between border-b border-border px-5 py-4 list-none select-none">
            <div class="flex items-center gap-2">
              <ReceiptText class="h-4 w-4 text-primary" />
              <h2 class="text-sm font-semibold">算力流水</h2>
              <span class="text-xs text-muted-foreground">（共 {{ statementPage.total }} 条）</span>
            </div>
            <svg 
              class="h-4 w-4 text-muted-foreground transition-transform duration-200 group-open:rotate-180" 
              fill="none" 
              viewBox="0 0 24 24" 
              stroke="currentColor"
              stroke-width="2"
            >
              <path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" />
            </svg>
          </summary>
          
          <!-- 原有表格内容 -->
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
                <tr v-for="log in logs" :key="log.id">
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
              <span>共 {{ statementPage.total }} 条，每页 {{ pageSize }} 条</span>
              <div class="flex items-center gap-2">
                <button
                  type="button"
                  class="rounded-md border border-border px-3 py-1.5 transition-colors hover:bg-secondary disabled:cursor-not-allowed disabled:opacity-50"
                  :disabled="loading || currentPage <= 1"
                  @click="changePage(currentPage - 1)"
                >
                  上一页
                </button>
                <span>{{ currentPage }} / {{ totalPages }}</span>
                <button
                  type="button"
                  class="rounded-md border border-border px-3 py-1.5 transition-colors hover:bg-secondary disabled:cursor-not-allowed disabled:opacity-50"
                  :disabled="loading || !statementPage.hasNext"
                  @click="changePage(currentPage + 1)"
                >
                  下一页
                </button>
              </div>
            </div>
            <div v-if="logs.length === 0" class="px-5 py-8 text-center text-sm text-muted-foreground">暂无算力流水</div>
          </div>
        </details>
      </section>
    </div>
</template>
