<script setup lang="ts">
import { onMounted, ref } from "vue"
import { RouterLink } from "vue-router"
import { ArrowLeft, ChevronRight } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import ResultRenderer from "@/components/ResultRenderer/ResultRenderer.vue"
import { fetchTaskById } from "@/api/taskApi"
import type { TaskDetail } from "@/api/types"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"

const props = defineProps<{ taskId: string }>()
const auth = useAuthStore()
const task = ref<TaskDetail | null>(null)
const loading = ref(true)
const error = ref("")
const blocks = ref<{ type: "text"; title: string; content: string }[]>([])

onMounted(async () => {
  loading.value = true
  error.value = ""
  try {
    task.value = await fetchTaskById(props.taskId, { token: auth.token })
    if (task.value.result?.contentText) {
      blocks.value = [{ type: "text", title: "生成结果", content: task.value.result.contentText }]
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : "获取任务结果失败"
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <AppShell title="任务结果" :description="'任务 ' + (task?.taskNo ?? taskId) + ' · 生成输出'">
    <div class="mx-auto max-w-4xl space-y-6 px-6 py-6">
      <nav class="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        <RouterLink :to="userRoutes.myTasks" class="inline-flex items-center gap-1 hover:text-foreground">
          <ArrowLeft class="h-3 w-3" /> 我的任务
        </RouterLink>
        <ChevronRight class="h-3 w-3" />
        <span class="font-mono text-foreground">{{ task?.taskNo ?? taskId }}</span>
      </nav>

      <div v-if="loading" class="flex justify-center py-12 text-sm text-muted-foreground">加载中...</div>

      <div v-else-if="error" class="rounded-xl border border-destructive/30 bg-destructive/5 p-6 text-center">
        <p class="text-sm text-destructive">{{ error }}</p>
      </div>

      <div v-else-if="blocks.length === 0" class="rounded-xl border border-dashed border-border p-8 text-center text-sm text-muted-foreground">
        暂无结果数据
      </div>

      <ResultRenderer v-else :blocks="blocks as any" />

      <div class="flex flex-wrap justify-center gap-2 pt-2">
        <RouterLink :to="userRoutes.myTasks" class="inline-flex h-10 items-center justify-center rounded-md border border-border bg-background px-4 text-sm hover:bg-secondary">
          返回我的任务
        </RouterLink>
        <RouterLink :to="userRoutes.toolList" class="inline-flex h-10 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:opacity-90">
          再去工具超市
        </RouterLink>
      </div>
    </div>
  </AppShell>
</template>
