<script setup lang="ts">
import { computed } from "vue"
import { useRouter } from "vue-router"
import { ImageIcon, Loader2, Sparkles, UserRound } from "lucide-vue-next"
import AssetCard from "@/components/AssetCard.vue"
import { trackCommunityEvent } from "@/api/communityApi"
import { getApiOrigin } from "@/api/client"
import { useAuthStore } from "@/store/authStore"
import type { CommunityPost } from "@/api/types"
import { assetFromCommunityPost } from "@/utils/assetPreviewAdapter"
import { communityDisplaySubtitle, communityDisplayTitle } from "@/utils/communityDisplay"
import { resolveCommunityPrompt } from "@/utils/communityPostNormalize"
import type { AssetPreviewItem } from "@/types/assetPreview"

const props = defineProps<{
  posts: CommunityPost[]
  featuredPosts?: CommunityPost[]
  loading?: boolean
  loadingMore?: boolean
  error?: string
  hasNext?: boolean
}>()

const emit = defineEmits<{
  loadMore: []
}>()

const router = useRouter()
const auth = useAuthStore()

function mediaUrl(value?: string | null) {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

function postKind(post: CommunityPost): AssetPreviewItem["kind"] {
  const modality = (post.modality || "").toLowerCase()
  if (modality.includes("video")) return "video"
  if (modality.includes("audio")) return "audio"
  if (modality.includes("image")) return "image"
  if (modality.includes("text")) return "text"
  return "other"
}

function toAsset(post: CommunityPost): AssetPreviewItem {
  const prompt = resolveCommunityPrompt(post)
  const kind = postKind(post)
  const base = assetFromCommunityPost(post, mediaUrl(post.coverUrl))
  const title = communityDisplayTitle({
    title: post.title,
    prompt,
    promptPreview: post.promptPreview || prompt,
    topic: post.topic,
    tags: post.tags,
    toolName: post.toolName,
    toolCode: post.toolCode,
    kind,
  })
  const subtitle =
    communityDisplaySubtitle({
      description: post.description,
      prompt,
      promptPreview: post.promptPreview || prompt,
      topic: post.topic,
      tags: post.tags,
      toolName: post.toolName,
      toolCode: post.toolCode,
    }) || undefined

  return {
    ...base,
    title,
    subtitle,
    source: "community",
  } as AssetPreviewItem
}

const coverPostId = computed(() => {
  const featured = props.featuredPosts?.[0]
  if (featured) return featured.id
  if (!props.posts.length) return null
  const best = [...props.posts].sort((a, b) => {
    const scoreA = (a.sameStyleCount ?? 0) * 3 + (a.likeCount ?? 0)
    const scoreB = (b.sameStyleCount ?? 0) * 3 + (b.likeCount ?? 0)
    return scoreB - scoreA
  })[0]
  return best?.id ?? null
})

const gridItems = computed(() =>
  props.posts.map((post) => ({
    post,
    asset: toAsset(post),
    isCover: post.id === coverPostId.value,
  })),
)

function openPost(asset: AssetPreviewItem) {
  if (!asset.communityPostId) return
  void trackCommunityEvent(
    { postId: asset.communityPostId, eventType: "detail_view", source: "creator_profile", toolCode: asset.toolCode },
    { token: auth.token },
  ).catch(() => undefined)
  router.push(`/community/posts/${asset.communityPostId}`)
}
</script>

<template>
  <section class="portfolio-section">
    <div class="section-title">
      <UserRound class="h-4 w-4" />
      <span>公开作品</span>
    </div>

    <div v-if="loading" class="state-panel">
      <Loader2 class="h-5 w-5 animate-spin" />
      加载主页中
    </div>
    <div v-else-if="error" class="state-panel state-panel--error">{{ error }}</div>
    <div v-else-if="!posts.length" class="state-panel state-panel--empty">
      <ImageIcon class="h-8 w-8 opacity-20" />
      <p>TA 的第一件作品，也许就在明天</p>
    </div>

    <div v-else class="portfolio-grid">
      <div
        v-for="item in gridItems"
        :key="item.post.id"
        class="portfolio-cell"
        :class="{ 'portfolio-cell--cover': item.isCover }"
      >
        <AssetCard
          :asset="item.asset"
          source="community"
          gallery
          compact
          class="portfolio-card"
          @open="openPost"
        />
        <span v-if="item.isCover" class="cover-badge">
          <Sparkles class="h-3.5 w-3.5" />
          封面作品
        </span>
      </div>
    </div>

    <button
      v-if="hasNext && !loading"
      class="load-more"
      type="button"
      :disabled="loadingMore"
      @click="emit('loadMore')"
    >
      <Loader2 v-if="loadingMore" class="h-4 w-4 animate-spin" />
      加载更多
    </button>
  </section>
</template>

<style scoped>
.portfolio-section {
  padding-top: 36px;
  padding-bottom: 48px;
}

.section-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: rgb(255 255 255 / 0.58);
  font-weight: 800;
  font-size: 14px;
  letter-spacing: 0.06em;
}

.portfolio-grid {
  margin-top: 24px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  grid-auto-flow: dense;
  gap: 20px;
}

.portfolio-cell {
  position: relative;
  min-width: 0;
}

.portfolio-cell--cover {
  grid-column: span 2;
  grid-row: span 2;
}

.portfolio-cell :deep(.asset-card.gallery) {
  height: 100%;
  border-color: rgb(255 255 255 / 0.08);
  background: rgb(255 255 255 / 0.04);
  transition:
    transform 0.22s ease,
    border-color 0.22s ease,
    box-shadow 0.22s ease;
}

.portfolio-cell :deep(.asset-card.gallery:hover) {
  transform: translateY(-4px);
  border-color: var(--profile-accent-soft);
  box-shadow: var(--profile-card-hover-shadow);
}

.portfolio-cell :deep(.gallery .body) {
  background: var(--profile-glass-bg);
  backdrop-filter: blur(16px) saturate(140%);
  border-radius: 0 0 28px 28px;
}

.cover-badge {
  position: absolute;
  left: 14px;
  top: 14px;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border-radius: 999px;
  border: 1px solid var(--profile-accent-soft);
  background: rgb(0 0 0 / 0.45);
  backdrop-filter: blur(8px);
  color: var(--profile-accent-light);
  padding: 5px 10px;
  font-size: 11px;
  font-weight: 700;
  pointer-events: none;
}

.state-panel {
  margin-top: 24px;
  display: flex;
  flex-direction: column;
  min-height: 180px;
  align-items: center;
  justify-content: center;
  gap: 12px;
  border: 1px dashed rgb(255 255 255 / 0.1);
  border-radius: 28px;
  color: rgb(255 255 255 / 0.48);
}

.state-panel--error {
  color: rgb(254 202 202);
}

.state-panel--empty p {
  margin: 0;
  font-size: 14px;
}

.load-more {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin: 32px auto 0;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.05);
  color: rgb(255 255 255 / 0.72);
  padding: 10px 20px;
  font-weight: 700;
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease;
}

.load-more:hover:not(:disabled) {
  border-color: var(--profile-accent-soft);
  background: var(--profile-accent-soft);
  color: #fff;
}

.load-more:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

@media (max-width: 1024px) {
  .portfolio-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .portfolio-cell--cover {
    grid-column: span 2;
    grid-row: span 1;
  }
}

@media (max-width: 640px) {
  .portfolio-grid {
    grid-template-columns: 1fr;
  }

  .portfolio-cell--cover {
    grid-column: span 1;
  }
}
</style>
