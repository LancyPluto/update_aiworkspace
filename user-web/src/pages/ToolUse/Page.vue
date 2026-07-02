<script setup lang="ts">
import { ref, onMounted, computed, watch } from "vue"
import { RouterLink, useRoute, useRouter } from "vue-router"
import { ArrowLeft, ChevronRight, Info, Loader2, Sparkles } from "lucide-vue-next"
import DynamicForm from "@/components/DynamicForm/DynamicForm.vue"
import TaskStatusTag from "@/components/TaskStatusTag/TaskStatusTag.vue"
import { getApiOrigin } from "@/api/client"
import { userRoutes } from "@/router/userRoutes"
import { fetchToolByCode, createTask, ApiBusinessError } from "@/api"
import type { ToolDetail } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { randomUUID } from "@/utils/randomUUID"
import { formatToolCreditHint, formatToolCreditLabel } from "@/utils/toolCreditLabel"
import { buildTaskParams } from "@/utils/toolTaskParams"
import { useTaskEstimate, type UseTaskEstimateInput } from "@/composables/useTaskEstimate"

const props = defineProps<{
  id: string
}>()

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const tool = ref<ToolDetail | null>(null)
const loading = ref(true)
const pageError = ref<string | null>(null)
const submitError = ref<string | null>(null)
const submitting = ref(false)
const createdTask = ref<{ taskId: number; taskNo: string } | null>(null)
const formValues = ref<Record<string, unknown>>({})
const dynamicFormRef = ref<InstanceType<typeof DynamicForm> | null>(null)

const title = computed(() => tool.value?.toolName ?? `工具 · ${props.id}`)
const isOffline = computed(() => tool.value?.status === "OFFLINE")
const coverMediaUrl = computed(() => normalizeToolMediaUrl(tool.value?.coverUrl))
const coverIsVideo = computed(() => isVideoPreviewUrl(tool.value?.coverUrl))

watch(
  () => tool.value?.toolCode,
  () => {
    formValues.value = {}
  },
)

// 实时算力预估：表单参数变化时防抖调用后端权威预估接口。
const estimateInput = computed<UseTaskEstimateInput | null>(() => {
  const current = tool.value
  if (!current?.toolCode) return null
  let params: Record<string, unknown> = {}
  try {
    params = buildTaskParams(current.fields ?? [], formValues.value)
  } catch {
    params = {}
  }
  return { toolCode: current.toolCode, params, modelConfigId: current.modelConfigId ?? null }
})

const { estimate: liveEstimate, loading: estimateLoading } = useTaskEstimate(estimateInput)

const creditLabel = computed(() => {
  const result = liveEstimate.value
  if (result && !result.variable) return `${result.estimatedCredits} 算力`
  if (result && result.variable) return "算力不详"
  if (estimateLoading.value) return "估算中…"
  return formatToolCreditLabel(tool.value)
})

const creditInsufficient = computed(() => {
  const result = liveEstimate.value
  return !!result && !result.variable && !result.sufficient
})

function normalizeToolMediaUrl(value?: string | null): string {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

function isVideoPreviewUrl(value?: string | null): boolean {
  return /\.(mp4|webm|mov|m4v)(?:[?#].*)?$/i.test(value?.trim() || "")
}

function applyCommunityPromptPreset() {
  const prompt = typeof route.query.prompt === "string" ? route.query.prompt.trim() : ""
  if (!prompt || !tool.value?.fields?.length) return
  const target =
    tool.value.fields.find((field) => ["prompt", "description", "text", "content", "message"].includes(field.fieldKey.toLowerCase())) ||
    tool.value.fields.find((field) => field.fieldType === "textarea") ||
    tool.value.fields.find((field) => field.fieldType === "text")
  if (target && formValues.value[target.fieldKey] === undefined) {
    formValues.value = { ...formValues.value, [target.fieldKey]: prompt }
  }
}

onMounted(async () => {
  try {
    tool.value = await fetchToolByCode(props.id, { token: auth.token })
    formValues.value = {}
    applyCommunityPromptPreset()
  } catch (e) {
    pageError.value = (e as Error).message || "加载工具详情失败"
  } finally {
    loading.value = false
  }
})

async function handleCreateTask() {
  if (!tool.value) return

  submitError.value = null
  if (isOffline.value) {
    submitError.value = "该工具已下架，暂时无法创建任务"
    return
  }

  const check = dynamicFormRef.value?.validate()
  if (check && !check.valid) {
    submitError.value = check.message ?? "请完善必填项"
    return
  }

  const params = buildTaskParams(tool.value.fields, formValues.value)

  submitting.value = true
  try {
    const res = await createTask(
      {
        toolCode: tool.value.toolCode,
        params,
        clientRequestId: randomUUID(),
      },
      { token: auth.token },
    )
    createdTask.value = { taskId: res.taskId, taskNo: res.taskNo }
    router.push(userRoutes.taskStatus(String(res.taskId)))
  } catch (e) {
    if (e instanceof ApiBusinessError) {
      if (e.code === "CREDIT_NOT_ENOUGH" || e.code === "AGENT_CREDIT_NOT_ENOUGH") {
        submitError.value = "算力不足，请前往会员与算力页充值后再试"
        return
      }
      if (e.code === "TOOL_OFFLINE") {
        submitError.value = "该工具已下架，无法创建任务"
        return
      }
      submitError.value = e.message || "创建任务失败"
      return
    }
    submitError.value = (e as Error).message || "创建任务失败"
  } finally {
    submitting.value = false
  }
}
</script>

<template>
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

      <div v-if="loading" class="flex justify-center py-12">
        <span class="text-sm text-muted-foreground">加载中…</span>
      </div>

      <div v-else-if="pageError" class="rounded-xl border border-destructive/30 bg-destructive/5 p-6 text-center">
        <p class="text-sm text-destructive">{{ pageError }}</p>
      </div>

      <template v-else-if="tool">
        <div
          v-if="isOffline"
          class="rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-sm text-amber-950 dark:text-amber-100"
        >
          该工具已下架，暂时无法创建任务。请返回工具超市选择其他工具。
        </div>

        <div class="grid gap-6 lg:grid-cols-[1fr_320px]">
          <div class="space-y-5">
            <DynamicForm ref="dynamicFormRef" v-model="formValues" :fields="tool.fields" />

            <div v-if="submitError" class="rounded-xl border border-destructive/30 bg-destructive/5 p-4">
              <p class="text-sm text-destructive">{{ submitError }}</p>
            </div>

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
            <div v-if="coverMediaUrl" class="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
              <video
                v-if="coverIsVideo"
                :src="coverMediaUrl"
                class="aspect-video w-full object-cover"
                muted
                loop
                playsinline
                autoplay
                controls
                preload="metadata"
              />
              <img
                v-else
                :src="coverMediaUrl"
                :alt="tool.toolName"
                class="aspect-video w-full object-cover"
              />
            </div>
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
                  <RouterLink :to="userRoutes.dashboard" class="text-primary hover:underline">生成工作台</RouterLink>
                </li>
              </ul>

              <div class="my-4 h-px bg-border" />

              <h3 class="text-sm font-semibold mb-3">算力消耗预估</h3>
              <div class="mt-3 flex items-center justify-between rounded-lg bg-primary/5 p-3 border border-primary/20">
                <span class="text-sm font-medium">{{ tool.variableCreditPricing ? "预计消耗" : "本次共消耗" }}</span>
                <span class="text-lg font-semibold" :class="creditInsufficient ? 'text-destructive' : 'text-primary'">{{ creditLabel }}</span>
              </div>
              <p v-if="creditInsufficient" class="mt-2 text-xs text-destructive leading-relaxed">
                算力不足，请前往会员与算力页充值后再试
              </p>
              <p v-else-if="tool.variableCreditPricing" class="mt-2 text-xs text-muted-foreground leading-relaxed">
                {{ formatToolCreditHint(tool) }}
              </p>

              <button
                type="button"
                class="mt-4 inline-flex h-11 w-full items-center justify-center gap-2 rounded-md bg-primary text-sm font-medium text-primary-foreground hover:opacity-90 disabled:pointer-events-none disabled:opacity-50"
                :disabled="submitting || isOffline"
                @click="handleCreateTask"
              >
                <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />
                <Sparkles v-else class="h-4 w-4" />
                {{ submitting ? "创建中…" : isOffline ? "工具已下架" : "创建生成任务" }}
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
</template>
