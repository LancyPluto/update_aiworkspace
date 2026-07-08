<script setup lang="ts">
import { RouterLink } from "vue-router"
import { ChevronRight, Play, Sparkles } from "lucide-vue-next"
import type { WorkspaceMediaItem, WorkspaceStaticToolItem } from "@/types/workspace"

defineProps<{
  kind?: "button" | "mediaRail" | "toolGrid"
  to?: string
  media?: WorkspaceMediaItem[]
  tools?: WorkspaceStaticToolItem[]
}>()
</script>

<template>
  <RouterLink v-if="kind === 'button' && to" :to="to" class="workspace-pink-button"><slot /></RouterLink>
  <button v-else-if="kind === 'button'" class="workspace-pink-button"><slot /></button>

  <div v-else-if="kind === 'mediaRail'" class="workspace-media-rail">
    <RouterLink v-for="item in media" :key="item.title" :to="item.to || '/tool'" class="workspace-media-card">
      <img v-if="item.image" :src="item.image" alt="" />
      <div v-else class="workspace-media-placeholder">
        <Sparkles :size="24" />
      </div>
      <div class="workspace-media-shade" />
      <span v-if="item.tag" class="workspace-media-tag">{{ item.tag }}</span>
      <ChevronRight class="workspace-media-chevron" :size="16" />
      <Play class="workspace-media-play" :size="20" fill="currentColor" />
      <div class="workspace-media-text">
        <p v-if="item.subtitle">{{ item.subtitle }}</p>
        <h3>{{ item.title }}</h3>
      </div>
    </RouterLink>
  </div>

  <div v-else-if="kind === 'toolGrid'" class="workspace-tool-grid">
    <RouterLink v-for="tool in tools" :key="tool.title" :to="tool.to || '/tool'" class="workspace-tool-card">
      <div class="workspace-tool-image">
        <img v-if="tool.image" :src="tool.image" alt="" />
        <div v-else class="workspace-tool-placeholder">
          <component :is="tool.icon" :size="24" />
        </div>
        <div class="workspace-tool-shade" />
        <span class="workspace-tool-tag"><component :is="tool.icon" :size="14" />{{ tool.tag }}</span>
        <span class="workspace-try-now">立即试用</span>
      </div>
      <div class="workspace-tool-copy">
        <h3>{{ tool.title }}</h3>
        <p>{{ tool.description }}</p>
      </div>
    </RouterLink>
  </div>

  <div v-else class="workspace-workspace-card">
    <slot />
  </div>
</template>
