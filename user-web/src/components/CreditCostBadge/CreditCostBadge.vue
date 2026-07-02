<script setup lang="ts">
// 算力徽标：固定成本显示数值，工作流类工具显示"算力不详"
import { computed } from "vue"

const props = withDefaults(
  defineProps<{
    cost?: number | null
    unit?: string
    size?: "sm" | "md"
    /** 工作流等按实际模型用量计费的工具：显示"算力不详" */
    variable?: boolean
  }>(),
  {
    unit: "算力/次",
    size: "sm",
    variable: false,
  },
)

const visible = computed(() => props.variable || props.cost != null)
const showUnit = computed(() => !props.variable && props.cost != null && props.cost > 0)

const label = computed(() => {
  if (props.variable) return "算力不详"
  if (props.cost === 0) return "免费"
  return String(props.cost)
})
</script>

<template>
  <span
    v-if="visible"
    class="inline-flex items-center gap-1 text-sky-400"
    :class="size === 'md' ? 'text-sm' : 'text-xs'"
    :title="variable ? '按每次实际调用的模型成本 ×1.2 扣减算力' : undefined"
  >
    <span v-if="showUnit" class="text-muted-foreground">约</span>
    <span :class="size === 'md' ? 'text-base font-semibold text-foreground' : 'font-medium'">{{ label }}</span>
    <span v-if="showUnit" class="text-muted-foreground">{{ unit }}</span>
  </span>
</template>
