<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue"
import { useRouter } from "vue-router"
import { Loader2, RefreshCcw, Search, SlidersHorizontal, X } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import AssetCard from "@/components/AssetCard.vue"
import UserAvatar from "@/components/UserAvatar.vue"
import { searchCommunityPosts, trackCommunityEvent } from "@/api/communityApi"
import { getApiOrigin } from "@/api/client"
import type { CommunityPost } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { assetFromCommunityPost } from "@/utils/assetPreviewAdapter"
import { communityDisplaySubtitle, communityDisplayTitle } from "@/utils/communityDisplay"
import { resolveCommunityAuthorName, resolveCommunityAuthorAvatar, resolveCommunityPrompt } from "@/utils/communityPostNormalize"

const router = useRouter()
const auth = useAuthStore()

const posts = ref<CommunityPost[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const error = ref("")
const pageNo = ref(1)
const hasNext = ref(false)
const modality = ref("")
const sort = ref("LATEST")
const featuredOnly = ref(false)
const tag = ref("")
const searchOpen = ref(false)
const filtersExpanded = ref(false)
const filterRef = ref<HTMLElement | null>(null)
const filterButtons = ref<(HTMLElement | null)[]>([])
const indicator = ref({ width: 0, left: 0 })

const filters = [
  { label: "全部", value: "" },
  { label: "图片", value: "IMAGE" },
  { label: "视频", value: "VIDEO" },
  { label: "文本", value: "TEXT" },
  { label: "音频", value: "AUDIO" },
]

const sorts = [
  { label: "最新", value: "LATEST" },
  { label: "热门", value: "POPULAR" },
  { label: "最多收藏", value: "FAVORITES" },
  { label: "同款最多", value: "SAME_STYLE" },
]

const activeFilterIndex = computed(() => filters.findIndex((item) => item.value === modality.value))

const featuredPosts = computed(() => posts.value.filter((post) => post.featured || post.pinned).slice(0, 4))

const postAssets = computed(() =>
  posts.value.map((post) => ({
    post,
    asset: assetFromCommunityPost(post, mediaUrl(post.coverUrl)),
  })),
)

const indicatorStyle = computed(() => ({
  width: `${indicator.value.width}px`,
  transform: `translateX(${indicator.value.left}px)`,
}))

function mediaUrl(value?: string | null) {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

function featuredTitle(post: CommunityPost) {
  const prompt = resolveCommunityPrompt(post)
  return communityDisplayTitle({
    title: post.title,
    prompt,
    promptPreview: post.promptPreview || prompt,
    topic: post.topic,
    tags: post.tags,
    toolName: post.toolName,
    toolCode: post.toolCode,
    kind: post.modality?.toLowerCase().includes("video")
      ? "video"
      : post.modality?.toLowerCase().includes("image")
        ? "image"
        : post.modality?.toLowerCase().includes("audio")
          ? "audio"
          : "text",
  })
}

function featuredSubtitle(post: CommunityPost) {
  const prompt = resolveCommunityPrompt(post)
  return (
    communityDisplaySubtitle({
      description: post.description,
      prompt,
      promptPreview: post.promptPreview || prompt,
      topic: post.topic,
      tags: post.tags,
      toolName: post.toolName,
      toolCode: post.toolCode,
    }) || "探索创作背后的故事"
  )
}

function featuredAuthorName(post: CommunityPost) {
  return resolveCommunityAuthorName(post)
}

function openPost(post: CommunityPost) {
  router.push(`/community/posts/${post.id}`)
}

function setFilterButtonRef(index: number, element: HTMLElement | null) {
  filterButtons.value[index] = element
}

async function syncFilterIndicator() {
  await nextTick()
  const index = activeFilterIndex.value
  const button = filterButtons.value[index]
  const container = filterRef.value
  if (!button || !container) return
  indicator.value = {
    width: button.offsetWidth,
    left: button.offsetLeft,
  }
}

function toggleSearch() {
  searchOpen.value = !searchOpen.value
  if (!searchOpen.value) return
  nextTick(() => {
    const input = document.querySelector<HTMLInputElement>(".community-search-input")
    input?.focus()
  })
}

function closeSearch() {
  searchOpen.value = false
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
        keyword: tag.value.trim() || undefined,
        tag: tag.value.trim() || undefined,
      },
    })
    posts.value = reset ? page.list : [...posts.value, ...page.list]
    for (const post of page.list.slice(0, 8)) {
      void trackCommunityEvent(
        { postId: post.id, eventType: "impression", source: "discover", toolCode: post.toolCode },
        { token: auth.token },
      ).catch(() => undefined)
    }
    hasNext.value = page.hasNext
    pageNo.value = currentPage + 1
  } catch (err) {
    error.value = err instanceof Error ? err.message : "社区作品加载失败"
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

let resizeObserver: ResizeObserver | null = null

watch([modality, sort, featuredOnly], () => void load(true))
watch(activeFilterIndex, () => void syncFilterIndicator())

onMounted(() => {
  void load(true)
  void syncFilterIndicator()
  if (typeof ResizeObserver !== "undefined" && filterRef.value) {
    resizeObserver = new ResizeObserver(() => void syncFilterIndicator())
    resizeObserver.observe(filterRef.value)
  }
})

onUnmounted(() => {
  resizeObserver?.disconnect()
})
</script>

<template>
  <AppShell title="社区发现">
    <div class="community-discover">
      <section class="hero">
        <div class="hero-copy">
          <h1>作品、Prompt<br />和工具灵感流</h1>
          <p class="hero-lead">发现真实创作，收藏灵感，回到工具继续创作。</p>
        </div>
        <button type="button" class="ghost-button" :disabled="loading" @click="load(true)">
          <RefreshCcw class="h-4 w-4" :class="{ 'animate-spin': loading }" />
          刷新
        </button>
      </section>

      <section class="toolbar">
        <div ref="filterRef" class="filter-capsule">
          <span class="filter-indicator" :style="indicatorStyle" aria-hidden="true" />
          <button
            v-for="(item, index) in filters"
            :key="item.value || 'all'"
            :ref="(el) => setFilterButtonRef(index, el as HTMLElement | null)"
            type="button"
            class="filter-chip"
            :class="{ active: modality === item.value }"
            @click="modality = item.value"
          >
            {{ item.label }}
          </button>
        </div>

        <div class="toolbar-actions">
          <button type="button" class="icon-button" :class="{ active: filtersExpanded }" @click="filtersExpanded = !filtersExpanded">
            <SlidersHorizontal class="h-4 w-4" />
          </button>

          <div class="search-shell" :class="{ expanded: searchOpen }">
            <button type="button" class="icon-button" aria-label="搜索" @click="toggleSearch">
              <Search class="h-4 w-4" />
            </button>
            <form class="search-form" @submit.prevent="load(true)">
              <input
                v-model="tag"
                class="community-search-input"
                placeholder="标签、主题…"
                @keydown.esc="closeSearch"
              />
              <button v-if="searchOpen" type="button" class="search-close" aria-label="关闭搜索" @click="closeSearch">
                <X class="h-3.5 w-3.5" />
              </button>
            </form>
          </div>
        </div>
      </section>

      <Transition name="toolbar-slide">
        <section v-if="filtersExpanded" class="secondary-toolbar">
          <select v-model="sort" class="select-control">
            <option v-for="item in sorts" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
          <label class="toggle-control">
            <input v-model="featuredOnly" type="checkbox" />
            <span>只看精选</span>
          </label>
        </section>
      </Transition>

      <section v-if="featuredPosts.length" class="featured-strip">
        <article v-for="post in featuredPosts" :key="post.id" class="featured-card" @click="openPost(post)">
          <span class="featured-badge">{{ post.pinned ? "置顶" : "精选" }}</span>
          <div class="featured-author">
            <UserAvatar :src="resolveCommunityAuthorAvatar(post)" :name="featuredAuthorName(post)" size="sm" />
            <span>{{ featuredAuthorName(post) }}</span>
          </div>
          <strong>{{ featuredTitle(post) }}</strong>
          <p>{{ featuredSubtitle(post) }}</p>
        </article>
      </section>

      <div v-if="loading" class="state-panel">
        <Loader2 class="h-5 w-5 animate-spin" />
        正在加载社区作品
      </div>
      <div v-else-if="error" class="state-panel error">{{ error }}</div>
      <div v-else-if="!posts.length" class="state-panel">暂时没有匹配的公开作品</div>

      <section v-else class="post-grid">
        <AssetCard
          v-for="item in postAssets"
          :key="item.post.id"
          :asset="item.asset"
          source="community"
          gallery
          @open="openPost(item.post)"
        />
      </section>

      <button v-if="hasNext" class="load-more" type="button" :disabled="loadingMore" @click="load(false)">
        <Loader2 v-if="loadingMore" class="h-4 w-4 animate-spin" />
        加载更多
      </button>
    </div>
  </AppShell>
</template>

<style scoped>
.community-discover {
  min-height: 100%;
  padding: clamp(24px, 4vw, 48px);
  color: #f8fafc;
}

.hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 28px;
  padding-bottom: clamp(28px, 4vw, 40px);
}

.hero-copy {
  max-width: 760px;
}

.eyebrow {
  margin: 0 0 14px;
  color: rgb(255 255 255 / 0.38);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.22em;
  text-transform: uppercase;
}

.hero h1 {
  margin: 0;
  font-size: clamp(34px, 5vw, 62px);
  font-weight: 700;
  line-height: 1.04;
  letter-spacing: -0.03em;
}

.hero-lead {
  max-width: 420px;
  margin: 20px 0 0;
  color: rgb(255 255 255 / 0.48);
  font-size: 15px;
  line-height: 2;
}

.ghost-button,
.load-more {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.06);
  backdrop-filter: blur(20px) saturate(140%);
  color: rgb(255 255 255 / 0.72);
  padding: 11px 18px;
  font-size: 13px;
  font-weight: 600;
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.06);
  transition: background 0.22s ease, transform 0.22s ease, box-shadow 0.22s ease;
}

.ghost-button:hover:not(:disabled),
.load-more:hover:not(:disabled) {
  background: rgb(255 255 255 / 0.1);
  transform: translateY(-1px);
  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 0.08),
    0 12px 32px rgb(0 0 0 / 0.28);
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.filter-capsule {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.04);
  backdrop-filter: blur(18px) saturate(130%);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.05);
}

.filter-indicator {
  position: absolute;
  top: 4px;
  left: 0;
  height: calc(100% - 8px);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.12);
  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 0.14),
    0 8px 24px rgb(0 0 0 / 0.18);
  transition: transform 0.32s cubic-bezier(0.22, 1, 0.36, 1), width 0.32s cubic-bezier(0.22, 1, 0.36, 1);
  pointer-events: none;
}

.filter-chip {
  position: relative;
  z-index: 1;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: rgb(255 255 255 / 0.52);
  padding: 10px 18px;
  font-size: 13px;
  font-weight: 600;
  transition: color 0.22s ease;
}

.filter-chip.active {
  color: #fff;
}

.toolbar-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.icon-button {
  display: inline-flex;
  width: 42px;
  height: 42px;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.04);
  color: rgb(255 255 255 / 0.58);
  backdrop-filter: blur(16px);
  transition: background 0.2s ease, color 0.2s ease, transform 0.2s ease;
}

.icon-button:hover,
.icon-button.active {
  background: rgb(255 255 255 / 0.1);
  color: #fff;
}

.search-shell {
  display: inline-flex;
  align-items: center;
  overflow: hidden;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.04);
  backdrop-filter: blur(16px);
  transition: background 0.24s ease, box-shadow 0.24s ease;
}

.search-shell.expanded {
  background: rgb(255 255 255 / 0.07);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.06);
}

.search-form {
  display: flex;
  align-items: center;
  width: 0;
  opacity: 0;
  transition: width 0.28s cubic-bezier(0.22, 1, 0.36, 1), opacity 0.2s ease;
}

.search-shell.expanded .search-form {
  width: min(240px, 42vw);
  opacity: 1;
}

.community-search-input {
  width: 100%;
  border: 0;
  background: transparent;
  color: #fff;
  padding: 0 8px 0 4px;
  font-size: 13px;
  outline: none;
}

.community-search-input::placeholder {
  color: rgb(255 255 255 / 0.34);
}

.search-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.42);
  padding: 0 12px 0 4px;
}

.secondary-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 28px;
}

.select-control {
  height: 40px;
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.05);
  color: rgb(255 255 255 / 0.78);
  padding: 0 16px;
  font-size: 13px;
  outline: none;
}

.toggle-control {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: rgb(255 255 255 / 0.56);
  font-size: 13px;
}

.featured-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 20px;
  margin: 28px 0 36px;
}

.featured-card {
  position: relative;
  overflow: hidden;
  border: 0;
  border-radius: 28px;
  background: rgb(255 255 255 / 0.035);
  padding: 22px;
  cursor: pointer;
  box-shadow:
    0 24px 60px rgb(0 0 0 / 0.32),
    inset 0 1px 0 rgb(255 255 255 / 0.05);
  transition: transform 0.28s cubic-bezier(0.22, 1, 0.36, 1), box-shadow 0.28s ease;
}

@supports (corner-shape: squircle) {
  .featured-card {
    corner-shape: squircle;
  }
}

.featured-card::after {
  content: "";
  position: absolute;
  inset: 0;
  background: linear-gradient(120deg, transparent 30%, rgb(255 255 255 / 0.08) 50%, transparent 70%);
  transform: translateX(-120%);
  transition: transform 0.65s ease;
}

.featured-card:hover {
  transform: translateY(-4px);
  box-shadow:
    0 32px 72px rgb(0 0 0 / 0.42),
    inset 0 1px 0 rgb(255 255 255 / 0.08);
}

.featured-card:hover::after {
  transform: translateX(120%);
}

.featured-badge {
  display: inline-flex;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.08);
  backdrop-filter: blur(12px);
  color: rgb(255 255 255 / 0.82);
  padding: 5px 10px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.featured-author {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  color: rgb(255 255 255 / 0.68);
  font-size: 12px;
  font-weight: 600;
}

.featured-card strong,
.featured-card p {
  display: block;
  margin: 12px 0 0;
}

.featured-card strong {
  font-size: 17px;
  font-weight: 700;
  line-height: 1.4;
}

.featured-card p {
  color: rgb(255 255 255 / 0.46);
  font-size: 13px;
  line-height: 1.75;
}

.post-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: clamp(24px, 3vw, 36px);
}

.state-panel {
  display: flex;
  min-height: 280px;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border-radius: 28px;
  background: rgb(255 255 255 / 0.02);
  color: rgb(255 255 255 / 0.44);
  font-size: 14px;
}

.state-panel.error {
  color: rgb(254 202 202);
}

.load-more {
  margin: 40px auto 0;
}

.toolbar-slide-enter-active,
.toolbar-slide-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}

.toolbar-slide-enter-from,
.toolbar-slide-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

@media (max-width: 760px) {
  .hero {
    align-items: flex-start;
    flex-direction: column;
  }

  .toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .filter-capsule {
    overflow-x: auto;
    max-width: 100%;
  }

  .toolbar-actions {
    justify-content: flex-end;
  }
}
</style>
