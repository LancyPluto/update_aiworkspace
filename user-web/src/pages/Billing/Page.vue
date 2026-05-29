<script setup lang="ts">
import { onMounted, ref } from "vue"
import { ReceiptText, Wallet } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import RechargeSection from "@/pages/Billing/RechargeSection.vue"
import { fetchCreditAccount, fetchCreditLogs } from "@/api/creditApi"
import type { CreditAccount, CreditLog } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { sortCreditLogsByCreatedAtDesc } from "@/utils/creditLogSort"

const auth = useAuthStore()
const loading = ref(false)
const error = ref("")
const account = ref<CreditAccount | null>(null)
const logs = ref<CreditLog[]>([])

async function loadBilling() {
  loading.value = true
  error.value = ""
  try {
    const [accountRes, logRes] = await Promise.all([
      fetchCreditAccount({ token: auth.token }),
      fetchCreditLogs({ token: auth.token, query: { pageNo: 1, pageSize: 20 } }),
    ])
    account.value = accountRes
    logs.value = sortCreditLogsByCreatedAtDesc(logRes.list)
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
          <table class="w-full min-w-[720px] text-sm">
            <thead class="bg-secondary/70 text-xs text-muted-foreground">
              <tr>
                <th class="px-4 py-3 text-left font-medium">时间</th>
                <th class="px-4 py-3 text-left font-medium">类型</th>
                <th class="px-4 py-3 text-left font-medium">原因</th>
                <th class="px-4 py-3 text-right font-medium">变动</th>
                <th class="px-4 py-3 text-right font-medium">余额</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-border">
              <tr v-for="log in logs" :key="log.id">
                <td class="px-4 py-3 text-muted-foreground">{{ new Date(log.createdAt).toLocaleString() }}</td>
                <td class="px-4 py-3">{{ log.logType }}</td>
                <td class="px-4 py-3">{{ log.reason || "-" }}</td>
                <td
                  class="px-4 py-3 text-right"
                  :class="log.logType === 'DEDUCT' || log.logType === 'FREEZE' || log.logType === 'MANUAL_DEDUCT' ? 'text-destructive' : 'text-primary'"
                >
                  {{ log.logType === 'DEDUCT' || log.logType === 'FREEZE' || log.logType === 'MANUAL_DEDUCT' ? '-' : '+' }}{{ log.amount }}
                </td>
                <td class="px-4 py-3 text-right">{{ log.balanceAfter }}</td>
              </tr>
            </tbody>
          </table>
          <div v-if="logs.length === 0" class="px-5 py-8 text-center text-sm text-muted-foreground">暂无算力流水</div>
        </div>
      </section>
    </div>
  </AppShell>
</template>
