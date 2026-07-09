<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue"
import { isVideoPreviewUrl, normalizeMediaUrl } from "@/utils/toolCoverMedia"

const props = withDefaults(
  defineProps<{
    beforeSrc: string
    afterSrc: string
    alt?: string
    imageClass?: string
    effectClass?: string
    lineClass?: string
    handleClass?: string
  }>(),
  {
    alt: "",
    imageClass: "marketplace-tool-image",
    effectClass: "marketplace-tool-image--effect",
    lineClass: "marketplace-comparison-line",
    handleClass: "marketplace-comparison-handle",
  },
)

const videoPlaybackFallback = ref(false)
const isResettingAnimation = ref(false)
const hasVideoMetadata = ref(false)
const videoDuration = ref("3s")

const beforeUrl = computed(() => normalizeMediaUrl(props.beforeSrc))
const afterUrl = computed(() => normalizeMediaUrl(props.afterSrc))
const beforeIsVideo = computed(() => isVideoPreviewUrl(props.beforeSrc))
const afterIsVideo = computed(() => isVideoPreviewUrl(props.afterSrc))
const isVideoDriven = computed(() => afterIsVideo.value && !videoPlaybackFallback.value)
const motionStyle = computed(() =>
  isVideoDriven.value
    ? {
        animationDuration: videoDuration.value,
        animationIterationCount: "1",
        animationPlayState: hasVideoMetadata.value ? undefined : "paused",
      }
    : undefined,
)

watch(
  () => [props.beforeSrc, props.afterSrc],
  () => {
    videoPlaybackFallback.value = false
    isResettingAnimation.value = false
    hasVideoMetadata.value = false
    videoDuration.value = "3s"
  },
)

function updateVideoDuration(event: Event) {
  const video = event.currentTarget as HTMLVideoElement
  if (Number.isFinite(video.duration) && video.duration > 0.2) {
    videoDuration.value = `${video.duration}s`
  }
  hasVideoMetadata.value = true
}

function markStaticFallback() {
  videoPlaybackFallback.value = true
  isResettingAnimation.value = false
  hasVideoMetadata.value = false
}

function tryPlay(video: HTMLVideoElement) {
  const playResult = video.play()
  if (playResult && typeof playResult.catch === "function") {
    void playResult.catch(markStaticFallback)
  }
}

function onAfterVideoReady(event: Event) {
  updateVideoDuration(event)
  tryPlay(event.currentTarget as HTMLVideoElement)
}

async function onAfterVideoEnded(event: Event) {
  if (videoPlaybackFallback.value) return
  const video = event.currentTarget as HTMLVideoElement
  isResettingAnimation.value = true
  await nextTick()
  requestAnimationFrame(() => {
    video.currentTime = 0
    isResettingAnimation.value = false
    tryPlay(video)
  })
}
</script>

<template>
  <div
    class="tool-comparison-cover"
    :class="{
      'tool-comparison-cover--video': isVideoDriven,
      'tool-comparison-cover--resetting': isResettingAnimation,
    }"
  >
    <video
      v-if="beforeIsVideo"
      :src="beforeUrl"
      :class="imageClass"
      muted
      loop
      autoplay
      playsinline
      preload="metadata"
    />
    <img
      v-else
      :src="beforeUrl"
      :alt="alt ? `${alt} 原图` : '原图'"
      :class="imageClass"
      draggable="false"
      decoding="async"
    />

    <video
      v-if="afterIsVideo"
      :src="afterUrl"
      :class="[imageClass, effectClass]"
      :style="motionStyle"
      muted
      autoplay
      playsinline
      preload="metadata"
      @loadedmetadata="onAfterVideoReady"
      @ended="onAfterVideoEnded"
      @error="markStaticFallback"
    />
    <img
      v-else
      :src="afterUrl"
      :alt="alt ? `${alt} 效果图` : '效果图'"
      :class="[imageClass, effectClass]"
      draggable="false"
      decoding="async"
    />

    <div :class="lineClass" :style="motionStyle" />
    <div :class="handleClass" :style="motionStyle">
      <svg width="28" height="28" viewBox="0 0 28 28" fill="none" aria-hidden="true">
        <circle cx="14" cy="14" r="13" fill="white" stroke="rgba(0,0,0,0.3)" stroke-width="1.5" />
        <path d="M10 10L6 14L10 18" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M18 10L22 14L18 18" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </div>
  </div>
</template>

<style>
.tool-comparison-cover {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}

.tool-comparison-cover--resetting .marketplace-tool-image--effect,
.tool-comparison-cover--resetting .marketplace-comparison-line,
.tool-comparison-cover--resetting .marketplace-comparison-handle,
.tool-comparison-cover--resetting .tool-cover-comparison-effect,
.tool-comparison-cover--resetting .tool-cover-comparison-line,
.tool-comparison-cover--resetting .tool-cover-comparison-handle {
  animation: none !important;
}

@keyframes home-comparison-wipe {
  0% { clip-path: inset(0 100% 0 0); }
  82%,
  100% { clip-path: inset(0 0 0 0); }
}

@keyframes home-comparison-pos {
  0% { left: 0%; }
  82%,
  100% { left: 100%; }
}

.tool-comparison-cover .tool-cover-comparison-media {
  position: absolute;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.tool-comparison-cover .tool-cover-comparison-effect {
  z-index: 1;
  animation: home-comparison-wipe 3s ease-in-out infinite;
}

.tool-comparison-cover .tool-cover-comparison-line {
  position: absolute;
  inset-block: 0;
  z-index: 5;
  width: 3px;
  background: white;
  box-shadow: 0 0 8px rgb(0 0 0 / 0.5), 0 0 20px rgb(255 255 255 / 0.3);
  pointer-events: none;
  animation: home-comparison-pos 3s ease-in-out infinite;
}

.tool-comparison-cover .tool-cover-comparison-handle {
  position: absolute;
  top: 50%;
  z-index: 5;
  display: flex;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.45);
  color: var(--foreground, #333);
  box-shadow: 0 16px 32px rgb(0 0 0 / 0.3);
  transform: translate(-50%, -50%);
  pointer-events: none;
  backdrop-filter: blur(12px);
  animation: home-comparison-pos 3s ease-in-out infinite;
}

.tool-card:hover .tool-comparison-cover .tool-cover-comparison-effect,
.tool-card:hover .tool-comparison-cover .tool-cover-comparison-line,
.tool-card:hover .tool-comparison-cover .tool-cover-comparison-handle {
  animation-play-state: paused;
}
</style>
