<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { RouterLink } from "vue-router"
import { Ban, CheckCircle2, Clock, Eye, Filter, Loader2, RotateCw, Trash2, XCircle } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import TaskStatusTag from "@/components/TaskStatusTag/TaskStatusTag.vue"
import { cancelTask, deleteTask, fetchTasks, regenerateTask } from "@/api/taskApi"
import type { ListTasksQuery, TaskDetail, TaskStatus } from "@/api/types"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { isFailedTaskStatus, taskProgressMessage, taskStatusDocLabel, taskStatusViewKind } from "@/utils/taskStatusLabels"

const PAGE_SIZE = 20
const MAX_FETCH_PAGE_SIZE = 100

const RUNNING_STATUSES: TaskStatus[] = ["PROCESSING", "RETRYING"]
const QUEUED_STATUSES: TaskStatus[] = ["QUEUED", "CREATED"]
const FAILED_STATUSES: TaskStatus[] = ["FAILED", "TIMEOUT"]

const auth = useAuthStore()
const tasks = ref<TaskDetail[]>([])
const listTotal = ref(0)
const loading = ref(false)
const error = ref("")
const currentPage = ref(1)
const selectedStatus = ref<string>("all")
const cancellingTaskIds = ref<Set<number>>(new Set())
const deletingTaskIds = ref<Set<number>>(new Set())
const regeneratingTaskIds = ref<Set<number>>(new Set())
const statusCounts = ref({
  all: 0,
  processing: 0,
  queued: 0,
  success: 0,
  failed: 0,
  cancelled: 0,
})

function canRegenerate(status: TaskStatus): boolean {
  return isFailedTaskStatus(status)
}

function canDelete(status: TaskStatus): boolean {
  return status === "FAILED" || status === "TIMEOUT" || status === "CANCELLED"
}

const statusFilters = computed(() => [
  { id: "all", label: "全部任务", count: statusCounts.value.all, icon: Filter },
  { id: "PROCESSING", label: "生成中", count: statusCounts.value.processing, icon: Loader2 },
  { id: "QUEUED", label: "排队中", count: statusCounts.value.queued, icon: Clock },
  { id: "SUCCESS", label: "已完成", count: statusCounts.value.success, icon: CheckCircle2 },
  { id: "FAILED", label: "失败", count: statusCounts.value.failed, icon: XCircle },
  { id: "CANCELLED", label: "已取消", count: statusCounts.value.cancelled, icon: Ban },
])

async function fetchStatusTotal(status?: TaskStatus): Promise<number> {
  const query: ListTasksQuery = { pageNo: 1, pageSize: 1 }
  if (status) query.status = status
  const response = await fetchTasks({ token: auth.token, query })
  return response.total
}

async function loadStatusCounts() {
  const [
    all,
    processing,
    retrying,
    queued,
    created,
    success,
    failed,
    timeout,
    cancelled,
  ] = await Promise.all([
    fetchStatusTotal(),
    fetchStatusTotal("PROCESSING"),
    fetchStatusTotal("RETRYING"),
    fetchStatusTotal("QUEUED"),
    fetchStatusTotal("CREATED"),
    fetchStatusTotal("SUCCESS"),
    fetchStatusTotal("FAILED"),
    fetchStatusTotal("TIMEOUT"),
    fetchStatusTotal("CANCELLED"),
  ])
  statusCounts.value = {
    all,
    processing: processing + retrying,
    queued: queued + created,
    success,
    failed: failed + timeout,
    cancelled,
  }
}

async function fetchAllByStatus(status: TaskStatus): Promise<TaskDetail[]> {
  const items: TaskDetail[] = []
  let pageNo = 1
  let total = 0
  do {
    const response = await fetchTasks({
      token: auth.token,
      query: { pageNo, pageSize: MAX_FETCH_PAGE_SIZE, status },
    })
    total = response.total
    items.push(...response.list)
    if (response.list.length === 0) break
    pageNo += 1
  } while (items.length < total)
  return items
}

async function loadMergedTasks(statuses: TaskStatus[], groupTotal: number) {
  if (groupTotal === 0) {
    tasks.value = []
    listTotal.value = 0
    return
  }
  const responses = await Promise.all(statuses.map((status) => fetchAllByStatus(status)))
  const merged = responses.flat().sort((a, b) => b.taskId - a.taskId)
  const offset = (currentPage.value - 1) * PAGE_SIZE
  tasks.value = merged.slice(offset, offset + PAGE_SIZE)
  listTotal.value = groupTotal
}

async function loadTasks() {
  loading.value = true
  error.value = ""
  try {
    if (selectedStatus.value === "all") {
      const response = await fetchTasks({
        token: auth.token,
        query: { pageNo: currentPage.value, pageSize: PAGE_SIZE },
      })
      tasks.value = response.list
      listTotal.value = response.total
    } else if (selectedStatus.value === "SUCCESS") {
      const response = await fetchTasks({
        token: auth.token,
        query: { pageNo: currentPage.value, pageSize: PAGE_SIZE, status: "SUCCESS" },
      })
      tasks.value = response.list
      listTotal.value = response.total
    } else if (selectedStatus.value === "PROCESSING") {
      await loadMergedTasks(RUNNING_STATUSES, statusCounts.value.processing)
    } else if (selectedStatus.value === "QUEUED") {
      await loadMergedTasks(QUEUED_STATUSES, statusCounts.value.queued)
    } else if (selectedStatus.value === "FAILED") {
      await loadMergedTasks(FAILED_STATUSES, statusCounts.value.failed)
    } else if (selectedStatus.value === "CANCELLED") {
      const response = await fetchTasks({
        token: auth.token,
        query: { pageNo: currentPage.value, pageSize: PAGE_SIZE, status: "CANCELLED" },
      })
      tasks.value = response.list
      listTotal.value = response.total
    } else {
      tasks.value = []
      listTotal.value = 0
    }
  } catch (err) {
    tasks.value = []
    listTotal.value = 0
    error.value = err instanceof Error ? err.message : "加载任务失败"
  } finally {
    loading.value = false
  }
}

async function refreshPage() {
  await loadStatusCounts()
  await loadTasks()
}

function filterByStatus(status: string) {
  selectedStatus.value = status
  currentPage.value = 1
  loadTasks()
}

async function handleCancel(taskId: number) {
  if (cancellingTaskIds.value.has(taskId)) return
  cancellingTaskIds.value = new Set([...cancellingTaskIds.value, taskId])
  try {
    await cancelTask(taskId, { token: auth.token })
    await refreshPage()
  } catch (err) {
    error.value = err instanceof Error ? err.message : "取消任务失败"
  } finally {
    const next = new Set(cancellingTaskIds.value)
    next.delete(taskId)
    cancellingTaskIds.value = next
  }
}

async function handleRegenerate(task: TaskDetail) {
  if (!canRegenerate(task.status) || regeneratingTaskIds.value.has(task.taskId)) return
  regeneratingTaskIds.value = new Set([...regeneratingTaskIds.value, task.taskId])
  try {
    await regenerateTask(
      task.taskId,
      {
        params: { ...(task.params || {}) },
        clientRequestId: `${task.taskNo || task.taskId}-retry-${Date.now()}`,
      },
      { token: auth.token },
    )
    await refreshPage()
  } catch (err) {
    error.value = err instanceof Error ? err.message : "重试任务失败"
  } finally {
    const next = new Set(regeneratingTaskIds.value)
    next.delete(task.taskId)
    regeneratingTaskIds.value = next
  }
}

async function handleDelete(taskId: number) {
  if (deletingTaskIds.value.has(taskId)) return
  deletingTaskIds.value = new Set([...deletingTaskIds.value, taskId])
  try {
    await deleteTask(taskId, { token: auth.token })
    await refreshPage()
  } catch (err) {
    error.value = err instanceof Error ? err.message : "删除任务失败"
  } finally {
    const next = new Set(deletingTaskIds.value)
    next.delete(taskId)
    deletingTaskIds.value = next
  }
}

onMounted(refreshPage)
</script>

<template>
  <AppShell title="我的任务" description="查看所有 AI 生成任务的状态、结果与历史记录">
    <div class="space-y-5 px-6 py-6">
      <div v-if="error" class="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
        {{ error }}
      </div>

      <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
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
                <TaskStatusTag :status="taskStatusViewKind(task.status)" :label="taskStatusDocLabel(task.status)" />
                <h3 class="truncate text-sm font-semibold">{{ task.toolName }}</h3>
              </div>
              <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
                <span>任务 ID：<code class="font-mono text-foreground">{{ task.taskNo }}</code></span>
                <span>工具：<span class="text-foreground">{{ task.toolCode }}</span></span>
                <span>{{ task.createdAt ? new Date(task.createdAt).toLocaleString() : "-" }}</span>
                <span v-if="task.finishedAt">{{ new Date(task.finishedAt).toLocaleString() }} 完成</span>
              </div>
              <div v-if="taskStatusViewKind(task.status) === 'running'" class="max-w-md space-y-1.5">
                <div class="flex items-center justify-between text-xs">
                  <span class="text-muted-foreground">{{ taskProgressMessage(task.status, task.progressMessage) }}</span>
                  <span v-if="task.progress != null" class="font-medium">{{ task.progress }}%</span>
                </div>
                <div v-if="task.progress != null" class="h-1.5 overflow-hidden rounded-full bg-secondary">
                  <div class="h-full rounded-full bg-primary" :style="{ width: task.progress + '%' }" />
                </div>
              </div>
            </div>

            <div class="flex shrink-0 flex-col gap-1.5">
              <RouterLink
                v-if="taskStatusViewKind(task.status) === 'success'"
                :to="userRoutes.taskResult(String(task.taskId))"
                class="inline-flex h-8 items-center justify-center gap-1.5 rounded-md border border-border bg-background px-3 text-xs hover:bg-secondary"
              >
                <Eye class="h-3.5 w-3.5" /> 查看结果
              </RouterLink>
              <RouterLink
                v-else-if="taskStatusViewKind(task.status) === 'running'"
                :to="userRoutes.taskStatus(String(task.taskId))"
                class="inline-flex h-8 items-center justify-center rounded-md border border-border bg-background px-3 text-xs hover:bg-secondary"
              >
                查看进度
              </RouterLink>
              <button
                v-if="taskStatusViewKind(task.status) === 'queued' || taskStatusViewKind(task.status) === 'running'"
                type="button"
                class="inline-flex h-8 items-center justify-center gap-1.5 rounded-md border border-border px-3 text-xs"
                :disabled="cancellingTaskIds.has(task.taskId)"
                @click="handleCancel(task.taskId)"
              >
                <Loader2 v-if="cancellingTaskIds.has(task.taskId)" class="h-3.5 w-3.5 animate-spin" />
                <RotateCw v-else class="h-3.5 w-3.5" />
                {{ cancellingTaskIds.has(task.taskId) ? "取消中" : "取消任务" }}
              </button>
              <button
                v-if="canRegenerate(task.status)"
                type="button"
                class="inline-flex h-8 items-center justify-center gap-1.5 rounded-md border border-border px-3 text-xs"
                :disabled="regeneratingTaskIds.has(task.taskId) || deletingTaskIds.has(task.taskId)"
                @click="handleRegenerate(task)"
              >
                <Loader2 v-if="regeneratingTaskIds.has(task.taskId)" class="h-3.5 w-3.5 animate-spin" />
                <RotateCw v-else class="h-3.5 w-3.5" />
                {{ regeneratingTaskIds.has(task.taskId) ? "重试中" : "重试" }}
              </button>
              <button
                v-if="canDelete(task.status)"
                type="button"
                class="inline-flex h-8 items-center justify-center gap-1.5 rounded-md border border-border px-3 text-xs text-destructive"
                :disabled="deletingTaskIds.has(task.taskId) || regeneratingTaskIds.has(task.taskId)"
                @click="handleDelete(task.taskId)"
              >
                <Loader2 v-if="deletingTaskIds.has(task.taskId)" class="h-3.5 w-3.5 animate-spin" />
                <Trash2 v-else class="h-3.5 w-3.5" />
                {{ deletingTaskIds.has(task.taskId) ? "删除中" : "删除" }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </AppShell>
</template>
