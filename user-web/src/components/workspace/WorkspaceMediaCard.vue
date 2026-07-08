<script setup lang="ts">
import { RouterLink } from "vue-router"
import { Play } from "lucide-vue-next"
import type { WorkspaceMediaItem } from "@/types/workspace"

defineProps<{
  item: WorkspaceMediaItem
}>()
</script>

<template>
  <RouterLink :to="item.to || '/tool'" class="workspace-media-card">
    <img
      :src="item.image"
      :alt="item.title"
      width="252"
      height="141"
      loading="lazy"
      decoding="async"
    />
    <div class="workspace-media-shade" />
    <span v-if="item.tag" class="workspace-media-tag">{{ item.tag }}</span>
    <Play class="workspace-media-play" :size="20" fill="currentColor" />
    <div class="workspace-media-text">
      <p v-if="item.subtitle">{{ item.subtitle }}</p>
      <h3>{{ item.title }}</h3>
    </div>
  </RouterLink>
</template>
