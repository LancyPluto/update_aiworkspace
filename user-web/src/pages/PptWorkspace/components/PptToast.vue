<script setup lang="ts">
defineProps<{
  type: "success" | "error"
  message: string
}>()
</script>

<template>
  <div
    class="rounded-lg px-4 py-2 text-sm"
    :class="
      type === 'success'
        ? 'border border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300'
        : 'border border-destructive/30 bg-destructive/5 text-destructive'
    "
  >
    {{ message }}
  </div>
</template>
