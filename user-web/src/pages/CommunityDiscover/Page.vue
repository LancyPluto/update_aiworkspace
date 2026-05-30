<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import { useRouter } from "vue-router"
import { Loader2, RefreshCcw, Search } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import AssetCard from "@/components/AssetCard.vue"
import { fetchCommunityPosts } from "@/api/communityApi"
import { getApiOrigin } from "@/api/client"
import type { CommunityPost } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { assetFromCommunityPost } from "@/utils/assetPreviewAdapter"

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

const featuredPosts = computed(() => posts.value.filter((post) => post.featured || post.pinned).slice(0, 4))
const postAssets = computed(() => posts.value.map((post) => ({ post, asset: assetFromCommunityPost(post, mediaUrl(post.coverUrl)) })))

function mediaUrl(value?: string | null) {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}

function openPost(post: CommunityPost) {
  router.push(`/community/posts/${post.id}`)
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
    const page = await fetchCommunityPosts({
      token: auth.token,
      query: {
        pageNo: currentPage,
        pageSize: 16,
        modality: modality.value || undefined,
        sort: sort.value,
        featured: featuredOnly.value ? true : undefined,
        tag: tag.value.trim() || undefined,
      },
    })
    posts.value = reset ? page.list : [...posts.value, ...page.list]
    hasNext.value = page.hasNext
    pageNo.value = currentPage + 1
  } catch (err) {
    error.value = err instanceof Error ? err.message : "社区作品加载失败"
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

watch([modality, sort, featuredOnly], () => void load(true))
onMounted(() => void load(true))
</script>

<template>
  <AppShell title="社区发现" description="浏览优秀作品，学习公开 Prompt，并一键同款创作">
    <div class="community-discover">
      <section class="topbar">
        <div>
          <p class="eyebrow">Community Gallery</p>
          <h1>作品、Prompt 和工具灵感流</h1>
          <p>从真实生成结果出发，发现好案例，收藏灵感，再回到工具里继续创作。</p>
        </div>
        <button type="button" class="refresh-button" :disabled="loading" @click="load(true)">
          <RefreshCcw class="h-4 w-4" />
          刷新
        </button>
      </section>

      <section class="filters">
        <div class="segmented">
          <button
            v-for="item in filters"
            :key="item.value || 'all'"
            type="button"
            :class="{ active: modality === item.value }"
            @click="modality = item.value"
          >
            {{ item.label }}
          </button>
        </div>
        <select v-model="sort" class="select-control">
          <option v-for="item in sorts" :key="item.value" :value="item.value">{{ item.label }}</option>
        </select>
        <label class="toggle-control">
          <input v-model="featuredOnly" type="checkbox" />
          只看精选
        </label>
        <form class="search-control" @submit.prevent="load(true)">
          <Search class="h-4 w-4" />
          <input v-model="tag" placeholder="按标签搜索，如 产品图、短视频" />
          <button type="submit">搜索</button>
        </form>
      </section>

      <section v-if="featuredPosts.length" class="featured-strip">
        <article v-for="post in featuredPosts" :key="post.id" class="featured-card" @click="openPost(post)">
          <span class="badge">{{ post.pinned ? "置顶" : "精选" }}</span>
          <strong>{{ post.title }}</strong>
          <p>{{ post.description || post.toolName || "AI 创作案例" }}</p>
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
          compact
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
  padding: 28px;
  color: #f8fafc;
}

.topbar {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 20px;
  border-bottom: 1px solid rgb(255 255 255 / 0.08);
  padding-bottom: 24px;
}

.eyebrow {
  margin: 0 0 10px;
  color: rgb(255 255 255 / 0.45);
  font-size: 12px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.topbar h1 {
  margin: 0;
  font-size: clamp(30px, 4vw, 56px);
  line-height: 1.05;
}

.topbar p {
  max-width: 680px;
  margin: 14px 0 0;
  color: rgb(255 255 255 / 0.58);
  line-height: 1.8;
}

.refresh-button,
.load-more,
.search-control button,
.segmented button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 8px;
  background: rgb(255 255 255 / 0.06);
  color: rgb(255 255 255 / 0.78);
  padding: 10px 14px;
  font-weight: 800;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin: 24px 0;
}

.segmented {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.segmented button.active {
  border-color: rgb(125 211 252 / 0.42);
  background: rgb(14 165 233 / 0.18);
  color: #fff;
}

.select-control,
.search-control input {
  height: 42px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 8px;
  background: rgb(255 255 255 / 0.06);
  color: #fff;
  padding: 0 12px;
  outline: none;
}

.toggle-control,
.search-control {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: rgb(255 255 255 / 0.66);
  font-size: 14px;
}

.search-control {
  min-width: min(100%, 380px);
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 8px;
  background: rgb(255 255 255 / 0.05);
  padding: 0 8px 0 12px;
}

.search-control input {
  min-width: 0;
  flex: 1;
  border: 0;
  background: transparent;
}

.featured-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 14px;
  margin-bottom: 24px;
}

.featured-card {
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 8px;
  background: rgb(255 255 255 / 0.055);
  padding: 16px;
  cursor: pointer;
}

.featured-card strong,
.featured-card p {
  display: block;
  margin: 8px 0 0;
}

.featured-card p {
  color: rgb(255 255 255 / 0.52);
  line-height: 1.6;
}

.badge,
.kind-pill,
.featured-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.48);
  color: #fff;
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 800;
}

.post-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 18px;
}

.post-card {
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 8px;
  background: rgb(255 255 255 / 0.055);
  cursor: pointer;
  transition: transform 0.18s ease, border-color 0.18s ease;
}

.post-card:hover {
  transform: translateY(-2px);
  border-color: rgb(125 211 252 / 0.38);
}

.media-frame {
  position: relative;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  background: rgb(255 255 255 / 0.04);
}

.media-frame img,
.media-frame video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.text-cover {
  display: grid;
  height: 100%;
  place-items: center;
  padding: 20px;
  color: rgb(255 255 255 / 0.72);
  line-height: 1.7;
}

.kind-pill {
  position: absolute;
  left: 12px;
  top: 12px;
}

.featured-pill {
  position: absolute;
  right: 12px;
  top: 12px;
  background: rgb(14 165 233 / 0.72);
}

.post-body {
  padding: 16px;
}

.post-body h2 {
  margin: 0;
  font-size: 18px;
  line-height: 1.35;
}

.post-body p {
  min-height: 22px;
  margin: 8px 0 12px;
  color: rgb(255 255 255 / 0.48);
  line-height: 1.6;
}

.tag-row,
.post-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.tag-row span {
  color: rgb(125 211 252 / 0.82);
  font-size: 12px;
}

.post-meta {
  margin-top: 12px;
  color: rgb(255 255 255 / 0.46);
}

.post-meta span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.state-panel {
  display: flex;
  min-height: 240px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px dashed rgb(255 255 255 / 0.12);
  border-radius: 8px;
  color: rgb(255 255 255 / 0.5);
}

.state-panel.error {
  color: rgb(254 202 202);
}

.load-more {
  margin: 26px auto 0;
}

@media (max-width: 760px) {
  .community-discover {
    padding: 18px;
  }

  .topbar {
    align-items: start;
    flex-direction: column;
  }
}
</style>
