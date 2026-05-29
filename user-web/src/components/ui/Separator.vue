<script setup lang="ts">
withDefaults(
  defineProps<{
    class?: string
    orientation?: "horizontal" | "vertical"
  }>(),
  {
    orientation: "horizontal",
  },
)
</script>

<template>
  <div
    :class="[
      'shrink-0 bg-border',
      orientation === 'horizontal' ? 'h-px w-full' : 'h-full w-px',
      $props.class,
    ]"
  />
</template>
