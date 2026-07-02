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
      class="generation-preview-slot"
      :class="{ 'generation-preview-slot--failed': failed }"
      :style="{ aspectRatio: displayRatio }"
    >
      <div class="generation-preview-slot__content">
        <div v-if="failed" class="generation-preview-slot__failed" aria-hidden="true">
          <AlertCircle class="h-6 w-6" />
        </div>
        <div v-else class="generation-preview-slot__bars" aria-hidden="true">
          <span />
          <span />
          <span />
        </div>
        <p class="generation-preview-slot__index">{{ slot + 1 }}</p>
        <p v-if="!failed" class="generation-preview-slot__percent">{{ parallelSlotPercentLabel(slot) }}</p>
      </div>
      <div v-if="!failed" class="generation-preview-slot__progress" aria-hidden="true">
        <span class="generation-preview-slot__progress-bar" :style="{ width: `${parallelSlotPercent(slot)}%` }" />
      </div>
    </div>
  </div>

  <div
    v-else
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
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  width: min(100%, 720px);
}

.generation-preview-slot {
  position: relative;
  flex: 1 1 140px;
  min-width: 120px;
  max-width: 220px;
  border: 1px solid rgb(255 255 255 / 0.08);
  border-radius: 16px;
  background:
    radial-gradient(circle at 50% 0%, rgb(255 255 255 / 0.05), transparent 42%),
    linear-gradient(180deg, rgb(42 42 48), rgb(22 22 28));
  overflow: hidden;
}

.generation-preview-slot--failed {
  border-color: rgb(248 113 113 / 0.28);
  background:
    radial-gradient(circle at 50% 0%, rgb(248 113 113 / 0.12), transparent 42%),
    linear-gradient(180deg, rgb(48 32 32), rgb(24 18 18));
}

.generation-preview-slot__content {
  display: flex;
  height: 100%;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 16px 12px 18px;
  text-align: center;
}

.generation-preview-slot__bars {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 5px;
  height: 28px;
}

.generation-preview-slot__bars span {
  width: 7px;
  border-radius: 999px;
  background: linear-gradient(180deg, #ff7eb3, #d946ef);
  box-shadow: 0 0 14px rgb(217 70 239 / 0.35);
  animation: generation-bar-pulse 1.05s ease-in-out infinite;
}

.generation-preview-slot__bars span:nth-child(1) {
  height: 14px;
  animation-delay: 0s;
}

.generation-preview-slot__bars span:nth-child(2) {
  height: 24px;
  animation-delay: 0.14s;
}

.generation-preview-slot__bars span:nth-child(3) {
  height: 18px;
  animation-delay: 0.28s;
}

.generation-preview-slot__failed {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 999px;
  color: rgb(254 202 202);
  background: rgb(248 113 113 / 0.12);
}

.generation-preview-slot__index {
  margin: 0;
  color: rgb(255 255 255 / 0.34);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.generation-preview-slot__percent {
  margin: 0;
  color: rgb(255 255 255 / 0.46);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.generation-preview-slot__progress,
.generation-loading-preview__progress {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 3px;
  background: rgb(255 255 255 / 0.06);
}

.generation-preview-slot__progress-bar,
.generation-loading-preview__progress-bar {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #ff7eb3, #d946ef);
  transition: width 320ms ease;
}

.generation-loading-preview {
  position: relative;
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
