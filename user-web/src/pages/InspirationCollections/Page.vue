<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { useRouter } from "vue-router"
import { Loader2, Plus, Trash2 } from "lucide-vue-next"
import AppShell from "@/components/AppShell.vue"
import AssetCard from "@/components/AssetCard.vue"
import {
  createCommunityCollection,
  deleteCommunityCollection,
  fetchCommunityCollections,
  removeCommunityCollectionItem,
} from "@/api/communityApi"
import { getApiOrigin } from "@/api/client"
import type { CommunityCollection, CommunityPost } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { assetFromCommunityPost } from "@/utils/assetPreviewAdapter"
import { openDashboardWithAsset } from "@/utils/assetReplay"

const auth = useAuthStore()
const router = useRouter()
const collections = ref<CommunityCollection[]>([])
const activeId = ref<number | null>(null)
const loading = ref(false)
const error = ref("")
const newName = ref("")

const activeCollection = computed(() => collections.value.find((item) => item.id === activeId.value) || collections.value[0] || null)
const assets = computed(() =>
  (activeCollection.value?.items || []).map((post) => ({
    post,
    asset: assetFromCommunityPost(post, mediaUrl(post.coverUrl)),
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

async function load() {
  loading.value = true
  error.value = ""
  try {
    collections.value = await fetchCommunityCollections({ token: auth.token })
    activeId.value = activeId.value || collections.value[0]?.id || null
  } catch (err) {
    error.value = err instanceof Error ? err.message : "Failed to load inspirations"
  } finally {
    loading.value = false
  }
}

async function createCollection() {
  const name = newName.value.trim()
  if (!name) return
  const created = await createCommunityCollection(name, { token: auth.token })
  newName.value = ""
  collections.value = [created, ...collections.value]
  activeId.value = created.id
}

async function deleteActive() {
  const collection = activeCollection.value
  if (!collection || collection.defaultCollection) return
  await deleteCommunityCollection(collection.id, { token: auth.token })
  await load()
}

async function removePost(post: CommunityPost) {
  const collection = activeCollection.value
  if (!collection) return
  await removeCommunityCollectionItem(collection.id, post.id, { token: auth.token })
  await load()
}

function replay(post: CommunityPost) {
  if (!post.toolCode) return
  openDashboardWithAsset(assetFromCommunityPost(post, mediaUrl(post.coverUrl)), post.toolCode, {
    modality: post.modality,
    sourcePost: post.id,
  })
}

onMounted(() => void load())
</script>

<template>
  <AppShell title="灵感收藏夹" description="收藏公开社区作品，作为可复用的个人灵感库">
    <main class="collections-page">
      <section class="toolbar">
        <div>
          <p class="eyebrow">Inspiration Library</p>
          <h1>灵感收藏夹</h1>
        </div>
        <form class="create-form" @submit.prevent="createCollection">
          <input v-model="newName" placeholder="新收藏夹名称" />
          <button type="submit"><Plus class="h-4 w-4" />新建</button>
        </form>
      </section>

      <div v-if="loading" class="state-panel"><Loader2 class="h-5 w-5 animate-spin" />Loading</div>
      <div v-else-if="error" class="state-panel error">{{ error }}</div>
      <section v-else class="content-grid">
        <aside class="collection-list">
          <button
            v-for="collection in collections"
            :key="collection.id"
            type="button"
            :class="{ active: activeCollection?.id === collection.id }"
            @click="activeId = collection.id"
          >
            <span>{{ collection.name }}</span>
            <strong>{{ collection.itemCount }}</strong>
          </button>
          <button v-if="activeCollection && !activeCollection.defaultCollection" type="button" class="danger" @click="deleteActive">
            <Trash2 class="h-4 w-4" />删除当前收藏夹
          </button>
        </aside>

        <section class="asset-grid">
          <div v-if="!assets.length" class="state-panel">还没有收藏作品</div>
          <AssetCard
            v-for="item in assets"
            :key="item.post.id"
            :asset="item.asset"
            source="community"
            compact
            @open="router.push(`/community/posts/${item.post.id}`)"
          >
            <template #footer>
              <button type="button" @click.stop="replay(item.post)">同款创作</button>
              <button type="button" @click.stop="removePost(item.post)">移除</button>
            </template>
          </AssetCard>
        </section>
      </section>
    </main>
  </AppShell>
</template>

<style scoped>
.collections-page {
  min-height: 100%;
  padding: 28px;
  color: #f8fafc;
}

.toolbar,
.content-grid,
.create-form,
.collection-list button {
  display: flex;
  gap: 14px;
}

.toolbar {
  align-items: end;
  justify-content: space-between;
  border-bottom: 1px solid rgb(255 255 255 / 0.08);
  padding-bottom: 22px;
}

.eyebrow {
  margin: 0 0 8px;
  color: rgb(255 255 255 / 0.45);
  font-size: 12px;
  text-transform: uppercase;
}

h1 {
  margin: 0;
  font-size: 40px;
}

.create-form {
  align-items: center;
}

input,
button {
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 8px;
  background: rgb(255 255 255 / 0.06);
  color: #fff;
  padding: 10px 12px;
}

button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
}

.content-grid {
  align-items: start;
  margin-top: 24px;
}

.collection-list {
  display: grid;
  width: 240px;
  gap: 8px;
}

.collection-list button {
  justify-content: space-between;
}

.collection-list .active {
  border-color: rgb(56 189 248 / 0.5);
  background: rgb(14 165 233 / 0.16);
}

.danger {
  color: rgb(254 202 202);
}

.asset-grid {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 18px;
}

.state-panel {
  display: flex;
  min-height: 220px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px dashed rgb(255 255 255 / 0.12);
  border-radius: 8px;
  color: rgb(255 255 255 / 0.55);
}

.error {
  color: rgb(254 202 202);
}

@media (max-width: 820px) {
  .toolbar,
  .content-grid {
    flex-direction: column;
    align-items: stretch;
  }

  .collection-list {
    width: 100%;
  }
}
</style>
