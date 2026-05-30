<script setup lang="ts">
import { computed } from "vue"
import { ArrowRight, Clock, Eye, FileText, Heart, Image as ImageIcon, Music, Sparkles, Star, Video, Wand2 } from "lucide-vue-next"
import { getApiOrigin } from "@/api/client"
import type { AssetPreviewItem } from "@/types/assetPreview"

const props = withDefaults(
  defineProps<{
    asset: AssetPreviewItem
    source?: "private" | "community"
    compact?: boolean
  }>(),
  {
    source: "private",
    compact: false,
  },
)

const emit = defineEmits<{
  open: [asset: AssetPreviewItem]
}>()

const mediaUrl = computed(() => normalizeMediaUrl(props.asset.url))
const badgeText = computed(() => {
  if (props.asset.pinned) return "置顶"
  if (props.asset.featured) return "精选"
  return props.asset.modality || kindLabel(props.asset.kind)
})
const subtitle = computed(() => props.asset.subtitle || props.asset.toolName || props.asset.toolCode || "")
const previewText = computed(() => props.asset.rawText || props.asset.prompt || props.asset.title)

function normalizeMediaUrl(value?: string | null) {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

function kindLabel(kind: AssetPreviewItem["kind"]) {
  if (kind === "image") return "图片"
  if (kind === "video") return "视频"
  if (kind === "audio") return "音频"
  if (kind === "text") return "文本"
  return "作品"
}

function formatTime(value?: string | null) {
  if (!value) return ""
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  })
}
</script>

<template>
  <article class="asset-card group" :class="{ compact }" @click="emit('open', asset)">
    <div class="media-frame">
      <img
        v-if="asset.kind === 'image' && mediaUrl"
        :src="mediaUrl"
        :alt="asset.title"
        class="media"
        loading="lazy"
      />
      <video
        v-else-if="asset.kind === 'video' && mediaUrl"
        :src="mediaUrl"
        class="media bg-black"
        :controls="source === 'private'"
        muted
        loop
        playsinline
        preload="metadata"
      />
      <div v-else-if="asset.kind === 'audio'" class="audio-cover">
        <div class="icon-bubble">
          <Music class="h-6 w-6" />
        </div>
        <audio v-if="mediaUrl" :src="mediaUrl" controls preload="metadata" class="w-full" @click.stop />
      </div>
      <div v-else class="text-cover">
        <FileText class="h-6 w-6 text-white/35" />
        <p>{{ previewText }}</p>
      </div>

      <div class="absolute left-3 top-3 flex items-center gap-2">
        <span class="pill">
          <Sparkles v-if="asset.featured || asset.pinned" class="h-3.5 w-3.5" />
          <ImageIcon v-else-if="asset.kind === 'image'" class="h-3.5 w-3.5" />
          <Video v-else-if="asset.kind === 'video'" class="h-3.5 w-3.5" />
          <Music v-else-if="asset.kind === 'audio'" class="h-3.5 w-3.5" />
          <FileText v-else class="h-3.5 w-3.5" />
          {{ badgeText }}
        </span>
      </div>

      <slot name="media-actions" />
    </div>

    <div class="body">
      <div class="flex items-start justify-between gap-3">
        <div class="min-w-0">
          <h3>{{ asset.title }}</h3>
          <p class="subtitle" :title="subtitle">{{ subtitle }}</p>
        </div>
        <div v-if="formatTime(asset.createdAt)" class="time">
          <Clock class="h-3 w-3" />
          {{ formatTime(asset.createdAt) }}
        </div>
      </div>

      <div v-if="asset.topic || asset.tags?.length" class="tag-row">
        <span v-if="asset.topic">{{ asset.topic }}</span>
        <span v-for="tag in asset.tags?.slice(0, 3)" :key="tag">#{{ tag }}</span>
      </div>

      <div class="footer">
        <div v-if="source === 'community'" class="stats">
          <span><Eye class="h-3.5 w-3.5" />{{ asset.stats?.views || 0 }}</span>
          <span><Heart class="h-3.5 w-3.5" />{{ asset.stats?.likes || 0 }}</span>
          <span><Star class="h-3.5 w-3.5" />{{ asset.stats?.favorites || 0 }}</span>
          <span><Wand2 class="h-3.5 w-3.5" />{{ asset.stats?.sameStyle || 0 }}</span>
        </div>
        <slot name="footer">
          <span v-if="source === 'private'" class="tool-code">{{ asset.toolCode }}</span>
          <span class="open-link">
            {{ source === "community" ? "查看作品" : "查看完整内容" }}
            <ArrowRight class="h-3 w-3" />
          </span>
        </slot>
      </div>
    </div>
  </article>
</template>

<style scoped>
.asset-card {
  display: inline-block;
  width: 100%;
  break-inside: avoid;
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 24px;
  background: rgb(255 255 255 / 0.04);
  box-shadow: 0 18px 42px rgb(0 0 0 / 0.24);
  cursor: zoom-in;
  transition: transform 0.2s ease, border-color 0.2s ease, background 0.2s ease;
}

.asset-card.compact {
  border-radius: 8px;
}

.asset-card:hover {
  transform: translateY(-3px);
  border-color: rgb(176 92 255 / 0.5);
  background: rgb(255 255 255 / 0.055);
}

.media-frame {
  position: relative;
  overflow: hidden;
  background: rgb(255 255 255 / 0.05);
}

.media {
  display: block;
  width: 100%;
  max-height: 560px;
  object-fit: cover;
}

.compact .media-frame {
  aspect-ratio: 4 / 3;
}

.compact .media {
  height: 100%;
}

.audio-cover,
.text-cover {
  display: grid;
  gap: 16px;
  min-height: 230px;
  align-content: center;
  padding: 48px 20px 22px;
  color: rgb(255 255 255 / 0.72);
}

.text-cover p {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  -webkit-line-clamp: 10;
  -webkit-box-orient: vertical;
  white-space: pre-line;
  line-height: 1.65;
}

.icon-bubble {
  display: flex;
  width: 52px;
  height: 52px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.1);
  color: rgb(176 92 255);
}

.pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.58);
  color: #fff;
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 800;
  backdrop-filter: blur(12px);
}

.body {
  display: grid;
  gap: 12px;
  padding: 16px;
}

h3 {
  margin: 0;
  overflow: hidden;
  color: #fff;
  font-size: 16px;
  font-weight: 800;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.subtitle {
  margin: 5px 0 0;
  overflow: hidden;
  color: rgb(255 255 255 / 0.45);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.time,
.stats,
.footer,
.open-link {
  display: inline-flex;
  align-items: center;
}

.time {
  flex-shrink: 0;
  gap: 4px;
  color: rgb(255 255 255 / 0.35);
  font-size: 12px;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-row span {
  border-radius: 999px;
  background: rgb(255 255 255 / 0.07);
  color: rgb(125 211 252 / 0.86);
  padding: 4px 8px;
  font-size: 12px;
}

.footer {
  justify-content: space-between;
  gap: 12px;
}

.stats {
  flex-wrap: wrap;
  gap: 10px;
  color: rgb(255 255 255 / 0.46);
  font-size: 12px;
}

.stats span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.tool-code {
  overflow: hidden;
  color: rgb(255 255 255 / 0.35);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.open-link {
  flex-shrink: 0;
  gap: 4px;
  color: rgb(176 92 255);
  font-size: 12px;
  font-weight: 800;
}
</style>
