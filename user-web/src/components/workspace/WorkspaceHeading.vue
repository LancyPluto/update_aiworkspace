<script setup lang="ts">
defineProps<{
  eyebrow?: string
  title: string
  description?: string
}>()
</script>

<template>
  <div class="workspace-page-heading">
    <div>
      <p v-if="eyebrow" class="workspace-eyebrow">{{ eyebrow }}</p>
      <h1>{{ title }}</h1>
      <p v-if="description" class="workspace-heading-desc">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="workspace-heading-actions">
      <slot name="actions" />
    </div>
  </div>
</template>
