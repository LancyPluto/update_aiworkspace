<script setup lang="ts">
import { computed } from "vue"
import { AlertCircle } from "lucide-vue-next"
import { clampAspectRatio } from "@/utils/taskAspectRatio"
import {
  buildTaskImageOutputPlan,
  inferParallelSlotPercent,
  inferSerialActiveSlot,
  type ImageOutputLayout,
} from "@/utils/taskImageOutput"
import type { TaskDetail } from "@/api/types"

const props = withDefaults(
  defineProps<{
    aspectRatio?: number
    caption: string
    percentLabel?: string
    percent?: number
    failed?: boolean
    outputCount?: number
    layout?: ImageOutputLayout
    task?: Pick<TaskDetail, "params" | "outputModality" | "toolType"> | null
  }>(),
  {
    aspectRatio: 1,
    percentLabel: "",
    percent: 0,
    failed: false,
    outputCount: 1,
    layout: "parallel",
    task: null,
  },
)

const displayRatio = computed(() => clampAspectRatio(props.aspectRatio))

const outputPlan = computed(() => {
  if (props.task) return buildTaskImageOutputPlan(props.task)
  return {
    count: Math.max(1, Math.min(4, props.outputCount || 1)),
    layout: props.layout,
    showMultiPreview: props.layout === "parallel" && (props.outputCount || 1) > 1,
  }
})

const parallelSlots = computed(() => {
  if (!outputPlan.value.showMultiPreview) return []
  return Array.from({ length: outputPlan.value.count }, (_, index) => index)
})

const serialSlot = computed(() => inferSerialActiveSlot(props.percent, outputPlan.value.count))

const serialCaption = computed(() => {
  if (outputPlan.value.layout !== "serial" || outputPlan.value.count <= 1) return props.caption
  return `${props.caption}（${serialSlot.value + 1}/${outputPlan.value.count}）`
})

const estimatedProgress = computed(() => props.percentLabel.includes("预计"))

function parallelSlotPercent(slotIndex: number): number {
  return inferParallelSlotPercent(props.percent, slotIndex, outputPlan.value.count)
}

function parallelSlotPercentLabel(slotIndex: number): string {
  const value = parallelSlotPercent(slotIndex)
  return `${estimatedProgress.value ? "预计 " : ""}${value}%`
}
</script>

<template>
  <div v-if="outputPlan.showMultiPreview" class="generation-preview-group">
    <div
      v-for="slot in parallelSlots"
      :key="`slot-${slot}`"
      class="generation-loading-preview generation-loading-preview--slot"
      :class="{ 'generation-loading-preview--failed': failed }"
      :style="{ aspectRatio: displayRatio }"
    >
      <div class="generation-loading-preview__glow" aria-hidden="true" />
      <div class="generation-loading-preview__content">
        <div v-if="failed" class="generation-loading-preview__failed" aria-hidden="true">
          <AlertCircle class="h-8 w-8" />
        </div>
        <div v-else class="generation-loading-preview__bars" aria-hidden="true">
          <span />
          <span />
          <span />
        </div>
        <p class="generation-loading-preview__caption">作品 {{ slot + 1 }} / {{ outputPlan.count }}</p>
        <p v-if="!failed" class="generation-loading-preview__percent">{{ parallelSlotPercentLabel(slot) }}</p>
      </div>
      <div v-if="!failed" class="generation-loading-preview__progress" aria-hidden="true">
        <span class="generation-loading-preview__progress-bar" :style="{ width: `${parallelSlotPercent(slot)}%` }" />
      </div>
    </div>
  </div>

  <div
    v-else
    class="generation-loading-preview"
    :class="{ 'generation-loading-preview--failed': failed }"
    :style="{ aspectRatio: displayRatio }"
  >
    <div class="generation-loading-preview__glow" aria-hidden="true" />
    <div class="generation-loading-preview__content">
      <div v-if="failed" class="generation-loading-preview__failed" aria-hidden="true">
        <AlertCircle class="h-8 w-8" />
      </div>
      <div v-else class="generation-loading-preview__bars" aria-hidden="true">
        <span />
        <span />
        <span />
      </div>
      <p class="generation-loading-preview__caption">{{ serialCaption }}</p>
      <p v-if="percentLabel && !failed" class="generation-loading-preview__percent">{{ percentLabel }}</p>
    </div>
    <div v-if="!failed" class="generation-loading-preview__progress" aria-hidden="true">
      <span class="generation-loading-preview__progress-bar" :style="{ width: `${Math.max(0, Math.min(100, percent))}%` }" />
    </div>
  </div>
</template>

<style scoped>
.generation-preview-group {
  display: grid;
  width: min(100%, 960px);
  grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr));
  gap: 12px;
}

.generation-preview-group .generation-loading-preview {
  width: 100%;
  max-height: 260px;
}

.generation-loading-preview--slot {
  min-height: 180px;
}

.generation-loading-preview__progress {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 3px;
  background: rgb(255 255 255 / 0.06);
}

.generation-loading-preview__progress-bar {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--brand-progress-gradient, linear-gradient(90deg, #22d3ee, #2563eb, #34d399));
  transition: width 320ms ease;
}

.generation-loading-preview {
  --generation-loader-primary-rgb: var(--brand-primary-rgb, 34 211 238);
  --generation-loader-secondary-rgb: var(--brand-secondary-rgb, 37 99 235);
  --generation-loader-tertiary-rgb: var(--brand-tertiary-rgb, 52 211 153);
  position: relative;
  width: min(100%, 640px);
  max-height: min(72vh, 520px);
  border: 1px solid rgb(255 255 255 / 0.075);
  border-radius: 16px;
  background:
    radial-gradient(circle at 46% 42%, rgb(var(--generation-loader-primary-rgb) / 0.18), transparent 32%),
    radial-gradient(circle at 62% 54%, rgb(var(--generation-loader-secondary-rgb) / 0.12), transparent 34%),
    linear-gradient(145deg, #23232a, #141419);
  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 0.045),
    inset 0 0 56px rgb(var(--generation-loader-primary-rgb) / 0.055);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.generation-loading-preview::before {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, rgb(255 255 255 / 0.025), transparent 34%),
    linear-gradient(0deg, rgb(0 0 0 / 0.24), transparent 54%);
  content: "";
}

.generation-loading-preview--failed {
  border-color: rgb(248 113 113 / 0.22);
  background:
    radial-gradient(circle at 48% 42%, rgb(248 113 113 / 0.14), transparent 34%),
    linear-gradient(145deg, #272126, #151417);
}

.generation-loading-preview__glow {
  position: absolute;
  width: 42%;
  max-width: 320px;
  aspect-ratio: 1;
  border-radius: 999px;
  background: rgb(var(--generation-loader-primary-rgb) / 0.10);
  filter: blur(42px);
  transform: translate3d(0, -4%, 0);
}

.generation-loading-preview__content {
  position: relative;
  z-index: 1;
  display: flex;
  width: min(72%, 340px);
  flex-direction: column;
  align-items: center;
  gap: 0;
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
  width: 6px;
  border-radius: 999px;
  background: linear-gradient(
    180deg,
    rgb(var(--generation-loader-primary-rgb) / 0.98),
    rgb(var(--generation-loader-tertiary-rgb) / 0.82)
  );
  box-shadow: 0 0 16px rgb(var(--generation-loader-primary-rgb) / 0.34);
  animation: generation-bar-pulse 850ms ease-in-out infinite;
}

.generation-loading-preview__bars span:nth-child(1) {
  height: 18px;
  animation-delay: 0s;
}

.generation-loading-preview__bars span:nth-child(2) {
  height: 34px;
  background: linear-gradient(
    180deg,
    rgb(var(--generation-loader-secondary-rgb) / 0.96),
    rgb(var(--generation-loader-primary-rgb) / 0.86)
  );
  box-shadow: 0 0 18px rgb(var(--generation-loader-secondary-rgb) / 0.32);
  animation-delay: 0.14s;
}

.generation-loading-preview__bars span:nth-child(3) {
  height: 24px;
  background: linear-gradient(
    180deg,
    rgb(var(--generation-loader-tertiary-rgb) / 0.96),
    rgb(var(--generation-loader-secondary-rgb) / 0.82)
  );
  box-shadow: 0 0 18px rgb(var(--generation-loader-tertiary-rgb) / 0.28);
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
  margin: 10px 0 0;
  max-width: 280px;
  color: rgb(255 255 255 / 0.86);
  font-size: 15px;
  font-weight: 700;
  line-height: 1.35;
}

.generation-loading-preview__percent {
  margin: 8px 0 0;
  color: rgb(255 255 255 / 0.38);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
</style>
