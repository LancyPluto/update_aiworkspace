<script setup lang="ts">
import { computed } from "vue"

import outlineMask from "@/assets/credit-power-icon-outline-mask.png?url"

const props = withDefaults(
  defineProps<{
    class?: string
    size?: number | string
  }>(),
  {
    size: 132,
  },
)

const style = computed(() => {
  const px = typeof props.size === "number" ? `${props.size}px` : props.size
  return {
    width: px,
    height: px,
    WebkitMaskImage: `url(${outlineMask})`,
    maskImage: `url(${outlineMask})`,
  }
})
</script>

<template>
  <div
    class="credit-power-icon-watermark pointer-events-none shrink-0"
    :class="props.class"
    :style="style"
    aria-hidden="true"
  />
</template>

<style scoped>
.credit-power-icon-watermark {
  background: linear-gradient(145deg, #38bdf8 0%, #2563eb 52%, #1d4ed8 100%);
  -webkit-mask-size: contain;
  mask-size: contain;
  -webkit-mask-repeat: no-repeat;
  mask-repeat: no-repeat;
  -webkit-mask-position: center;
  mask-position: center;
}
</style>
