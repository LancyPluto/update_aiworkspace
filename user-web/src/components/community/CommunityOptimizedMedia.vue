<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue"
import { ImageOff, Play } from "lucide-vue-next"
import {
  normalizeCommunityMediaUrl,
  resolveCommunityDerivativeUrl,
  resolveOssVideoPosterUrl,
  type CommunityMediaKind,
} from "@/utils/communityPostMedia"

const props = defineProps<{
  kind: CommunityMediaKind
  sourceUrl?: string | null
  alt?: string
  fallbackText?: string
}>()

const emit = defineEmits<{
  loaded: []
}>()

const imageLoaded = ref(false)
const imageFailed = ref(false)
const lqipFailed = ref(false)
const posterLoaded = ref(false)
const posterFailed = ref(false)
const videoReady = ref(false)
const videoPreviewFailed = ref(false)
const hoverActive = ref(false)
const videoRef = ref<HTMLVideoElement | null>(null)
let hoverTimer: number | null = null

const rawOptimizationFlag = String(import.meta.env.VITE_COMMUNITY_MEDIA_OPTIMIZATION ?? "").toLowerCase()
const useDerivativeMedia =
  ["1", "true", "yes", "on"].includes(rawOptimizationFlag) ||
  (import.meta.env.PROD && !["0", "false", "no", "off"].includes(rawOptimizationFlag))

const normalizedSourceUrl = computed(() => normalizeCommunityMediaUrl(props.sourceUrl))
const imageThumbUrl = computed(() =>
  useDerivativeMedia ? resolveCommunityDerivativeUrl(props.sourceUrl, "image-thumb") : normalizedSourceUrl.value,
)
const imageLqipUrl = computed(() =>
  useDerivativeMedia ? resolveCommunityDerivativeUrl(props.sourceUrl, "image-lqip") : "",
)
const videoPosterUrl = computed(() => resolveOssVideoPosterUrl(props.sourceUrl))
const videoPreviewUrl = computed(() => {
  if (videoPreviewFailed.value) return normalizedSourceUrl.value
  return useDerivativeMedia ? resolveCommunityDerivativeUrl(props.sourceUrl, "video-preview") : normalizedSourceUrl.value
})
const isImage = computed(() => props.kind === "image")
const isVideo = computed(() => props.kind === "video")
const hasImageLqip = computed(() => Boolean(imageLqipUrl.value) && !lqipFailed.value)
const hasVideoPoster = computed(() => Boolean(videoPosterUrl.value) && !posterFailed.value)
const shouldRenderVideo = computed(() => isVideo.value && hoverActive.value && Boolean(videoPreviewUrl.value))
const showFallback = computed(() => {
  if (isImage.value) return !imageThumbUrl.value || imageFailed.value
  if (isVideo.value) return !videoPreviewUrl.value && !hasVideoPoster.value
  return true
})
const fallbackLabel = computed(() => props.fallbackText || props.alt || "作品预览")

function clearHoverTimer() {
  if (hoverTimer == null) return
  window.clearTimeout(hoverTimer)
  hoverTimer = null
}

function onPointerEnter() {
  if (!isVideo.value || !videoPreviewUrl.value) return
  clearHoverTimer()
  hoverTimer = window.setTimeout(() => {
    hoverActive.value = true
    hoverTimer = null
  }, 300)
}

function onPointerLeave() {
  clearHoverTimer()
  hoverActive.value = false
  videoReady.value = false
  const video = videoRef.value
  if (video) {
    video.pause()
    video.removeAttribute("src")
    video.load()
  }
}

function onVideoCanPlay() {
  videoReady.value = true
  void videoRef.value?.play().catch(() => undefined)
}

function onVideoError() {
  if (!videoPreviewFailed.value) {
    videoPreviewFailed.value = true
    videoReady.value = false
  }
}

watch(
  () => props.sourceUrl,
  () => {
    imageLoaded.value = false
    imageFailed.value = false
    lqipFailed.value = false
    posterLoaded.value = false
    posterFailed.value = false
    videoReady.value = false
    videoPreviewFailed.value = false
    hoverActive.value = false
    clearHoverTimer()
  },
)

watch(shouldRenderVideo, async (active) => {
  if (!active) return
  await nextTick()
  void videoRef.value?.play().catch(() => undefined)
})

onBeforeUnmount(() => {
  clearHoverTimer()
})
</script>

<template>
  <div
    class="community-optimized-media"
    :class="{ 'is-video': isVideo, 'is-loaded': imageLoaded || posterLoaded, 'has-fallback': showFallback }"
    @pointerenter="onPointerEnter"
    @pointerleave="onPointerLeave"
  >
    <template v-if="isImage && !showFallback">
      <img
        v-if="hasImageLqip"
        :src="imageLqipUrl"
        :alt="alt || ''"
        class="media-base media-layer--lqip"
        loading="lazy"
        decoding="async"
        aria-hidden="true"
        @error="lqipFailed = true"
      />
      <img
        :src="imageThumbUrl"
        :alt="alt || '社区图片作品'"
        class="media-layer--main transition-opacity duration-500"
        :class="hasImageLqip ? 'media-layer' : 'media-base'"
        :style="{ opacity: imageLoaded ? 1 : 0 }"
        loading="lazy"
        decoding="async"
        @load="imageLoaded = true; emit('loaded')"
        @error="imageFailed = true"
      />
    </template>

    <template v-else-if="isVideo && !showFallback">
      <img
        v-if="hasVideoPoster"
        :src="videoPosterUrl"
        :alt="alt || '社区视频封面'"
        class="media-base media-layer--main transition-opacity duration-500"
        :style="{ opacity: posterLoaded ? 1 : 0 }"
        loading="lazy"
        decoding="async"
        @load="posterLoaded = true; emit('loaded')"
        @error="posterFailed = true"
      />
      <div v-else class="media-fallback media-fallback--video">
        <ImageOff class="h-6 w-6 text-white/38" />
        <p>{{ fallbackLabel }}</p>
      </div>
      <div class="video-play-indicator" aria-hidden="true">
        <Play class="h-4 w-4 fill-current" />
      </div>
      <video
        v-if="shouldRenderVideo"
        ref="videoRef"
        :src="videoPreviewUrl"
        class="media-layer media-layer--video transition-opacity duration-300"
        :class="videoReady ? 'opacity-100' : 'opacity-0'"
        muted
        loop
        playsinline
        autoplay
        preload="none"
        @canplay="onVideoCanPlay"
        @error="onVideoError"
      />
    </template>

    <div v-else class="media-fallback">
      <ImageOff class="h-6 w-6 text-white/38" />
      <p>{{ fallbackLabel }}</p>
    </div>
  </div>
</template>

<style scoped>
.community-optimized-media {
  position: relative;
  display: block;
  width: 100%;
  min-height: 0;
  overflow: hidden;
  background:
    radial-gradient(circle at 18% 12%, rgb(255 255 255 / 0.10), transparent 30%),
    linear-gradient(135deg, rgb(124 58 237 / 0.18), rgb(14 165 233 / 0.10)),
    rgb(255 255 255 / 0.035);
}

.media-base {
  display: block;
  width: 100%;
  height: auto;
  max-width: 100%;
  vertical-align: top;
  object-fit: cover;
}

.media-layer {
  position: absolute;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.media-layer--lqip {
  transform: scale(1.08);
  filter: blur(15px);
  opacity: 0.82;
}

.media-layer--main {
  z-index: 1;
}

.media-layer--video {
  z-index: 2;
  background: #000;
}

.video-play-indicator {
  position: absolute;
  z-index: 3;
  right: 10px;
  top: 10px;
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.52);
  color: rgb(255 255 255 / 0.9);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.16);
  backdrop-filter: blur(10px);
}

.media-fallback {
  display: flex;
  min-height: 160px;
  height: 100%;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 10px;
  padding: 24px 16px;
  color: rgb(255 255 255 / 0.70);
  text-align: center;
}

.media-fallback p {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  font-size: 13px;
  line-height: 1.6;
}
</style>
