<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { useRoute, useRouter } from "vue-router"
import { ArrowLeft, Copy, Download, Heart, Loader2, Send, Star, UserRound } from "lucide-vue-next"
import {
  favoriteCommunityPost,
  fetchCommunityPost,
  fetchCommunityCollections,
  addCommunityCollectionItem,
  likeCommunityPost,
  markCommunityPostSameStyle,
  trackCommunityEvent,
  unfavoriteCommunityPost,
  unlikeCommunityPost,
} from "@/api/communityApi"
import { getApiOrigin } from "@/api/client"
import type { CommunityPost } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { assetFromCommunityPost } from "@/utils/assetPreviewAdapter"
import { openDashboardWithAsset } from "@/utils/assetReplay"

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const post = ref<CommunityPost | null>(null)
const loading = ref(false)
const acting = ref(false)
const sameStyleLoading = ref(false)
const collecting = ref(false)
const error = ref("")

const postId = computed(() => String(route.params.postId || ""))
const kind = computed(() => {
  const modality = (post.value?.modality || "").toLowerCase()
  if (modality.includes("video")) return "video"
  if (modality.includes("audio")) return "audio"
  if (modality.includes("image")) return "image"
  return "text"
})

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
    post.value = await fetchCommunityPost(postId.value, { token: auth.token })
  } catch (err) {
    error.value = err instanceof Error ? err.message : "作品加载失败"
  } finally {
    loading.value = false
  }
}

async function toggleLike() {
  if (!post.value || !auth.token) return router.push({ name: "Login", query: { redirect: route.fullPath } })
  acting.value = true
  try {
    post.value = post.value.liked
      ? await unlikeCommunityPost(post.value.id, { token: auth.token })
      : await likeCommunityPost(post.value.id, { token: auth.token })
  } finally {
    acting.value = false
  }
}

async function toggleFavorite() {
  if (!post.value || !auth.token) return router.push({ name: "Login", query: { redirect: route.fullPath } })
  acting.value = true
  try {
    post.value = post.value.favorited
      ? await unfavoriteCommunityPost(post.value.id, { token: auth.token })
      : await favoriteCommunityPost(post.value.id, { token: auth.token })
  } finally {
    acting.value = false
  }
}

async function createSameStyle() {
  if (!post.value) return
  if (!auth.token) return router.push({ name: "Login", query: { redirect: route.fullPath } })
  if (!post.value.toolCode) return
  sameStyleLoading.value = true
  try {
    post.value = await markCommunityPostSameStyle(post.value.id, { token: auth.token })
    void trackCommunityEvent(
      { postId: post.value.id, eventType: "dashboard_open", source: "community_detail", toolCode: post.value.toolCode },
      { token: auth.token },
    ).catch(() => undefined)
    openDashboardWithAsset(
      assetFromCommunityPost(post.value, mediaUrl(post.value.coverUrl)),
      post.value.toolCode,
      {
        modality: post.value.modality,
        sourcePost: post.value.id,
      },
    )
  } finally {
    sameStyleLoading.value = false
  }
}

async function addToInspiration() {
  if (!post.value || !auth.token) return router.push({ name: "Login", query: { redirect: route.fullPath } })
  collecting.value = true
  try {
    const collections = await fetchCommunityCollections({ token: auth.token })
    const target = collections.find((item) => item.defaultCollection) || collections[0]
    if (target) {
      await addCommunityCollectionItem(target.id, post.value.id, { token: auth.token })
      post.value = { ...post.value, favorited: true, favoriteCount: post.value.favoriteCount + (post.value.favorited ? 0 : 1) }
    }
  } finally {
    collecting.value = false
  }
}

async function sharePost() {
  if (!post.value) return
  const url = window.location.href
  if (navigator.share) await navigator.share({ title: post.value.title, url })
  else await navigator.clipboard?.writeText(url)
  void trackCommunityEvent(
    { postId: post.value.id, eventType: "share", source: "community_detail", toolCode: post.value.toolCode },
    { token: auth.token },
  ).catch(() => undefined)
}

async function copyPrompt() {
  if (post.value?.prompt) await navigator.clipboard?.writeText(post.value.prompt)
}

onMounted(() => void load())
</script>

<template>
  <main class="community-post-page">
    <button class="back-button" type="button" @click="router.back()">
      <ArrowLeft class="h-4 w-4" />
      返回
    </button>

    <div v-if="loading" class="state-panel">
      <Loader2 class="h-5 w-5 animate-spin" />
      加载作品中
    </div>
    <div v-else-if="error" class="state-panel error">{{ error }}</div>

    <section v-else-if="post" class="post-layout">
      <div class="media-stage">
        <img
          v-if="kind === 'image' && mediaUrl(post.coverUrl)"
          :src="mediaUrl(post.coverUrl)"
          :alt="post.title"
        />
        <video
          v-else-if="kind === 'video' && mediaUrl(post.coverUrl)"
          :src="mediaUrl(post.coverUrl)"
          controls
          playsinline
          preload="metadata"
        />
        <audio v-else-if="kind === 'audio' && mediaUrl(post.coverUrl)" :src="mediaUrl(post.coverUrl)" controls />
        <article v-else class="text-result">{{ post.prompt || post.description || post.title }}</article>
      </div>

      <aside class="post-panel">
        <p class="eyebrow">{{ post.modality }} creation</p>
        <h1>{{ post.title }}</h1>
        <p v-if="post.description" class="description">{{ post.description }}</p>

        <div class="action-row">
          <button type="button" :disabled="acting" :class="{ active: post.liked }" @click="toggleLike">
            <Heart class="h-4 w-4" />
            {{ post.likeCount }}
          </button>
          <button type="button" :disabled="acting" :class="{ active: post.favorited }" @click="toggleFavorite">
            <Star class="h-4 w-4" />
            {{ post.favoriteCount }}
          </button>
          <button type="button" :disabled="collecting" @click="addToInspiration">
            <Star class="h-4 w-4" />
            灵感
          </button>
          <button type="button" @click="sharePost">
            <Send class="h-4 w-4" />
            分享
          </button>
          <a v-if="mediaUrl(post.coverUrl)" :href="mediaUrl(post.coverUrl)" download>
            <Download class="h-4 w-4" />
          </a>
        </div>

        <div class="metadata">
          <div>
            <span>作者</span>
            <button class="author-link" type="button" @click="router.push(`/u/${post.userId}`)">
              <UserRound class="h-4 w-4" />
              查看公开主页
            </button>
          </div>
          <div>
            <span>模型</span>
            <strong>{{ post.toolName || post.toolCode || "AI 创作" }}</strong>
          </div>
          <div v-if="post.topic || post.tags?.length">
            <span>标签</span>
            <div class="tag-row">
              <strong v-if="post.topic">{{ post.topic }}</strong>
              <strong v-for="tag in post.tags" :key="tag">#{{ tag }}</strong>
            </div>
          </div>
          <div>
            <span>浏览</span>
            <strong>{{ post.viewCount }} · 同款 {{ post.sameStyleCount || 0 }}</strong>
          </div>
          <div v-if="post.promptVisible && post.prompt">
            <span>提示词</span>
            <p>{{ post.prompt }}</p>
            <button class="prompt-copy" type="button" @click="copyPrompt">
              <Copy class="h-4 w-4" />
              复制 Prompt
            </button>
          </div>
        </div>

        <button class="create-button" type="button" :disabled="sameStyleLoading || !post.toolCode" @click="createSameStyle">
          <Loader2 v-if="sameStyleLoading" class="h-4 w-4 animate-spin" />
          <Send v-else class="h-4 w-4" />
          使用同款创作
        </button>
      </aside>
    </section>
  </main>
</template>

<style scoped>
.community-post-page {
  min-height: 100vh;
  background:
    radial-gradient(circle at 12% 14%, rgb(176 92 255 / 0.14), transparent 34%),
    #030303;
  color: #fff;
  padding: clamp(22px, 4vw, 52px);
}

.back-button,
.action-row button,
.action-row a {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.055);
  color: rgb(255 255 255 / 0.72);
  padding: 10px 15px;
  font-weight: 800;
}

.post-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 390px;
  gap: 34px;
  align-items: start;
  margin-top: 28px;
}

.media-stage {
  display: grid;
  min-height: 72vh;
  place-items: center;
}

.media-stage img,
.media-stage video {
  max-width: 100%;
  max-height: 78vh;
  border-radius: 24px;
  box-shadow: 0 30px 100px rgb(0 0 0 / 0.68);
}

.text-result {
  max-width: 760px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 28px;
  background: rgb(255 255 255 / 0.04);
  padding: 32px;
  color: rgb(255 255 255 / 0.76);
  line-height: 1.8;
}

.post-panel {
  position: sticky;
  top: 28px;
  border-left: 1px solid rgb(255 255 255 / 0.08);
  padding-left: 28px;
}

.eyebrow {
  margin: 0 0 12px;
  color: rgb(255 255 255 / 0.38);
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.post-panel h1 {
  margin: 0;
  font-size: 34px;
  line-height: 1.08;
}

.description {
  color: rgb(255 255 255 / 0.52);
  line-height: 1.75;
}

.action-row {
  display: flex;
  gap: 10px;
  margin: 22px 0;
}

.action-row .active {
  border-color: rgb(176 92 255 / 0.46);
  color: #fff;
  background: rgb(176 92 255 / 0.16);
}

.metadata {
  display: grid;
  gap: 18px;
  border-top: 1px solid rgb(255 255 255 / 0.08);
  padding-top: 22px;
}

.metadata span {
  display: block;
  margin-bottom: 6px;
  color: rgb(255 255 255 / 0.35);
  font-size: 12px;
}

.metadata strong,
.metadata p {
  margin: 0;
  color: rgb(255 255 255 / 0.78);
  line-height: 1.7;
}

.author-link,
.prompt-copy {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 8px;
  background: rgb(255 255 255 / 0.055);
  color: rgb(255 255 255 / 0.72);
  padding: 8px 11px;
  font-size: 13px;
  font-weight: 800;
}

.prompt-copy {
  margin-top: 10px;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-row strong {
  border-radius: 999px;
  background: rgb(255 255 255 / 0.07);
  padding: 5px 9px;
  font-size: 12px;
}

.create-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  min-height: 48px;
  margin-top: 28px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #c884ff, #b05cff 55%, #ff9f68);
  color: #fff;
  font-weight: 900;
}

.create-button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.state-panel {
  margin-top: 28px;
  display: flex;
  min-height: 50vh;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px dashed rgb(255 255 255 / 0.12);
  border-radius: 28px;
  color: rgb(255 255 255 / 0.5);
}

.state-panel.error {
  color: rgb(254 202 202);
}

@media (max-width: 1100px) {
  .post-layout {
    grid-template-columns: 1fr;
  }

  .post-panel {
    position: static;
    border-left: 0;
    border-top: 1px solid rgb(255 255 255 / 0.08);
    padding-left: 0;
    padding-top: 24px;
  }
}
</style>
