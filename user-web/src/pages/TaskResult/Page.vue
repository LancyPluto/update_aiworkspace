<script setup lang="ts">
import { ref, onMounted } from "vue"
import { RouterLink } from "vue-router"
import { ArrowLeft, ChevronRight } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import ResultRenderer from "@/components/ResultRenderer/ResultRenderer.vue"
import { userRoutes } from "@/router/userRoutes"
import { fetchTaskById } from "@/api/taskApi"
import type { TaskDetail } from "@/api/types"
import { useAuthStore } from "@/store/authStore"

const props = defineProps<{
  taskId: string
}>()

const auth = useAuthStore()

const task = ref<TaskDetail | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

const blocks = ref<{ type: "text" | "list"; title: string; content?: string; items?: string[] }[]>([])

onMounted(async () => {
  try {
    task.value = await fetchTaskById(props.taskId, { token: auth.token })
    // 解析结果
    if (task.value.result) {
      blocks.value = [
        {
          type: "text",
          title: "生成结果",
          content: task.value.result.contentText || "无结果内容",
        },
      ]
    }
  } catch (e) {
    error.value = (e as Error).message || "获取任务结果失败"
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <AppShell title="任务结果" :description="'任务 ' + (task?.taskNo ?? taskId) + ' · 生成输出'">
    <div class="px-6 py-6 max-w-4xl mx-auto space-y-6">
      <nav class="flex items-center gap-1.5 text-xs text-muted-foreground flex-wrap">
        <RouterLink :to="userRoutes.myTasks" class="hover:text-foreground inline-flex items-center gap-1">
          <ArrowLeft class="h-3 w-3" /> 我的任务
        </RouterLink>
        <ChevronRight class="h-3 w-3" />
        <span class="font-mono text-foreground">{{ task?.taskNo ?? taskId }}</span>
      </nav>

      <!-- 加载中 -->
      <div v-if="loading" class="flex justify-center py-12">
        <span class="text-sm text-muted-foreground">加载中…</span>
      </div>

      <!-- 错误 -->
      <div v-else-if="error" class="rounded-xl border border-destructive/30 bg-destructive/5 p-6 text-center">
        <p class="text-sm text-destructive">{{ error }}</p>
      </div>

      <!-- 结果为空 -->
      <div v-else-if="blocks.length === 0" class="flex justify-center py-12">
        <span class="text-sm text-muted-foreground">暂无结果数据</span>
      </div>

      <!-- 结果内容 -->
      <template v-else>
        <ResultRenderer :blocks="blocks as any" />
      </template>

      <!-- 操作按钮 -->
      <div class="flex flex-wrap gap-2 justify-center pt-2">
        <RouterLink
          :to="userRoutes.myTasks"
          class="inline-flex h-10 items-center justify-center rounded-md border border-border bg-background px-4 text-sm hover:bg-secondary"
        >
          返回我的任务
        </RouterLink>
        <RouterLink
          :to="userRoutes.toolList"
          class="inline-flex h-10 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:opacity-90"
        >
          再去工具超市
        </RouterLink>
      </div>
    </div>
  </AppShell>
</template>
