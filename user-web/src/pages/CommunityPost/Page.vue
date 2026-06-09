<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue"
import { useRoute, useRouter } from "vue-router"
import { ArrowLeft, ChevronLeft, ChevronRight, Copy, Download, Heart, Loader2, Send, Star } from "lucide-vue-next"
import CommunityAudioMedia from "@/components/community/CommunityAudioMedia.vue"
import UserAvatar from "@/components/UserAvatar.vue"
import {
  favoriteCommunityPost,
  fetchCommunityPost,
  likeCommunityPost,
  markCommunityPostSameStyle,
  trackCommunityEvent,
  unfavoriteCommunityPost,
  unlikeCommunityPost,
} from "@/api/communityApi"
import { fetchTaskById } from "@/api/taskApi"
import { syncFavoriteToInspirationCollection } from "@/utils/communitySync"
import type { CommunityPost } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { assetFromCommunityPost } from "@/utils/assetPreviewAdapter"
import { communityDisplayTitle } from "@/utils/communityDisplay"
import { resolveCommunityAuthorAvatar, resolveCommunityAuthorName, resolveCommunityPrompt } from "@/utils/communityPostNormalize"
import {
  extractImageUrlsFromTask,
  normalizeCommunityMediaUrl,
  resolveCommunityImageUrls,
  resolveCommunityPostKind,
} from "@/utils/communityPostMedia"
import { openDashboardWithAsset } from "@/utils/assetReplay"
import { resolveCommunityAudioMedia } from "@/utils/communityAudioMedia"

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const post = ref<CommunityPost | null>(null)
const loading = ref(false)
const acting = ref(false)
const sameStyleLoading = ref(false)
const error = ref("")
const audioPlaying = ref(false)
const detailAudioRef = ref<HTMLAudioElement | null>(null)
const activeImageIndex = ref(0)
const extraImageUrls = ref<string[]>([])

const postId = computed(() => String(route.params.postId || ""))
const kind = computed(() => resolveCommunityPostKind(post.value?.modality))

const authorName = computed(() => (post.value ? resolveCommunityAuthorName(post.value) : ""))

const audioMedia = computed(() => {
  if (!post.value) return { coverUrl: "", audioUrl: "" }
  const resolved = resolveCommunityAudioMedia(post.value)
  return {
    coverUrl: normalizeCommunityMediaUrl(resolved.coverUrl),
    audioUrl: normalizeCommunityMediaUrl(resolved.audioUrl),
  }
})

const displayTitle = computed(() => {
  if (!post.value) return ""
  const prompt = resolveCommunityPrompt(post.value)
  return communityDisplayTitle({
    title: post.value.title,
    prompt,
    promptPreview: post.value.promptPreview || prompt,
    topic: post.value.topic,
    tags: post.value.tags,
    toolName: post.value.toolName,
    toolCode: post.value.toolCode,
    kind: kind.value,
  })
})

const imageUrls = computed(() => {
  if (!post.value) return []
  return resolveCommunityImageUrls(post.value, extraImageUrls.value)
})

const activeImageUrl = computed(() => {
  if (!imageUrls.value.length) return ""
  return imageUrls.value[Math.min(Math.max(activeImageIndex.value, 0), imageUrls.value.length - 1)] || imageUrls.value[0] || ""
})

const downloadUrl = computed(() => {
  if (kind.value === "audio") return audioMedia.value.audioUrl
  if (kind.value === "image") return activeImageUrl.value
  return post.value ? normalizeCommunityMediaUrl(post.value.coverUrl) : ""
})

async function enrichPostImagesFromTask(current: CommunityPost) {
  extraImageUrls.value = []
  if (!current.taskId || resolveCommunityPostKind(current.modality) !== "image") return

  const existing = resolveCommunityImageUrls(current)
  if (existing.length > 1) return

  try {
    const task = await fetchTaskById(current.taskId, { token: auth.token })
    const fromTask = extractImageUrlsFromTask(task)
    if (fromTask.length <= existing.length) return
    extraImageUrls.value = fromTask
    post.value = { ...current, mediaUrls: fromTask }
  } catch {
    // 任务补全失败时保留帖子接口返回的媒体信息
  }
}

async function load() {
  loading.value = true
  error.value = ""
  extraImageUrls.value = []
  try {
    const loaded = await fetchCommunityPost(postId.value, { token: auth.token })
    post.value = loaded
    activeImageIndex.value = 0
    await enrichPostImagesFromTask(loaded)
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
  const wasFavorited = post.value.favorited
  const postId = post.value.id
  try {
    post.value = wasFavorited
      ? await unfavoriteCommunityPost(postId, { token: auth.token })
      : await favoriteCommunityPost(postId, { token: auth.token })
    try {
      await syncFavoriteToInspirationCollection(postId, !wasFavorited, { token: auth.token })
    } catch {
      // 作品收藏状态已更新；同步灵感收藏夹失败时不阻断主流程
    }
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
    openDashboardWithAsset(assetFromCommunityPost(post.value, kind.value === "image" ? activeImageUrl.value : normalizeCommunityMediaUrl(post.value.coverUrl)), post.value.toolCode, {
      modality: post.value.modality,
      sourcePost: post.value.id,
    })
  } finally {
    sameStyleLoading.value = false
  }
}

function selectImage(index: number) {
  if (index < 0 || index >= imageUrls.value.length) return
  activeImageIndex.value = index
}

function stepImage(delta: number) {
  const count = imageUrls.value.length
  if (count <= 1) return
  activeImageIndex.value = (activeImageIndex.value + delta + count) % count
}

async function sharePost() {
  if (!post.value) return
  const url = window.location.href
  if (navigator.share) await navigator.share({ title: displayTitle.value, url })
  else await navigator.clipboard?.writeText(url)
  void trackCommunityEvent(
    { postId: post.value.id, eventType: "share", source: "community_detail", toolCode: post.value.toolCode },
    { token: auth.token },
  ).catch(() => undefined)
}

async function copyPrompt() {
  if (post.value?.prompt) await navigator.clipboard?.writeText(post.value.prompt)
}

function toggleDetailAudio() {
  const audio = detailAudioRef.value
  const source = audioMedia.value.audioUrl
  if (!audio || !source) return
  if (!audio.src) audio.src = source
  if (audio.paused) {
    void audio.play().catch(() => {
      audioPlaying.value = false
    })
  } else {
    audio.pause()
  }
}

function onDetailAudioPlay() {
  audioPlaying.value = true
}

function onDetailAudioPause() {
  audioPlaying.value = false
}

watch(() => auth.token, () => void load())

watch(postId, () => {
  audioPlaying.value = false
  activeImageIndex.value = 0
  if (detailAudioRef.value) {
    detailAudioRef.value.pause()
    detailAudioRef.value.removeAttribute("src")
  }
  void load()
})

onMounted(() => void load())

onUnmounted(() => {
  detailAudioRef.value?.pause()
})
</script>

<template>
  <main class="community-post-page">
    <button class="back-button" type="button" @click="router.back()">
      <ArrowLeft class="h-4 w-4" />
      返回
    </button>

    <div v-if="loading" class="state-panel">
      <Loader2 class="h-5 w-5 animate-spin" />
      正在加载作品
    </div>
    <div v-else-if="error" class="state-panel error">{{ error }}</div>

    <section v-else-if="post" class="post-layout">
      <div class="media-stage">
        <div v-if="kind === 'image' && activeImageUrl" class="image-viewer">
          <div class="image-frame">
            <img :src="activeImageUrl" :alt="displayTitle" />
            <button
              v-if="imageUrls.length > 1"
              type="button"
              class="image-nav previous"
              aria-label="上一张"
              @click="stepImage(-1)"
            >
              <ChevronLeft class="h-5 w-5" />
            </button>
            <button
              v-if="imageUrls.length > 1"
              type="button"
              class="image-nav next"
              aria-label="下一张"
              @click="stepImage(1)"
            >
              <ChevronRight class="h-5 w-5" />
            </button>
            <span v-if="imageUrls.length > 1" class="image-counter">
              {{ activeImageIndex + 1 }} / {{ imageUrls.length }}
            </span>
          </div>

          <div v-if="imageUrls.length > 1" class="image-strip" aria-label="同组图片">
            <button
              v-for="(url, index) in imageUrls"
              :key="url"
              type="button"
              class="image-thumb"
              :class="{ active: activeImageIndex === index }"
              :aria-label="`切换到第 ${index + 1} 张`"
              @click="selectImage(index)"
            >
              <img :src="url" :alt="`${displayTitle} ${index + 1}`" loading="lazy" decoding="async" />
            </button>
          </div>
        </div>
        <video
          v-else-if="kind === 'video' && normalizeCommunityMediaUrl(post.coverUrl)"
          :src="normalizeCommunityMediaUrl(post.coverUrl)"
          controls
          playsinline
          preload="metadata"
        />
        <div v-else-if="kind === 'audio'" class="audio-stage">
          <CommunityAudioMedia
            :cover-url="audioMedia.coverUrl"
            :audio-url="audioMedia.audioUrl"
            :title="displayTitle"
            :playing="audioPlaying"
            variant="detail"
            @toggle-play="toggleDetailAudio"
          />
          <div v-if="audioMedia.audioUrl" class="audio-controls">
            <audio
              ref="detailAudioRef"
              :src="audioMedia.audioUrl"
              controls
              preload="metadata"
              class="audio-player"
              @play="onDetailAudioPlay"
              @pause="onDetailAudioPause"
              @ended="onDetailAudioPause"
            />
          </div>
          <p v-else class="audio-empty">暂无可播放的音频资源</p>
        </div>
        <article v-else class="text-result">{{ post.prompt || post.description || displayTitle }}</article>
      </div>

      <aside class="post-panel">
        <p class="eyebrow">{{ post.modality }} creation</p>
        <h1>{{ displayTitle }}</h1>
        <p v-if="imageUrls.length > 1" class="image-count-hint">
          第 {{ activeImageIndex + 1 }} / {{ imageUrls.length }} 张
        </p>
        <p v-if="post.description" class="description">{{ post.description }}</p>

        <div class="action-row">
          <button
            type="button"
            class="action-likes"
            :disabled="acting"
            :class="{ active: post.liked }"
            @click="toggleLike"
          >
            <Heart class="h-4 w-4" :class="{ 'icon-filled': post.liked }" />
            {{ post.likeCount }}
          </button>
          <button
            type="button"
            class="action-favorites"
            :disabled="acting"
            :class="{ active: post.favorited }"
            @click="toggleFavorite"
          >
            <Star class="h-4 w-4" :class="{ 'icon-filled': post.favorited }" />
            {{ post.favoriteCount }}
          </button>
          <button type="button" @click="sharePost">
            <Send class="h-4 w-4" />
            分享
          </button>
          <a
            v-if="downloadUrl"
            :href="downloadUrl"
            download
            aria-label="下载作品"
          >
            <Download class="h-4 w-4" />
          </a>
        </div>

        <div class="metadata">
          <div>
            <span>作者</span>
            <button class="author-link" type="button" @click="router.push(`/u/${post.userId}`)">
              <UserAvatar :src="resolveCommunityAuthorAvatar(post)" :name="authorName" size="sm" />
              {{ authorName }}
            </button>
          </div>
          <div>
            <span>工具</span>
            <strong>{{ post.toolName || post.toolCode || "AI 创作" }}</strong>
          </div>
          <div v-if="post.topic || post.tags?.length">
            <span>专题与标签</span>
            <div class="tag-row">
              <strong v-if="post.topic">{{ post.topic }}</strong>
              <strong v-for="tag in post.tags" :key="tag">#{{ tag }}</strong>
            </div>
          </div>
          <div>
            <span>数据</span>
            <strong>{{ post.viewCount }} 浏览 / 同款 {{ post.sameStyleCount || 0 }}</strong>
          </div>
          <div v-if="post.promptVisible && post.prompt">
            <span>公开 Prompt</span>
            <p>{{ post.prompt }}</p>
            <button class="prompt-copy" type="button" @click="copyPrompt">
              <Copy class="h-4 w-4" />
              复制 Prompt
            </button>
          </div>
          <div v-else>
            <span>Prompt</span>
            <strong>作者未公开 Prompt，同款创作只会带入工具与可用媒体。</strong>
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
  display: flex;
  width: 100%;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
}

.image-viewer {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 18px;
  align-items: center;
}

.image-frame {
  position: relative;
  display: grid;
  max-width: 100%;
  place-items: center;
}

.image-frame img,
.media-stage video {
  max-width: 100%;
  max-height: min(78vh, 720px);
  border-radius: 24px;
  box-shadow: 0 30px 100px rgb(0 0 0 / 0.68);
}

.image-nav {
  position: absolute;
  top: 50%;
  display: inline-flex;
  width: 42px;
  height: 42px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 0.16);
  border-radius: 999px;
  background: rgb(0 0 0 / 0.48);
  color: rgb(255 255 255 / 0.9);
  transform: translateY(-50%);
  backdrop-filter: blur(12px);
}

.image-nav.previous {
  left: 18px;
}

.image-nav.next {
  right: 18px;
}

.image-counter {
  position: absolute;
  right: 18px;
  bottom: 18px;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.58);
  color: rgb(255 255 255 / 0.9);
  padding: 5px 10px;
  font-size: 12px;
  font-weight: 800;
  backdrop-filter: blur(10px);
}

.image-strip {
  display: flex;
  width: 100%;
  max-width: 760px;
  gap: 10px;
  overflow-x: auto;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 18px;
  background: rgb(255 255 255 / 0.035);
  padding: 10px;
  scrollbar-width: thin;
}

.image-thumb {
  flex: 0 0 96px;
  height: 68px;
  overflow: hidden;
  border: 2px solid transparent;
  border-radius: 12px;
  background: rgb(255 255 255 / 0.06);
  padding: 0;
  opacity: 0.58;
  transition: border-color 0.18s ease, opacity 0.18s ease, transform 0.18s ease;
}

.image-thumb:hover,
.image-thumb.active {
  border-color: #c884ff;
  opacity: 1;
}

.image-thumb.active {
  box-shadow: 0 0 0 3px rgb(200 132 255 / 0.16);
}

.image-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.audio-stage {
  display: grid;
  gap: 20px;
  width: min(100%, 520px);
  justify-items: center;
}

.audio-controls {
  width: min(100%, 520px);
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 18px;
  background: rgb(255 255 255 / 0.04);
  padding: 14px 16px;
}

.audio-player {
  width: 100%;
}

.audio-empty {
  margin: 0;
  color: rgb(255 255 255 / 0.45);
  font-size: 14px;
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

.image-count-hint {
  margin: 10px 0 0;
  color: rgb(255 255 255 / 0.58);
  font-size: 13px;
  font-weight: 700;
}

.description {
  color: rgb(255 255 255 / 0.52);
  line-height: 1.75;
}

.action-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 22px 0;
}

.action-likes.active {
  border-color: rgb(251 113 133 / 0.46);
  color: #fb7185;
  background: rgb(251 113 133 / 0.12);
}

.action-favorites.active {
  border-color: rgb(251 191 36 / 0.46);
  color: #fbbf24;
  background: rgb(251 191 36 / 0.12);
}

.icon-filled {
  fill: currentColor;
  stroke: currentColor;
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
  border-radius: 999px;
  background: rgb(255 255 255 / 0.055);
  color: rgb(255 255 255 / 0.82);
  padding: 6px 12px 6px 6px;
  font-size: 13px;
  font-weight: 600;
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
