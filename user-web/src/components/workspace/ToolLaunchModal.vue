<script setup lang="ts">
import { computed, ref, watch } from "vue"
import { RouterLink, useRouter } from "vue-router"
import { ExternalLink, Loader2, Sparkles, X, Zap } from "lucide-vue-next"
import DynamicForm from "@/components/DynamicForm/DynamicForm.vue"
import { ApiBusinessError, createTask, fetchToolByCode } from "@/api"
import { getApiOrigin } from "@/api/client"
import type { ToolDetail } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { userRoutes } from "@/router/userRoutes"
import { randomUUID } from "@/utils/randomUUID"
import { buildTaskParams } from "@/utils/toolTaskParams"
import { isVideoPreviewUrl } from "@/adapters/toolPresentationAdapter"
import { cleanToolDisplayText } from "@/utils/toolDisplayText"
import { formatToolCreditHint, formatToolCreditLabel } from "@/utils/toolCreditLabel"

const props = defineProps<{
  open: boolean
  toolCode: string
  toolName?: string
  toolCover?: string
}>()

const emit = defineEmits<{
  close: []
}>()

const router = useRouter()
const auth = useAuthStore()

const tool = ref<ToolDetail | null>(null)
const loading = ref(false)
const loadError = ref("")
const submitError = ref("")
const submitting = ref(false)
const formValues = ref<Record<string, unknown>>({})
const dynamicFormRef = ref<InstanceType<typeof DynamicForm> | null>(null)

const title = computed(() => cleanToolDisplayText(tool.value?.toolName) || props.toolName || props.toolCode)
const description = computed(() => cleanToolDisplayText(tool.value?.description) || "")
const isOffline = computed(() => tool.value?.status === "OFFLINE")
const creditLabel = computed(() => formatToolCreditLabel(tool.value))
const creditHint = computed(() => formatToolCreditHint(tool.value))
const coverMediaUrl = computed(() => normalizeMediaUrl(tool.value?.coverUrl || props.toolCover))
const coverIsVideo = computed(() => isVideoPreviewUrl(tool.value?.coverUrl || props.toolCover))

function normalizeMediaUrl(value?: string | null): string {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

async function loadTool() {
  loading.value = true
  loadError.value = ""
  submitError.value = ""
  tool.value = null
  formValues.value = {}
  try {
    tool.value = await fetchToolByCode(props.toolCode, { token: auth.token })
  } catch (e) {
    loadError.value = (e as Error).message || "加载工具配置失败"
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.open, props.toolCode] as const,
  ([open, code]) => {
    if (open && code) void loadTool()
  },
  { immediate: true },
)

function close() {
  if (submitting.value) return
  emit("close")
}

async function handleGenerate() {
  if (!tool.value) return
  submitError.value = ""

  if (!auth.isLoggedIn) {
    void router.push({ name: "RootLogin", query: { redirect: `/create?tool=${encodeURIComponent(props.toolCode)}` } })
    return
  }

  if (isOffline.value) {
    submitError.value = "该工具已下架，暂时无法使用"
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
    const task = await createTask(
      {
        toolCode: tool.value.toolCode,
        params,
        clientRequestId: randomUUID(),
      },
      { token: auth.token },
    )
    emit("close")
    await router.push(userRoutes.taskStatus(String(task.taskId)))
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
  <Teleport to="body">
    <div
      v-if="open"
      class="fixed inset-0 z-[130] flex items-stretch bg-black/80 backdrop-blur-sm"
      @click.self="close"
    >
      <div
        class="relative m-auto grid h-[min(92vh,760px)] w-[min(96vw,1180px)] grid-cols-[minmax(0,420px)_minmax(0,1fr)] overflow-hidden rounded-2xl border border-border bg-card text-foreground shadow-2xl max-lg:grid-cols-1 max-lg:h-[94vh]"
        role="dialog"
        aria-modal="true"
      >
        <!-- 左侧：工具配置表单 -->
        <section class="flex min-h-0 flex-col border-r border-border bg-background/60 max-lg:border-r-0 max-lg:border-b">
          <header class="flex items-center justify-between gap-3 px-6 pt-6">
            <h2 class="text-lg font-semibold leading-tight">{{ title }}</h2>
          </header>
          <p v-if="description" class="px-6 pt-1.5 text-xs leading-relaxed text-muted-foreground">{{ description }}</p>

          <div class="min-h-0 flex-1 overflow-y-auto px-6 py-5">
            <div v-if="loading" class="flex flex-col items-center justify-center gap-2 py-16 text-muted-foreground">
              <Loader2 class="h-5 w-5 animate-spin" />
              <span class="text-sm">正在加载工具配置…</span>
            </div>

            <div v-else-if="loadError" class="rounded-xl border border-destructive/30 bg-destructive/5 p-5 text-center">
              <p class="text-sm text-destructive">{{ loadError }}</p>
              <button type="button" class="mt-3 text-sm text-primary hover:underline" @click="loadTool">重试</button>
            </div>

            <template v-else-if="tool">
              <div
                v-if="isOffline"
                class="mb-4 rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-sm text-amber-950 dark:text-amber-100"
              >
                该工具已下架，暂时无法创建任务。
              </div>

              <DynamicForm ref="dynamicFormRef" v-model="formValues" :fields="tool.fields" :tool-id="tool.toolCode" />

              <div v-if="submitError" class="mt-4 rounded-xl border border-destructive/30 bg-destructive/5 p-3">
                <p class="text-sm text-destructive">{{ submitError }}</p>
              </div>
            </template>
          </div>

          <footer class="border-t border-border px-6 py-4">
            <div class="mb-3 flex items-center justify-between text-sm">
              <span class="flex items-center gap-1.5 text-muted-foreground">
                <Zap class="h-4 w-4 text-warning" />所需额度
              </span>
              <span class="text-base font-semibold text-primary">{{ creditLabel }}</span>
            </div>
            <p v-if="tool?.variableCreditPricing" class="mb-3 text-xs text-muted-foreground leading-relaxed">
              {{ creditHint }}
            </p>
            <button
              type="button"
              class="inline-flex h-11 w-full items-center justify-center gap-2 rounded-md bg-primary text-sm font-medium text-primary-foreground transition hover:opacity-90 disabled:pointer-events-none disabled:opacity-50"
              :disabled="loading || submitting || isOffline || !tool"
              @click="handleGenerate"
            >
              <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />
              <Sparkles v-else class="h-4 w-4" />
              {{ submitting ? "创建中…" : isOffline ? "工具已下架" : "生成" }}
            </button>
          </footer>
        </section>

        <!-- 右侧：预览区 -->
        <section class="relative flex min-h-0 items-center justify-center bg-[#0d0e13] p-8 max-lg:hidden">
          <div class="absolute right-4 top-4 z-10 flex items-center gap-2">
            <RouterLink
              :to="userRoutes.toolDetail(toolCode)"
              class="grid h-9 w-9 place-items-center rounded-full border border-white/10 bg-white/[0.06] text-white/70 transition hover:bg-white/12 hover:text-white"
              title="查看工具详情"
              @click="close"
            >
              <ExternalLink class="h-4 w-4" />
            </RouterLink>
            <button
              type="button"
              class="grid h-9 w-9 place-items-center rounded-full border border-white/10 bg-white/[0.06] text-white/70 transition hover:bg-white/12 hover:text-white"
              aria-label="关闭"
              @click="close"
            >
              <X class="h-4 w-4" />
            </button>
          </div>

          <div class="flex w-full max-w-lg flex-col items-center gap-5 text-center">
            <div
              v-if="coverMediaUrl"
              class="w-full overflow-hidden rounded-xl border border-white/10 bg-black/40"
            >
              <video
                v-if="coverIsVideo"
                :src="coverMediaUrl"
                class="aspect-video w-full object-cover"
                muted
                loop
                playsinline
                autoplay
                preload="metadata"
              />
              <img v-else :src="coverMediaUrl" :alt="title" class="aspect-video w-full object-cover" />
            </div>
            <h3 class="text-2xl font-bold text-white/90">{{ title }}</h3>
            <p class="max-w-md text-sm leading-relaxed text-white/45">
              按左侧配置填写所需信息并上传素材，点击「生成」后将进入生成页查看结果。
            </p>
          </div>
        </section>

        <!-- 移动端关闭按钮 -->
        <button
          type="button"
          class="absolute right-3 top-3 z-20 hidden h-9 w-9 place-items-center rounded-full border border-border bg-card text-muted-foreground max-lg:grid"
          aria-label="关闭"
          @click="close"
        >
          <X class="h-4 w-4" />
        </button>
      </div>
    </div>
  </Teleport>
</template>
