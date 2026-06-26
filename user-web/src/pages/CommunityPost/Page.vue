<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue"
import { useRoute, useRouter } from "vue-router"
import { ArrowLeft, ChevronLeft, ChevronRight, Copy, Download, Flag, Heart, Loader2, Lock, Pause, Play, Send, Star, Volume2, VolumeX } from "lucide-vue-next"
import CommunityAudioMedia from "@/components/community/CommunityAudioMedia.vue"
import CommunityCollectionPickerModal from "@/components/community/CommunityCollectionPickerModal.vue"
import CommunityReportModal from "@/components/CommunityReportModal.vue"
import UserAvatar from "@/components/UserAvatar.vue"
import {
  fetchCommunityPost,
  likeCommunityPost,
  markCommunityPostSameStyle,
  reportCommunityPost,
  trackCommunityEvent,
  unlikeCommunityPost,
} from "@/api/communityApi"
import { fetchTaskById } from "@/api/taskApi"
import { favoritePostToCollection, unfavoritePostFromAllCollections } from "@/utils/communitySync"
import type { CommunityPost } from "@/api/types"
import { useAuthStore } from "@/store/authStore"
import { getSessionBearerJwt } from "@/api/sessionBearer"
import { assetFromCommunityPost } from "@/utils/assetPreviewAdapter"
import { communityDisplayTitle } from "@/utils/communityDisplay"
import { resolveCommunityAuthorAvatar, resolveCommunityAuthorName, resolveCommunityPrompt } from "@/utils/communityPostNormalize"
import {
  extractImageUrlsFromTask,
  normalizeCommunityMediaUrl,
  resolveCommunityImageUrls,
  resolveCommunityPostKind,
} from "@/utils/communityPostMedia"
import { openCreateWithAssetRecommendation } from "@/utils/assetReplay"
import { resolveCommunityAudioMedia } from "@/utils/communityAudioMedia"

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

function resolveAuthToken() {
  return auth.token ?? getSessionBearerJwt()
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement("a")
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

async function forceDownload(url: string, filename: string) {
  if (!url) return
  try {
    const res = await fetch(url)
    if (!res.ok) throw new Error("Download failed")
    const blob = await res.blob()
    downloadBlob(blob, filename)
  } catch {
    window.open(url, "_blank")
  }
}

const post = ref<CommunityPost | null>(null)
const loading = ref(false)
const acting = ref(false)
const favoritePickerOpen = ref(false)
const favoritePickerSubmitting = ref(false)
const sameStyleLoading = ref(false)
const error = ref("")
const audioPlaying = ref(false)
const detailAudioRef = ref<HTMLAudioElement | null>(null)
const detailVideoRef = ref<HTMLVideoElement | null>(null)
const audioCurrentTime = ref(0)
const audioDuration = ref(0)
const videoPlaying = ref(false)
const mediaVolume = ref(0.8)
const mediaMuted = ref(false)
const activeImageIndex = ref(0)
const extraImageUrls = ref<string[]>([])
const reportModalOpen = ref(false)
const reportSubmitting = ref(false)
const reportHint = ref("")

const postId = computed(() => String(route.params.postId || ""))
const kind = computed(() => resolveCommunityPostKind(post.value?.modality))

const authorName = computed(() => (post.value ? resolveCommunityAuthorName(post.value) : ""))

const canReport = computed(() => {
  if (!post.value) return false
  if (!auth.user?.id) return true
  return post.value.userId !== auth.user.id
})

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
  return communityDisplayTitle({
    title: post.value.title,
    topic: post.value.topic,
    tags: post.value.tags,
    toolName: post.value.toolName,
    toolCode: post.value.toolCode,
    kind: kind.value,
    modality: post.value.modality,
    promptVisible: post.value.promptVisible,
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

const audioProgress = computed(() => {
  if (!audioDuration.value) return 0
  return Math.min(100, Math.max(0, (audioCurrentTime.value / audioDuration.value) * 100))
})

const effectiveMediaVolume = computed(() => (mediaMuted.value ? 0 : mediaVolume.value))

async function enrichPostImagesFromTask(current: CommunityPost) {
  extraImageUrls.value = []
  if (!current.taskId || resolveCommunityPostKind(current.modality) !== "image") return

  const existing = resolveCommunityImageUrls(current)
  if (existing.length > 1) return

  try {
    const task = await fetchTaskById(current.taskId, { token: resolveAuthToken() })
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
    const loaded = await fetchCommunityPost(postId.value, { token: resolveAuthToken() })
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
  const sessionToken = resolveAuthToken()
  if (!post.value || !sessionToken) return router.push({ name: "Login", query: { redirect: route.fullPath } })
  acting.value = true
  try {
    post.value = post.value.liked
      ? await unlikeCommunityPost(post.value.id, { token: sessionToken })
      : await likeCommunityPost(post.value.id, { token: sessionToken })
  } finally {
    acting.value = false
  }
}

async function toggleFavorite() {
  const sessionToken = resolveAuthToken()
  if (!post.value || !sessionToken) return router.push({ name: "Login", query: { redirect: route.fullPath } })

  if (post.value.favorited) {
    acting.value = true
    try {
      post.value = await unfavoritePostFromAllCollections(post.value.id, { token: sessionToken })
    } finally {
      acting.value = false
    }
    return
  }

  favoritePickerOpen.value = true
}

function closeFavoritePicker() {
  if (favoritePickerSubmitting.value) return
  favoritePickerOpen.value = false
}

async function confirmFavoritePicker(collectionId: number | null) {
  const sessionToken = resolveAuthToken()
  if (!post.value || !sessionToken) return

  favoritePickerSubmitting.value = true
  acting.value = true
  try {
    post.value = await favoritePostToCollection(post.value.id, collectionId, { token: sessionToken })
    favoritePickerOpen.value = false
  } finally {
    favoritePickerSubmitting.value = false
    acting.value = false
  }
}

function openReportModal() {
  if (!post.value) return
  if (!auth.token) return router.push({ name: "Login", query: { redirect: route.fullPath } })
  reportHint.value = ""
  reportModalOpen.value = true
}

async function submitReport(payload: { reason?: string }) {
  if (!post.value || !auth.token || reportSubmitting.value) return
  reportSubmitting.value = true
  reportHint.value = ""
  try {
    await reportCommunityPost(post.value.id, payload, { token: auth.token })
    reportModalOpen.value = false
    reportHint.value = "举报已提交，感谢你的反馈"
  } catch (err) {
    reportHint.value = err instanceof Error ? err.message : "举报提交失败"
  } finally {
    reportSubmitting.value = false
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
    openCreateWithAssetRecommendation(assetFromCommunityPost(post.value, kind.value === "image" ? activeImageUrl.value : normalizeCommunityMediaUrl(post.value.coverUrl)), post.value.toolCode, {
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
  applyMediaPreferences(audio)
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

function onDetailAudioTimeUpdate() {
  const audio = detailAudioRef.value
  if (!audio) return
  audioCurrentTime.value = audio.currentTime
  audioDuration.value = Number.isFinite(audio.duration) ? audio.duration : 0
}

function onDetailAudioLoadedMetadata() {
  const audio = detailAudioRef.value
  if (!audio) return
  applyMediaPreferences(audio)
  audioDuration.value = Number.isFinite(audio.duration) ? audio.duration : 0
  audioCurrentTime.value = audio.currentTime || 0
}

function seekDetailAudio(event: MouseEvent) {
  const audio = detailAudioRef.value
  if (!audio || !audioDuration.value) return
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width))
  audio.currentTime = ratio * audioDuration.value
  audioCurrentTime.value = audio.currentTime
}

function formatMediaTime(value: number) {
  if (!Number.isFinite(value) || value <= 0) return "0:00"
  const minutes = Math.floor(value / 60)
  const seconds = Math.floor(value % 60)
  return `${minutes}:${String(seconds).padStart(2, "0")}`
}

function applyMediaPreferences(target?: HTMLMediaElement | null) {
  const mediaElements = [detailAudioRef.value, detailVideoRef.value]
  for (const media of mediaElements) {
    if (!media) continue
    if (target && media !== target) continue
    media.volume = mediaVolume.value
    media.muted = mediaMuted.value
  }
}

function persistMediaPreferences() {
  window.localStorage.setItem(
    "ai_tool_market_media_preferences",
    JSON.stringify({ volume: mediaVolume.value, muted: mediaMuted.value }),
  )
}

function restoreMediaPreferences() {
  try {
    const saved = window.localStorage.getItem("ai_tool_market_media_preferences")
    if (!saved) return
    const parsed = JSON.parse(saved) as { volume?: number; muted?: boolean }
    if (typeof parsed.volume === "number") mediaVolume.value = Math.min(1, Math.max(0, parsed.volume))
    if (typeof parsed.muted === "boolean") mediaMuted.value = parsed.muted
  } catch {
    // 保留默认音量
  }
}

function toggleMediaMute() {
  mediaMuted.value = !mediaMuted.value
  applyMediaPreferences()
  persistMediaPreferences()
}

function updateMediaVolume(event: Event) {
  const input = event.target as HTMLInputElement
  mediaVolume.value = Math.min(1, Math.max(0, Number(input.value)))
  mediaMuted.value = mediaVolume.value === 0
  applyMediaPreferences()
  persistMediaPreferences()
}

function toggleDetailVideo() {
  const video = detailVideoRef.value
  if (!video) return
  applyMediaPreferences(video)
  if (video.paused) void video.play()
  else video.pause()
}

function onDetailVideoPlay() {
  applyMediaPreferences(detailVideoRef.value)
  videoPlaying.value = true
}

function onDetailVideoPause() {
  videoPlaying.value = false
}

watch(postId, () => {
  audioPlaying.value = false
  videoPlaying.value = false
  audioCurrentTime.value = 0
  audioDuration.value = 0
  activeImageIndex.value = 0
  if (detailAudioRef.value) {
    detailAudioRef.value.pause()
    detailAudioRef.value.removeAttribute("src")
  }
})

watch(
  [() => auth.bootstrapComplete, () => auth.token, postId],
  ([ready]) => {
    if (ready) void load()
  },
  { immediate: true },
)

watch([mediaVolume, mediaMuted], () => applyMediaPreferences())

onMounted(() => {
  restoreMediaPreferences()
})

onUnmounted(() => {
  detailAudioRef.value?.pause()
})
</script>

<template>
  <main class="community-post-page">
    <div class="page-top-bar">
      <button class="back-button" type="button" @click="router.back()">
        <ArrowLeft class="h-4 w-4" />
        返回
      </button>
      <button
        v-if="post && canReport"
        class="report-button"
        type="button"
        @click="openReportModal"
      >
        <Flag class="h-3.5 w-3.5" />
        举报
      </button>
    </div>

    <p v-if="reportHint" class="report-hint">{{ reportHint }}</p>

    <CommunityReportModal
      :open="reportModalOpen"
      :submitting="reportSubmitting"
      @close="reportModalOpen = false"
      @confirm="submitReport"
    />

    <CommunityCollectionPickerModal
      :open="favoritePickerOpen"
      :post-title="displayTitle"
      :submitting="favoritePickerSubmitting"
      @close="closeFavoritePicker"
      @confirm="confirmFavoritePicker"
    />

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
        <div v-else-if="kind === 'video' && normalizeCommunityMediaUrl(post.coverUrl)" class="video-frame">
          <video
            ref="detailVideoRef"
            :src="normalizeCommunityMediaUrl(post.coverUrl)"
            playsinline
            preload="metadata"
            @click="toggleDetailVideo"
            @play="onDetailVideoPlay"
            @pause="onDetailVideoPause"
            @ended="onDetailVideoPause"
          />
          <button
            type="button"
            class="video-play-button"
            :class="{ hidden: videoPlaying }"
            aria-label="播放视频"
            @click="toggleDetailVideo"
          >
            <Play class="h-8 w-8" />
          </button>
          <div class="video-control-shell">
            <div class="volume-cluster">
              <button type="button" class="media-icon-button" :aria-label="mediaMuted ? '取消静音' : '静音'" @click="toggleMediaMute">
                <VolumeX v-if="mediaMuted || effectiveMediaVolume === 0" class="h-4 w-4" />
                <Volume2 v-else class="h-4 w-4" />
              </button>
              <input
                class="volume-slider"
                type="range"
                min="0"
                max="1"
                step="0.01"
                :value="mediaVolume"
                aria-label="视频音量"
                @input="updateMediaVolume"
              />
            </div>
          </div>
        </div>
        <div v-else-if="kind === 'audio'" class="audio-stage">
          <CommunityAudioMedia
            :cover-url="audioMedia.coverUrl"
            :audio-url="audioMedia.audioUrl"
            :title="displayTitle"
            variant="detail"
          />
          <div v-if="audioMedia.audioUrl" class="audio-controls">
            <audio
              ref="detailAudioRef"
              :src="audioMedia.audioUrl"
              preload="metadata"
              class="audio-player"
              @play="onDetailAudioPlay"
              @pause="onDetailAudioPause"
              @ended="onDetailAudioPause"
              @timeupdate="onDetailAudioTimeUpdate"
              @loadedmetadata="onDetailAudioLoadedMetadata"
            />
            <div class="media-control-bar">
              <button type="button" class="media-icon-button" :aria-label="audioPlaying ? '暂停' : '播放'" @click="toggleDetailAudio">
                <Pause v-if="audioPlaying" class="h-4 w-4" />
                <Play v-else class="h-4 w-4" />
              </button>
              <span>{{ formatMediaTime(audioCurrentTime) }}</span>
              <button type="button" class="audio-track" aria-label="音频进度条" @click="seekDetailAudio">
                <span class="audio-track-fill" :style="{ width: `${audioProgress}%` }" />
              </button>
              <span>{{ formatMediaTime(audioDuration) }}</span>
              <div class="volume-cluster">
                <button type="button" class="media-icon-button" :aria-label="mediaMuted ? '取消静音' : '静音'" @click="toggleMediaMute">
                  <VolumeX v-if="mediaMuted || effectiveMediaVolume === 0" class="h-4 w-4" />
                  <Volume2 v-else class="h-4 w-4" />
                </button>
                <input
                  class="volume-slider"
                  type="range"
                  min="0"
                  max="1"
                  step="0.01"
                  :value="mediaVolume"
                  aria-label="音频音量"
                  @input="updateMediaVolume"
                />
              </div>
            </div>
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
          <button
            v-if="downloadUrl"
            type="button"
            aria-label="下载作品"
            @click="forceDownload(downloadUrl, `community-post-${post.id}.png`)"
          >
            <Download class="h-4 w-4" />
          </button>
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
          <div v-else class="prompt-locked-card">
            <Lock class="h-4 w-4" />
            <div>
              <span>Prompt 已保护</span>
              <strong>作者未公开 Prompt，同款创作只会带入工具与可用媒体。</strong>
            </div>
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

.page-top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.report-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.03);
  color: rgb(255 255 255 / 0.42);
  padding: 8px 12px;
  font-size: 12px;
  font-weight: 700;
  transition: border-color 0.18s ease, background-color 0.18s ease, color 0.18s ease;
}

.report-button:hover {
  border-color: rgb(255 120 120 / 0.28);
  background: rgb(255 80 80 / 0.08);
  color: rgb(255 210 210 / 0.92);
}

.report-hint {
  margin-top: 12px;
  font-size: 13px;
  color: rgb(255 255 255 / 0.55);
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
  transition: border-color 0.18s ease, background-color 0.18s ease, box-shadow 0.18s ease, color 0.18s ease;
}

.action-row button svg,
.action-row a svg {
  color: rgb(255 255 255 / 0.3);
  transition: color 0.18s ease, fill 0.18s ease, stroke 0.18s ease;
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
.video-frame video {
  max-width: 100%;
  max-height: min(78vh, 720px);
  border-radius: 24px;
  box-shadow: 0 30px 100px rgb(0 0 0 / 0.68);
}

.video-frame {
  position: relative;
  display: grid;
  max-width: 100%;
  place-items: center;
}

.video-frame video {
  cursor: pointer;
}

.video-play-button {
  position: absolute;
  inset: 50% auto auto 50%;
  display: inline-flex;
  width: 82px;
  height: 82px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 0.18);
  border-radius: 999px;
  background: rgb(18 18 22 / 0.48);
  color: #fff;
  box-shadow: 0 22px 60px rgb(0 0 0 / 0.45);
  transform: translate(-50%, -50%);
  backdrop-filter: blur(18px);
  transition: opacity 0.2s ease, transform 0.2s ease, background-color 0.2s ease;
}

.video-play-button:hover {
  background: rgb(255 255 255 / 0.14);
  transform: translate(-50%, -50%) scale(1.04);
}

.video-play-button.hidden {
  opacity: 0;
  pointer-events: none;
  transform: translate(-50%, -50%) scale(0.94);
}

.video-control-shell {
  position: absolute;
  right: 18px;
  bottom: 18px;
  display: flex;
  justify-content: flex-end;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 999px;
  background: rgb(18 18 22 / 0.54);
  padding: 6px;
  box-shadow: 0 16px 44px rgb(0 0 0 / 0.36);
  backdrop-filter: blur(16px);
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
  background: rgb(255 255 255 / 0.035);
  padding: 16px 18px;
}

.audio-player {
  display: none;
}

.media-control-bar {
  display: grid;
  grid-template-columns: auto auto minmax(120px, 1fr) auto auto;
  align-items: center;
  gap: 10px;
  width: 100%;
  color: rgb(255 255 255 / 0.42);
  font-size: 12px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.media-icon-button {
  display: inline-flex;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.055);
  color: rgb(255 255 255 / 0.72);
  transition: border-color 0.18s ease, background-color 0.18s ease, color 0.18s ease, transform 0.18s ease;
}

.media-icon-button:hover {
  border-color: rgb(168 85 247 / 0.32);
  background: rgb(168 85 247 / 0.16);
  color: #fff;
  transform: translateY(-1px);
}

.audio-track {
  position: relative;
  height: 4px;
  overflow: hidden;
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.1);
  padding: 0;
  cursor: pointer;
  transition: height 0.16s ease, background-color 0.16s ease;
}

.audio-track:hover {
  height: 6px;
  background: rgb(255 255 255 / 0.14);
}

.audio-track-fill {
  position: absolute;
  inset: 0 auto 0 0;
  border-radius: inherit;
  background: linear-gradient(90deg, #a855f7, #ff3f79);
  box-shadow: 0 0 18px rgb(168 85 247 / 0.36);
}

.volume-cluster {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.volume-slider {
  width: 0;
  height: 4px;
  accent-color: #a855f7;
  opacity: 0;
  cursor: pointer;
  transition: width 0.22s ease, opacity 0.18s ease;
}

.volume-cluster:hover .volume-slider,
.volume-cluster:focus-within .volume-slider {
  width: 86px;
  opacity: 1;
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
  box-shadow: 0 0 0 1px rgb(251 113 133 / 0.08), 0 0 22px rgb(251 113 133 / 0.14);
}

.action-favorites.active {
  border-color: rgb(245 158 11 / 0.48);
  color: #f59e0b;
  background: rgb(245 158 11 / 0.12);
  box-shadow: 0 0 0 1px rgb(245 158 11 / 0.08), 0 0 22px rgb(245 158 11 / 0.14);
}

.action-likes.active svg,
.action-favorites.active svg {
  color: currentColor;
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

.prompt-locked-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 12px;
  border: 1px solid rgb(255 255 255 / 0.07);
  border-radius: 16px;
  background: rgb(255 255 255 / 0.03);
  padding: 14px;
}

.prompt-locked-card > svg {
  margin-top: 2px;
  color: rgb(255 255 255 / 0.36);
}

.prompt-locked-card span {
  margin-bottom: 5px;
}

.prompt-locked-card strong {
  color: rgb(255 255 255 / 0.42);
  font-weight: 600;
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
