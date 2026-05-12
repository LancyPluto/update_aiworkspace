<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { RouterLink } from "vue-router"
import { ArrowRight, CheckCircle2, Clock, Store, Wallet, ListChecks } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import { fetchCreditAccount } from "@/api/creditApi"
import { fetchTools } from "@/api/toolApi"
import { fetchTasks } from "@/api/taskApi"
import type { CreditAccount, TaskDetail, ToolSummary } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { userRoutes } from "@/router/userRoutes"

const auth = useAuthStore()
const loading = ref(false)
const credit = ref<CreditAccount | null>(null)
const tools = ref<ToolSummary[]>([])
const tasks = ref<TaskDetail[]>([])

const successCount = computed(() => tasks.value.filter(task => task.status === "SUCCESS").length)
const runningCount = computed(() =>
  tasks.value.filter(task => ["CREATED", "QUEUED", "PROCESSING", "RETRYING"].includes(task.status)).length,
)

async function loadDashboard() {
  loading.value = true
  try {
    const [creditRes, toolRes, taskRes] = await Promise.all([
      fetchCreditAccount({ token: auth.token }),
      fetchTools({ token: auth.token, query: { pageNo: 1, pageSize: 6 } }),
      fetchTasks({ token: auth.token, query: { pageNo: 1, pageSize: 6 } }),
    ])
    credit.value = creditRes
    tools.value = toolRes.list
    tasks.value = taskRes.list
  } finally {
    loading.value = false
  }
}

onMounted(loadDashboard)
</script>

<template>
  <AppShell title="工作台" description="从后端同步你的工具、任务与算力概览">
    <div class="space-y-6 px-6 py-6">
      <div class="grid gap-4 md:grid-cols-4">
        <div class="rounded-lg border border-border bg-card p-4">
          <div class="flex items-center justify-between text-sm text-muted-foreground">
            <span>可用算力</span>
            <Wallet class="h-4 w-4 text-primary" />
          </div>
          <p class="mt-3 text-2xl font-semibold">{{ credit?.available ?? "--" }}</p>
        </div>
        <div class="rounded-lg border border-border bg-card p-4">
          <div class="flex items-center justify-between text-sm text-muted-foreground">
            <span>已用算力</span>
            <CheckCircle2 class="h-4 w-4 text-primary" />
          </div>
          <p class="mt-3 text-2xl font-semibold">{{ credit?.totalConsumed ?? "--" }}</p>
        </div>
        <div class="rounded-lg border border-border bg-card p-4">
          <div class="flex items-center justify-between text-sm text-muted-foreground">
            <span>成功任务</span>
            <ListChecks class="h-4 w-4 text-primary" />
          </div>
          <p class="mt-3 text-2xl font-semibold">{{ successCount }}</p>
        </div>
        <div class="rounded-lg border border-border bg-card p-4">
          <div class="flex items-center justify-between text-sm text-muted-foreground">
            <span>进行中</span>
            <Clock class="h-4 w-4 text-primary" />
          </div>
          <p class="mt-3 text-2xl font-semibold">{{ runningCount }}</p>
        </div>
      </div>

      <div class="grid gap-6 xl:grid-cols-2">
        <section class="rounded-lg border border-border bg-card">
          <div class="flex items-center justify-between border-b border-border px-5 py-4">
            <h2 class="text-sm font-semibold">推荐工具</h2>
            <RouterLink :to="userRoutes.toolList" class="inline-flex items-center gap-1 text-xs text-primary">
              查看全部 <ArrowRight class="h-3 w-3" />
            </RouterLink>
          </div>
          <div class="divide-y divide-border">
            <RouterLink
              v-for="tool in tools"
              :key="tool.id"
              :to="userRoutes.toolDetail(tool.toolCode)"
              class="flex items-center justify-between px-5 py-4 hover:bg-secondary/60"
            >
              <span>
                <span class="block text-sm font-medium">{{ tool.toolName }}</span>
                <span class="text-xs text-muted-foreground">{{ tool.categoryName }}</span>
              </span>
              <span class="text-xs text-muted-foreground">{{ tool.estimatedCreditCost }} 算力</span>
            </RouterLink>
            <div v-if="!loading && tools.length === 0" class="px-5 py-8 text-center text-sm text-muted-foreground">
              暂无上线工具
            </div>
          </div>
        </section>

        <section class="rounded-lg border border-border bg-card">
          <div class="flex items-center justify-between border-b border-border px-5 py-4">
            <h2 class="text-sm font-semibold">最近任务</h2>
            <RouterLink :to="userRoutes.myTasks" class="inline-flex items-center gap-1 text-xs text-primary">
              查看任务 <ArrowRight class="h-3 w-3" />
            </RouterLink>
          </div>
          <div class="divide-y divide-border">
            <RouterLink
              v-for="task in tasks"
              :key="task.taskId"
              :to="task.status === 'SUCCESS' ? userRoutes.taskResult(String(task.taskId)) : userRoutes.taskStatus(String(task.taskId))"
              class="flex items-center justify-between px-5 py-4 hover:bg-secondary/60"
            >
              <span>
                <span class="block text-sm font-medium">{{ task.toolName }}</span>
                <span class="text-xs text-muted-foreground">{{ task.taskNo }}</span>
              </span>
              <span class="rounded bg-secondary px-2 py-1 text-xs">{{ task.status }}</span>
            </RouterLink>
            <div v-if="!loading && tasks.length === 0" class="px-5 py-8 text-center text-sm text-muted-foreground">
              暂无任务记录
            </div>
          </div>
        </section>
      </div>

      <RouterLink
        :to="userRoutes.toolList"
        class="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground"
      >
        <Store class="h-4 w-4" /> 去工具超市创建任务
      </RouterLink>
    </div>
  </AppShell>
</template>
