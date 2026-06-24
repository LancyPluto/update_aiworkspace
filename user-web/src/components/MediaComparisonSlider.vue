<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from "vue"

const props = withDefaults(
  defineProps<{
    beforeSrc: string
    afterSrc: string
    beforeLabel?: string
    afterLabel?: string
    isVideo?: boolean
    autoPlay?: boolean
    aspectRatio?: string
  }>(),
  {
    beforeLabel: "Before",
    afterLabel: "After",
    isVideo: false,
    autoPlay: true,
    aspectRatio: "4/3",
  },
)

const containerRef = ref<HTMLElement | null>(null)
const position = ref(50)
const isDragging = ref(false)
const autoAnimating = ref(false)
let animationId: number | null = null
let autoTimeout: ReturnType<typeof setTimeout> | null = null

const containerStyle = computed(() => ({
  aspectRatio: props.aspectRatio,
}))

function updatePosition(clientX: number) {
  const rect = containerRef.value?.getBoundingClientRect()
  if (!rect || rect.width <= 0) return
  const pct = Math.min(95, Math.max(5, ((clientX - rect.left) / rect.width) * 100))
  position.value = pct
}

function onPointerDown(e: PointerEvent) {
  isDragging.value = true
  stopAutoAnimation()
  updatePosition(e.clientX)
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
}

function onPointerMove(e: PointerEvent) {
  if (!isDragging.value) return
  updatePosition(e.clientX)
}

function onPointerUp() {
  isDragging.value = false
}

function startAutoAnimation() {
  if (!props.autoPlay) return
  autoAnimating.value = true
  let direction = 1
  const speed = 0.15
  const animate = () => {
    if (!autoAnimating.value) return
    position.value += speed * direction
    if (position.value >= 85) direction = -1
    if (position.value <= 15) direction = 1
    animationId = requestAnimationFrame(animate)
  }
  position.value = 15
  animationId = requestAnimationFrame(animate)
}

function stopAutoAnimation() {
  autoAnimating.value = false
  if (animationId !== null) {
    cancelAnimationFrame(animationId)
    animationId = null
  }
  if (autoTimeout !== null) {
    clearTimeout(autoTimeout)
    autoTimeout = null
  }
}

onMounted(() => {
  if (props.autoPlay) {
    autoTimeout = setTimeout(startAutoAnimation, 800)
  }
})

onUnmounted(() => {
  stopAutoAnimation()
})
</script>

<template>
  <div
    ref="containerRef"
    class="comparison-slider"
    :style="containerStyle"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointerleave="onPointerUp"
  >
    <div class="comparison-layer comparison-before">
      <video
        v-if="isVideo"
        :src="beforeSrc"
        class="comparison-media"
        muted
        loop
        autoplay
        playsinline
        preload="metadata"
      />
      <img v-else :src="beforeSrc" alt="before" class="comparison-media" draggable="false" />
    </div>

    <div class="comparison-layer comparison-after" :style="{ clipPath: `inset(0 0 0 ${position}%)` }">
      <video
        v-if="isVideo"
        :src="afterSrc"
        class="comparison-media"
        muted
        loop
        autoplay
        playsinline
        preload="metadata"
      />
      <img v-else :src="afterSrc" alt="after" class="comparison-media" draggable="false" />
    </div>

    <div class="comparison-divider" :style="{ left: `${position}%` }">
      <div class="comparison-line" />
      <div class="comparison-handle">
        <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
          <circle cx="10" cy="10" r="9" fill="white" stroke="rgba(0,0,0,0.3)" stroke-width="1.5" />
          <path d="M7 7L4 10L7 13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
          <path d="M13 7L16 10L13 13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </div>
    </div>

    <span class="comparison-label comparison-label--before">{{ beforeLabel }}</span>
    <span class="comparison-label comparison-label--after">{{ afterLabel }}</span>
  </div>
</template>

<style scoped>
.comparison-slider {
  position: relative;
  overflow: hidden;
  border-radius: 0.5rem;
  cursor: col-resize;
  user-select: none;
  touch-action: none;
  background: var(--muted, hsl(240 4.8% 95.9%));
}

.comparison-layer {
  position: absolute;
  inset: 0;
}

.comparison-media {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.comparison-after {
  z-index: 1;
}

.comparison-divider {
  position: absolute;
  top: 0;
  bottom: 0;
  z-index: 2;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

.comparison-line {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 2px;
  background: white;
  box-shadow: 0 0 6px rgba(0, 0, 0, 0.35);
}

.comparison-handle {
  position: relative;
  z-index: 3;
  color: var(--foreground, #333);
  filter: drop-shadow(0 1px 3px rgba(0, 0, 0, 0.3));
}

.comparison-label {
  position: absolute;
  bottom: 8px;
  z-index: 3;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.02em;
  pointer-events: none;
  background: rgba(0, 0, 0, 0.55);
  color: white;
  backdrop-filter: blur(4px);
}

.comparison-label--before {
  left: 8px;
}

.comparison-label--after {
  right: 8px;
}
</style>
