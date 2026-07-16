<script setup lang="ts">
import { computed } from "vue"
import { CheckCircle2, Circle, Clock3, Loader2, MinusCircle, XCircle } from "lucide-vue-next"
import type { WorkflowRunStep } from "@/api/workflowApi"
import { stepStatusView } from "@/utils/workflowPresentation"

const props = defineProps<{
  steps: WorkflowRunStep[]
  currentStepId?: string | number | null
}>()

const orderedSteps = computed(() => props.steps ?? [])

function isCurrent(step: WorkflowRunStep): boolean {
  return props.currentStepId != null && String(step.stepId) === String(props.currentStepId)
}

function iconFor(status: string) {
  if (status === "SUCCESS") return CheckCircle2
  if (status === "FAILED" || status === "CANCELLED") return XCircle
  if (status === "SKIPPED") return MinusCircle
  if (status === "RUNNING" || status === "QUEUED") return Loader2
  if (status === "AWAITING_USER") return Clock3
  return Circle
}

function statusClass(status: string): string {
  if (status === "SUCCESS") return "text-success"
  if (status === "FAILED") return "text-destructive"
  if (status === "AWAITING_USER") return "text-warning"
  if (status === "RUNNING" || status === "QUEUED") return "text-primary"
  return "text-muted-foreground"
}

function formatTimestamp(value?: string | null): string {
  if (!value) return ""
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false })
}
</script>

<template>
  <section aria-labelledby="workflow-steps-heading">
    <div class="flex items-center justify-between gap-4">
      <h2 id="workflow-steps-heading" class="text-base font-semibold">运行步骤</h2>
      <span class="text-xs text-muted-foreground">{{ orderedSteps.length }} 步</span>
    </div>

    <div v-if="!orderedSteps.length" class="mt-4 rounded-md border border-dashed border-border px-4 py-8 text-center text-sm text-muted-foreground">
      暂无步骤信息，运行更新后会显示在这里。
    </div>

    <ol v-else class="mt-4 divide-y divide-border border-y border-border">
      <li v-for="(step, index) in orderedSteps" :key="String(step.stepId)" class="flex gap-4 py-4">
        <component
          :is="iconFor(step.status)"
          class="mt-0.5 h-5 w-5 shrink-0"
          :class="[statusClass(step.status), { 'animate-spin': step.status === 'RUNNING' || step.status === 'QUEUED' }]"
        />
        <div class="min-w-0 flex-1">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div class="flex min-w-0 items-center gap-2">
              <span class="text-xs tabular-nums text-muted-foreground">{{ index + 1 }}</span>
              <h3 class="truncate text-sm font-medium">{{ step.stepName || step.stepCode || `步骤 ${index + 1}` }}</h3>
              <span v-if="isCurrent(step)" class="rounded bg-primary/10 px-1.5 py-0.5 text-[11px] text-primary">当前</span>
            </div>
            <span class="text-xs" :class="statusClass(step.status)">{{ stepStatusView(step.status).label }}</span>
          </div>
          <p v-if="step.progressMessage" class="mt-1 text-xs leading-5 text-muted-foreground">{{ step.progressMessage }}</p>
          <p v-if="step.errorMessage" class="mt-1 text-xs leading-5 text-destructive">{{ step.errorMessage }}</p>
          <div v-if="step.progress != null && ['QUEUED', 'RUNNING'].includes(step.status)" class="mt-3 h-1.5 overflow-hidden rounded-full bg-secondary">
            <div class="h-full rounded-full bg-primary transition-[width]" :style="{ width: `${Math.max(0, Math.min(100, step.progress))}%` }" />
          </div>
          <div v-if="step.startedAt || step.completedAt" class="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-muted-foreground">
            <span v-if="step.startedAt">开始 {{ formatTimestamp(step.startedAt) }}</span>
            <span v-if="step.completedAt">结束 {{ formatTimestamp(step.completedAt) }}</span>
          </div>
        </div>
      </li>
    </ol>
  </section>
</template>
