<script setup lang="ts">
import WorkspaceHeading from "@/components/workspace/WorkspaceHeading.vue"
import WorkspaceShell from "@/components/workspace/WorkspaceShell.vue"

withDefaults(
  defineProps<{
    title?: string
    description?: string
  }>(),
  {},
)
</script>

<template>
  <WorkspaceShell>
    <WorkspaceHeading v-if="title" :title="title" :description="description" />
    <slot />
  </WorkspaceShell>
</template>
