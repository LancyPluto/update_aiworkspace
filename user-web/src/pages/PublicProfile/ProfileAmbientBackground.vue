<script setup lang="ts">
import { computed } from "vue"
import { useReducedMotion } from "@/composables/useReducedMotion"

const props = defineProps<{
  offsetX?: number
  offsetY?: number
  scrollOffset?: number
}>()

const { reducedMotion } = useReducedMotion()

const layerStyle = computed(() => {
  if (reducedMotion.value) return {}
  const y = (props.scrollOffset ?? 0) * 0.015
  return {
    transform: `translate3d(${props.offsetX ?? 0}px, ${(props.offsetY ?? 0) + y}px, 0)`,
  }
})
</script>

<template>
  <div class="profile-ambient" :class="{ 'profile-ambient--reduced': reducedMotion }" :style="layerStyle" aria-hidden="true">
    <div class="profile-ambient__mesh profile-ambient__mesh--1" />
    <div class="profile-ambient__mesh profile-ambient__mesh--2" />
    <div class="profile-ambient__mesh profile-ambient__mesh--3" />
    <div class="profile-ambient__noise" />
  </div>
</template>

<style scoped>
.profile-ambient {
  position: fixed;
  inset: -12% -8%;
  pointer-events: none;
  z-index: 0;
  overflow: hidden;
  will-change: transform;
}

.profile-ambient__mesh {
  position: absolute;
  inset: 0;
  opacity: 0.1;
}

.profile-ambient__mesh--1 {
  background: radial-gradient(circle at 18% 8%, var(--profile-mesh-1), transparent 34%);
}

.profile-ambient__mesh--2 {
  background: radial-gradient(circle at 92% 12%, var(--profile-mesh-2), transparent 32%);
}

.profile-ambient__mesh--3 {
  background: linear-gradient(180deg, var(--profile-mesh-3), transparent 22%);
}

.profile-ambient__noise {
  position: absolute;
  inset: 0;
  opacity: 0.028;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
  background-size: 128px 128px;
}

.profile-ambient:not(.profile-ambient--reduced) .profile-ambient__mesh {
  animation: profile-mesh-drift 10s ease-in-out infinite;
}

.profile-ambient__mesh--2 {
  animation-delay: -3s;
}

.profile-ambient__mesh--3 {
  animation-delay: -6s;
}

.profile-ambient--reduced .profile-ambient__mesh {
  animation: none;
}

@keyframes profile-mesh-drift {
  0%,
  100% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(1%, -0.8%) scale(1.015);
  }
}
</style>
