<script setup lang="ts">
import { computed } from "vue"
import { Coins } from "lucide-vue-next"
import type { WorkflowRun, WorkflowStepCharge } from "@/api/workflowApi"
import { workflowCostSummary } from "@/utils/workflowPresentation"

const props = defineProps<{ run: WorkflowRun }>()

const charges = computed<WorkflowStepCharge[]>(() => {
  const direct = props.run.cost?.charges ?? []
  const perStep = (props.run.steps ?? []).flatMap((step) => step.charges ?? [])
  return direct.length ? direct : perStep
})

const totals = computed(() => {
  if (charges.value.length) {
    let totalCredits = 0
    let reservedCredits = 0
    for (const charge of charges.value) {
      if (charge.status === "CAPTURED") totalCredits += charge.actualCredits ?? charge.capturedCredits ?? 0
      if (charge.status === "RESERVED") reservedCredits += charge.reservedCredits ?? 0
    }
    return { totalCredits, reservedCredits }
  }
  return {
    totalCredits: props.run.cost?.totalCredits ?? props.run.totalCredits,
    reservedCredits: props.run.cost?.reservedCredits ?? props.run.reservedCredits,
  }
})

const summary = computed(() => workflowCostSummary(totals.value))

function chargeAmount(charge: WorkflowStepCharge): string {
  const amount = charge.actualCredits ?? charge.capturedCredits ?? charge.reservedCredits
  return amount == null ? "暂不可用" : `${amount} 算力`
}
</script>

<template>
  <section aria-labelledby="workflow-cost-heading">
    <h2 id="workflow-cost-heading" class="flex items-center gap-2 text-base font-semibold">
      <Coins class="h-4 w-4 text-primary" />
      费用
    </h2>
    <dl class="mt-4 grid grid-cols-2 gap-3">
      <div class="rounded-md border border-border bg-secondary/20 p-3">
        <dt class="text-xs text-muted-foreground">已结算</dt>
        <dd class="mt-1 text-sm font-semibold tabular-nums">{{ summary.total }}</dd>
      </div>
      <div class="rounded-md border border-border bg-secondary/20 p-3">
        <dt class="text-xs text-muted-foreground">冻结中</dt>
        <dd class="mt-1 text-sm font-semibold tabular-nums">{{ summary.reserved }}</dd>
      </div>
    </dl>
    <ul v-if="charges.length" class="mt-3 divide-y divide-border text-xs">
      <li v-for="(charge, index) in charges" :key="String(charge.id ?? `${charge.stepId}-${index}`)" class="flex items-center justify-between gap-4 py-2">
        <span class="truncate text-muted-foreground">步骤 {{ charge.stepId ?? index + 1 }} · {{ charge.status || "未知" }}</span>
        <span class="shrink-0 tabular-nums">{{ chargeAmount(charge) }}</span>
      </li>
    </ul>
  </section>
</template>
