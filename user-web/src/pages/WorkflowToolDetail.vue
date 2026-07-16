<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue"
import { RouterLink, useRoute, useRouter } from "vue-router"
import { ArrowLeft, Coins, Loader2, Play, RefreshCw, Workflow } from "lucide-vue-next"
import { ApiBusinessError } from "@/api/client"
import {
  createRun,
  getTool,
  type WorkflowToolDetail,
} from "@/api/workflowApi"
import type { ToolField } from "@/api/types"
import DynamicForm from "@/components/DynamicForm/DynamicForm.vue"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { randomUUID } from "@/utils/randomUUID"

const props = defineProps<{ toolCode: string }>()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const tool = ref<WorkflowToolDetail | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const submitting = ref(false)
const submitError = ref<string | null>(null)
const formValues = ref<Record<string, unknown>>({})
const dynamicFormRef = ref<InstanceType<typeof DynamicForm> | null>(null)
let loadController: AbortController | null = null

const requestStorageKey = computed(() => `workflow-run-client-request:${props.toolCode}`)
const fields = computed<ToolField[]>(() => {
  if (tool.value?.fields?.length) return tool.value.fields
  return fieldsFromJsonSchema(tool.value?.inputSchema)
})
const cost = computed(() => tool.value?.minimumRequiredCredits ?? tool.value?.estimatedCreditCost)
const unavailable = computed(() => tool.value?.status === "OFFLINE")

function fieldsFromJsonSchema(schema?: Record<string, unknown> | null): ToolField[] {
  const properties = schema?.properties
  if (!properties || typeof properties !== "object" || Array.isArray(properties)) return []
  const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : [])
  return Object.entries(properties as Record<string, Record<string, unknown>>).map(([key, config], index) => {
    const options = Array.isArray(config.enum) ? config.enum.map(String) : null
    const type = String(config.type ?? "string")
    const fieldType: ToolField["fieldType"] = options
      ? "select"
      : type === "boolean"
        ? "checkbox"
        : type === "number" || type === "integer"
          ? "number"
          : config.format === "textarea" || Number(config.maxLength ?? 0) > 160
            ? "textarea"
            : "text"
    return {
      fieldKey: key,
      fieldName: String(config.title ?? key),
      fieldType,
      placeholder: typeof config.description === "string" ? config.description : null,
      options,
      required: required.has(key),
      defaultValue: config.default == null ? null : String(config.default),
      sortOrder: index,
    }
  })
}

function getOrCreateClientRequestId(): string {
  try {
    const existing = sessionStorage.getItem(requestStorageKey.value)
    if (existing) return existing
    const generated = randomUUID()
    sessionStorage.setItem(requestStorageKey.value, generated)
    return generated
  } catch {
    return randomUUID()
  }
}

function clearClientRequestId() {
  try {
    sessionStorage.removeItem(requestStorageKey.value)
  } catch {
    // Session storage may be disabled.
  }
}

async function loadTool() {
  loadController?.abort()
  loadController = new AbortController()
  loading.value = true
  error.value = null
  tool.value = null
  formValues.value = {}
  try {
    tool.value = await getTool(props.toolCode, { signal: loadController.signal })
  } catch (loadError) {
    if (loadController.signal.aborted) return
    error.value = loadError instanceof Error ? loadError.message : "工具详情暂时不可用"
  } finally {
    if (!loadController.signal.aborted) loading.value = false
  }
}

async function startRun() {
  if (submitting.value || !tool.value) return
  if (!auth.isLoggedIn) {
    await router.push({ ...userRoutes.login, query: { redirect: route.fullPath } })
    return
  }
  submitError.value = null
  if (unavailable.value) {
    submitError.value = "该工具当前不可用"
    return
  }
  const validation = dynamicFormRef.value?.validate()
  if (validation && !validation.valid) {
    submitError.value = validation.message ?? "请完善必填项"
    return
  }

  const clientRequestId = getOrCreateClientRequestId()
  submitting.value = true
  try {
    const created = await createRun(tool.value.toolCode, {
      input: { ...formValues.value },
      clientRequestId,
    }, { token: auth.token })
    clearClientRequestId()
    await router.push(userRoutes.workflowRun(created.taskId))
  } catch (createError) {
    if (createError instanceof ApiBusinessError && ["CREDIT_NOT_ENOUGH", "AGENT_CREDIT_NOT_ENOUGH"].includes(createError.code)) {
      submitError.value = "算力不足，请充值后重试"
    } else {
      submitError.value = createError instanceof Error ? createError.message : "启动工作流失败"
    }
  } finally {
    submitting.value = false
  }
}

watch(() => props.toolCode, loadTool, { immediate: true })
onBeforeUnmount(() => loadController?.abort())
</script>

<template>
  <main class="mx-auto w-full max-w-6xl px-5 py-7 sm:px-7 lg:py-10">
    <RouterLink :to="userRoutes.agentTools" class="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground">
      <ArrowLeft class="h-4 w-4" />
      工具中心
    </RouterLink>

    <div v-if="loading" class="flex min-h-72 items-center justify-center text-sm text-muted-foreground">
      <Loader2 class="mr-2 h-4 w-4 animate-spin" />
      正在加载工具
    </div>

    <section v-else-if="error" class="flex min-h-72 flex-col items-center justify-center text-center">
      <Workflow class="h-9 w-9 text-muted-foreground" />
      <h1 class="mt-4 text-lg font-semibold">无法打开工具</h1>
      <p class="mt-2 max-w-lg text-sm text-muted-foreground">{{ error }}</p>
      <button type="button" class="mt-5 inline-flex h-9 items-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary" @click="loadTool">
        <RefreshCw class="h-4 w-4" />
        重新加载
      </button>
    </section>

    <template v-else-if="tool">
      <header class="mt-6 border-b border-border pb-6">
        <div class="flex flex-wrap items-start justify-between gap-4">
          <div>
            <div class="mb-2 text-xs font-medium text-primary">{{ tool.categoryName || "工作流工具" }}</div>
            <h1 class="text-2xl font-semibold text-foreground">{{ tool.toolName }}</h1>
            <p class="mt-2 max-w-3xl text-sm leading-6 text-muted-foreground">{{ tool.description || "填写输入后启动工作流。" }}</p>
          </div>
          <div class="inline-flex items-center gap-2 rounded-md border border-border bg-card px-3 py-2 text-sm">
            <Coins class="h-4 w-4 text-primary" />
            {{ cost == null ? "按步骤计费" : `${cost} 算力起` }}
          </div>
        </div>
      </header>

      <div class="grid gap-8 py-7 lg:grid-cols-[minmax(0,1fr)_280px]">
        <section>
          <h2 class="text-base font-semibold">运行输入</h2>
          <p v-if="!fields.length" class="mt-4 rounded-md border border-border bg-secondary/30 px-4 py-3 text-sm text-muted-foreground">此工具无需额外输入。</p>
          <DynamicForm v-else ref="dynamicFormRef" v-model="formValues" :fields="fields" :tool-id="tool.toolCode" class="mt-5" />
          <div v-if="submitError" class="mt-5 rounded-md border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive">
            {{ submitError }}
          </div>
        </section>

        <aside class="border-t border-border pt-6 lg:border-l lg:border-t-0 lg:pl-7 lg:pt-0">
          <h2 class="text-sm font-semibold">启动工作流</h2>
          <dl class="mt-4 space-y-3 text-sm">
            <div class="flex justify-between gap-3">
              <dt class="text-muted-foreground">预计费用</dt>
              <dd>{{ cost == null ? "按实际步骤结算" : `${cost} 算力起` }}</dd>
            </div>
            <div v-if="tool.estimatedDurationSeconds" class="flex justify-between gap-3">
              <dt class="text-muted-foreground">预计用时</dt>
              <dd>{{ tool.estimatedDurationSeconds }} 秒</dd>
            </div>
          </dl>
          <button
            type="button"
            class="mt-6 inline-flex h-11 w-full items-center justify-center gap-2 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground hover:opacity-90 disabled:pointer-events-none disabled:opacity-50"
            :disabled="submitting || unavailable"
            @click="startRun"
          >
            <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />
            <Play v-else class="h-4 w-4" />
            {{ submitting ? "正在启动" : unavailable ? "工具不可用" : "启动工作流" }}
          </button>
        </aside>
      </div>
    </template>
  </main>
</template>
