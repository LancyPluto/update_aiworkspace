<script setup lang="ts">
import { RouterLink } from "vue-router"
import { ArrowLeft, ChevronRight, CheckCircle2, Loader2 } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import TaskStatusTag from "@/components/TaskStatusTag/TaskStatusTag.vue"
import { userRoutes } from "@/router/userRoutes"

defineProps<{
  taskId: string
}>()
</script>

<template>
  <AppShell title="任务状态" :description="'任务 ' + taskId + ' · 实时进度'">
    <div class="px-6 py-6 max-w-4xl mx-auto space-y-5">
      <nav class="flex items-center gap-1.5 text-xs text-muted-foreground flex-wrap">
        <RouterLink :to="userRoutes.myTasks" class="hover:text-foreground inline-flex items-center gap-1">
          <ArrowLeft class="h-3 w-3" /> 我的任务
        </RouterLink>
        <ChevronRight class="h-3 w-3" />
        <span class="text-foreground">任务进度</span>
      </nav>

      <div class="rounded-xl border border-primary/20 bg-accent/30 p-6 shadow-sm">
        <div class="flex items-center justify-between mb-3 flex-wrap gap-2">
          <div class="flex items-center gap-2">
            <Loader2 class="h-4 w-4 text-primary animate-spin" />
            <h3 class="text-sm font-semibold">生成进度</h3>
            <TaskStatusTag status="running" />
          </div>
          <span class="text-xs text-muted-foreground font-mono">任务 ID：{{ taskId }}</span>
        </div>
        <div class="space-y-3">
          <div class="flex items-center justify-between text-xs">
            <span class="text-muted-foreground">正在生成第 3 / 5 条候选文案…</span>
            <span class="font-medium">62%</span>
          </div>
          <div class="h-2 overflow-hidden rounded-full bg-secondary">
            <div class="h-full w-[62%] rounded-full bg-gradient-to-r from-primary to-chart-2" />
          </div>
          <ul class="space-y-1.5 text-xs">
            <li class="flex items-center gap-2 text-foreground">
              <CheckCircle2 class="h-3.5 w-3.5 text-success" /> 解析输入参数与平台规则
            </li>
            <li class="flex items-center gap-2 text-foreground">
              <CheckCircle2 class="h-3.5 w-3.5 text-success" /> 调用模型生成候选文案
            </li>
            <li class="flex items-center gap-2 text-primary font-medium">
              <Loader2 class="h-3.5 w-3.5 animate-spin" /> 对候选文案进行打分排序
            </li>
            <li class="flex items-center gap-2 text-muted-foreground">
              <span class="h-3.5 w-3.5 rounded-full border border-border" /> 格式化输出与合规检查
            </li>
          </ul>
        </div>
      </div>

      <p class="text-xs text-muted-foreground text-center">
        完成后可在
        <RouterLink :to="userRoutes.taskResult(taskId)" class="text-primary hover:underline">任务结果</RouterLink>
        查看输出。
      </p>
    </div>
  </AppShell>
</template>
