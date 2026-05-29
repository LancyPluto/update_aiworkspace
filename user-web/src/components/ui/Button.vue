<script setup lang="ts">
import { computed } from "vue"

const props = withDefaults(
  defineProps<{
    variant?: "default" | "ghost" | "outline" | "secondary"
    size?: "default" | "lg" | "sm"
    class?: string
  }>(),
  {
    variant: "default",
    size: "default",
  },
)

const variantClass: Record<string, string> = {
  default: "bg-primary text-primary-foreground hover:bg-primary/90",
  ghost: "hover:bg-accent hover:text-accent-foreground",
  outline: "border border-border bg-background hover:bg-accent hover:text-accent-foreground",
  secondary: "bg-secondary text-secondary-foreground hover:bg-secondary/80",
}

const sizeClass: Record<string, string> = {
  default: "h-9 px-4 py-2",
  sm: "h-8 px-3 text-sm",
  lg: "h-10 px-6",
}

const classes = computed(() =>
  [
    "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium transition-all disabled:pointer-events-none disabled:opacity-50 outline-none [&_svg]:pointer-events-none [&_svg]:shrink-0",
    variantClass[props.variant],
    sizeClass[props.size],
    props.class,
  ]
    .filter(Boolean)
    .join(" "),
)
</script>

<template>
  <button type="button" :class="classes">
    <slot />
  </button>
</template>
