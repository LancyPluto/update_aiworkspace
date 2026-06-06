<script setup lang="ts">
import { Music, Pause, Play } from "lucide-vue-next"

withDefaults(
  defineProps<{
    coverUrl?: string
    audioUrl?: string
    title?: string
    playing?: boolean
    variant?: "card" | "detail"
  }>(),
  {
    coverUrl: "",
    audioUrl: "",
    title: "",
    playing: false,
    variant: "card",
  },
)

const emit = defineEmits<{
  togglePlay: []
}>()
</script>

<template>
  <div class="community-audio-media" :class="variant">
    <div class="audio-cover-stage">
      <img
        v-if="coverUrl"
        :src="coverUrl"
        :alt="title || '音乐封面'"
        class="audio-cover-image"
        loading="lazy"
      />
      <div v-else class="audio-cover-fallback">
        <Music class="fallback-icon" />
      </div>

      <div class="audio-cover-gradient" aria-hidden="true" />

      <button
        v-if="audioUrl"
        type="button"
        class="audio-play-button"
        :aria-label="playing ? '暂停' : '播放'"
        @click.stop="emit('togglePlay')"
      >
        <Pause v-if="playing" class="play-icon" />
        <Play v-else class="play-icon" />
      </button>

      <span v-if="playing" class="audio-viz" aria-hidden="true">
        <i v-for="bar in 4" :key="bar" class="audio-viz-bar" />
      </span>
    </div>

    <p v-if="variant === 'card' && title" class="audio-card-title">{{ title }}</p>
  </div>
</template>

<style scoped>
.community-audio-media {
  width: 100%;
}

.audio-cover-stage {
  position: relative;
  overflow: hidden;
  background: rgb(255 255 255 / 0.04);
}

.community-audio-media.card .audio-cover-stage {
  aspect-ratio: 1;
}

.community-audio-media.detail .audio-cover-stage {
  aspect-ratio: 1;
  max-width: 420px;
  margin: 0 auto;
  border-radius: 24px;
  box-shadow: 0 30px 100px rgb(0 0 0 / 0.68);
}

.audio-cover-image {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.audio-cover-fallback {
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(circle at 30% 20%, rgb(176 92 255 / 0.35), transparent 38%),
    linear-gradient(145deg, rgb(34 34 42), rgb(8 8 10));
}

.fallback-icon {
  width: 48px;
  height: 48px;
  color: var(--primary, #b05cff);
}

.community-audio-media.card .fallback-icon {
  width: 40px;
  height: 40px;
}

.audio-cover-gradient {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgb(0 0 0 / 0.08) 0%, rgb(0 0 0 / 0.42) 72%, rgb(0 0 0 / 0.72) 100%);
  pointer-events: none;
}

.audio-play-button {
  position: absolute;
  right: 12px;
  bottom: 12px;
  z-index: 2;
  display: inline-flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.94);
  color: #111;
  box-shadow: 0 14px 30px rgb(0 0 0 / 0.32);
  cursor: pointer;
  transition: transform 0.2s ease, opacity 0.2s ease;
}

.community-audio-media.detail .audio-play-button {
  right: auto;
  left: 50%;
  bottom: 28px;
  width: 64px;
  height: 64px;
  transform: translateX(-50%);
}

.community-audio-media.detail .audio-play-button:hover {
  transform: translateX(-50%) scale(1.05);
}

.audio-play-button:hover {
  transform: scale(1.05);
}

.play-icon {
  width: 18px;
  height: 18px;
}

.community-audio-media.detail .play-icon {
  width: 26px;
  height: 26px;
}

.audio-viz {
  position: absolute;
  left: 12px;
  bottom: 12px;
  z-index: 2;
  display: flex;
  height: 14px;
  align-items: flex-end;
  justify-content: center;
  gap: 2px;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.45);
  padding: 0 8px;
}

.audio-viz-bar {
  display: block;
  width: 2px;
  min-height: 3px;
  border-radius: 999px;
  background: #fff;
  animation: audio-viz 760ms ease-in-out infinite;
}

.audio-viz-bar:nth-child(2) {
  animation-delay: 120ms;
}

.audio-viz-bar:nth-child(3) {
  animation-delay: 240ms;
}

.audio-viz-bar:nth-child(4) {
  animation-delay: 360ms;
}

.audio-card-title {
  margin: 0;
  padding: 10px 12px 0;
  overflow: hidden;
  color: rgb(255 255 255 / 0.72);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@keyframes audio-viz {
  0%,
  100% {
    height: 3px;
    opacity: 0.45;
  }

  50% {
    height: 12px;
    opacity: 1;
  }
}
</style>
