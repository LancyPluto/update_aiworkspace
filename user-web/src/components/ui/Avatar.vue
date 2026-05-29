<script setup lang="ts">
import { User } from "lucide-vue-next"

defineProps<{
  class?: string
  src?: string
  alt?: string
}>()
</script>

<template>
  <div :class="['relative flex h-10 w-10 shrink-0 overflow-hidden rounded-full', $props.class]">
    <img
      v-if="src"
      :src="src"
      :alt="alt"
      class="aspect-square h-full w-full object-cover"
    />
    <div v-else class="flex h-full w-full items-center justify-center bg-muted">
      <User class="h-5 w-5 text-muted-foreground" />
    </div>
  </div>
</template>
