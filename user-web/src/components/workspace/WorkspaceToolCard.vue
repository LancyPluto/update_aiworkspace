<script setup lang="ts">
import { RouterLink } from "vue-router"
import { computed, ref } from "vue"
import { ArrowRight, Sparkles, Zap } from "lucide-vue-next"
import { DEFAULT_TOOL_COVER_URL, type ToolCardModel } from "@/adapters/toolPresentationAdapter"
import ToolLaunchModal from "@/components/workspace/ToolLaunchModal.vue"

const props = defineProps<{
  tool: ToolCardModel
}>()

const fallbackAsImage = ref(false)
const launchOpen = ref(false)

/** 工具统一弹出配置悬浮框（PPT 独立工作台已下线） */
const usesWorkspaceRoute = computed(() => false)
const hasRenderableCover = computed(() => Boolean(props.tool.image || fallbackAsImage.value))

function handleCoverError(event: Event) {
  const media = event.target as HTMLImageElement | HTMLVideoElement | null
  if (!media || media.dataset.fallbackApplied === "1") return
  media.dataset.fallbackApplied = "1"
  fallbackAsImage.value = true
}

function openLaunch() {
  launchOpen.value = true
}
</script>

<template>
  <article class="workspace-official-tool-card">
    <div class="workspace-official-tool-image">
      <template v-if="hasRenderableCover">
        <!-- v-if="tool.mediaType === 'video'" -->
        <video
          v-if="tool.mediaType === 'video' && !fallbackAsImage"
          :src="tool.image"
          class="workspace-tool-media"
          muted
          loop
          playsinline
          autoplay
          preload="metadata"
          @error="handleCoverError"
        />
        <img
          v-else
          :src="fallbackAsImage ? DEFAULT_TOOL_COVER_URL : tool.image"
          :alt="tool.title"
          class="workspace-tool-media"
          @error="handleCoverError"
        />
      </template>
      <div v-else class="workspace-official-tool-placeholder">
        <Sparkles :size="24" />
      </div>
      <span class="workspace-tool-tag"><Sparkles :size="14" />{{ tool.tag }}</span>
      <RouterLink v-if="usesWorkspaceRoute" :to="tool.useTo" class="workspace-use-tool">
        使用此工具
        <ArrowRight :size="13" />
      </RouterLink>
      <button
        v-else
        type="button"
        class="workspace-use-tool"
        style="border: none; cursor: pointer; font-family: inherit"
        @click="openLaunch"
      >
        使用此工具
        <ArrowRight :size="13" />
      </button>
    </div>
    <div class="workspace-official-tool-copy">
      <RouterLink :to="tool.to">{{ tool.title }}</RouterLink>
      <p>{{ tool.description }}</p>
      <span><Zap :size="13" />{{ tool.costLabel }}</span>
    </div>

    <ToolLaunchModal
      v-if="!usesWorkspaceRoute"
      :open="launchOpen"
      :tool-code="tool.id"
      :tool-name="tool.title"
      :tool-cover="tool.image"
      @close="launchOpen = false"
    />
  </article>
</template>
