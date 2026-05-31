<script setup lang="ts">
import { computed } from "vue"
import { useRouter } from "vue-router"
import { ArrowRight, Clock, Eye, FileText, Heart, Image as ImageIcon, Music, Sparkles, Star, Video, Wand2 } from "lucide-vue-next"
import { getApiOrigin } from "@/api/client"
import UserAvatar from "@/components/UserAvatar.vue"
import type { AssetPreviewItem } from "@/types/assetPreview"

const props = withDefaults(
  defineProps<{
    asset: AssetPreviewItem
    source?: "private" | "community"
    compact?: boolean
    gallery?: boolean
  }>(),
  {
    source: "private",
    compact: false,
    gallery: false,
  },
)

const emit = defineEmits<{
  open: [asset: AssetPreviewItem]
}>()

const router = useRouter()
const mediaUrl = computed(() => normalizeMediaUrl(props.asset.url))
const showFeaturedBadge = computed(() => Boolean(props.asset.featured || props.asset.pinned))
const featuredBadgeText = computed(() => (props.asset.pinned ? "置顶" : "精选"))
const showCreator = computed(() => Boolean(props.gallery && props.source === "community" && props.asset.authorUserId))
const creatorName = computed(
  () => props.asset.authorName?.trim() || (props.asset.authorUserId ? `用户 ${props.asset.authorUserId}` : ""),
)
const subtitle = computed(() => props.asset.subtitle || props.asset.toolName || props.asset.toolCode || "")
const previewText = computed(() => props.asset.rawText || props.asset.prompt || props.asset.title)
const primaryStat = computed(() => {
  const likes = props.asset.stats?.likes || 0
  const views = props.asset.stats?.views || 0
  return likes > 0 ? { icon: Heart, value: likes, label: "点赞" } : { icon: Eye, value: views, label: "浏览" }
})

function normalizeMediaUrl(value?: string | null) {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
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

function openAuthorProfile() {
  if (!props.asset.authorUserId) return
  router.push(`/u/${props.asset.authorUserId}`)
}
</script>

<template>
  <article
    class="asset-card group"
    :class="{ compact, gallery: gallery || (source === 'community' && compact) }"
    @click="emit('open', asset)"
  >
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

      <div class="media-overlay" aria-hidden="true" />

      <div class="badge-stack">
        <span v-if="showFeaturedBadge" class="glass-badge featured">
          <Sparkles class="h-3.5 w-3.5" />
          {{ featuredBadgeText }}
        </span>
        <span v-else-if="gallery || source === 'community'" class="glass-badge icon-only" :aria-label="asset.kind">
          <ImageIcon v-if="asset.kind === 'image'" class="h-3.5 w-3.5" />
          <Video v-else-if="asset.kind === 'video'" class="h-3.5 w-3.5" />
          <Music v-else-if="asset.kind === 'audio'" class="h-3.5 w-3.5" />
          <FileText v-else class="h-3.5 w-3.5" />
        </span>
        <span v-else class="glass-badge">
          <Sparkles v-if="asset.featured || asset.pinned" class="h-3.5 w-3.5" />
          <ImageIcon v-else-if="asset.kind === 'image'" class="h-3.5 w-3.5" />
          <Video v-else-if="asset.kind === 'video'" class="h-3.5 w-3.5" />
          <Music v-else-if="asset.kind === 'audio'" class="h-3.5 w-3.5" />
          <FileText v-else class="h-3.5 w-3.5" />
          {{ asset.modality || asset.kind }}
        </span>
      </div>

      <div v-if="source === 'community' && (gallery || compact)" class="hover-stats">
        <span><component :is="primaryStat.icon" class="h-3.5 w-3.5" />{{ primaryStat.value }}</span>
      </div>

      <slot name="media-actions" />
    </div>

    <div class="body">
      <button
        v-if="showCreator"
        type="button"
        class="creator-row"
        @click.stop="openAuthorProfile"
      >
        <UserAvatar :src="asset.authorAvatarUrl" :name="creatorName" size="sm" />
        <span class="creator-name">{{ creatorName }}</span>
      </button>

      <div class="title-row">
        <div class="min-w-0">
          <h3>{{ asset.title }}</h3>
          <p v-if="subtitle" class="subtitle" :title="subtitle">{{ subtitle }}</p>
        </div>
        <div v-if="!gallery && formatTime(asset.createdAt)" class="time">
          <Clock class="h-3 w-3" />
          {{ formatTime(asset.createdAt) }}
        </div>
      </div>

      <div v-if="!gallery && (asset.topic || asset.tags?.length)" class="tag-row">
        <span v-if="asset.topic">{{ asset.topic }}</span>
        <span v-for="tag in asset.tags?.slice(0, 3)" :key="tag">#{{ tag }}</span>
      </div>

      <div v-if="!gallery" class="footer">
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
  transition: transform 0.28s cubic-bezier(0.22, 1, 0.36, 1), box-shadow 0.28s ease, background 0.28s ease;
}

.asset-card.compact {
  border-radius: 8px;
}

.asset-card.gallery {
  border: 0;
  border-radius: 28px;
  background: rgb(255 255 255 / 0.028);
  box-shadow:
    0 28px 64px rgb(0 0 0 / 0.38),
    inset 0 1px 0 rgb(255 255 255 / 0.04);
}

@supports (corner-shape: squircle) {
  .asset-card.gallery {
    corner-shape: squircle;
  }
}

.asset-card:hover {
  transform: translateY(-3px);
  border-color: rgb(176 92 255 / 0.5);
  background: rgb(255 255 255 / 0.055);
}

.asset-card.gallery:hover {
  transform: translateY(-6px);
  background: rgb(255 255 255 / 0.04);
  box-shadow:
    var(--profile-card-hover-shadow, 0 36px 88px rgb(0 0 0 / 0.48)),
    inset 0 1px 0 rgb(255 255 255 / 0.06);
}

.media-frame {
  position: relative;
  overflow: hidden;
  background: rgb(255 255 255 / 0.05);
}

.gallery .media-frame {
  border-radius: 28px 28px 0 0;
}

.media {
  display: block;
  width: 100%;
  max-height: 560px;
  object-fit: cover;
  transition: transform 0.45s cubic-bezier(0.22, 1, 0.36, 1);
}

.asset-card.gallery:hover .media {
  transform: scale(1.03);
}

.compact .media-frame,
.gallery .media-frame {
  aspect-ratio: 4 / 3;
}

.compact .media,
.gallery .media {
  height: 100%;
}

.media-overlay {
  position: absolute;
  inset: 0;
  opacity: 0;
  background: linear-gradient(120deg, transparent 35%, rgb(255 255 255 / 0.16) 50%, transparent 65%);
  transform: translateX(-120%);
  transition: transform 0.75s ease, opacity 0.3s ease;
  pointer-events: none;
}

.asset-card.gallery:hover .media-overlay {
  opacity: 1;
  transform: translateX(120%);
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

.badge-stack {
  position: absolute;
  left: 14px;
  top: 14px;
  display: flex;
  gap: 8px;
}

.glass-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.1);
  color: rgb(255 255 255 / 0.88);
  padding: 7px 10px;
  font-size: 11px;
  font-weight: 600;
  backdrop-filter: blur(16px) saturate(140%);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.12);
}

.glass-badge.icon-only {
  width: 34px;
  height: 34px;
  justify-content: center;
  padding: 0;
  color: rgb(255 255 255 / 0.82);
}

.glass-badge.featured {
  background: rgb(176 92 255 / 0.22);
}

.hover-stats {
  position: absolute;
  right: 14px;
  bottom: 14px;
  opacity: 0;
  transform: translateY(6px);
  transition: opacity 0.24s ease, transform 0.24s ease;
}

.hover-stats span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.42);
  backdrop-filter: blur(14px);
  color: rgb(255 255 255 / 0.88);
  padding: 7px 11px;
  font-size: 12px;
  font-weight: 500;
}

.asset-card.gallery:hover .hover-stats {
  opacity: 1;
  transform: translateY(0);
}

.body {
  display: grid;
  gap: 12px;
  padding: 16px;
}

.gallery .body {
  gap: 10px;
  padding: 14px 6px 6px;
}

.creator-row {
  display: inline-flex;
  width: fit-content;
  max-width: 100%;
  align-items: center;
  gap: 8px;
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.72);
  padding: 0;
  cursor: pointer;
  transition: color 0.2s ease;
}

.creator-row:hover {
  color: #fff;
}

.creator-name {
  overflow: hidden;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

h3 {
  margin: 0;
  overflow: hidden;
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gallery h3 {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.subtitle {
  margin: 6px 0 0;
  overflow: hidden;
  color: rgb(255 255 255 / 0.42);
  font-size: 12px;
  font-weight: 400;
  line-height: 1.5;
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
