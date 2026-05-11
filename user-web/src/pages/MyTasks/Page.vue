<script setup lang="ts">
  import { RouterLink } from "vue-router"
  import {
    Search,
    Filter,
    Copy,
    Heart,
    RotateCw,
    Eye,
    Download,
    MoreHorizontal,
    Calendar,
    Loader2,
    CheckCircle2,
    XCircle,
    Clock,
  } from "lucide-vue-next"
  import AppShell from "@/components/AppShell.vue"
  import TaskStatusTag from "@/components/TaskStatusTag/TaskStatusTag.vue"
  import { userRoutes } from "@/router/userRoutes"

  type TaskRunStatus = "running" | "success" | "failed" | "queued"

<<<<<<< Updated upstream
  type TaskItem = {
    id: string
    name: string
    tool: string
    status: TaskRunStatus
    progress?: number
    cost: number
    time: string
    creator: string
    preview: string
    error?: string
=======
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
    const response = await fetchTasks({ query: query as any })
    tasks.value = response.list
    total.value = response.total
  } catch (err) {
    tasks.value = []
    total.value = 0
    error.value = err instanceof Error ? err.message : "加载任务失败"
  } finally {
    loading.value = false
>>>>>>> Stashed changes
  }

  const statusFilters = [
    { id: "all", label: "全部任务", count: 248, icon: Filter },
    { id: "running", label: "生成中", count: 4, icon: Loader2 },
    { id: "queued", label: "排队中", count: 2, icon: Clock },
    { id: "success", label: "已完成", count: 232, icon: CheckCircle2 },
    { id: "failed", label: "失败", count: 10, icon: XCircle },
  ]

<<<<<<< Updated upstream
  const tasks: TaskItem[] = [
    {
      id: "T-20260509-1042",
      name: "618 大促主推款详情页文案",
      tool: "电商商品文案生成",
      status: "running",
      progress: 62,
      cost: 24,
      time: "10 分钟前",
      creator: "李运营",
      preview: "盛夏的海风，是连衣裙最好的搭档……",
    },
    {
      id: "T-20260509-0820",
      name: "连衣裙主图 - 海边场景 ×4",
      tool: "商品主图生成",
      status: "queued",
      cost: 240,
      time: "1 小时前",
      creator: "王设计",
      preview: "队列位置 #2 · 预计 2 分钟后开始",
    },
    {
      id: "T-20260508-1815",
      name: "退换货客服话术（高情绪）",
      tool: "客服话术优化",
      status: "success",
      cost: 8,
      time: "昨天 18:15",
      creator: "张客服",
      preview: "亲，非常抱歉给您带来了这样的体验……",
    },
    {
      id: "T-20260507-2210",
      name: "短视频脚本 · 60s 口播",
      tool: "短视频口播脚本",
      status: "failed",
      cost: 0,
      time: "2 天前",
      creator: "李运营",
      preview: "—",
      error: "输入素材识别失败：上传的参考视频时长超过 5 分钟",
    },
  ]
=======
async function handleCancel(taskId: number) {
  try {
    await cancelTask(taskId)
    await loadTasks()
  } catch (err) {
    error.value = err instanceof Error ? err.message : "取消任务失败"
  }
}

onMounted(loadTasks)
>>>>>>> Stashed changes
</script>

<template>
  <AppShell title="我的任务" description="查看所有 AI 生成任务的状态、结果与历史记录">
    <div class="px-6 py-6 space-y-5">
      <div class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
        <button
          v-for="(s, i) in statusFilters"
          :key="s.id"
          type="button"
          class="text-left rounded-lg border p-4 transition"
          :class="i === 0 ? 'border-primary bg-primary/5' : 'border-border bg-card hover:border-primary/30'"
        >
          <div class="flex items-center justify-between">
            <span class="text-xs text-muted-foreground">{{ s.label }}</span>
            <component :is="s.icon" class="h-3.5 w-3.5" :class="i === 0 ? 'text-primary' : 'text-muted-foreground'" />
          </div>
          <p class="mt-2 text-2xl font-semibold">{{ s.count }}</p>
        </button>
      </div>

      <div class="rounded-xl border border-border bg-card p-4 shadow-sm">
        <div class="flex flex-col md:flex-row md:items-center gap-3">
          <div class="relative flex-1">
            <Search class="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground pointer-events-none" />
            <input
              class="flex h-10 w-full rounded-md border border-border bg-background pl-9 pr-3 text-sm"
              placeholder="按任务名称、工具或任务 ID 搜索"
            />
          </div>
          <div class="flex flex-wrap items-center gap-2">
            <button
              type="button"
              class="inline-flex h-9 items-center rounded-md border border-border bg-background px-3 text-xs"
            >
              <Calendar class="h-3.5 w-3.5 mr-1" /> 最近 7 天
            </button>
            <button
              type="button"
              class="inline-flex h-9 items-center rounded-md border border-border bg-background px-3 text-xs"
            >
              <Filter class="h-3.5 w-3.5 mr-1" /> 全部工具
            </button>
            <button
              type="button"
              class="inline-flex h-9 items-center rounded-md bg-primary px-3 text-xs text-primary-foreground"
            >
              <Download class="h-3.5 w-3.5 mr-1" /> 导出记录
            </button>
          </div>
        </div>
      </div>

      <div class="space-y-3">
        <div
          v-for="task in tasks"
          :key="task.id"
          class="rounded-xl border border-border bg-card p-5 shadow-sm hover:border-primary/30 transition"
        >
          <div class="flex items-start justify-between gap-4 flex-wrap">
            <div class="flex-1 min-w-0 space-y-2">
              <div class="flex flex-wrap items-center gap-2">
                <TaskStatusTag :status="task.status" />
                <h3 class="text-sm font-semibold truncate">{{ task.name }}</h3>
              </div>
              <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
                <span>任务 ID：<code class="text-foreground font-mono">{{ task.id }}</code></span>
                <span>· 工具：<span class="text-foreground">{{ task.tool }}</span></span>
                <span>· 创建人：{{ task.creator }}</span>
                <span>· {{ task.time }}</span>
                <span v-if="task.cost > 0"
                  >· 消耗 <span class="text-foreground font-medium">{{ task.cost }}</span> 算力</span
                >
              </div>

              <div v-if="task.status === 'running' && task.progress != null" class="space-y-1.5 max-w-md">
                <div class="flex items-center justify-between text-xs">
                  <span class="text-muted-foreground">生成进度</span>
                  <span class="font-medium">{{ task.progress }}%</span>
                </div>
                <div class="h-1.5 overflow-hidden rounded-full bg-secondary">
                  <div
                    class="h-full rounded-full bg-gradient-to-r from-primary to-chart-2"
                    :style="{ width: task.progress + '%' }"
                  />
                </div>
              </div>

              <div
                v-if="task.status === 'failed' && task.error"
                class="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive"
              >
                <span class="font-medium">失败原因：</span>{{ task.error }}
              </div>
              <div
                v-else-if="task.status !== 'failed'"
                class="rounded-md bg-secondary/60 p-3 text-xs text-foreground/80 line-clamp-2 leading-relaxed"
              >
                {{ task.preview }}
              </div>
            </div>

            <div class="flex flex-col gap-1.5 shrink-0">
              <template v-if="task.status === 'success'">
                <RouterLink
                  :to="userRoutes.taskResult(task.id)"
                  class="inline-flex h-8 items-center justify-center gap-1.5 rounded-md border border-border bg-background px-3 text-xs hover:bg-secondary"
                >
                  <Eye class="h-3.5 w-3.5" /> 查看结果
                </RouterLink>
                <div class="flex gap-1.5">
                  <button
                    type="button"
                    class="inline-flex h-8 w-8 items-center justify-center rounded-md hover:bg-secondary text-muted-foreground"
                  >
                    <Copy class="h-3.5 w-3.5" />
                  </button>
                  <button
                    type="button"
                    class="inline-flex h-8 w-8 items-center justify-center rounded-md hover:bg-secondary text-muted-foreground"
                  >
                    <Heart class="h-3.5 w-3.5" />
                  </button>
                  <button
                    type="button"
                    class="inline-flex h-8 w-8 items-center justify-center rounded-md hover:bg-secondary text-muted-foreground"
                  >
                    <RotateCw class="h-3.5 w-3.5" />
                  </button>
                  <button
                    type="button"
                    class="inline-flex h-8 w-8 items-center justify-center rounded-md hover:bg-secondary text-muted-foreground"
                  >
                    <MoreHorizontal class="h-3.5 w-3.5" />
                  </button>
                </div>
              </template>
              <button
                v-else-if="task.status === 'failed'"
                type="button"
                class="inline-flex h-8 items-center gap-1.5 rounded-md bg-primary px-3 text-xs text-primary-foreground"
              >
                <RotateCw class="h-3.5 w-3.5" /> 重新生成
              </button>
              <template v-else-if="task.status === 'running' || task.status === 'queued'">
                <RouterLink
                  :to="userRoutes.taskStatus(task.id)"
                  class="inline-flex h-8 items-center justify-center rounded-md border border-border bg-background px-3 text-xs hover:bg-secondary"
                >
                  查看进度
                </RouterLink>
                <button
                  type="button"
                  class="inline-flex h-8 items-center justify-center rounded-md border border-border px-3 text-xs"
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
