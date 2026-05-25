<script setup lang="ts">
import { computed } from "vue"
import { Zap } from "lucide-vue-next"

const props = withDefaults(
  defineProps<{
    cost?: number | null
    unit?: string
    size?: "sm" | "md"
  }>(),
  {
    unit: "算力/次",
    size: "sm",
  },
)

const visible = computed(() => props.cost != null)

const label = computed(() => {
  if (props.cost === 0) return "免费"
  return String(props.cost)
})

const showUnit = computed(() => props.cost != null && props.cost > 0)
</script>

<template>
  <span
    v-if="visible"
    class="inline-flex items-center gap-1 text-warning"
    :class="size === 'md' ? 'text-sm' : 'text-xs'"
  >
    <Zap :class="size === 'md' ? 'h-4 w-4' : 'h-3.5 w-3.5'" />
    <span :class="size === 'md' ? 'text-base font-semibold text-foreground' : 'font-medium'">{{ label }}</span>
    <span v-if="showUnit" class="text-muted-foreground">{{ unit }}</span>
  </span>
</template>
