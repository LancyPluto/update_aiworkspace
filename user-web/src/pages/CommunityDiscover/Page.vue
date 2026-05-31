<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import { useRouter } from "vue-router"
import { Clock3, Flame, Loader2, RefreshCcw, Search, SlidersHorizontal, Sparkles, Wand2, X } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import AssetCard from "@/components/AssetCard.vue"
import { fetchCommunityTopics, searchCommunityPosts, trackCommunityEvent } from "@/api/communityApi"
import { getApiOrigin } from "@/api/client"
import type { CommunityPost, CommunityTopic } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { assetFromCommunityPost } from "@/utils/assetPreviewAdapter"

const router = useRouter()
const auth = useAuthStore()

const posts = ref<CommunityPost[]>([])
const featuredPosts = ref<CommunityPost[]>([])
const latestPosts = ref<CommunityPost[]>([])
const popularPosts = ref<CommunityPost[]>([])
const topics = ref<CommunityTopic[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const sectionsLoading = ref(false)
const error = ref("")
const pageNo = ref(1)
const hasNext = ref(false)
const modality = ref("")
const sort = ref("QUALITY")
const featuredOnly = ref(false)
const keyword = ref("")
const topic = ref("")
const searchOpen = ref(false)
const filtersExpanded = ref(false)

const filters = [
  { label: "全部", value: "" },
  { label: "图片", value: "IMAGE" },
  { label: "视频", value: "VIDEO" },
  { label: "文本", value: "TEXT" },
  { label: "音频", value: "AUDIO" },
]

const sorts = [
  { label: "质量推荐", value: "QUALITY" },
  { label: "最新发布", value: "LATEST" },
  { label: "最多点赞", value: "POPULAR" },
  { label: "最多收藏", value: "FAVORITES" },
  { label: "最多同款", value: "SAME_STYLE" },
]

const postAssets = computed(() => posts.value.map((post) => ({ post, asset: assetFromCommunityPost(post, mediaUrl(post.coverUrl)) })))
const featuredAssets = computed(() => featuredPosts.value.map((post) => ({ post, asset: assetFromCommunityPost(post, mediaUrl(post.coverUrl)) })))
const latestAssets = computed(() => latestPosts.value.map((post) => ({ post, asset: assetFromCommunityPost(post, mediaUrl(post.coverUrl)) })))
const popularAssets = computed(() => popularPosts.value.map((post) => ({ post, asset: assetFromCommunityPost(post, mediaUrl(post.coverUrl)) })))
const selectedTopicLabel = computed(() => topic.value || "全部话题")

function mediaUrl(value?: string | null) {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

async function trackImpressions(list: CommunityPost[], source: string) {
  await Promise.all(
    list.slice(0, 8).map((post) =>
      trackCommunityEvent(
        { postId: post.id, eventType: "impression", source, toolCode: post.toolCode },
        { token: auth.token },
      ).catch(() => undefined),
    ),
  )
}

function openPost(post: CommunityPost, source = "discover") {
  void trackCommunityEvent(
    { postId: post.id, eventType: "detail_view", source, toolCode: post.toolCode },
    { token: auth.token },
  ).catch(() => undefined)
  router.push(`/community/posts/${post.id}`)
}

function selectTopic(nextTopic: string) {
  topic.value = topic.value === nextTopic ? "" : nextTopic
}

async function loadSections() {
  sectionsLoading.value = true
  try {
    const [topicList, featured, latest, popular] = await Promise.all([
      fetchCommunityTopics({ token: auth.token, limit: 8 }),
      searchCommunityPosts({ token: auth.token, query: { pageNo: 1, pageSize: 6, sort: "QUALITY", featured: true } }),
      searchCommunityPosts({ token: auth.token, query: { pageNo: 1, pageSize: 6, sort: "LATEST" } }),
      searchCommunityPosts({ token: auth.token, query: { pageNo: 1, pageSize: 6, sort: "SAME_STYLE" } }),
    ])
    topics.value = topicList.filter((item) => item.name && item.postCount > 0)
    featuredPosts.value = featured.list
    latestPosts.value = latest.list
    popularPosts.value = popular.list
    void trackImpressions(featured.list, "discover_featured")
    void trackImpressions(latest.list, "discover_latest")
    void trackImpressions(popular.list, "discover_popular")
  } catch {
    topics.value = []
    featuredPosts.value = []
    latestPosts.value = []
    popularPosts.value = []
  } finally {
    sectionsLoading.value = false
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
    hasNext.value = page.hasNext
    pageNo.value = currentPage + 1
    void trackImpressions(page.list, "discover_feed")
  } catch (err) {
    error.value = err instanceof Error ? err.message : "社区作品加载失败"
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

watch([modality, sort, featuredOnly, topic], () => void load(true))
onMounted(() => {
  void loadSections()
  void load(true)
})
</script>

<template>
  <AppShell title="社区发现" description="浏览优秀作品，收藏灵感，并回到工作台继续同款创作">
    <div class="community-discover">
      <section class="hero">
        <div>
          <p class="eyebrow">Community Gallery</p>
          <h1>发现作品，学习 Prompt，回到工具继续创作</h1>
          <p class="hero-lead">社区不是论坛入口，而是从公开案例到工作台复用的增长路径。</p>
        </div>
        <button type="button" class="ghost-button" :disabled="loading || sectionsLoading" @click="loadSections(); load(true)">
          <RefreshCcw class="h-4 w-4" :class="{ 'animate-spin': loading || sectionsLoading }" />
          刷新
        </button>
      </section>

      <section class="toolbar">
        <div class="filter-capsule" aria-label="作品类型">
          <button
            v-for="item in filters"
            :key="item.value || 'all'"
            type="button"
            class="filter-chip"
            :class="{ active: modality === item.value }"
            @click="modality = item.value"
          >
            {{ item.label }}
          </button>
        </div>

        <div class="toolbar-actions">
          <button type="button" class="icon-button" :class="{ active: filtersExpanded }" aria-label="筛选" @click="filtersExpanded = !filtersExpanded">
            <SlidersHorizontal class="h-4 w-4" />
          </button>
          <div class="search-shell" :class="{ expanded: searchOpen }">
            <button type="button" class="icon-button" aria-label="搜索" @click="searchOpen = true">
              <Search class="h-4 w-4" />
            </button>
            <form class="search-form" @submit.prevent="load(true)">
              <input v-model="keyword" class="community-search-input" placeholder="搜索标题、标签、工具" @keydown.esc="searchOpen = false" />
              <button type="button" class="search-close" aria-label="关闭搜索" @click="searchOpen = false">
                <X class="h-3.5 w-3.5" />
              </button>
            </form>
          </div>
        </div>
      </section>

      <section v-if="topics.length" class="topic-strip" aria-label="运营话题">
        <button
          v-for="item in topics"
          :key="item.name"
          type="button"
          :class="{ active: topic === item.name }"
          @click="selectTopic(item.name)"
        >
          {{ item.name }}
          <span>{{ item.postCount }}</span>
        </button>
      </section>

      <section v-if="filtersExpanded" class="secondary-toolbar">
        <select v-model="sort" class="select-control" aria-label="排序">
          <option v-for="item in sorts" :key="item.value" :value="item.value">{{ item.label }}</option>
        </select>
        <label class="toggle-control">
          <input v-model="featuredOnly" type="checkbox" />
          <span>只看精选</span>
        </label>
      </section>

      <section v-if="featuredAssets.length" class="content-section">
        <header class="section-title">
          <span><Sparkles class="h-4 w-4" />精选作品</span>
          <button type="button" @click="featuredOnly = true">查看精选</button>
        </header>
        <div class="section-grid compact">
          <AssetCard
            v-for="item in featuredAssets"
            :key="item.post.id"
            :asset="item.asset"
            source="community"
            compact
            gallery
            @open="openPost(item.post, 'discover_featured')"
          />
        </div>
      </section>

      <section v-if="latestAssets.length || popularAssets.length" class="split-sections">
        <div v-if="latestAssets.length" class="content-section">
          <header class="section-title">
            <span><Clock3 class="h-4 w-4" />最新作品</span>
          </header>
          <div class="section-grid small">
            <AssetCard
              v-for="item in latestAssets"
              :key="item.post.id"
              :asset="item.asset"
              source="community"
              compact
              gallery
              @open="openPost(item.post, 'discover_latest')"
            />
          </div>
        </div>
        <div v-if="popularAssets.length" class="content-section">
          <header class="section-title">
            <span><Flame class="h-4 w-4" />热门同款</span>
          </header>
          <div class="section-grid small">
            <AssetCard
              v-for="item in popularAssets"
              :key="item.post.id"
              :asset="item.asset"
              source="community"
              compact
              gallery
              @open="openPost(item.post, 'discover_popular')"
            />
          </div>
        </div>
      </section>

      <section class="feed-header">
        <div>
          <p>全部作品</p>
          <strong>{{ selectedTopicLabel }}</strong>
        </div>
        <span><Wand2 class="h-4 w-4" />点击作品进入详情后使用同款创作</span>
      </section>

      <div v-if="loading" class="state-panel">
        <Loader2 class="h-5 w-5 animate-spin" />
        正在加载社区作品
      </div>
      <div v-else-if="error" class="state-panel error">{{ error }}</div>
      <div v-else-if="!posts.length" class="state-panel">
        暂时没有匹配的公开作品，换个筛选条件再试试。
      </div>

      <section v-else class="post-grid">
        <AssetCard
          v-for="item in postAssets"
          :key="item.post.id"
          :asset="item.asset"
          source="community"
          gallery
          @open="openPost(item.post, 'discover_feed')"
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
  padding-bottom: 32px;
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
  font-size: clamp(32px, 4.8vw, 58px);
  font-weight: 700;
  line-height: 1.08;
}

.hero-lead {
  max-width: 520px;
  margin: 18px 0 0;
  color: rgb(255 255 255 / 0.58);
  line-height: 1.9;
}

.toolbar,
.secondary-toolbar,
.topic-strip,
.section-title,
.feed-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.toolbar,
.section-title,
.feed-header {
  justify-content: space-between;
}

.toolbar {
  margin-bottom: 14px;
}

.filter-capsule,
.search-shell {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.05);
  padding: 4px;
}

.filter-chip,
.topic-strip button,
.ghost-button,
.load-more,
.icon-button,
.section-title button {
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.07);
  color: rgb(255 255 255 / 0.74);
  padding: 10px 16px;
  font-weight: 700;
}

.filter-chip {
  background: transparent;
}

.filter-chip.active,
.topic-strip button.active,
.icon-button.active {
  background: rgb(255 255 255 / 0.15);
  color: #fff;
}

.topic-strip {
  margin-bottom: 22px;
}

.topic-strip span {
  margin-left: 8px;
  color: rgb(255 255 255 / 0.46);
  font-size: 12px;
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
  padding: 0;
}

.search-shell {
  overflow: hidden;
  padding: 0;
}

.search-form {
  display: flex;
  width: 0;
  opacity: 0;
  transition: width 0.22s ease, opacity 0.18s ease;
}

.search-shell.expanded .search-form {
  width: min(300px, 56vw);
  opacity: 1;
}

.community-search-input {
  width: 100%;
  border: 0;
  background: transparent;
  color: #fff;
  outline: none;
}

.search-close {
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.55);
  padding: 0 12px;
}

.secondary-toolbar {
  margin-bottom: 28px;
}

.select-control {
  height: 40px;
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.07);
  color: rgb(255 255 255 / 0.84);
  padding: 0 16px;
}

.toggle-control {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: rgb(255 255 255 / 0.72);
}

.content-section {
  margin: 26px 0 34px;
}

.section-title {
  margin-bottom: 16px;
}

.section-title span,
.feed-header span {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: rgb(255 255 255 / 0.78);
  font-weight: 800;
}

.section-title button {
  padding: 8px 13px;
}

.section-grid {
  display: grid;
  gap: 18px;
}

.section-grid.compact {
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
}

.section-grid.small {
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
}

.split-sections {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: clamp(22px, 3vw, 34px);
}

.feed-header {
  margin: 34px 0 18px;
}

.feed-header p,
.feed-header strong {
  display: block;
  margin: 0;
}

.feed-header p {
  color: rgb(255 255 255 / 0.48);
  font-size: 13px;
}

.feed-header strong {
  margin-top: 4px;
  font-size: 22px;
}

.feed-header span {
  color: rgb(255 255 255 / 0.56);
  font-size: 13px;
}

.post-grid {
  columns: 4 260px;
  column-gap: clamp(20px, 2.6vw, 32px);
}

.post-grid :deep(.asset-card) {
  margin-bottom: clamp(20px, 2.6vw, 32px);
}

.state-panel {
  display: flex;
  min-height: 260px;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border-radius: 8px;
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
  margin: 40px auto 0;
}

@media (max-width: 860px) {
  .hero,
  .toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .split-sections {
    grid-template-columns: 1fr;
  }
}
</style>
