<script setup lang="ts">
import { computed, ref, watch } from "vue"
import { RouterLink } from "vue-router"
import { Ban, Check, CircleDollarSign, Loader2, Play, Send, X } from "lucide-vue-next"
import type {
  WorkflowFeedbackAction,
  WorkflowFeedbackField,
  WorkflowRun,
} from "@/api/workflowApi"
import { userRoutes } from "@/router/userRoutes"
import { runActions } from "@/utils/workflowPresentation"

const props = defineProps<{
  run: WorkflowRun
  submitting?: boolean
  error?: string | null
}>()

const emit = defineEmits<{
  cancel: []
  resume: []
  feedback: [payload: { action: WorkflowFeedbackAction; fields: Record<string, unknown> }]
}>()

const formValues = ref<Record<string, unknown>>({})
const validationError = ref<string | null>(null)
const userAction = computed(() => props.run.userAction ?? props.run.confirmation ?? null)
const fields = computed<WorkflowFeedbackField[]>(() => userAction.value?.fields ?? [])
const allowedActions = computed<WorkflowFeedbackAction[]>(() => userAction.value?.allowedActions ?? [])
const visibleActions = computed(() => runActions(props.run.status))

watch(
  () => userAction.value?.stepId,
  () => {
    formValues.value = Object.fromEntries(fields.value.map((field) => [field.fieldKey, field.defaultValue ?? (field.fieldType === "checkbox" ? false : "")]))
    validationError.value = null
  },
  { immediate: true },
)

function setValue(key: string, value: unknown) {
  formValues.value = { ...formValues.value, [key]: value }
}

function optionValue(option: string | { label: string; value: string }): string {
  return typeof option === "string" ? option : option.value
}

function optionLabel(option: string | { label: string; value: string }): string {
  return typeof option === "string" ? option : option.label
}

function submit(action: WorkflowFeedbackAction) {
  validationError.value = null
  if (action === "CONTINUE_WITH_FEEDBACK") {
    const missing = fields.value.find((field) => field.required && (formValues.value[field.fieldKey] === "" || formValues.value[field.fieldKey] == null))
    if (missing) {
      validationError.value = `请填写${missing.fieldName}`
      return
    }
  }
  emit("feedback", { action, fields: { ...formValues.value } })
}

function actionLabel(action: WorkflowFeedbackAction): string {
  if (action === "APPROVE") return "确认继续"
  if (action === "CONTINUE_WITH_FEEDBACK") return "提交并继续"
  if (action === "REJECT") return "拒绝"
  return "取消运行"
}

function actionIcon(action: WorkflowFeedbackAction) {
  if (action === "APPROVE") return Check
  if (action === "CONTINUE_WITH_FEEDBACK") return Send
  if (action === "REJECT") return X
  return Ban
}
</script>

<template>
  <section v-if="visibleActions.length" class="border-y border-border bg-secondary/20 px-4 py-5 sm:px-5" aria-labelledby="workflow-actions-heading">
    <h2 id="workflow-actions-heading" class="text-sm font-semibold">
      {{ run.status === "AWAITING_USER" ? "需要你的确认" : run.status === "AWAITING_FUNDS" ? "需要补充算力" : "运行操作" }}
    </h2>
    <p v-if="userAction?.prompt" class="mt-2 text-sm leading-6 text-muted-foreground">{{ userAction.prompt }}</p>

    <div v-if="run.status === 'AWAITING_USER'" class="mt-4 space-y-4">
      <label v-for="field in fields" :key="field.fieldKey" class="block">
        <span class="mb-1.5 block text-xs font-medium">{{ field.fieldName }}<span v-if="field.required" class="text-destructive"> *</span></span>
        <textarea
          v-if="field.fieldType === 'textarea'"
          :value="String(formValues[field.fieldKey] ?? '')"
          :placeholder="field.placeholder || ''"
          rows="3"
          class="w-full resize-y rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:border-primary focus:ring-2 focus:ring-primary/20"
          @input="setValue(field.fieldKey, ($event.target as HTMLTextAreaElement).value)"
        />
        <select
          v-else-if="field.fieldType === 'select'"
          :value="String(formValues[field.fieldKey] ?? '')"
          class="h-10 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus:border-primary focus:ring-2 focus:ring-primary/20"
          @change="setValue(field.fieldKey, ($event.target as HTMLSelectElement).value)"
        >
          <option value="">请选择</option>
          <option v-for="option in field.options ?? []" :key="optionValue(option)" :value="optionValue(option)">{{ optionLabel(option) }}</option>
        </select>
        <label v-else-if="field.fieldType === 'checkbox'" class="inline-flex items-center gap-2 text-sm">
          <input type="checkbox" :checked="Boolean(formValues[field.fieldKey])" class="h-4 w-4 accent-primary" @change="setValue(field.fieldKey, ($event.target as HTMLInputElement).checked)" />
          {{ field.placeholder || "确认" }}
        </label>
        <input
          v-else
          :value="String(formValues[field.fieldKey] ?? '')"
          :placeholder="field.placeholder || ''"
          class="h-10 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus:border-primary focus:ring-2 focus:ring-primary/20"
          @input="setValue(field.fieldKey, ($event.target as HTMLInputElement).value)"
        />
      </label>

      <div class="flex flex-wrap gap-2">
        <button
          v-for="action in allowedActions"
          :key="action"
          type="button"
          class="inline-flex h-9 items-center gap-2 rounded-md px-3 text-sm font-medium disabled:pointer-events-none disabled:opacity-50"
          :class="action === 'APPROVE' || action === 'CONTINUE_WITH_FEEDBACK' ? 'bg-primary text-primary-foreground hover:opacity-90' : 'border border-border hover:bg-secondary'"
          :disabled="submitting"
          @click="submit(action)"
        >
          <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />
          <component :is="actionIcon(action)" v-else class="h-4 w-4" />
          {{ actionLabel(action) }}
        </button>
      </div>
    </div>

    <div v-else-if="run.status === 'AWAITING_FUNDS'" class="mt-4 flex flex-wrap gap-2">
      <RouterLink :to="userRoutes.billing" class="inline-flex h-9 items-center gap-2 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground hover:opacity-90">
        <CircleDollarSign class="h-4 w-4" />
        前往充值
      </RouterLink>
      <button type="button" class="inline-flex h-9 items-center gap-2 rounded-md border border-border px-3 text-sm hover:bg-secondary disabled:opacity-50" :disabled="submitting" @click="emit('resume')">
        <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />
        <Play v-else class="h-4 w-4" />
        充值后继续
      </button>
      <button type="button" class="inline-flex h-9 items-center gap-2 rounded-md border border-border px-3 text-sm hover:border-destructive/50 hover:text-destructive disabled:opacity-50" :disabled="submitting" @click="emit('cancel')">
        <Ban class="h-4 w-4" />
        取消运行
      </button>
    </div>

    <button v-else type="button" class="mt-4 inline-flex h-9 items-center gap-2 rounded-md border border-border px-3 text-sm hover:border-destructive/50 hover:text-destructive disabled:opacity-50" :disabled="submitting" @click="emit('cancel')">
      <Loader2 v-if="submitting" class="h-4 w-4 animate-spin" />
      <Ban v-else class="h-4 w-4" />
      取消运行
    </button>

    <p v-if="validationError || error" class="mt-3 text-xs text-destructive">{{ validationError || error }}</p>
  </section>
</template>
