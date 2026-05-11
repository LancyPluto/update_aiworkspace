<script setup lang="ts">
import type { ResultBlock } from "@/types/result"

defineProps<{
  blocks: ResultBlock[]
}>()
</script>

<template>
  <div class="space-y-4">
    <div
      v-for="(b, i) in blocks"
      :key="i"
      class="rounded-xl border border-border bg-card p-4 sm:p-6 shadow-sm"
    >
      <template v-if="b.type === 'text'">
        <p class="text-xs text-muted-foreground mb-1">{{ b.title }}</p>
        <p class="text-sm leading-relaxed whitespace-pre-wrap">{{ b.content }}</p>
      </template>
      <template v-else>
        <p class="text-xs text-muted-foreground mb-2">{{ b.title }}</p>
        <ol class="space-y-1.5 text-sm list-decimal list-inside text-foreground/90">
          <li v-for="item in b.items" :key="item">{{ item }}</li>
        </ol>
      </template>
    </div>
  </div>
</template>
