<script setup lang="ts">
import { RouterLink } from "vue-router"
import { ArrowLeft, ChevronRight, Info, Loader2, Sparkles, Zap, CheckCircle2 } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import DynamicForm from "@/components/DynamicForm/DynamicForm.vue"
import TaskStatusTag from "@/components/TaskStatusTag/TaskStatusTag.vue"
import { userRoutes } from "@/router/userRoutes"

defineProps<{
  id: string
}>()
</script>

<template>
  <AppShell title="电商商品文案生成" description="填写参数，AI 将为你生成候选文案">
    <div class="px-6 py-6 max-w-7xl mx-auto space-y-5">
      <nav class="flex items-center gap-1.5 text-xs text-muted-foreground flex-wrap">
        <RouterLink :to="userRoutes.toolList" class="hover:text-foreground inline-flex items-center gap-1">
          <ArrowLeft class="h-3 w-3" /> 工具超市
        </RouterLink>
        <ChevronRight class="h-3 w-3" />
        <RouterLink :to="'/tools/' + id" class="hover:text-foreground">电商商品文案生成</RouterLink>
        <ChevronRight class="h-3 w-3" />
        <span class="text-foreground">使用</span>
      </nav>

      <div class="grid gap-6 lg:grid-cols-[1fr_320px]">
        <div class="space-y-5">
          <DynamicForm />

          <div class="rounded-xl border border-primary/20 bg-accent/30 p-6 shadow-sm">
            <div class="flex items-center justify-between mb-3 flex-wrap gap-2">
              <div class="flex items-center gap-2">
                <Loader2 class="h-4 w-4 text-primary animate-spin" />
                <h3 class="text-sm font-semibold">生成进度</h3>
                <TaskStatusTag status="running" />
              </div>
              <RouterLink
                :to="userRoutes.taskStatus('T-20260509-1042')"
                class="text-xs text-primary hover:underline font-mono"
              >
                任务 ID：T-20260509-1042
              </RouterLink>
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
        </div>

        <aside class="space-y-4">
          <div class="rounded-xl border border-border bg-card p-5 shadow-sm lg:sticky lg:top-20">
            <h3 class="text-sm font-semibold mb-3 flex items-center gap-2">
              <Sparkles class="h-4 w-4 text-primary" /> 任务说明
            </h3>
            <ul class="space-y-2.5 text-xs text-muted-foreground leading-relaxed">
              <li class="flex gap-2">
                <span class="text-primary mt-0.5">•</span>
                AI 将根据所选平台规则，自动调整字数与关键词密度
              </li>
              <li class="flex gap-2">
                <span class="text-primary mt-0.5">•</span>
                生成后可一键复制、二次润色或重新生成
              </li>
              <li class="flex gap-2">
                <span class="text-primary mt-0.5">•</span>
                生成结果将保存至
                <RouterLink :to="userRoutes.myTasks" class="text-primary hover:underline">我的任务</RouterLink>
              </li>
            </ul>

            <div class="my-4 h-px bg-border" />

            <h3 class="text-sm font-semibold mb-3">算力消耗预估</h3>
            <ul class="space-y-2 text-sm">
              <li class="flex items-center justify-between">
                <span class="text-muted-foreground">基础生成（1 条）</span>
                <span class="font-medium">12 算力</span>
              </li>
              <li class="flex items-center justify-between">
                <span class="text-muted-foreground">多条候选 + 排序</span>
                <span class="font-medium">+8 算力</span>
              </li>
            </ul>
            <div class="mt-3 flex items-center justify-between rounded-lg bg-primary/5 p-3 border border-primary/20">
              <div class="flex items-center gap-1.5">
                <Zap class="h-4 w-4 text-warning" />
                <span class="text-sm font-medium">本次共消耗</span>
              </div>
              <span class="text-lg font-semibold text-primary">24 算力</span>
            </div>

            <button
              type="button"
              class="mt-4 inline-flex h-11 w-full items-center justify-center gap-2 rounded-md bg-primary text-sm font-medium text-primary-foreground hover:opacity-90"
            >
              <Sparkles class="h-4 w-4" /> 创建生成任务
            </button>

            <div class="mt-3 flex items-center gap-2 rounded-md bg-secondary/60 p-2 text-[11px] text-muted-foreground">
              <Info class="h-3.5 w-3.5 shrink-0" />
              <span>当前剩余 12,480 算力</span>
            </div>
          </div>
        </aside>
      </div>
    </div>
  </AppShell>
</template>
