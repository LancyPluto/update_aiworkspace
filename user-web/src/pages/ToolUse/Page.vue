<script setup lang="ts">
import { ref, onMounted, computed } from "vue"
import { RouterLink, useRouter } from "vue-router"
import { ArrowLeft, ChevronRight, Info, Loader2, Sparkles, Zap } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import DynamicForm from "@/components/DynamicForm/DynamicForm.vue"
import TaskStatusTag from "@/components/TaskStatusTag/TaskStatusTag.vue"
import { userRoutes } from "@/router/userRoutes"
import { fetchToolByCode, createTask } from "@/api"
import type { ToolDetail } from "@/api/types"
import { useAuthStore } from "@/store/authStore"

const props = defineProps<{
  id: string
}>()

const router = useRouter()
const auth = useAuthStore()

const tool = ref<ToolDetail | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const submitting = ref(false)
const createdTask = ref<{ taskId: number; taskNo: string } | null>(null)

const title = computed(() => tool.value?.toolName ?? `工具 · ${props.id}`)

onMounted(async () => {
  try {
    tool.value = await fetchToolByCode(props.id, { token: auth.token })
  } catch (e) {
    error.value = (e as Error).message || "加载工具详情失败"
  } finally {
    loading.value = false
  }
})

async function handleCreateTask() {
  if (!tool.value) return
  submitting.value = true
  try {
    const res = await createTask(
      {
        toolCode: tool.value.toolCode,
        params: {},
      },
      { token: auth.token },
    )
    createdTask.value = { taskId: res.taskId, taskNo: res.taskNo }
    // 跳转到任务状态页
    router.push(userRoutes.taskStatus(String(res.taskId)))
  } catch (e) {
    error.value = (e as Error).message || "创建任务失败"
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AppShell :title="title" description="填写参数，AI 将为你生成候选结果">
    <div class="px-6 py-6 max-w-7xl mx-auto space-y-5">
      <nav class="flex items-center gap-1.5 text-xs text-muted-foreground flex-wrap">
        <RouterLink :to="userRoutes.toolList" class="hover:text-foreground inline-flex items-center gap-1">
          <ArrowLeft class="h-3 w-3" /> 工具超市
        </RouterLink>
        <ChevronRight class="h-3 w-3" />
        <RouterLink :to="'/tools/' + id" class="hover:text-foreground">{{ title }}</RouterLink>
        <ChevronRight class="h-3 w-3" />
        <span class="text-foreground">使用</span>
      </nav>

      <!-- 加载中 -->
      <div v-if="loading" class="flex justify-center py-12">
        <span class="text-sm text-muted-foreground">加载中…</span>
      </div>

      <!-- 错误 -->
      <div v-else-if="error" class="rounded-xl border border-destructive/30 bg-destructive/5 p-6 text-center">
        <p class="text-sm text-destructive">{{ error }}</p>
      </div>

      <template v-else-if="tool">
        <div class="grid gap-6 lg:grid-cols-[1fr_320px]">
          <div class="space-y-5">
            <DynamicForm :fields="tool.fields" />

            <!-- 进度展示（任务创建后） -->
            <div v-if="createdTask" class="rounded-xl border border-primary/20 bg-accent/30 p-6 shadow-sm">
              <div class="flex items-center justify-between mb-3 flex-wrap gap-2">
                <div class="flex items-center gap-2">
                  <Loader2 class="h-4 w-4 text-primary animate-spin" />
                  <h3 class="text-sm font-semibold">生成进度</h3>
                  <TaskStatusTag status="running" />
                </div>
                <RouterLink
                  :to="userRoutes.taskStatus(String(createdTask.taskId))"
                  class="text-xs text-primary hover:underline font-mono"
                >
                  任务 ID：{{ createdTask.taskNo }}
                </RouterLink>
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
                  AI 将根据输入参数生成结果内容
                </li>
                <li class="flex gap-2">
                  <span class="text-primary mt-0.5">•</span>
                  生成结果将保存至
                  <RouterLink :to="userRoutes.myTasks" class="text-primary hover:underline">我的任务</RouterLink>
                </li>
              </ul>

              <div class="my-4 h-px bg-border" />

              <h3 class="text-sm font-semibold mb-3">算力消耗预估</h3>
              <div class="mt-3 flex items-center justify-between rounded-lg bg-primary/5 p-3 border border-primary/20">
                <div class="flex items-center gap-1.5">
                  <Zap class="h-4 w-4 text-warning" />
                  <span class="text-sm font-medium">本次共消耗</span>
                </div>
                <span class="text-lg font-semibold text-primary">{{ tool.estimatedCreditCost }} 算力</span>
              </div>

              <button
                type="button"
                class="mt-4 inline-flex h-11 w-full items-center justify-center gap-2 rounded-md bg-primary text-sm font-medium text-primary-foreground hover:opacity-90"
                :disabled="submitting"
                @click="handleCreateTask"
              >
                <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />
                <Sparkles v-else class="h-4 w-4" />
                {{ submitting ? '创建中…' : '创建生成任务' }}
              </button>

              <div class="mt-3 flex items-center gap-2 rounded-md bg-secondary/60 p-2 text-[11px] text-muted-foreground">
                <Info class="h-3.5 w-3.5 shrink-0" />
                <span>创建任务后进入 AI 处理队列</span>
              </div>
            </div>
          </aside>
        </div>
      </template>
    </div>
  </AppShell>
</template>
