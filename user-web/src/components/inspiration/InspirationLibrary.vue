<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue"
import { useRouter } from "vue-router"
import {
  ArrowRight,
  Check,
  Copy,
  FolderHeart,
  Heart,
  Image as ImageIcon,
  Loader2,
  Menu,
  MessageCircle,
  MoreHorizontal,
  Plus,
  Search,
  Trash2,
  Wand2,
  X,
} from "lucide-vue-next"
import {
  addCommunityCollectionItem,
  createCommunityCollection,
  deleteCommunityCollection,
  fetchCommunityCollections,
  removeCommunityCollectionItem,
  renameCommunityCollection,
  trackCommunityEvent,
} from "@/api/communityApi"
import { getApiOrigin } from "@/api/client"
import type { CommunityCollection, CommunityPost } from "@/api/types"
import MasonryLayout from "@/components/MasonryLayout.vue"
import { confirmDelete } from "@/composables/useConfirmDelete"
import { userRoutes } from "@/router/userRoutes"
import { useAuthStore } from "@/store/authStore"
import { assetFromCommunityPost } from "@/utils/assetPreviewAdapter"
import { openDashboardWithAsset } from "@/utils/assetReplay"
import { communityDisplayTitle } from "@/utils/communityDisplay"
import { resolveCommunityAuthorName, resolveCommunityPrompt } from "@/utils/communityPostNormalize"

type SortKey = "collected_newest" | "collected_oldest" | "usage_most" | "usage_least"

const PAGE_SIZE = 12

const auth = useAuthStore()
const router = useRouter()

const collections = ref<CommunityCollection[]>([])
const collectionsSupported = ref(true)
const activeId = ref<number | null>(null)
const loading = ref(false)
const acting = ref(false)
const error = ref("")
const copyHint = ref("")

const sortKey = ref<SortKey>("collected_newest")
const filterKeyword = ref("")
const visibleCount = ref(PAGE_SIZE)
const selectedIds = ref<Set<number>>(new Set())

const sidebarOpen = ref(false)
const createOpen = ref(false)
const createName = ref("")
const renameOpen = ref(false)
const renameTarget = ref<CommunityCollection | null>(null)
const renameName = ref("")
const moveOpen = ref(false)
const moveTargets = ref<CommunityPost[]>([])
const cardMenuPostId = ref<number | null>(null)
const collectionMenuId = ref<number | null>(null)

const activeCollection = computed(
  () => collections.value.find((item) => item.id === activeId.value) || collections.value[0] || null,
)

const sortedPosts = computed(() => {
  const items = [...(activeCollection.value?.items || [])]
  if (sortKey.value === "collected_oldest") items.reverse()
  if (sortKey.value === "usage_most") items.sort((a, b) => (b.sameStyleCount || 0) - (a.sameStyleCount || 0))
  if (sortKey.value === "usage_least") items.sort((a, b) => (a.sameStyleCount || 0) - (b.sameStyleCount || 0))
  return items
})

const filteredPosts = computed(() => {
  const keyword = filterKeyword.value.trim().toLowerCase()
  if (!keyword) return sortedPosts.value
  return sortedPosts.value.filter((post) => {
    const title = postTitle(post).toLowerCase()
    const tags = displayTags(post).join(" ").toLowerCase()
    const tool = (post.toolName || post.toolCode || "").toLowerCase()
    return title.includes(keyword) || tags.includes(keyword) || tool.includes(keyword)
  })
})

const visiblePosts = computed(() => filteredPosts.value.slice(0, visibleCount.value))
const hasMore = computed(() => visibleCount.value < filteredPosts.value.length)
const selectedCount = computed(() => selectedIds.value.size)
const batchActive = computed(() => selectedCount.value > 0)
const otherCollections = computed(() => collections.value.filter((item) => item.id !== activeCollection.value?.id))

const skeletonItems = computed(() =>
  Array.from({ length: 6 }, (_, index) => ({
    id: index + 1,
    height: 180 + (index % 3) * 40,
  })),
)

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

function displayTags(post: CommunityPost) {
  const tags: string[] = []
  if (post.topic) tags.push(post.topic)
  for (const tag of post.tags || []) {
    const normalized = tag.startsWith("#") ? tag.slice(1) : tag
    if (normalized && !tags.includes(normalized)) tags.push(normalized)
  }
  return tags.slice(0, 3)
}

function sourceLabel(post: CommunityPost) {
  const author = resolveCommunityAuthorName(post)
  return author ? `来自 @${author}` : "社区作品"
}

function hasCover(post: CommunityPost) {
  return Boolean(mediaUrl(post.coverUrl)) && postKind(post) !== "text"
}

function isSelected(postId: number) {
  return selectedIds.value.has(postId)
}

function toggleSelect(postId: number) {
  const next = new Set(selectedIds.value)
  if (next.has(postId)) next.delete(postId)
  else next.add(postId)
  selectedIds.value = next
}

function clearSelection() {
  selectedIds.value = new Set()
}

async function load() {
  loading.value = true
  error.value = ""
  try {
    const result = await fetchCommunityCollections({ token: auth.token })
    collectionsSupported.value = result.supported
    collections.value = result.collections
    if (!collections.value.some((item) => item.id === activeId.value)) {
      activeId.value = collections.value[0]?.id ?? null
    }
    if (!result.supported) {
      error.value = "灵感收藏夹需要后端升级后可用。当前仍可在作品详情页使用普通收藏。"
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : "灵感收藏夹加载失败"
  } finally {
    loading.value = false
  }
}

function selectCollection(id: number) {
  activeId.value = id
  sidebarOpen.value = false
}

watch(activeId, () => {
  visibleCount.value = PAGE_SIZE
  clearSelection()
})

watch([sortKey, filterKeyword], () => {
  visibleCount.value = PAGE_SIZE
})

async function submitCreate() {
  const name = createName.value.trim()
  if (!name || !collectionsSupported.value) return
  acting.value = true
  try {
    const created = await createCommunityCollection(name, { token: auth.token })
    createName.value = ""
    createOpen.value = false
    await load()
    activeId.value = created.id
  } finally {
    acting.value = false
  }
}

function openRename(collection: CommunityCollection) {
  renameTarget.value = collection
  renameName.value = collection.name
  renameOpen.value = true
  collectionMenuId.value = null
}

async function submitRename() {
  const collection = renameTarget.value
  const name = renameName.value.trim()
  if (!collection || !name) return
  acting.value = true
  try {
    await renameCommunityCollection(collection.id, name, { token: auth.token })
    renameOpen.value = false
    renameTarget.value = null
    await load()
  } finally {
    acting.value = false
  }
}

async function deleteCollection(collection: CommunityCollection) {
  collectionMenuId.value = null
  if (collection.defaultCollection) return
  const confirmed = await confirmDelete({
    title: "删除收藏夹",
    itemName: collection.name,
    description: `确定要删除收藏夹「${collection.name}」吗？其中的作品引用也会被移除。`,
    confirmLabel: "确认删除",
  })
  if (!confirmed) return
  acting.value = true
  try {
    await deleteCommunityCollection(collection.id, { token: auth.token })
    if (activeId.value === collection.id) activeId.value = null
    await load()
  } finally {
    acting.value = false
  }
}

async function removePost(post: CommunityPost) {
  const collection = activeCollection.value
  if (!collection) return
  const confirmed = await confirmDelete({
    title: "确认移除",
    itemName: postTitle(post),
    description: "确定要从当前收藏夹移除此作品吗？",
    warning: "作品本身不会被删除，仅取消收藏。",
    confirmLabel: "确认移除",
  })
  if (!confirmed) return
  acting.value = true
  try {
    await removeCommunityCollectionItem(collection.id, post.id, { token: auth.token })
    selectedIds.value.delete(post.id)
    await load()
  } finally {
    acting.value = false
  }
}

async function movePosts(posts: CommunityPost[], targetCollectionId: number) {
  const source = activeCollection.value
  if (!source || !posts.length) return
  const target = collections.value.find((item) => item.id === targetCollectionId)
  const confirmed = await confirmDelete({
    title: "确认移动",
    description: `将 ${posts.length} 个作品移动到「${target?.name || "目标收藏夹"}」？`,
    warning: "",
    confirmLabel: "确认移动",
  })
  if (!confirmed) return
  acting.value = true
  try {
    for (const post of posts) {
      await addCommunityCollectionItem(targetCollectionId, post.id, { token: auth.token })
      if (targetCollectionId !== source.id) {
        await removeCommunityCollectionItem(source.id, post.id, { token: auth.token })
      }
    }
    moveOpen.value = false
    moveTargets.value = []
    clearSelection()
    await load()
  } finally {
    acting.value = false
  }
}

function openMoveDialog(posts: CommunityPost[]) {
  moveTargets.value = posts
  moveOpen.value = true
  cardMenuPostId.value = null
}

async function batchRemove() {
  const posts = visiblePosts.value.filter((post) => selectedIds.value.has(post.id))
  if (!posts.length) return
  const confirmed = await confirmDelete({
    title: "批量移除",
    description: `确定要从当前收藏夹移除 ${posts.length} 个作品吗？`,
    warning: "作品本身不会被删除，仅取消收藏。",
    confirmLabel: "确认移除",
  })
  if (!confirmed) return
  acting.value = true
  try {
    const collection = activeCollection.value
    if (!collection) return
    for (const post of posts) {
      await removeCommunityCollectionItem(collection.id, post.id, { token: auth.token })
    }
    clearSelection()
    await load()
  } finally {
    acting.value = false
  }
}

function replay(post: CommunityPost) {
  if (!post.toolCode) return
  void trackCommunityEvent(
    { postId: post.id, eventType: "dashboard_open", source: "inspiration_collection", toolCode: post.toolCode },
    { token: auth.token },
  ).catch(() => undefined)
  openDashboardWithAsset(assetFromCommunityPost(post, mediaUrl(post.coverUrl)), post.toolCode, {
    modality: post.modality,
    sourcePost: post.id,
  })
}

async function copyPrompt(post: CommunityPost) {
  cardMenuPostId.value = null
  const prompt = resolveCommunityPrompt(post)
  if (!prompt) {
    copyHint.value = "该作品未公开 Prompt"
    return
  }
  try {
    await navigator.clipboard.writeText(prompt)
    copyHint.value = "Prompt 已复制"
  } catch {
    copyHint.value = "复制失败，请手动复制"
  }
  window.setTimeout(() => {
    copyHint.value = ""
  }, 2200)
}

function openPost(post: CommunityPost) {
  router.push(`/community/posts/${post.id}`)
}

function loadMore() {
  visibleCount.value += PAGE_SIZE
}

function closeMenus() {
  cardMenuPostId.value = null
  collectionMenuId.value = null
}

onMounted(() => {
  void load()
  document.addEventListener("click", closeMenus)
})

onUnmounted(() => {
  document.removeEventListener("click", closeMenus)
})
</script>

<template>
  <div class="inspiration-library" @click="closeMenus">
    <div v-if="sidebarOpen" class="sidebar-backdrop md:hidden" @click="sidebarOpen = false" />

    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <div class="sidebar-head">
        <FolderHeart class="h-5 w-5 text-primary" />
        <span>我的收藏夹</span>
      </div>

      <button type="button" class="new-collection-btn" :disabled="!collectionsSupported || acting" @click.stop="createOpen = true">
        <Plus class="h-4 w-4" />
        新建收藏夹
      </button>

      <div v-if="loading" class="sidebar-loading">
        <Loader2 class="h-4 w-4 animate-spin" />
      </div>

      <nav v-else class="collection-nav" aria-label="收藏夹列表">
        <div
          v-for="collection in collections"
          :key="collection.id"
          class="collection-row"
          :class="{ active: activeCollection?.id === collection.id }"
        >
          <button type="button" class="collection-btn" @click="selectCollection(collection.id)">
            <span class="collection-name">{{ collection.name }}</span>
            <span class="collection-count">({{ collection.itemCount }})</span>
          </button>
          <div class="collection-actions">
            <button
              type="button"
              class="icon-btn"
              aria-label="更多操作"
              @click.stop="collectionMenuId = collectionMenuId === collection.id ? null : collection.id"
            >
              <MoreHorizontal class="h-4 w-4" />
            </button>
            <div v-if="collectionMenuId === collection.id" class="dropdown" @click.stop>
              <button type="button" :disabled="collection.defaultCollection" @click="openRename(collection)">重命名</button>
              <button type="button" class="danger" :disabled="collection.defaultCollection" @click="deleteCollection(collection)">
                删除
              </button>
            </div>
          </div>
        </div>
      </nav>
    </aside>

    <main class="main-panel">
      <header class="content-header">
        <div class="header-left">
          <button type="button" class="drawer-toggle md:hidden" aria-label="打开收藏夹列表" @click="sidebarOpen = true">
            <Menu class="h-5 w-5" />
          </button>
          <div>
            <p class="eyebrow">Inspiration Library</p>
            <h1>{{ activeCollection?.name || "灵感收藏夹" }}</h1>
          </div>
        </div>

        <div class="header-controls">
          <div class="search-box">
            <Search class="h-4 w-4" />
            <input v-model="filterKeyword" type="search" placeholder="筛选标题或标签" />
          </div>
          <select v-model="sortKey" class="sort-select" aria-label="排序">
            <option value="collected_newest">收藏时间 · 最新</option>
            <option value="collected_oldest">收藏时间 · 最早</option>
            <option value="usage_most">使用次数 · 最多</option>
            <option value="usage_least">使用次数 · 最少</option>
          </select>
        </div>
      </header>

      <p v-if="copyHint" class="inline-hint">{{ copyHint }}</p>
      <div v-if="error" class="state-panel error">{{ error }}</div>

      <MasonryLayout
        v-else-if="loading"
        :items="skeletonItems"
        item-key="id"
        :estimate-height="(item) => item.height"
        aria-busy="true"
        aria-label="加载中"
      >
        <template #default="{ item }">
          <article class="insp-card skeleton" :style="{ '--skeleton-h': `${item.height}px` }">
            <div class="thumb skeleton-block" />
            <div class="card-body">
              <div class="skeleton-line wide" />
              <div class="skeleton-line" />
            </div>
          </article>
        </template>
      </MasonryLayout>

      <div v-else-if="!visiblePosts.length" class="empty-state">
        <FolderHeart class="h-10 w-10 text-primary/70" />
        <p>暂无收藏作品，去社区发现灵感吧</p>
        <button type="button" class="primary-btn" @click="router.push(userRoutes.community)">
          去社区
          <ArrowRight class="h-4 w-4" />
        </button>
      </div>

      <MasonryLayout v-else :items="visiblePosts" :item-key="(post) => post.id" aria-label="收藏作品">
        <template #default="{ item: post }">
        <article class="insp-card group">
          <label class="select-box" :class="{ checked: isSelected(post.id) }" @click.stop>
            <input type="checkbox" :checked="isSelected(post.id)" @change="toggleSelect(post.id)" />
            <Check v-if="isSelected(post.id)" class="h-3 w-3" />
          </label>

          <button type="button" class="card-link" @click="openPost(post)">
            <div class="thumb">
              <img
                v-if="hasCover(post) && postKind(post) === 'image'"
                :src="mediaUrl(post.coverUrl)"
                :alt="postTitle(post)"
                loading="lazy"
                decoding="async"
              />
              <video
                v-else-if="hasCover(post) && postKind(post) === 'video'"
                :src="mediaUrl(post.coverUrl)"
                class="thumb-video"
                muted
                loop
                playsinline
                preload="metadata"
              />
              <div v-else class="thumb-placeholder">
                <ImageIcon class="h-8 w-8" />
              </div>
            </div>

            <div class="card-body">
              <h3>{{ postTitle(post) }}</h3>
              <p class="source">{{ sourceLabel(post) }}</p>
              <div v-if="displayTags(post).length" class="tag-row">
                <span v-for="tag in displayTags(post)" :key="tag">#{{ tag }}</span>
              </div>
              <div class="stats-row">
                <span><Heart class="h-3.5 w-3.5" />{{ post.likeCount }}</span>
                <span><MessageCircle class="h-3.5 w-3.5" />{{ post.shareCount || 0 }}</span>
              </div>
            </div>
          </button>

          <div class="card-actions">
            <button type="button" class="action-btn primary" :disabled="!post.toolCode" title="同款创作" @click.stop="replay(post)">
              <Wand2 class="h-3.5 w-3.5" />
              <span>同款</span>
            </button>
            <button type="button" class="action-btn" title="移除" @click.stop="removePost(post)">
              <Trash2 class="h-3.5 w-3.5" />
              <span>移除</span>
            </button>
            <div class="more-wrap">
              <button
                type="button"
                class="action-btn"
                aria-label="更多"
                @click.stop="cardMenuPostId = cardMenuPostId === post.id ? null : post.id"
              >
                <MoreHorizontal class="h-3.5 w-3.5" />
              </button>
              <div v-if="cardMenuPostId === post.id" class="dropdown card-dropdown" @click.stop>
                <button type="button" :disabled="!otherCollections.length" @click="openMoveDialog([post])">移动到其他收藏夹</button>
                <button type="button" @click="copyPrompt(post)">
                  <Copy class="h-3.5 w-3.5" />
                  复制 Prompt
                </button>
              </div>
            </div>
          </div>
        </article>
        </template>
      </MasonryLayout>

      <button v-if="hasMore && !loading" type="button" class="load-more" :disabled="acting" @click="loadMore">
        加载更多
      </button>
    </main>

    <div v-if="batchActive" class="batch-bar">
      <span>已选 {{ selectedCount }} 项</span>
      <div class="batch-actions">
        <button type="button" :disabled="!otherCollections.length || acting" @click="openMoveDialog(visiblePosts.filter((p) => selectedIds.has(p.id)))">
          批量移动
        </button>
        <button type="button" class="danger" :disabled="acting" @click="batchRemove">批量删除</button>
        <button type="button" class="ghost" @click="clearSelection">取消</button>
      </div>
    </div>

    <!-- 新建收藏夹 -->
    <Teleport to="body">
      <div v-if="createOpen" class="modal-backdrop" @click.self="createOpen = false">
        <div class="modal-panel" role="dialog" aria-labelledby="create-title">
          <div class="modal-head">
            <h3 id="create-title">新建收藏夹</h3>
            <button type="button" class="icon-btn" @click="createOpen = false"><X class="h-4 w-4" /></button>
          </div>
          <form @submit.prevent="submitCreate">
            <input v-model="createName" autofocus placeholder="收藏夹名称" maxlength="80" />
            <div class="modal-foot">
              <button type="button" class="ghost" @click="createOpen = false">取消</button>
              <button type="submit" class="primary-btn" :disabled="!createName.trim() || acting">
                <Loader2 v-if="acting" class="h-4 w-4 animate-spin" />
                创建
              </button>
            </div>
          </form>
        </div>
      </div>
    </Teleport>

    <!-- 重命名 -->
    <Teleport to="body">
      <div v-if="renameOpen" class="modal-backdrop" @click.self="renameOpen = false">
        <div class="modal-panel" role="dialog" aria-labelledby="rename-title">
          <div class="modal-head">
            <h3 id="rename-title">重命名收藏夹</h3>
            <button type="button" class="icon-btn" @click="renameOpen = false"><X class="h-4 w-4" /></button>
          </div>
          <form @submit.prevent="submitRename">
            <input v-model="renameName" autofocus placeholder="新名称" maxlength="80" />
            <div class="modal-foot">
              <button type="button" class="ghost" @click="renameOpen = false">取消</button>
              <button type="submit" class="primary-btn" :disabled="!renameName.trim() || acting">保存</button>
            </div>
          </form>
        </div>
      </div>
    </Teleport>

    <!-- 移动到 -->
    <Teleport to="body">
      <div v-if="moveOpen" class="modal-backdrop" @click.self="moveOpen = false">
        <div class="modal-panel" role="dialog" aria-labelledby="move-title">
          <div class="modal-head">
            <h3 id="move-title">移动到收藏夹</h3>
            <button type="button" class="icon-btn" @click="moveOpen = false"><X class="h-4 w-4" /></button>
          </div>
          <p class="modal-desc">选择目标收藏夹（{{ moveTargets.length }} 个作品）</p>
          <div class="move-list">
            <button
              v-for="collection in otherCollections"
              :key="collection.id"
              type="button"
              class="move-item"
              :disabled="acting"
              @click="movePosts(moveTargets, collection.id)"
            >
              {{ collection.name }}
              <span>({{ collection.itemCount }})</span>
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.inspiration-library {
  display: flex;
  min-height: calc(100vh - 64px);
  color: #f8fafc;
}

.sidebar-backdrop {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: rgb(0 0 0 / 0.55);
}

.sidebar {
  flex-shrink: 0;
  width: 260px;
  border-right: 1px solid rgb(255 255 255 / 0.08);
  background: rgb(255 255 255 / 0.02);
  padding: 20px 14px;
}

.sidebar-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  font-weight: 700;
}

.new-collection-btn {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px dashed rgb(255 255 255 / 0.18);
  border-radius: 12px;
  background: rgb(255 255 255 / 0.04);
  color: rgb(255 255 255 / 0.82);
  padding: 10px 12px;
  margin-bottom: 14px;
  font-weight: 600;
  transition: border-color 0.18s ease, background 0.18s ease;
}

.new-collection-btn:hover:not(:disabled) {
  border-color: var(--primary);
  background: rgb(124 58 237 / 0.1);
}

.sidebar-loading {
  display: flex;
  justify-content: center;
  padding: 24px 0;
  color: rgb(255 255 255 / 0.5);
}

.collection-nav {
  display: grid;
  gap: 6px;
}

.collection-row {
  position: relative;
  display: flex;
  align-items: center;
  border-radius: 10px;
  transition: background 0.18s ease;
}

.collection-row.active {
  background: rgb(124 58 237 / 0.18);
  box-shadow: inset 3px 0 0 var(--primary);
}

.collection-btn {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.78);
  padding: 10px 8px 10px 12px;
  text-align: left;
  cursor: pointer;
}

.collection-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
}

.collection-count {
  flex-shrink: 0;
  color: rgb(255 255 255 / 0.42);
  font-size: 12px;
}

.collection-actions {
  position: relative;
  padding-right: 4px;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: rgb(255 255 255 / 0.55);
  padding: 6px;
  cursor: pointer;
}

.icon-btn:hover {
  background: rgb(255 255 255 / 0.08);
  color: #fff;
}

.dropdown {
  position: absolute;
  top: calc(100% + 4px);
  right: 0;
  z-index: 30;
  min-width: 140px;
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 10px;
  background: rgb(24 24 27);
  box-shadow: 0 16px 40px rgb(0 0 0 / 0.35);
}

.dropdown button {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  border: 0;
  background: transparent;
  color: rgb(255 255 255 / 0.82);
  padding: 10px 12px;
  text-align: left;
  font-size: 13px;
  cursor: pointer;
}

.dropdown button:hover:not(:disabled) {
  background: rgb(255 255 255 / 0.06);
}

.dropdown button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.dropdown button.danger {
  color: rgb(252 165 165);
}

.main-panel {
  flex: 1;
  min-width: 0;
  padding: clamp(20px, 3vw, 32px);
  padding-bottom: 88px;
}

.content-header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 22px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.drawer-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 10px;
  background: rgb(255 255 255 / 0.05);
  color: #fff;
  padding: 8px;
}

.eyebrow {
  margin: 0 0 6px;
  color: rgb(255 255 255 / 0.42);
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.content-header h1 {
  margin: 0;
  font-size: clamp(24px, 3vw, 34px);
  font-weight: 700;
}

.header-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.search-box {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.05);
  color: rgb(255 255 255 / 0.45);
  padding: 0 12px;
}

.search-box input {
  width: min(220px, 42vw);
  border: 0;
  background: transparent;
  color: #fff;
  padding: 9px 0;
  outline: none;
  font-size: 13px;
}

.sort-select {
  height: 38px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.05);
  color: rgb(255 255 255 / 0.84);
  padding: 0 14px;
  font-size: 13px;
}

.inline-hint {
  margin: 0 0 12px;
  color: rgb(134 239 172);
  font-size: 13px;
}

.insp-card {
  position: relative;
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 16px;
  background: rgb(255 255 255 / 0.04);
  transition: transform 0.22s ease, box-shadow 0.22s ease, border-color 0.22s ease;
}

.insp-card:hover {
  transform: translateY(-3px);
  border-color: rgb(255 255 255 / 0.14);
  box-shadow: 0 18px 40px rgb(0 0 0 / 0.28);
}

.select-box {
  position: absolute;
  top: 10px;
  left: 10px;
  z-index: 2;
  display: inline-flex;
  width: 22px;
  height: 22px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 0.35);
  border-radius: 6px;
  background: rgb(0 0 0 / 0.35);
  opacity: 0;
  transition: opacity 0.18s ease;
  cursor: pointer;
}

.insp-card:hover .select-box,
.select-box.checked {
  opacity: 1;
}

.select-box input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.select-box.checked {
  border-color: var(--primary);
  background: var(--primary);
  color: var(--primary-foreground);
}

.card-link {
  display: block;
  width: 100%;
  border: 0;
  background: transparent;
  color: inherit;
  padding: 0;
  text-align: left;
  cursor: pointer;
}

.thumb {
  overflow: hidden;
  border-radius: 16px 16px 0 0;
  background: rgb(255 255 255 / 0.03);
}

.thumb img,
.thumb video,
.thumb .thumb-video {
  display: block;
  width: 100%;
  height: auto;
  vertical-align: top;
}

.thumb-placeholder {
  display: flex;
  min-height: 140px;
  width: 100%;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, rgb(124 58 237 / 0.16), rgb(59 130 246 / 0.1));
  color: rgb(255 255 255 / 0.35);
}

.card-body {
  padding: 10px 12px 4px;
}

.card-body h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 700;
  line-height: 1.45;
  color: rgb(255 255 255 / 0.92);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.source {
  margin: 6px 0 0;
  color: rgb(255 255 255 / 0.42);
  font-size: 12px;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.tag-row span {
  border-radius: 999px;
  background: rgb(255 255 255 / 0.08);
  color: rgb(255 255 255 / 0.62);
  padding: 2px 9px;
  font-size: 11px;
}

.stats-row {
  display: flex;
  gap: 12px;
  margin-top: 8px;
  color: rgb(255 255 255 / 0.38);
  font-size: 12px;
  font-weight: 500;
}

.stats-row span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.card-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  border-top: 1px solid rgb(255 255 255 / 0.06);
  padding: 8px 10px;
}

.action-btn {
  display: inline-flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-width: 0;
  border: 0;
  border-radius: 8px;
  background: rgb(255 255 255 / 0.06);
  color: rgb(255 255 255 / 0.72);
  padding: 7px 6px;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
}

.action-btn.primary {
  background: rgb(124 58 237 / 0.22);
  color: #e9d5ff;
}

.action-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.more-wrap {
  position: relative;
  flex: 0 0 auto;
}

.more-wrap .action-btn {
  flex: none;
  width: 34px;
  padding: 7px;
}

.card-dropdown {
  bottom: calc(100% + 6px);
  top: auto;
  min-width: 168px;
}

.empty-state,
.state-panel {
  display: flex;
  min-height: 280px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  border: 1px dashed rgb(255 255 255 / 0.12);
  border-radius: 16px;
  color: rgb(255 255 255 / 0.58);
  text-align: center;
}

.state-panel.error {
  color: rgb(254 202 202);
}

.primary-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 999px;
  background: var(--primary);
  color: var(--primary-foreground);
  padding: 10px 18px;
  font-weight: 700;
  cursor: pointer;
}

.load-more {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 28px auto 0;
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.07);
  color: rgb(255 255 255 / 0.74);
  padding: 10px 18px;
  font-weight: 600;
  cursor: pointer;
}

.batch-bar {
  position: fixed;
  right: 24px;
  bottom: 24px;
  left: 284px;
  z-index: 35;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 14px;
  background: rgb(24 24 27 / 0.96);
  backdrop-filter: blur(12px);
  padding: 12px 16px;
  box-shadow: 0 20px 48px rgb(0 0 0 / 0.35);
}

.batch-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.batch-actions button,
.modal-foot button,
.ghost {
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.08);
  color: rgb(255 255 255 / 0.82);
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.batch-actions button.danger {
  background: rgb(239 68 68 / 0.18);
  color: rgb(254 202 202);
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgb(0 0 0 / 0.55);
  padding: 16px;
  backdrop-filter: blur(4px);
}

.modal-panel {
  width: min(420px, 100%);
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 16px;
  background: rgb(24 24 27);
  padding: 18px;
  box-shadow: 0 24px 60px rgb(0 0 0 / 0.4);
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.modal-head h3 {
  margin: 0;
  font-size: 18px;
}

.modal-panel input {
  width: 100%;
  border: 1px solid rgb(255 255 255 / 0.12);
  border-radius: 10px;
  background: rgb(255 255 255 / 0.05);
  color: #fff;
  padding: 11px 12px;
  outline: none;
}

.modal-desc {
  margin: 0 0 12px;
  color: rgb(255 255 255 / 0.55);
  font-size: 13px;
}

.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}

.move-list {
  display: grid;
  gap: 8px;
  max-height: 280px;
  overflow: auto;
}

.move-item {
  display: flex;
  justify-content: space-between;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 10px;
  background: rgb(255 255 255 / 0.04);
  color: rgb(255 255 255 / 0.82);
  padding: 11px 12px;
  text-align: left;
  cursor: pointer;
}

.move-item span {
  color: rgb(255 255 255 / 0.42);
  font-size: 12px;
}

.move-item:hover:not(:disabled) {
  border-color: var(--primary);
  background: rgb(124 58 237 / 0.12);
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
  width: 80%;
}

@keyframes shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}

@media (max-width: 767px) {
  .sidebar {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    z-index: 50;
    transform: translateX(-100%);
    transition: transform 0.22s ease;
    box-shadow: 0 0 40px rgb(0 0 0 / 0.4);
  }

  .sidebar.open {
    transform: translateX(0);
  }

  .batch-bar {
    left: 16px;
    flex-direction: column;
    align-items: stretch;
  }

  .action-btn span {
    display: none;
  }

  .tag-row,
  .stats-row span:last-child {
    display: none;
  }
}

@media (min-width: 768px) {
  .md\:hidden {
    display: none;
  }
}
</style>
