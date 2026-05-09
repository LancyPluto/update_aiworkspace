<script setup lang="ts">
import { ref, onMounted, computed } from "vue"
import { RouterLink } from "vue-router"
import {
  Filter,
  RotateCw,
  Eye,
  Loader2,
  CheckCircle2,
  XCircle,
  Clock,
} from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import TaskStatusTag from "@/components/TaskStatusTag/TaskStatusTag.vue"
import { userRoutes } from "@/router/userRoutes"
import { fetchTasks, cancelTask } from "@/api/taskApi"
import type { TaskDetail, TaskStatus } from "@/api/types"
import { useAuthStore } from "@/store/authStore"

const auth = useAuthStore()

const tasks = ref<TaskDetail[]>([])
const total = ref(0)
const loading = ref(false)
const currentPage = ref(1)

// 将后端 TaskStatus 映射到前端标签使用的状态
function mapStatus(s: TaskStatus): "running" | "success" | "failed" | "queued" {
  switch (s) {
    case "PROCESSING":
    case "RETRYING":
      return "running"
    case "SUCCESS":
      return "success"
    case "FAILED":
    case "TIMEOUT":
      return "failed"
    case "CREATED":
    case "QUEUED":
    default:
      return "queued"
  }
}

const statusFilters = computed(() => [
  { id: "all", label: "全部任务", count: total.value, icon: Filter },
  { id: "PROCESSING", label: "生成中", count: tasks.value.filter(t => t.status === "PROCESSING" || t.status === "RETRYING").length, icon: Loader2 },
  { id: "QUEUED", label: "排队中", count: tasks.value.filter(t => t.status === "QUEUED" || t.status === "CREATED").length, icon: Clock },
  { id: "SUCCESS", label: "已完成", count: tasks.value.filter(t => t.status === "SUCCESS").length, icon: CheckCircle2 },
  { id: "FAILED", label: "失败", count: tasks.value.filter(t => t.status === "FAILED" || t.status === "TIMEOUT" || t.status === "CANCELLED").length, icon: XCircle },
])

const selectedStatus = ref<string>("all")

async function loadTasks() {
  loading.value = true
  try {
    const query: Record<string, string | number | boolean | undefined> = {
      pageNo: currentPage.value,
      pageSize: 20,
    }
    if (selectedStatus.value !== "all") {
      query.status = selectedStatus.value
    }
    const res = await fetchTasks({
      token: auth.token,
      query: query as any,
    })
    tasks.value = res.list
    total.value = res.total
  } catch {
    tasks.value = []
    total.value = 0
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
    loadTasks()
  } catch {
    // 忽略错误
  }
}

onMounted(() => {
  loadTasks()
})
</script>

<template>
  <AppShell title="我的任务" description="查看所有 AI 生成任务的状态、结果与历史记录">
    <div class="px-6 py-6 space-y-5">
      <div class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
        <button
          v-for="s in statusFilters"
          :key="s.id"
          type="button"
          class="text-left rounded-lg border p-4 transition"
          :class="selectedStatus === s.id ? 'border-primary bg-primary/5' : 'border-border bg-card hover:border-primary/30'"
          @click="filterByStatus(s.id)"
        >
          <div class="flex items-center justify-between">
            <span class="text-xs text-muted-foreground">{{ s.label }}</span>
            <component :is="s.icon" class="h-3.5 w-3.5" :class="selectedStatus === s.id ? 'text-primary' : 'text-muted-foreground'" />
          </div>
          <p class="mt-2 text-2xl font-semibold">{{ s.count }}</p>
        </button>
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="flex justify-center py-12">
        <span class="text-sm text-muted-foreground">加载中…</span>
      </div>

      <!-- 空状态 -->
      <div v-else-if="tasks.length === 0" class="flex justify-center py-12">
        <span class="text-sm text-muted-foreground">暂无任务</span>
      </div>

      <!-- 任务列表 -->
      <div v-else class="space-y-3">
        <div
          v-for="task in tasks"
          :key="task.taskId"
          class="rounded-xl border border-border bg-card p-5 shadow-sm hover:border-primary/30 transition"
        >
          <div class="flex items-start justify-between gap-4 flex-wrap">
            <div class="flex-1 min-w-0 space-y-2">
              <div class="flex flex-wrap items-center gap-2">
                <TaskStatusTag :status="mapStatus(task.status)" />
                <h3 class="text-sm font-semibold truncate">{{ task.toolName }}</h3>
              </div>
              <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
                <span>任务 ID：<code class="text-foreground font-mono">{{ task.taskNo }}</code></span>
                <span>· 工具：<span class="text-foreground">{{ task.toolCode }}</span></span>
                <span>· {{ task.createdAt ? new Date(task.createdAt).toLocaleString() : '-' }}</span>
                <span v-if="task.finishedAt">· {{ new Date(task.finishedAt).toLocaleString() }} 完成</span>
              </div>

              <div v-if="mapStatus(task.status) === 'running'" class="space-y-1.5 max-w-md">
                <div class="flex items-center justify-between text-xs">
                  <span class="text-muted-foreground">{{ task.progressMessage || '处理中' }}</span>
                  <span v-if="task.progress != null" class="font-medium">{{ task.progress }}%</span>
                </div>
                <div v-if="task.progress != null" class="h-1.5 overflow-hidden rounded-full bg-secondary">
                  <div
                    class="h-full rounded-full bg-gradient-to-r from-primary to-chart-2"
                    :style="{ width: task.progress + '%' }"
                  />
                </div>
              </div>

              <div
                v-if="mapStatus(task.status) === 'failed'"
                class="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive"
              >
                <span class="font-medium">失败</span>
              </div>
            </div>

            <div class="flex flex-col gap-1.5 shrink-0">
              <template v-if="mapStatus(task.status) === 'success'">
                <RouterLink
                  :to="userRoutes.taskResult(String(task.taskId))"
                  class="inline-flex h-8 items-center justify-center gap-1.5 rounded-md border border-border bg-background px-3 text-xs hover:bg-secondary"
                >
                  <Eye class="h-3.5 w-3.5" /> 查看结果
                </RouterLink>
              </template>
              <template v-else-if="mapStatus(task.status) === 'failed' || mapStatus(task.status) === 'queued'">
                <button
                  type="button"
                  class="inline-flex h-8 items-center gap-1.5 rounded-md bg-primary px-3 text-xs text-primary-foreground"
                  @click="handleCancel(task.taskId)"
                >
                  <RotateCw class="h-3.5 w-3.5" /> 取消
                </button>
              </template>
              <template v-else>
                <RouterLink
                  :to="userRoutes.taskStatus(String(task.taskId))"
                  class="inline-flex h-8 items-center justify-center rounded-md border border-border bg-background px-3 text-xs hover:bg-secondary"
                >
                  查看进度
                </RouterLink>
                <button
                  type="button"
                  class="inline-flex h-8 items-center justify-center rounded-md border border-border px-3 text-xs"
                  @click="handleCancel(task.taskId)"
                >
                  取消任务
                </button>
              </template>
            </div>
          </div>
        </div>
      </div>
    </div>
  </AppShell>
</template>
