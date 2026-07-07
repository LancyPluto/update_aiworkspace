<script setup lang="ts">
import { computed, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from "vue"
import { useRoute, useRouter } from "vue-router"
import {
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Heart,
  Loader2,
  Music,
  RefreshCcw,
  Search,
  Sparkles,
  Star,
  Store,
  Wand2,
  X,
} from "lucide-vue-next"
import CommunityAudioMedia from "@/components/community/CommunityAudioMedia.vue"
import CommunityCollectionPickerModal from "@/components/community/CommunityCollectionPickerModal.vue"
import CommunityOptimizedMedia from "@/components/community/CommunityOptimizedMedia.vue"
import { ApiBusinessError } from "@/api/client"
import {
  fetchCommunityTopics,
  likeCommunityPost,
  markCommunityPostSameStyle,
  searchCommunityPosts,
  trackCommunityEvent,
  unlikeCommunityPost,
} from "@/api/communityApi"
import type { CommunityPost, CommunityTopic } from "@/api/types"
import {
  COMMUNITY_POST_UNPUBLISHED_EVENT,
  type CommunityPostUnpublishedDetail,
  preloadDefaultCommunityCollection,
  resetDefaultCommunityCollectionCache,
  favoritePostToCollection,
  unfavoritePostFromAllCollections,
} from "@/utils/communitySync"
import MasonryLayout from "@/components/MasonryLayout.vue"
import UserAvatar from "@/components/UserAvatar.vue"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { getSessionBearerJwt } from "@/api/sessionBearer"
import { assetFromCommunityPost } from "@/utils/assetPreviewAdapter"
import { openDashboardWithAsset } from "@/utils/assetReplay"
import { communityDisplayTitle, communityCardDescription } from "@/utils/communityDisplay"
import { hasCommunityAudioMedia, resolveCommunityAudioMedia } from "@/utils/communityAudioMedia"
import { resolveCommunityAuthorAvatar, resolveCommunityAuthorName } from "@/utils/communityPostNormalize"
import {
  normalizeCommunityMediaUrl,
  resolveCommunityImageUrls,
  resolveCommunityPostKind,
} from "@/utils/communityPostMedia"

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

function resolveAuthToken() {
  return auth.token ?? getSessionBearerJwt()
}

const posts = ref<CommunityPost[]>([])
const topics = ref<CommunityTopic[]>([])
const total = ref(0)
const loading = ref(false)
const loadingMore = ref(false)
const topicsLoading = ref(false)
const error = ref("")
const sameStyleError = ref("")
const pageNo = ref(1)
const hasNext = ref(false)
const modality = ref("")
const sort = ref("SAME_STYLE")
const featuredOnly = ref(false)
const keyword = ref("")
const topic = ref("")
const searchOpen = ref(false)
const sortDropdownOpen = ref(false)
const sameStyleLoadingId = ref<number | null>(null)
const actingPostId = ref<number | null>(null)
const loadSentinelRef = ref<HTMLElement | null>(null)
const sortDropdownRef = ref<HTMLElement | null>(null)
const playingPostId = ref<number | null>(null)
const galleryAudioPlaying = ref(false)
const galleryAudioCurrentTime = ref(0)
const galleryAudioDuration = ref(0)
const galleryAudioRef = ref<HTMLAudioElement | null>(null)
const favoritePickerOpen = ref(false)
const favoritePickerPost = ref<CommunityPost | null>(null)
const favoritePickerSubmitting = ref(false)
const activeMediaIndexes = ref<Record<number, number>>({})
let loadObserver: IntersectionObserver | null = null

const modalityFilters = [
  { label: "全部", value: "" },
  { label: "图片", value: "IMAGE" },
  { label: "视频", value: "VIDEO" },
  { label: "文本", value: "TEXT" },
  { label: "音频", value: "AUDIO" },
]

const quickSorts = [
  { label: "最新作品", value: "LATEST" },
  { label: "热门同款", value: "SAME_STYLE" },
]

const extendedSorts = [
  { label: "按发布时间", value: "LATEST" },
  { label: "按点赞数", value: "POPULAR" },
  { label: "按收藏数", value: "FAVORITES" },
  { label: "按同款数", value: "SAME_STYLE" },
  { label: "按浏览量", value: "VIEWS" },
]

const currentSortLabel = computed(() => extendedSorts.find((item) => item.value === sort.value)?.label || "更多排序")

const skeletonItems = computed(() =>
  Array.from({ length: 8 }, (_, index) => ({
    id: index + 1,
    height: 120 + (index % 4) * 48,
  })),
)

function postImageUrls(post: CommunityPost) {
  return resolveCommunityImageUrls(post)
}

function activePostImageUrl(post: CommunityPost) {
  const urls = postImageUrls(post)
  if (!urls.length) return ""
  const current = activeMediaIndexes.value[post.id] ?? 0
  return urls[Math.min(Math.max(current, 0), urls.length - 1)] || urls[0] || ""
}

function activePostImageIndex(post: CommunityPost) {
  const urls = postImageUrls(post)
  if (!urls.length) return 0
  return Math.min(Math.max(activeMediaIndexes.value[post.id] ?? 0, 0), urls.length - 1)
}

function stepPostImage(post: CommunityPost, delta: number, event: Event) {
  event.stopPropagation()
  const urls = postImageUrls(post)
  if (urls.length <= 1) return
  const current = activePostImageIndex(post)
  activeMediaIndexes.value = {
    ...activeMediaIndexes.value,
    [post.id]: (current + delta + urls.length) % urls.length,
  }
}

function postKind(post: CommunityPost) {
  return resolveCommunityPostKind(post.modality)
}

function postTitle(post: CommunityPost) {
  return communityDisplayTitle({
    title: post.title,
    topic: post.topic,
    tags: post.tags,
    toolName: post.toolName,
    toolCode: post.toolCode,
    kind: postKind(post),
    modality: post.modality,
    promptVisible: post.promptVisible,
  })
}

function cardDescription(post: CommunityPost) {
  return communityCardDescription(post)
}

function authorName(post: CommunityPost) {
  return resolveCommunityAuthorName(post)
}

function authorAvatar(post: CommunityPost) {
  return resolveCommunityAuthorAvatar(post)
}

function patchPost(updated: CommunityPost) {
  const index = posts.value.findIndex((item) => item.id === updated.id)
  if (index >= 0) posts.value[index] = updated
}

function hasMediaCover(post: CommunityPost) {
  if (postKind(post) === "audio") return hasCommunityAudioMedia(post)
  if (postKind(post) === "image") return Boolean(activePostImageUrl(post))
  return Boolean(normalizeCommunityMediaUrl(post.coverUrl)) && postKind(post) !== "text"
}

function audioMedia(post: CommunityPost) {
  const resolved = resolveCommunityAudioMedia(post)
  return {
    coverUrl: normalizeCommunityMediaUrl(resolved.coverUrl),
    audioUrl: normalizeCommunityMediaUrl(resolved.audioUrl),
  }
}

function galleryAudioProgressFor(postId: number) {
  if (playingPostId.value !== postId || !galleryAudioDuration.value) return 0
  return Math.min(100, Math.max(0, (galleryAudioCurrentTime.value / galleryAudioDuration.value) * 100))
}

function resetGalleryAudioProgress() {
  galleryAudioCurrentTime.value = 0
  galleryAudioDuration.value = 0
}

function toggleCardAudio(post: CommunityPost) {
  const { audioUrl } = audioMedia(post)
  if (!audioUrl) return

  if (playingPostId.value === post.id && galleryAudioPlaying.value) {
    galleryAudioRef.value?.pause()
    return
  }

  playingPostId.value = post.id
  resetGalleryAudioProgress()
  const audio = galleryAudioRef.value
  if (!audio) return
  audio.src = audioUrl
  void audio.play().catch(() => {
    playingPostId.value = null
    galleryAudioPlaying.value = false
    resetGalleryAudioProgress()
  })
}

function seekCardAudio(event: MouseEvent) {
  event.stopPropagation()
  const audio = galleryAudioRef.value
  if (!audio || !galleryAudioDuration.value) return
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width))
  audio.currentTime = ratio * galleryAudioDuration.value
  galleryAudioCurrentTime.value = audio.currentTime
}

function onGalleryAudioPlay() {
  galleryAudioPlaying.value = true
}

function onGalleryAudioEnded() {
  playingPostId.value = null
  galleryAudioPlaying.value = false
  resetGalleryAudioProgress()
}

function onGalleryAudioPause() {
  galleryAudioPlaying.value = false
}

function onGalleryAudioTimeUpdate() {
  const audio = galleryAudioRef.value
  if (!audio) return
  galleryAudioCurrentTime.value = audio.currentTime
  galleryAudioDuration.value = Number.isFinite(audio.duration) ? audio.duration : 0
}

function onGalleryAudioLoadedMetadata() {
  const audio = galleryAudioRef.value
  if (!audio) return
  galleryAudioDuration.value = Number.isFinite(audio.duration) ? audio.duration : 0
  galleryAudioCurrentTime.value = audio.currentTime || 0
}

function findScrollRoot(el: HTMLElement | null): Element | null {
  let node = el?.parentElement ?? null
  while (node) {
    const { overflowY } = getComputedStyle(node)
    if (overflowY === "auto" || overflowY === "scroll") return node
    node = node.parentElement
  }
  return null
}

async function trackImpressions(list: CommunityPost[]) {
  await Promise.all(
    list.slice(0, 8).map((post) =>
      trackCommunityEvent(
        { postId: post.id, eventType: "impression", source: "discover_feed", toolCode: post.toolCode },
        { token: auth.token },
      ).catch(() => undefined),
    ),
  )
}

function openPost(post: CommunityPost) {
  void trackCommunityEvent(
    { postId: post.id, eventType: "detail_view", source: "discover_feed", toolCode: post.toolCode },
    { token: auth.token },
  ).catch(() => undefined)
  router.push(`/community/posts/${post.id}`)
}

function openAuthorProfile(post: CommunityPost, event: Event) {
  event.stopPropagation()
  if (!post.userId) return
  router.push(`/u/${post.userId}`)
}

async function toggleLike(post: CommunityPost, event: Event) {
  event.stopPropagation()
  if (!auth.token) return router.push({ name: "Login", query: { redirect: route.fullPath } })
  actingPostId.value = post.id
  try {
    const updated = post.liked
      ? await unlikeCommunityPost(post.id, { token: auth.token })
      : await likeCommunityPost(post.id, { token: auth.token })
    patchPost(updated)
  } finally {
    actingPostId.value = null
  }
}

async function toggleFavorite(post: CommunityPost, event: Event) {
  event.stopPropagation()
  const sessionToken = resolveAuthToken()
  if (!sessionToken) return router.push({ name: "Login", query: { redirect: route.fullPath } })

  if (post.favorited) {
    actingPostId.value = post.id
    try {
      const updated = await unfavoritePostFromAllCollections(post.id, { token: sessionToken })
      patchPost(updated)
    } finally {
      actingPostId.value = null
    }
    return
  }

  favoritePickerPost.value = post
  favoritePickerOpen.value = true
}

function closeFavoritePicker() {
  if (favoritePickerSubmitting.value) return
  favoritePickerOpen.value = false
  favoritePickerPost.value = null
}

async function confirmFavoritePicker(collectionId: number | null) {
  const post = favoritePickerPost.value
  const sessionToken = resolveAuthToken()
  if (!post || !sessionToken) return

  favoritePickerSubmitting.value = true
  actingPostId.value = post.id
  try {
    const updated = await favoritePostToCollection(post.id, collectionId, { token: sessionToken })
    patchPost(updated)
    favoritePickerOpen.value = false
    favoritePickerPost.value = null
  } finally {
    favoritePickerSubmitting.value = false
    actingPostId.value = null
  }
}

function selectTopic(nextTopic: string) {
  topic.value = topic.value === nextTopic ? "" : nextTopic
}

function setQuickSort(value: string) {
  sort.value = value
  sortDropdownOpen.value = false
}

function selectDropdownSort(value: string) {
  sort.value = value
  sortDropdownOpen.value = false
}

function handleDocumentClick(event: MouseEvent) {
  const target = event.target
  if (!(target instanceof Node)) return
  if (sortDropdownRef.value?.contains(target)) return
  sortDropdownOpen.value = false
}

async function loadTopics() {
  topicsLoading.value = true
  try {
    const topicList = await fetchCommunityTopics({ token: resolveAuthToken(), limit: 12 })
    topics.value = topicList.filter((item) => item.name && item.postCount > 0)
  } catch {
    topics.value = []
  } finally {
    topicsLoading.value = false
  }
}

async function load(reset = true) {
  if (reset) {
    loading.value = true
    pageNo.value = 1
    posts.value = []
    activeMediaIndexes.value = {}
  } else {
    loadingMore.value = true
  }
  error.value = ""
  try {
    const currentPage = reset ? 1 : pageNo.value
    const page = await searchCommunityPosts({
      token: resolveAuthToken(),
      query: {
        pageNo: currentPage,
        pageSize: 16,
        modality: modality.value || undefined,
        sort: sort.value,
        featured: featuredOnly.value ? true : undefined,
        keyword: keyword.value.trim() || undefined,
        tag: keyword.value.trim() || undefined,
        topic: topic.value || undefined,
      },
    })
    posts.value = reset ? page.list : [...posts.value, ...page.list]
    total.value = page.total
    hasNext.value = page.hasNext
    pageNo.value = currentPage + 1
    void trackImpressions(page.list)
  } catch (err) {
    error.value = err instanceof Error ? err.message : "社区作品加载失败"
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

async function createSameStyle(post: CommunityPost, event: Event) {
  event.stopPropagation()
  sameStyleError.value = ""
  if (!auth.token) {
    return router.push({ name: "Login", query: { redirect: route.fullPath } })
  }
  if (!post.toolCode) {
    sameStyleError.value = "该作品未关联工具，暂时无法同款创作"
    return
  }
  sameStyleLoadingId.value = post.id
  try {
    await markCommunityPostSameStyle(post.id, { token: auth.token })
    void trackCommunityEvent(
      { postId: post.id, eventType: "dashboard_open", source: "discover_card", toolCode: post.toolCode },
      { token: auth.token },
    ).catch(() => undefined)
    const selectedMediaUrl = postKind(post) === "image" ? activePostImageUrl(post) : normalizeCommunityMediaUrl(post.coverUrl)
    openDashboardWithAsset(assetFromCommunityPost(post, selectedMediaUrl), post.toolCode, {
      modality: post.modality,
      sourcePost: post.id,
    })
  } catch (err) {
    if (err instanceof ApiBusinessError) {
      const message = err.message.toLowerCase()
      if (message.includes("credit") || message.includes("算力") || message.includes("余额")) {
        sameStyleError.value = "算力不足，请先充值后再进行同款创作"
      } else {
        sameStyleError.value = err.message || "同款创作失败，请稍后重试"
      }
    } else {
      sameStyleError.value = err instanceof Error ? err.message : "同款创作失败，请稍后重试"
    }
  } finally {
    sameStyleLoadingId.value = null
  }
}

function goToTools() {
  router.push(userRoutes.toolList)
}

function refreshAll() {
  void loadTopics()
  void load(true)
}

function setupLoadObserver() {
  loadObserver?.disconnect()
  if (!loadSentinelRef.value || !hasNext.value) return
  const root = findScrollRoot(loadSentinelRef.value)
  loadObserver = new IntersectionObserver(
    (entries) => {
      if (!entries.some((entry) => entry.isIntersecting)) return
      if (loading.value || loadingMore.value || !hasNext.value) return
      void load(false)
    },
    { root, rootMargin: "320px" },
  )
  loadObserver.observe(loadSentinelRef.value)
}

watch([modality, sort, featuredOnly, topic], () => {
  if (auth.bootstrapComplete) void load(true)
})
watch(
  [() => auth.bootstrapComplete, () => auth.token],
  ([ready, token], [prevReady, prevToken]) => {
    resetDefaultCommunityCollectionCache()
    if (token) void preloadDefaultCommunityCollection(token).catch(() => undefined)
    if (ready && (ready !== prevReady || token !== prevToken)) {
      void load(true)
    }
  },
  { immediate: true },
)
watch([posts, hasNext, loading, loadingMore], async () => {
  await nextTick()
  setupLoadObserver()
})

function handleCommunityPostUnpublished(event: Event) {
  const detail = (event as CustomEvent<CommunityPostUnpublishedDetail>).detail
  if (!detail?.postId && !detail?.taskId) return
  const before = posts.value.length
  posts.value = posts.value.filter((post) => {
    if (detail.postId && post.id === detail.postId) return false
    if (detail.taskId && post.taskId === detail.taskId) return false
    return true
  })
  const removed = before - posts.value.length
  if (removed > 0) {
    total.value = Math.max(0, total.value - removed)
  }
}

let lastLoadAt = 0
const STALE_MS = 60_000

onMounted(() => {
  void loadTopics()
  lastLoadAt = Date.now()
  const sessionToken = resolveAuthToken()
  if (sessionToken) void preloadDefaultCommunityCollection(sessionToken).catch(() => undefined)
  window.addEventListener(COMMUNITY_POST_UNPUBLISHED_EVENT, handleCommunityPostUnpublished)
  document.addEventListener("click", handleDocumentClick)
})

onActivated(() => {
  window.addEventListener(COMMUNITY_POST_UNPUBLISHED_EVENT, handleCommunityPostUnpublished)
  document.addEventListener("click", handleDocumentClick)
  if (Date.now() - lastLoadAt > STALE_MS) {
    void load(true)
    lastLoadAt = Date.now()
  }
})

onDeactivated(() => {
  window.removeEventListener(COMMUNITY_POST_UNPUBLISHED_EVENT, handleCommunityPostUnpublished)
  document.removeEventListener("click", handleDocumentClick)
})

onUnmounted(() => {
  loadObserver?.disconnect()
  loadObserver = null
  window.removeEventListener(COMMUNITY_POST_UNPUBLISHED_EVENT, handleCommunityPostUnpublished)
  document.removeEventListener("click", handleDocumentClick)
})
</script>

<template>
  <div class="community-gallery">
    <section class="hero">
      <div>
        <p class="eyebrow">Community Gallery</p>
        <h1>探索创意灵感，一键复刻起航</h1>
        <p class="hero-lead">探寻触手可及的创意火花。支持一键复刻优秀作品的提示词与参数，让灵感即刻落地。</p>
      </div>
      <button type="button" class="primary-button" @click="goToTools">
        <Store class="h-4 w-4" />
        回到工具
      </button>
    </section>

    <section class="filter-stack">
      <div class="filter-row filter-row--primary">
        <div class="filter-capsule" aria-label="作品类型">
          <button
            v-for="item in modalityFilters"
            :key="item.value || 'all'"
            type="button"
            class="filter-chip"
            :class="{ active: modality === item.value }"
            @click="modality = item.value"
          >
            {{ item.label }}
          </button>
        </div>

        <div class="filter-actions">
          <span class="total-count">共 {{ total }} 个作品</span>

          <div class="search-shell" :class="{ expanded: searchOpen }">
            <button type="button" class="icon-button" aria-label="搜索" @click="searchOpen = !searchOpen">
              <Search class="h-4 w-4" />
            </button>
            <form v-if="searchOpen" class="search-form" @submit.prevent="load(true)">
              <input v-model="keyword" class="search-input" placeholder="搜索标题、标签、工具" @keydown.esc="searchOpen = false" />
              <button type="button" class="search-close" aria-label="关闭搜索" @click="searchOpen = false">
                <X class="h-3.5 w-3.5" />
              </button>
            </form>
          </div>

          <button type="button" class="icon-button" :disabled="loading || topicsLoading" aria-label="刷新" @click="refreshAll">
            <RefreshCcw class="h-4 w-4" :class="{ 'animate-spin': loading || topicsLoading }" />
          </button>
        </div>
      </div>

      <div class="filter-row filter-row--secondary">
        <div class="topic-inline" aria-label="话题筛选">
          <button type="button" class="topic-chip" :class="{ active: !topic }" @click="topic = ''">
            全部话题
          </button>
          <button
            v-for="item in topics"
            :key="item.name"
            type="button"
            class="topic-chip"
            :class="{ active: topic === item.name }"
            @click="selectTopic(item.name)"
          >
            #{{ item.name }}
            <span class="topic-count">{{ item.postCount }}</span>
          </button>
        </div>

        <div class="sort-controls">
          <div class="sort-tabs" aria-label="排序方式">
            <button
              v-for="item in quickSorts"
              :key="item.value"
              type="button"
              class="sort-tab"
              :class="{ active: sort === item.value }"
              @click="setQuickSort(item.value)"
            >
              {{ item.label }}
            </button>
          </div>

          <div ref="sortDropdownRef" class="sort-dropdown">
            <button type="button" class="sort-dropdown-trigger" @click.stop="sortDropdownOpen = !sortDropdownOpen">
              <span>{{ currentSortLabel }}</span>
              <ChevronDown class="h-4 w-4" :class="{ rotated: sortDropdownOpen }" />
            </button>
            <div v-show="sortDropdownOpen" class="sort-dropdown-menu">
              <button
                v-for="item in extendedSorts"
                :key="item.value"
                type="button"
                class="sort-dropdown-option"
                :class="{ active: sort === item.value }"
                @click="selectDropdownSort(item.value)"
              >
                {{ item.label }}
              </button>
            </div>
          </div>

          <label class="featured-toggle">
            <input v-model="featuredOnly" type="checkbox" />
            <span>精选</span>
          </label>
        </div>
      </div>
    </section>

    <p v-if="sameStyleError" class="inline-alert" role="alert">{{ sameStyleError }}</p>

    <MasonryLayout
      v-if="loading"
      :items="skeletonItems"
      item-key="id"
      :gap="2"
      :estimate-height="(item) => item.height"
      aria-busy="true"
      aria-label="加载中"
    >
      <template #default="{ item }">
        <article class="post-card skeleton" :style="{ '--skeleton-h': `${item.height}px` }">
          <div class="thumb skeleton-block">
            <div class="card-meta-bar skeleton-meta-bar">
              <div class="skeleton-line short" />
              <div class="skeleton-line tiny" />
            </div>
          </div>
        </article>
      </template>
    </MasonryLayout>

    <div v-else-if="error" class="state-panel error">{{ error }}</div>
    <div v-else-if="!posts.length" class="state-panel">暂时没有匹配的公开作品，换个筛选条件再试试。</div>

    <template v-else>
      <MasonryLayout :items="posts" :item-key="(post) => post.id" :gap="2" aria-label="社区作品">
        <template #default="{ item: post }">
        <article class="post-card group">
          <div class="card-main">
            <div class="thumb">
              <button
                v-if="!(hasMediaCover(post) && postKind(post) === 'audio')"
                type="button"
                class="card-clickable"
                @click="openPost(post)"
              >
                <CommunityOptimizedMedia
                  v-if="hasMediaCover(post) && (postKind(post) === 'image' || postKind(post) === 'video')"
                  class="thumb-media"
                  :kind="postKind(post)"
                  :source-url="postKind(post) === 'image' ? activePostImageUrl(post) : normalizeCommunityMediaUrl(post.coverUrl)"
                  :alt="postTitle(post)"
                  :fallback-text="cardDescription(post) || postTitle(post)"
                />
                <div v-else class="thumb-text">
                  <p>{{ cardDescription(post) || postTitle(post) }}</p>
                </div>
              </button>

              <CommunityAudioMedia
                v-else
                class="thumb-audio"
                :cover-url="audioMedia(post).coverUrl"
                :audio-url="audioMedia(post).audioUrl"
                :playing="playingPostId === post.id && galleryAudioPlaying"
                :progress="galleryAudioProgressFor(post.id)"
                variant="card"
                @toggle-play="toggleCardAudio(post)"
                @seek="seekCardAudio"
                @open-detail="openPost(post)"
              />

              <span v-if="post.featured" class="featured-badge">
                  <Sparkles class="h-3 w-3" />
                  精选
                </span>
                <span v-if="postKind(post) === 'audio'" class="modality-badge">
                  <Music class="h-3 w-3" />
                  音乐
                </span>
                <span v-if="postImageUrls(post).length > 1" class="media-count-badge">
                  {{ activePostImageIndex(post) + 1 }} / {{ postImageUrls(post).length }}
                </span>

                <div class="card-meta-bar">
                  <button type="button" class="creator-chip" @click="openAuthorProfile(post, $event)">
                    <UserAvatar :src="authorAvatar(post)" :name="authorName(post)" size="sm" />
                    <span class="creator-name">{{ authorName(post) }}</span>
                  </button>

                  <div class="stats-row">
                    <button
                      type="button"
                      class="stat-item stat-likes"
                      :class="{ active: post.liked }"
                      :disabled="actingPostId === post.id"
                      title="点赞"
                      @click="toggleLike(post, $event)"
                    >
                      <Heart class="h-3 w-3" :class="{ 'icon-filled': post.liked }" />
                      {{ post.likeCount }}
                    </button>
                    <button
                      type="button"
                      class="stat-item stat-favorites"
                      :class="{ active: post.favorited }"
                      :disabled="actingPostId === post.id"
                      title="收藏"
                      @click="toggleFavorite(post, $event)"
                    >
                      <Star class="h-3 w-3" :class="{ 'icon-filled': post.favorited }" />
                      {{ post.favoriteCount }}
                    </button>
                  </div>
                </div>
            </div>

            <div v-if="postImageUrls(post).length > 1" class="carousel-controls" aria-label="切换图片">
              <button type="button" class="carousel-button previous" aria-label="上一张" @click="stepPostImage(post, -1, $event)">
                <ChevronLeft class="h-4 w-4" />
              </button>
              <button type="button" class="carousel-button next" aria-label="下一张" @click="stepPostImage(post, 1, $event)">
                <ChevronRight class="h-4 w-4" />
              </button>
            </div>

          </div>
          <div class="card-footer">
            <button
              type="button"
              class="same-style-btn"
              :disabled="sameStyleLoadingId === post.id || !post.toolCode"
              :title="post.toolCode ? '同款创作' : '未关联工具'"
              @click="createSameStyle(post, $event)"
            >
              <Loader2 v-if="sameStyleLoadingId === post.id" class="h-3.5 w-3.5 animate-spin" />
              <Wand2 v-else class="h-3.5 w-3.5" />
              <span class="same-style-label">同款创作</span>
            </button>
          </div>
        </article>
        </template>
      </MasonryLayout>

      <div ref="loadSentinelRef" class="load-sentinel" aria-hidden="true" />
      <div v-if="loadingMore" class="loading-more" aria-live="polite">
        <Loader2 class="h-4 w-4 animate-spin" />
        正在加载更多作品
      </div>
    </template>

    <audio
      ref="galleryAudioRef"
      class="gallery-audio-player"
      preload="metadata"
      @play="onGalleryAudioPlay"
      @ended="onGalleryAudioEnded"
      @pause="onGalleryAudioPause"
      @timeupdate="onGalleryAudioTimeUpdate"
      @loadedmetadata="onGalleryAudioLoadedMetadata"
    />

    <CommunityCollectionPickerModal
      :open="favoritePickerOpen"
      :post-title="favoritePickerPost ? postTitle(favoritePickerPost) : ''"
      :submitting="favoritePickerSubmitting"
      @close="closeFavoritePicker"
      @confirm="confirmFavoritePicker"
    />
  </div>
</template>

<style scoped>
.community-gallery {
  min-height: 100%;
  padding: clamp(24px, 4vw, 48px);
  color: #f8fafc;
}

.hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 28px;
  padding-bottom: 28px;
}

.eyebrow {
  margin: 0 0 14px;
  color: rgb(255 255 255 / 0.38);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.2em;
  text-transform: uppercase;
}

.hero h1 {
  max-width: 820px;
  margin: 0;
  font-size: clamp(28px, 4.2vw, 52px);
  font-weight: 700;
  line-height: 1.08;
}

.hero-lead {
  max-width: 520px;
  margin: 16px 0 0;
  color: rgb(255 255 255 / 0.6);
  font-weight: 400;
  line-height: 1.8;
}

.primary-button {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 999px;
  background: var(--primary);
  color: var(--primary-foreground);
  padding: 12px 20px;
  font-weight: 700;
  box-shadow: 0 12px 28px rgb(124 58 237 / 0.28);
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.primary-button:hover {
  opacity: 0.92;
  transform: translateY(-1px);
}

.filter-stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 18px;
}

.filter-row,
.filter-actions,
.sort-controls,
.topic-inline {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.filter-row {
  justify-content: space-between;
}

.filter-row--secondary {
  align-items: flex-start;
}

.topic-inline {
  min-width: 0;
  flex: 1;
}

.filter-actions,
.sort-controls {
  flex-shrink: 0;
  justify-content: flex-end;
}

.filter-capsule {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.05);
  padding: 4px;
}

.filter-chip,
.topic-chip,
.sort-tab,
.icon-button {
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.07);
  color: rgb(255 255 255 / 0.74);
  padding: 9px 14px;
  font-weight: 600;
  transition: background 0.18s ease, color 0.18s ease;
}

.filter-chip {
  background: transparent;
}

.filter-chip.active,
.topic-chip.active {
  background: rgb(255 255 255 / 0.15);
  color: #fff;
}

.sort-tabs {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.05);
}

.sort-tab {
  background: transparent;
  position: relative;
}

.sort-tab.active {
  background: rgb(124 58 237 / 0.22);
  color: #fff;
  box-shadow: inset 0 -2px 0 var(--primary);
}

.sort-dropdown {
  position: relative;
  z-index: 20;
}

.sort-dropdown-trigger {
  display: inline-flex;
  height: 38px;
  align-items: center;
  gap: 8px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 12px;
  background: #121216;
  padding: 0 14px;
  color: rgb(255 255 255 / 0.8);
  font-size: 13px;
  font-weight: 600;
  transition: border-color 0.18s ease, background-color 0.18s ease, color 0.18s ease;
}

.sort-dropdown-trigger:hover {
  border-color: rgb(255 255 255 / 0.2);
  background: #16161c;
  color: #fff;
}

.sort-dropdown-trigger svg {
  color: rgb(255 255 255 / 0.42);
  transition: transform 0.18s ease;
}

.sort-dropdown-trigger svg.rotated {
  transform: rotate(180deg);
}

.sort-dropdown-menu {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 50;
  width: 148px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 14px;
  background: rgb(18 18 22 / 0.95);
  padding: 6px;
  box-shadow: 0 22px 60px rgb(0 0 0 / 0.45);
  backdrop-filter: blur(16px);
}

.sort-dropdown-option {
  display: flex;
  width: 100%;
  align-items: center;
  border: 0;
  border-radius: 2px;
  background: transparent;
  padding: 9px 10px;
  color: rgb(255 255 255 / 0.68);
  font-size: 12px;
  font-weight: 600;
  text-align: left;
  transition: background-color 0.16s ease, color 0.16s ease;
}

.sort-dropdown-option:hover,
.sort-dropdown-option.active {
  background: rgb(255 255 255 / 0.07);
  color: #fff;
}

.featured-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: rgb(255 255 255 / 0.68);
  font-size: 13px;
}

.total-count {
  color: rgb(255 255 255 / 0.52);
  font-size: 13px;
  white-space: nowrap;
}

.icon-button {
  display: inline-flex;
  width: 38px;
  height: 38px;
  align-items: center;
  justify-content: center;
  padding: 0;
}

.icon-button:disabled {
  opacity: 0.5;
}

.search-shell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.search-form {
  display: flex;
  align-items: center;
  width: min(260px, 48vw);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.07);
  padding: 0 4px 0 12px;
}

.search-input {
  width: 100%;
  border: 0;
  background: transparent;
  color: #fff;
  outline: none;
  font-size: 13px;
}

.search-close {
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.55);
  padding: 8px;
}

.topic-strip {
  margin-bottom: 20px;
}

.topic-count {
  margin-left: 4px;
  color: rgb(255 255 255 / 0.46);
  font-size: 12px;
  font-weight: 500;
}

.inline-alert {
  margin: 0 0 16px;
  padding: 10px 14px;
  border-radius: 12px;
  background: rgb(239 68 68 / 0.12);
  color: rgb(254 202 202);
  font-size: 13px;
}

.post-card {
  position: relative;
  overflow: visible;
  border: 1px solid rgb(255 255 255 / 0.06);
  border-radius: 6px;
  background: rgb(255 255 255 / 0.02);
  transition: transform 0.22s ease, box-shadow 0.22s ease, border-color 0.22s ease;
  z-index: 1;
}

.post-card:hover {
  z-index: 10;
}

.post-card:hover {
  transform: translateY(-2px);
  border-color: rgb(255 255 255 / 0.14);
  box-shadow: 0 16px 36px rgb(0 0 0 / 0.28);
}

.card-main {
  position: relative;
  overflow: hidden;
  border-radius: 6px;
}

.card-clickable {
  display: block;
  width: 100%;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  padding: 0;
}

.thumb {
  position: relative;
  overflow: hidden;
  border-radius: 6px;
  background: rgb(255 255 255 / 0.03);
}

.thumb video {
  display: block;
  width: 100%;
  height: auto;
  pointer-events: none;
}

.thumb video::-webkit-media-controls {
  display: none !important;
}

.thumb video::-webkit-media-controls-enclosure {
  display: none !important;
}

.thumb-media {
  display: block;
  width: 100%;
  height: auto;
  max-width: 100%;
  vertical-align: top;
}

.thumb-audio {
  display: block;
  width: 100%;
}

.thumb-audio :deep(.community-audio-media) {
  width: 100%;
}

.gallery-audio-player {
  display: none;
}

.modality-badge {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.52);
  color: rgb(255 255 255 / 0.88);
  padding: 4px 8px;
  font-size: 11px;
  font-weight: 700;
  backdrop-filter: blur(8px);
}

.media-count-badge {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 1;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.58);
  color: rgb(255 255 255 / 0.9);
  padding: 4px 9px;
  font-size: 11px;
  font-weight: 700;
  backdrop-filter: blur(8px);
}

.card-meta-bar {
  position: absolute;
  z-index: 3;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  background: linear-gradient(180deg, transparent 0%, rgb(0 0 0 / 0.18) 38%, rgb(0 0 0 / 0.62) 100%);
  padding: 28px 10px 9px;
  opacity: 0;
  transition: opacity 0.22s ease;
}

.post-card:hover .card-meta-bar,
.card-meta-bar:focus-within {
  opacity: 1;
}

.card-meta-bar > * {
  pointer-events: auto;
}

.carousel-controls {
  position: absolute;
  inset: 0;
  z-index: 2;
  pointer-events: none;
}

.carousel-button {
  position: absolute;
  top: 50%;
  display: inline-flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 0.16);
  border-radius: 999px;
  background: rgb(0 0 0 / 0.46);
  color: rgb(255 255 255 / 0.88);
  opacity: 0;
  pointer-events: auto;
  transform: translateY(-50%);
  transition: opacity 0.18s ease, background 0.18s ease, transform 0.18s ease;
  backdrop-filter: blur(10px);
}

.carousel-button.previous {
  left: 10px;
}

.carousel-button.next {
  right: 10px;
}

.post-card:hover .carousel-button,
.carousel-button:focus-visible {
  opacity: 1;
}

.carousel-button:hover {
  background: rgb(0 0 0 / 0.68);
  transform: translateY(-50%) scale(1.04);
}

.thumb-text {
  display: flex;
  min-height: 160px;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  background: linear-gradient(135deg, rgb(124 58 237 / 0.18), rgb(59 130 246 / 0.12));
}

.thumb-text p {
  margin: 0;
  color: rgb(255 255 255 / 0.82);
  font-size: 13px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.creator-chip {
  display: inline-flex;
  min-width: 0;
  max-width: 62%;
  align-items: center;
  gap: 5px;
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.58);
  padding: 0;
  cursor: pointer;
  transition: color 0.18s ease;
}

.creator-chip:hover {
  color: rgb(255 255 255 / 0.82);
}

.creator-name {
  overflow: hidden;
  font-size: 11px;
  font-weight: 500;
  line-height: 1.2;
  letter-spacing: 0.01em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.featured-badge {
  position: absolute;
  top: 10px;
  left: 10px;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.45);
  backdrop-filter: blur(8px);
  color: #fde68a;
  padding: 4px 10px;
  font-size: 11px;
  font-weight: 700;
}

.stats-row {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 10px;
}

.stat-item {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.46);
  padding: 0;
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  transition: color 0.18s ease;
}

.stat-item:disabled {
  opacity: 0.7;
  cursor: wait;
}

.stat-item:hover {
  color: rgb(255 255 255 / 0.68);
}

.stat-likes.active {
  color: #f4729a;
}

.stat-favorites.active {
  color: #f59e0b;
}

.icon-filled {
  fill: currentColor;
  stroke: currentColor;
}

.card-footer {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 5;
  overflow: hidden;
  max-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 8px;
  border-radius: 0 0 6px 6px;
  background:
    linear-gradient(180deg, rgb(18 18 18 / 0.82), rgb(10 10 10 / 0.92)),
    rgb(20 20 20 / 0.9);
  box-shadow: 0 10px 22px rgb(0 0 0 / 0.28);
  backdrop-filter: blur(10px);
  opacity: 0;
  transform: translateY(100%);
  transition: max-height 0.24s ease, opacity 0.22s ease;
}

.post-card:hover .card-footer {
  max-height: 48px;
  opacity: 1;
  transform: translateY(100%);
}

.thumb :deep(.video-play-indicator) {
  display: none;
}

.same-style-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: calc(100% - 2px);
  min-height: 33px;
  gap: 6px;
  border: 1px solid rgb(255 255 255 / 0.06);
  border-radius: 6px;
  background:
    linear-gradient(180deg, rgb(74 74 74 / 0.98) 0%, rgb(50 50 50 / 0.98) 56%, rgb(42 42 42 / 0.98) 100%),
    rgb(50 50 50);
  color: rgb(255 255 255 / 0.9);
  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 0.08),
    0 1px 0 rgb(0 0 0 / 0.25);
  padding: 7px 12px;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
  transition: filter 0.18s ease, transform 0.18s ease, border-color 0.18s ease;
}

.same-style-btn:hover:not(:disabled) {
  border-color: rgb(255 255 255 / 0.11);
  filter: brightness(1.08);
  transform: translateY(-1px);
}

.same-style-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.skeleton {
  pointer-events: none;
}

.skeleton .thumb {
  min-height: var(--skeleton-h, 180px);
}

.skeleton-block {
  width: 100%;
  min-height: var(--skeleton-h, 180px);
  background: linear-gradient(90deg, rgb(255 255 255 / 0.04), rgb(255 255 255 / 0.1), rgb(255 255 255 / 0.04));
  background-size: 200% 100%;
  animation: shimmer 1.4s infinite;
}

.skeleton-line {
  height: 10px;
  border-radius: 999px;
  margin-top: 8px;
  background: linear-gradient(90deg, rgb(255 255 255 / 0.04), rgb(255 255 255 / 0.08), rgb(255 255 255 / 0.04));
  background-size: 200% 100%;
  animation: shimmer 1.4s infinite;
}

.skeleton-line.wide {
  width: 88%;
  margin-top: 0;
}

.skeleton-line:not(.wide):not(.short):not(.tiny) {
  width: 62%;
}

.skeleton-meta-bar {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 28px 10px 9px;
}

.skeleton-line.short {
  width: 38%;
  margin-top: 0;
}

.skeleton-line.tiny {
  width: 22%;
  margin-top: 0;
}

@keyframes shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}

.state-panel {
  display: flex;
  min-height: 260px;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border-radius: 12px;
  background: rgb(255 255 255 / 0.04);
  color: rgb(255 255 255 / 0.58);
}

.state-panel.error {
  color: rgb(254 202 202);
}

.load-sentinel {
  width: 100%;
  height: 1px;
}

.loading-more {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin: 8px 0 24px;
  color: rgb(255 255 255 / 0.52);
  font-size: 13px;
}

@media (max-width: 860px) {
  .hero {
    align-items: flex-start;
    flex-direction: column;
  }

  .filter-row {
    flex-direction: column;
    align-items: stretch;
  }

  .filter-actions,
  .sort-controls {
    justify-content: flex-start;
  }
}

@media (max-width: 767px) {
  .same-style-label {
    display: none;
  }

  .same-style-btn {
    opacity: 1;
    transform: none;
    padding: 8px;
  }

  .carousel-button {
    opacity: 1;
  }

  .featured-toggle {
    display: none;
  }

  .sort-dropdown {
    width: 100%;
  }

  .sort-dropdown-trigger {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
