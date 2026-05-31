<script setup lang="ts">
import { computed } from "vue"
import { useReducedMotion } from "@/composables/useReducedMotion"

const props = defineProps<{
  ambientState?: "idle" | "thinking" | "awaiting_confirmation"
  scrollOffset?: number
}>()

const { reducedMotion } = useReducedMotion()

const parallaxStyle = computed(() => {
  if (reducedMotion.value) return {}
  const y = (props.scrollOffset ?? 0) * 0.02
  return { transform: `translate3d(0, ${y}px, 0)` }
})

const stateClass = computed(() => {
  if (props.ambientState === "thinking") return "ambient-bg--thinking"
  if (props.ambientState === "awaiting_confirmation") return "ambient-bg--awaiting"
  return "ambient-bg--idle"
})
</script>

<template>
  <div
    class="ambient-bg"
    :class="[stateClass, reducedMotion && 'ambient-bg--reduced']"
    :style="parallaxStyle"
    aria-hidden="true"
  >
    <div class="ambient-bg__mesh ambient-bg__mesh--1" />
    <div class="ambient-bg__mesh ambient-bg__mesh--2" />
    <div class="ambient-bg__mesh ambient-bg__mesh--3" />
    <div class="ambient-bg__noise" />
  </div>
</template>

<style scoped>
.ambient-bg {
  position: absolute;
  inset: -10% -5%;
  pointer-events: none;
  z-index: 0;
  overflow: hidden;
  will-change: transform;
}

.ambient-bg__mesh {
  position: absolute;
  inset: 0;
  opacity: 0.08;
}

.ambient-bg__mesh--1 {
  background: radial-gradient(circle at 50% 0%, var(--agent-bg-mesh-1), transparent 34%);
}

.ambient-bg__mesh--2 {
  background: radial-gradient(circle at 84% 18%, var(--agent-bg-mesh-2), transparent 30%);
}

.ambient-bg__mesh--3 {
  background: linear-gradient(180deg, var(--agent-bg-mesh-3), transparent 22%);
}

.ambient-bg__noise {
  position: absolute;
  inset: 0;
  opacity: 0.03;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
  background-size: 128px 128px;
}

.ambient-bg--idle .ambient-bg__mesh {
  animation: mesh-drift 8s ease-in-out infinite;
}

.ambient-bg--thinking .ambient-bg__mesh {
  opacity: 0.11;
  animation: mesh-drift 3s ease-in-out infinite;
}

.ambient-bg--awaiting .ambient-bg__mesh {
  animation: mesh-drift 4s ease-in-out infinite, mesh-pulse 2s ease-in-out infinite;
}

.ambient-bg--reduced .ambient-bg__mesh {
  animation: none !important;
}

.ambient-bg__mesh--2 {
  animation-delay: -2s;
}

.ambient-bg__mesh--3 {
  animation-delay: -4s;
}

@keyframes mesh-drift {
  0%,
  100% {
    transform: translate(0, 0) scale(1);
  }
  33% {
    transform: translate(1.5%, -1%) scale(1.02);
  }
  66% {
    transform: translate(-1%, 1.5%) scale(0.98);
  }
}

@keyframes mesh-pulse {
  0%,
  100% {
    opacity: 0.08;
  }
  50% {
    opacity: 0.14;
  }
}
</style>
