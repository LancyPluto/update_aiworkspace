<script setup lang="ts">
import { computed } from "vue"
import { AlertCircle } from "lucide-vue-next"
import { clampAspectRatio } from "@/utils/taskAspectRatio"

const props = withDefaults(
  defineProps<{
    aspectRatio?: number
    caption: string
    percentLabel?: string
    failed?: boolean
  }>(),
  {
    aspectRatio: 1,
    percentLabel: "",
    failed: false,
  },
)

const displayRatio = computed(() => clampAspectRatio(props.aspectRatio))
</script>

<template>
  <div
    class="generation-loading-preview"
    :class="{ 'generation-loading-preview--failed': failed }"
    :style="{ aspectRatio: displayRatio }"
  >
    <div class="generation-loading-preview__content">
      <div v-if="failed" class="generation-loading-preview__failed" aria-hidden="true">
        <AlertCircle class="h-8 w-8" />
      </div>
      <div v-else class="generation-loading-preview__bars" aria-hidden="true">
        <span />
        <span />
        <span />
      </div>
      <p class="generation-loading-preview__caption">{{ caption }}</p>
      <p v-if="percentLabel && !failed" class="generation-loading-preview__percent">{{ percentLabel }}</p>
    </div>
  </div>
</template>

<style scoped>
.generation-loading-preview {
  width: min(100%, 640px);
  max-height: min(72vh, 520px);
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 18px;
  background:
    radial-gradient(circle at 50% 0%, rgb(255 255 255 / 0.05), transparent 42%),
    linear-gradient(180deg, rgb(42 42 48), rgb(22 22 28));
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.generation-loading-preview--failed {
  border-color: rgb(248 113 113 / 0.28);
  background:
    radial-gradient(circle at 50% 0%, rgb(248 113 113 / 0.12), transparent 42%),
    linear-gradient(180deg, rgb(48 32 32), rgb(24 18 18));
}

.generation-loading-preview__content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 24px;
  text-align: center;
}

.generation-loading-preview__bars {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 7px;
  height: 40px;
}

.generation-loading-preview__bars span {
  width: 9px;
  border-radius: 999px;
  background: linear-gradient(180deg, #ff7eb3, #d946ef);
  box-shadow: 0 0 18px rgb(217 70 239 / 0.35);
  animation: generation-bar-pulse 1.05s ease-in-out infinite;
}

.generation-loading-preview__bars span:nth-child(1) {
  height: 18px;
  animation-delay: 0s;
}

.generation-loading-preview__bars span:nth-child(2) {
  height: 34px;
  animation-delay: 0.14s;
}

.generation-loading-preview__bars span:nth-child(3) {
  height: 24px;
  animation-delay: 0.28s;
}

@keyframes generation-bar-pulse {
  0%,
  100% {
    transform: scaleY(0.72);
    opacity: 0.72;
  }
  50% {
    transform: scaleY(1);
    opacity: 1;
  }
}

.generation-loading-preview__failed {
  display: grid;
  place-items: center;
  width: 62px;
  height: 62px;
  border-radius: 999px;
  color: rgb(254 202 202);
  background: rgb(248 113 113 / 0.12);
}

.generation-loading-preview__caption {
  margin: 0;
  max-width: 280px;
  color: rgb(255 255 255 / 0.58);
  font-size: 14px;
  line-height: 1.6;
}

.generation-loading-preview__percent {
  margin: -6px 0 0;
  color: rgb(255 255 255 / 0.34);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
</style>
