<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, ImageOff, Loader2 } from 'lucide-vue-next'
import { searchCommunityPosts } from '@/api/communityApi'
import type { CommunityPost, PageResult } from '@/api/types'
import OptimizedImage from '@/components/OptimizedImage.vue'
import { communityDisplayTitle } from '@/utils/communityDisplay'
import {
  isVideoMediaUrl,
  normalizeCommunityMediaUrl,
  resolveCommunityImageUrls,
  resolveCommunityPostKind,
  resolveOssVideoPosterUrl,
} from '@/utils/communityPostMedia'

type ShowcaseItem = {
  post: CommunityPost
  previewUrl: string
  title: string
}

const posts = ref<CommunityPost[]>([])
const loading = ref(true)
const loadError = ref(false)
const failedPostIds = ref<Set<number>>(new Set())

function postTitle(post: CommunityPost) {
  return communityDisplayTitle({
    title: post.title,
    topic: post.topic,
    tags: post.tags,
    toolName: post.toolName,
    toolCode: post.toolCode,
    kind: resolveCommunityPostKind(post.modality),
    modality: post.modality,
    promptVisible: post.promptVisible,
  })
}

function postPreviewUrl(post: CommunityPost) {
  const imageUrl = resolveCommunityImageUrls(post)[0]
  if (imageUrl) return imageUrl

  const coverUrl = normalizeCommunityMediaUrl(post.coverUrl)
  if (coverUrl && !isVideoMediaUrl(coverUrl)) return coverUrl

  if (resolveCommunityPostKind(post.modality) === 'video') {
    return resolveOssVideoPosterUrl(post.mediaUrl || post.coverUrl)
  }

  return ''
}

const showcaseItems = computed<ShowcaseItem[]>(() =>
  posts.value
    .map((post) => ({ post, previewUrl: postPreviewUrl(post), title: postTitle(post) }))
    .filter((item) => Boolean(item.previewUrl) && !failedPostIds.value.has(item.post.id))
    .sort((a, b) => Number(b.previewUrl.startsWith('http')) - Number(a.previewUrl.startsWith('http')))
    .slice(0, 16),
)

function markMediaFailed(postId: number) {
  const next = new Set(failedPostIds.value)
  next.add(postId)
  failedPostIds.value = next
}

function buildTrack(items: ShowcaseItem[]) {
  if (!items.length) return []
  const base: ShowcaseItem[] = []
  while (base.length < 7) base.push(...items)
  const normalized = base.slice(0, Math.max(7, items.length))
  return [...normalized, ...normalized]
}

const topTrack = computed(() => buildTrack(showcaseItems.value.filter((_, index) => index % 2 === 0)))
const bottomTrack = computed(() => {
  const bottom = showcaseItems.value.filter((_, index) => index % 2 === 1)
  return buildTrack(bottom.length ? bottom : showcaseItems.value)
})

async function loadCommunityWorks() {
  loading.value = true
  loadError.value = false
  try {
    const page = await searchCommunityPosts({
      query: { pageNo: 1, pageSize: 100, sort: 'LATEST' },
    }).catch(() => null as PageResult<CommunityPost> | null)
    posts.value = page?.list || []
    loadError.value = !page
  } finally {
    loading.value = false
  }
}

onMounted(loadCommunityWorks)
</script>

<template>
  <section class="community-showcase" aria-labelledby="community-showcase-title">
    <div class="community-showcase__heading">
      <div>
        <p class="community-showcase__kicker">社区精选</p>
        <h2 id="community-showcase-title">来自真实创作的灵感</h2>
        <p class="community-showcase__lead">浏览社区公开作品，从结果回到工具、提示词与创作流程。</p>
      </div>
      <RouterLink to="/community" class="community-showcase__link">
        查看社区作品
        <ArrowRight class="h-4 w-4" aria-hidden="true" />
      </RouterLink>
    </div>

    <div v-if="loading" class="community-showcase__loading" role="status">
      <Loader2 class="h-5 w-5 animate-spin" aria-hidden="true" />
      正在载入社区作品
    </div>

    <div v-else-if="showcaseItems.length" class="community-showcase__viewport">
      <div class="community-showcase__fade community-showcase__fade--left" aria-hidden="true" />
      <div class="community-showcase__fade community-showcase__fade--right" aria-hidden="true" />

      <div class="community-showcase__track community-showcase__track--forward">
        <RouterLink
          v-for="(item, index) in topTrack"
          :key="`top-${item.post.id}-${index}`"
          :to="`/community/posts/${item.post.id}`"
          class="community-showcase__card"
          :aria-label="`查看作品：${item.title}`"
          :aria-hidden="index >= topTrack.length / 2 ? 'true' : undefined"
          :tabindex="index >= topTrack.length / 2 ? -1 : undefined"
        >
          <OptimizedImage
            :src="item.previewUrl"
            :alt="item.title"
            preset="list"
            loading="eager"
            fetchpriority="low"
            @failed="markMediaFailed(item.post.id)"
          />
          <span>{{ item.title }}</span>
        </RouterLink>
      </div>

      <div class="community-showcase__track community-showcase__track--reverse">
        <RouterLink
          v-for="(item, index) in bottomTrack"
          :key="`bottom-${item.post.id}-${index}`"
          :to="`/community/posts/${item.post.id}`"
          class="community-showcase__card"
          :aria-label="`查看作品：${item.title}`"
          :aria-hidden="index >= bottomTrack.length / 2 ? 'true' : undefined"
          :tabindex="index >= bottomTrack.length / 2 ? -1 : undefined"
        >
          <OptimizedImage
            :src="item.previewUrl"
            :alt="item.title"
            preset="list"
            loading="eager"
            fetchpriority="low"
            @failed="markMediaFailed(item.post.id)"
          />
          <span>{{ item.title }}</span>
        </RouterLink>
      </div>
    </div>

    <div v-else class="community-showcase__empty" role="status">
      <ImageOff class="h-5 w-5" aria-hidden="true" />
      <p>{{ loadError ? '社区作品暂时无法载入，请稍后再试。' : '社区还没有可展示的公开作品。' }}</p>
      <RouterLink to="/community">进入社区</RouterLink>
    </div>
  </section>
</template>

<style scoped>
.community-showcase {
  overflow: hidden;
  padding: 96px 0 104px;
  border-top: 1px solid rgb(255 255 255 / 0.06);
  background: rgb(17 18 22);
}

.community-showcase__heading {
  display: flex;
  max-width: 1180px;
  margin: 0 auto 48px;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
  padding: 0 24px;
}

.community-showcase__kicker {
  margin: 0 0 12px;
  color: var(--brand-active-text);
  font-size: 13px;
  font-weight: 700;
}

.community-showcase h2 {
  margin: 0;
  color: rgb(255 255 255 / 0.96);
  font-size: clamp(32px, 4vw, 48px);
  font-weight: 740;
  line-height: 1.18;
}

.community-showcase__lead {
  margin: 16px 0 0;
  color: rgb(255 255 255 / 0.62);
  font-size: 16px;
  line-height: 1.7;
}

.community-showcase__link {
  display: inline-flex;
  min-height: 44px;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  color: rgb(255 255 255 / 0.78);
  font-size: 14px;
  font-weight: 650;
  transition: color 160ms ease;
}

.community-showcase__link:hover {
  color: var(--brand-active-text);
}

.community-showcase__viewport {
  position: relative;
  display: grid;
  gap: 24px;
}

.community-showcase__track {
  display: flex;
  width: max-content;
  gap: 16px;
  will-change: transform;
}

.community-showcase__track--forward {
  animation: community-forward 42s linear infinite;
}

.community-showcase__track--reverse {
  animation: community-reverse 46s linear infinite;
}

.community-showcase__viewport:hover .community-showcase__track {
  animation-play-state: paused;
}

.community-showcase__card {
  position: relative;
  display: block;
  width: 304px;
  height: 208px;
  flex: 0 0 auto;
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 12px;
  background: rgb(255 255 255 / 0.04);
}

.community-showcase__card :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 240ms ease;
}

.community-showcase__card:hover :deep(img) {
  transform: scale(1.025);
}

.community-showcase__card span {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  overflow: hidden;
  padding: 32px 14px 12px;
  background: linear-gradient(to top, rgb(0 0 0 / 0.78), transparent);
  color: #fff;
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
  opacity: 0;
  transition: opacity 160ms ease;
}

.community-showcase__card:hover span,
.community-showcase__card:focus-visible span {
  opacity: 1;
}

.community-showcase__card:focus-visible,
.community-showcase__link:focus-visible,
.community-showcase__empty a:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 3px;
}

.community-showcase__fade {
  position: absolute;
  top: 0;
  bottom: 0;
  z-index: 2;
  width: min(10vw, 120px);
  pointer-events: none;
}

.community-showcase__fade--left {
  left: 0;
  background: linear-gradient(90deg, rgb(17 18 22), transparent);
}

.community-showcase__fade--right {
  right: 0;
  background: linear-gradient(-90deg, rgb(17 18 22), transparent);
}

.community-showcase__loading,
.community-showcase__empty {
  display: flex;
  min-height: 280px;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: rgb(255 255 255 / 0.6);
}

.community-showcase__empty {
  flex-direction: column;
  padding: 24px;
  text-align: center;
}

.community-showcase__empty p {
  margin: 0;
}

.community-showcase__empty a {
  color: var(--brand-active-text);
  font-weight: 650;
}

@keyframes community-forward {
  from { transform: translateX(0); }
  to { transform: translateX(calc(-50% - 8px)); }
}

@keyframes community-reverse {
  from { transform: translateX(calc(-50% - 8px)); }
  to { transform: translateX(0); }
}

@media (max-width: 640px) {
  .community-showcase {
    padding: 72px 0 80px;
  }

  .community-showcase__heading {
    margin-bottom: 32px;
    align-items: flex-start;
    flex-direction: column;
    gap: 16px;
    padding: 0 16px;
  }

  .community-showcase__card {
    width: 248px;
    height: 168px;
  }

  .community-showcase__card span {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .community-showcase__viewport {
    overflow-x: auto;
    padding: 0 16px 8px;
  }

  .community-showcase__track {
    animation: none;
    transform: none;
  }

  .community-showcase__track--reverse {
    display: none;
  }

  .community-showcase__card :deep(img),
  .community-showcase__card span,
  .community-showcase__link {
    transition: none;
  }
}
</style>
