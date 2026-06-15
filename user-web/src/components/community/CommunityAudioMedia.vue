<script setup lang="ts">
import { Music } from "lucide-vue-next"

withDefaults(
  defineProps<{
    coverUrl?: string
    audioUrl?: string
    title?: string
    playing?: boolean
    progress?: number
    variant?: "card" | "detail"
  }>(),
  {
    coverUrl: "",
    audioUrl: "",
    title: "",
    playing: false,
    progress: 0,
    variant: "card",
  },
)

const emit = defineEmits<{
  togglePlay: []
  openDetail: []
  seek: [event: MouseEvent]
}>()
</script>

<template>
  <div class="community-audio-media" :class="variant">
    <div
      class="audio-cover-stage"
      :class="{ 'is-clickable': variant === 'card' }"
      @click="variant === 'card' && emit('openDetail')"
    >
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
        v-if="audioUrl && variant === 'card'"
        type="button"
        class="audio-play-button"
        :class="{ 'is-playing': playing }"
        :aria-label="playing ? '暂停' : '播放'"
        @click.stop="emit('togglePlay')"
      />

      <div v-if="playing && variant === 'card'" class="audio-card-progress" @click.stop>
        <button type="button" class="audio-card-track" aria-label="音频进度" @click="emit('seek', $event)">
          <span class="audio-card-track-fill" :style="{ width: `${progress}%` }" />
        </button>
      </div>
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

.community-audio-media.card .audio-cover-stage.is-clickable {
  cursor: pointer;
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
  transition:
    transform 0.2s ease,
    opacity 0.2s ease;
}

.community-audio-media.card .audio-play-button {
  left: 50%;
  top: 50%;
  opacity: 0;
  pointer-events: none;
  transform: translate(-50%, -50%) scale(0.92);
}

.community-audio-media.card:hover .audio-play-button,
.community-audio-media.card .audio-play-button.is-playing,
.community-audio-media.card .audio-play-button:focus-visible {
  opacity: 1;
  pointer-events: auto;
  transform: translate(-50%, -50%) scale(1);
}

.community-audio-media.card .audio-play-button::after {
  content: "";
  display: block;
  width: 0;
  height: 0;
  border-style: solid;
  border-width: 9px 0 9px 15px;
  border-color: transparent transparent transparent currentColor;
  margin-left: 4px;
}

.community-audio-media.card .audio-play-button.is-playing::after {
  width: 14px;
  height: 16px;
  border: none;
  margin-left: 0;
  background:
    linear-gradient(
      to right,
      currentColor 0,
      currentColor 4px,
      transparent 4px,
      transparent 10px,
      currentColor 10px,
      currentColor 14px
    );
}

.community-audio-media.card:hover .audio-play-button:hover,
.community-audio-media.card .audio-play-button.is-playing:hover,
.community-audio-media.card .audio-play-button:focus-visible:hover {
  transform: translate(-50%, -50%) scale(1.05);
}

.community-audio-media.card:hover .audio-play-button:active,
.community-audio-media.card .audio-play-button.is-playing:active {
  transform: translate(-50%, -50%) scale(0.98);
}

.audio-card-progress {
  position: absolute;
  right: 10px;
  bottom: 42px;
  left: 10px;
  z-index: 4;
}

.audio-card-track {
  position: relative;
  display: block;
  width: 100%;
  height: 4px;
  overflow: hidden;
  border: 0;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.16);
  padding: 0;
  cursor: pointer;
  transition: height 0.16s ease, background-color 0.16s ease;
}

.audio-card-track:hover {
  height: 6px;
  background: rgb(255 255 255 / 0.22);
}

.audio-card-track-fill {
  position: absolute;
  inset: 0 auto 0 0;
  border-radius: inherit;
  background: linear-gradient(90deg, #a855f7, #ff3f79);
  box-shadow: 0 0 12px rgb(168 85 247 / 0.36);
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
</style>
