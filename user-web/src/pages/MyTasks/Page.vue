<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { RouterLink } from "vue-router"
import { CheckCircle2, Clock, Eye, Filter, Loader2, RotateCw, XCircle } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import TaskStatusTag from "@/components/TaskStatusTag/TaskStatusTag.vue"
import { cancelTask, fetchTasks } from "@/api/taskApi"
import type { TaskDetail, TaskStatus } from "@/api/types"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"

const auth = useAuthStore()
const tasks = ref<TaskDetail[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref("")
const currentPage = ref(1)
const selectedStatus = ref<string>("all")

function mapStatus(status: TaskStatus): "running" | "success" | "failed" | "queued" {
  if (status === "PROCESSING" || status === "RETRYING") return "running"
  if (status === "SUCCESS") return "success"
  if (status === "FAILED" || status === "TIMEOUT" || status === "CANCELLED") return "failed"
  return "queued"
}

const statusFilters = computed(() => [
  { id: "all", label: "全部任务", count: total.value, icon: Filter },
  { id: "PROCESSING", label: "生成中", count: tasks.value.filter((task) => task.status === "PROCESSING" || task.status === "RETRYING").length, icon: Loader2 },
  { id: "QUEUED", label: "排队中", count: tasks.value.filter((task) => task.status === "QUEUED" || task.status === "CREATED").length, icon: Clock },
  { id: "SUCCESS", label: "已完成", count: tasks.value.filter((task) => task.status === "SUCCESS").length, icon: CheckCircle2 },
  { id: "FAILED", label: "失败", count: tasks.value.filter((task) => task.status === "FAILED" || task.status === "TIMEOUT" || task.status === "CANCELLED").length, icon: XCircle },
])

async function loadTasks() {
  loading.value = true
  error.value = ""
  try {
    const query: Record<string, string | number | boolean | undefined> = {
      pageNo: currentPage.value,
      pageSize: 20,
    }
    if (selectedStatus.value !== "all") {
      query.status = selectedStatus.value
    }
    const response = await fetchTasks({ token: auth.token, query: query as any })
    tasks.value = response.list
    total.value = response.total
  } catch (err) {
    tasks.value = []
    total.value = 0
    error.value = err instanceof Error ? err.message : "加载任务失败"
  } finally {
    loading.value = false
  }
}

function filterByStatus(status: string) {
  selectedStatus.value = status
  currentPage.value = 1
  loadTasks()
}

async function handleCancel(taskId: number) {
  try {
    await cancelTask(taskId, { token: auth.token })
    await loadTasks()
  } catch (err) {
    error.value = err instanceof Error ? err.message : "取消任务失败"
  }
}

onMounted(loadTasks)
</script>

<template>
  <AppShell title="我的任务" description="查看所有 AI 生成任务的状态、结果与历史记录">
    <div class="space-y-5 px-6 py-6">
      <div v-if="error" class="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
        {{ error }}
      </div>

      <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
        <button
          v-for="status in statusFilters"
          :key="status.id"
          type="button"
          class="rounded-lg border p-4 text-left transition"
          :class="selectedStatus === status.id ? 'border-primary bg-primary/5' : 'border-border bg-card hover:border-primary/30'"
          @click="filterByStatus(status.id)"
        >
          <div class="flex items-center justify-between">
            <span class="text-xs text-muted-foreground">{{ status.label }}</span>
            <component :is="status.icon" class="h-3.5 w-3.5" :class="selectedStatus === status.id ? 'text-primary' : 'text-muted-foreground'" />
          </div>
          <p class="mt-2 text-2xl font-semibold">{{ status.count }}</p>
        </button>
      </div>

      <div v-if="loading" class="flex justify-center py-12 text-sm text-muted-foreground">加载中...</div>

      <div v-else-if="tasks.length === 0" class="flex justify-center py-12 text-sm text-muted-foreground">暂无任务</div>

      <div v-else class="space-y-3">
        <div v-for="task in tasks" :key="task.taskId" class="rounded-xl border border-border bg-card p-5 shadow-sm transition hover:border-primary/30">
          <div class="flex flex-wrap items-start justify-between gap-4">
            <div class="min-w-0 flex-1 space-y-2">
              <div class="flex flex-wrap items-center gap-2">
                <TaskStatusTag :status="mapStatus(task.status)" />
                <h3 class="truncate text-sm font-semibold">{{ task.toolName }}</h3>
              </div>
              <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
                <span>任务 ID：<code class="font-mono text-foreground">{{ task.taskNo }}</code></span>
                <span>工具：<span class="text-foreground">{{ task.toolCode }}</span></span>
                <span>{{ task.createdAt ? new Date(task.createdAt).toLocaleString() : "-" }}</span>
                <span v-if="task.finishedAt">{{ new Date(task.finishedAt).toLocaleString() }} 完成</span>
              </div>
              <div v-if="mapStatus(task.status) === 'running'" class="max-w-md space-y-1.5">
                <div class="flex items-center justify-between text-xs">
                  <span class="text-muted-foreground">{{ task.progressMessage || "处理中" }}</span>
                  <span v-if="task.progress != null" class="font-medium">{{ task.progress }}%</span>
                </div>
                <div v-if="task.progress != null" class="h-1.5 overflow-hidden rounded-full bg-secondary">
                  <div class="h-full rounded-full bg-primary" :style="{ width: task.progress + '%' }" />
                </div>
              </div>
            </div>

            <div class="flex shrink-0 flex-col gap-1.5">
              <RouterLink
                v-if="mapStatus(task.status) === 'success'"
                :to="userRoutes.taskResult(String(task.taskId))"
                class="inline-flex h-8 items-center justify-center gap-1.5 rounded-md border border-border bg-background px-3 text-xs hover:bg-secondary"
              >
                <Eye class="h-3.5 w-3.5" /> 查看结果
              </RouterLink>
              <RouterLink
                v-else-if="mapStatus(task.status) === 'running'"
                :to="userRoutes.taskStatus(String(task.taskId))"
                class="inline-flex h-8 items-center justify-center rounded-md border border-border bg-background px-3 text-xs hover:bg-secondary"
              >
                查看进度
              </RouterLink>
              <button
                v-if="mapStatus(task.status) === 'queued' || mapStatus(task.status) === 'running'"
                type="button"
                class="inline-flex h-8 items-center justify-center gap-1.5 rounded-md border border-border px-3 text-xs"
                @click="handleCancel(task.taskId)"
              >
                <RotateCw class="h-3.5 w-3.5" /> 取消任务
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </AppShell>
</template>
