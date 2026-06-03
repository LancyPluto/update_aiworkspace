<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import { useRoute, useRouter } from "vue-router"
import {
  Heart,
  Loader2,
  MessageCircle,
  RefreshCcw,
  Search,
  Sparkles,
  Star,
  Store,
  Wand2,
  X,
} from "lucide-vue-next"
import { ApiBusinessError, getApiOrigin } from "@/api/client"
import { fetchCommunityTopics, markCommunityPostSameStyle, searchCommunityPosts, trackCommunityEvent } from "@/api/communityApi"
import type { CommunityPost, CommunityTopic } from "@/api/types"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { assetFromCommunityPost } from "@/utils/assetPreviewAdapter"
import { openDashboardWithAsset } from "@/utils/assetReplay"
import { communityDisplayTitle, promptExcerpt } from "@/utils/communityDisplay"

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

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
const sort = ref("LATEST")
const featuredOnly = ref(false)
const keyword = ref("")
const topic = ref("")
const searchOpen = ref(false)
const sameStyleLoadingId = ref<number | null>(null)

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
]

const inlineTopics = computed(() => topics.value.length > 0 && topics.value.length < 5)

function displayTags(post: CommunityPost) {
  const tags: string[] = []
  if (post.topic) tags.push(post.topic)
  for (const tag of post.tags || []) {
    const normalized = tag.startsWith("#") ? tag.slice(1) : tag
    if (normalized && !tags.includes(normalized)) tags.push(normalized)
  }
  return tags.slice(0, 2)
}

function mediaUrl(value?: string | null) {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

function postKind(post: CommunityPost) {
  const modality = (post.modality || "").toLowerCase()
  if (modality.includes("video")) return "video"
  if (modality.includes("audio")) return "audio"
  if (modality.includes("image")) return "image"
  return "text"
}

function postTitle(post: CommunityPost) {
  return communityDisplayTitle({
    title: post.title,
    prompt: post.promptPreview || post.prompt,
    promptPreview: post.promptPreview || post.prompt,
    topic: post.topic,
    tags: post.tags,
    toolName: post.toolName,
    toolCode: post.toolCode,
    kind: postKind(post),
  })
}

function textPreview(post: CommunityPost) {
  const raw = post.promptPreview || post.prompt || post.description || post.title || ""
  return promptExcerpt(raw, 40)
}

function hasMediaCover(post: CommunityPost) {
  return Boolean(mediaUrl(post.coverUrl)) && postKind(post) !== "text"
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

function selectTopic(nextTopic: string) {
  topic.value = topic.value === nextTopic ? "" : nextTopic
}

function setQuickSort(value: string) {
  sort.value = value
}

async function loadTopics() {
  topicsLoading.value = true
  try {
    const topicList = await fetchCommunityTopics({ token: auth.token, limit: 12 })
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
  } else {
    loadingMore.value = true
  }
  error.value = ""
  try {
    const currentPage = reset ? 1 : pageNo.value
    const page = await searchCommunityPosts({
      token: auth.token,
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
    openDashboardWithAsset(assetFromCommunityPost(post, mediaUrl(post.coverUrl)), post.toolCode, {
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

watch([modality, sort, featuredOnly, topic], () => void load(true))
onMounted(() => {
  void loadTopics()
  void load(true)
})
</script>

<template>
  <div class="community-gallery">
    <section class="hero">
      <div>
        <p class="eyebrow">Community Gallery</p>
        <h1>发现作品，学习 Prompt，回到工具继续创作</h1>
        <p class="hero-lead">社区不是论坛入口，而是从公开案例到工作台复用的增长路径。</p>
      </div>
      <button type="button" class="primary-button" @click="goToTools">
        <Store class="h-4 w-4" />
        回到工具
      </button>
    </section>

    <section class="filter-bar">
      <div class="filter-left">
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

        <div v-if="inlineTopics" class="topic-inline" aria-label="话题筛选">
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
      </div>

      <div class="filter-right">
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

        <select v-model="sort" class="sort-select" aria-label="更多排序">
          <option v-for="item in extendedSorts" :key="item.value" :value="item.value">{{ item.label }}</option>
        </select>

        <label class="featured-toggle">
          <input v-model="featuredOnly" type="checkbox" />
          <span>精选</span>
        </label>

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
    </section>

    <section v-if="!inlineTopics && topics.length" class="topic-strip" aria-label="话题筛选">
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
        <span class="topic-count">· {{ item.postCount }} 个作品</span>
      </button>
    </section>

    <p v-if="sameStyleError" class="inline-alert" role="alert">{{ sameStyleError }}</p>

    <div v-if="loading" class="post-grid" aria-busy="true" aria-label="加载中">
      <article v-for="index in 8" :key="index" class="post-card skeleton">
        <div class="thumb skeleton-block" />
        <div class="card-body">
          <div class="skeleton-line wide" />
          <div class="skeleton-line" />
          <div class="skeleton-line short" />
        </div>
      </article>
    </div>

    <div v-else-if="error" class="state-panel error">{{ error }}</div>
    <div v-else-if="!posts.length" class="state-panel">暂时没有匹配的公开作品，换个筛选条件再试试。</div>

    <section v-else class="post-grid">
      <article v-for="post in posts" :key="post.id" class="post-card group">
        <button type="button" class="card-clickable" @click="openPost(post)">
          <div class="thumb">
            <img
              v-if="hasMediaCover(post) && postKind(post) === 'image'"
              :src="mediaUrl(post.coverUrl)"
              :alt="postTitle(post)"
              class="thumb-media"
              loading="lazy"
            />
            <video
              v-else-if="hasMediaCover(post) && postKind(post) === 'video'"
              :src="mediaUrl(post.coverUrl)"
              class="thumb-media"
              muted
              loop
              playsinline
              preload="metadata"
            />
            <div v-else class="thumb-text">
              <p>{{ textPreview(post) }}</p>
            </div>
            <span v-if="post.featured" class="featured-badge">
              <Sparkles class="h-3 w-3" />
              精选
            </span>
          </div>

          <div class="card-body">
            <h3>{{ postTitle(post) }}</h3>
            <div v-if="displayTags(post).length" class="tag-row">
              <span v-for="tag in displayTags(post)" :key="tag">{{ tag }}</span>
            </div>
            <div class="stats-row">
              <span class="stat-item stat-likes" title="点赞">
                <Heart class="h-3.5 w-3.5" />
                {{ post.likeCount }}
              </span>
              <span class="stat-item stat-shares" title="分享">
                <MessageCircle class="h-3.5 w-3.5" />
                {{ post.shareCount || 0 }}
              </span>
              <span class="stat-item stat-favorites" title="收藏">
                <Star class="h-3.5 w-3.5" />
                {{ post.favoriteCount }}
              </span>
            </div>
          </div>
        </button>

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
      </article>
    </section>

    <button v-if="hasNext && !loading" class="load-more" type="button" :disabled="loadingMore" @click="load(false)">
      <Loader2 v-if="loadingMore" class="h-4 w-4 animate-spin" />
      加载更多
    </button>
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
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.18em;
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
  color: rgb(255 255 255 / 0.58);
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

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 18px;
}

.filter-left,
.filter-right,
.topic-strip,
.topic-inline {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
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
.icon-button,
.load-more {
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

.sort-select {
  height: 38px;
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.07);
  color: rgb(255 255 255 / 0.84);
  padding: 0 14px;
  font-size: 13px;
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

.post-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: clamp(16px, 2vw, 24px);
}

.post-card {
  position: relative;
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 16px;
  background: rgb(255 255 255 / 0.04);
  transition: transform 0.22s ease, box-shadow 0.22s ease, border-color 0.22s ease;
}

.post-card:hover {
  transform: translateY(-2px);
  border-color: rgb(255 255 255 / 0.14);
  box-shadow: 0 16px 36px rgb(0 0 0 / 0.28);
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
  height: 160px;
  overflow: hidden;
  border-radius: 16px 16px 0 0;
  background: rgb(255 255 255 / 0.03);
}

.thumb-media {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-text {
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background: linear-gradient(135deg, rgb(124 58 237 / 0.18), rgb(59 130 246 / 0.12));
}

.thumb-text p {
  margin: 0;
  color: rgb(255 255 255 / 0.82);
  font-size: 14px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.featured-badge {
  position: absolute;
  top: 10px;
  left: 10px;
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

.card-body {
  padding: 16px;
}

.card-body h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.tag-row span {
  border-radius: 999px;
  background: rgb(255 255 255 / 0.08);
  color: rgb(255 255 255 / 0.68);
  padding: 3px 10px;
  font-size: 11px;
}

.stats-row {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 12px;
  color: rgb(255 255 255 / 0.56);
  font-size: 12px;
}

.stat-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.same-style-btn {
  position: absolute;
  right: 12px;
  bottom: 12px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 0;
  border-radius: 999px;
  background: var(--primary);
  color: var(--primary-foreground);
  padding: 8px 12px;
  font-size: 12px;
  font-weight: 700;
  opacity: 0;
  transform: translateY(4px);
  transition: opacity 0.18s ease, transform 0.18s ease;
  cursor: pointer;
}

.post-card:hover .same-style-btn,
.same-style-btn:focus-visible {
  opacity: 1;
  transform: translateY(0);
}

.same-style-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.skeleton {
  pointer-events: none;
}

.skeleton-block,
.skeleton-line {
  background: linear-gradient(90deg, rgb(255 255 255 / 0.04), rgb(255 255 255 / 0.1), rgb(255 255 255 / 0.04));
  background-size: 200% 100%;
  animation: shimmer 1.4s infinite;
}

.skeleton-line {
  height: 12px;
  border-radius: 999px;
  margin-top: 10px;
}

.skeleton-line.wide {
  width: 88%;
}

.skeleton-line.short {
  width: 42%;
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

.load-more {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 36px auto 0;
}

@media (max-width: 860px) {
  .hero {
    align-items: flex-start;
    flex-direction: column;
  }

  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .filter-right {
    justify-content: flex-start;
  }
}

@media (max-width: 767px) {
  .thumb {
    height: 120px;
  }

  .tag-row,
  .stat-shares,
  .stat-favorites {
    display: none;
  }

  .same-style-label {
    display: none;
  }

  .same-style-btn {
    opacity: 1;
    transform: none;
    padding: 8px;
  }

  .sort-select,
  .featured-toggle {
    display: none;
  }
}
</style>
