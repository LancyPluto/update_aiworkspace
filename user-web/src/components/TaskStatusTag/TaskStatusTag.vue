<script setup lang="ts">
import { computed } from "vue"
import type { TaskStatusViewKind } from "@/utils/taskStatusLabels"

const props = withDefaults(
  defineProps<{
    status: TaskStatusViewKind
    label?: string
  }>(),
  { label: undefined },
)

const map: Record<TaskStatusViewKind, { label: string; wrap: string; dot: string }> = {
  running: {
    label: "生成中",
    wrap: "border-primary/30 bg-primary/10 text-primary",
    dot: "bg-primary animate-pulse",
  },
  queued: {
    label: "排队中",
    wrap: "border-border bg-secondary text-secondary-foreground",
    dot: "bg-muted-foreground",
  },
  success: {
    label: "已完成",
    wrap: "border-success/30 bg-success/10 text-success",
    dot: "bg-success",
  },
  failed: {
    label: "失败",
    wrap: "border-destructive/30 bg-destructive/10 text-destructive",
    dot: "bg-destructive",
  },
  cancelled: {
    label: "已取消",
    wrap: "border-border bg-muted text-muted-foreground",
    dot: "bg-muted-foreground",
  },
}

const cfg = computed(() => map[props.status])
</script>

<template>
  <span
    class="inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-[11px] font-medium"
    :class="cfg.wrap"
  >
    <span class="h-1.5 w-1.5 rounded-full" :class="cfg.dot" />
    {{ props.label ?? cfg.label }}
  </span>
</template>
