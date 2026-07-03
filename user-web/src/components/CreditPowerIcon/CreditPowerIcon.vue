<script setup lang="ts">
import { computed } from "vue"

import {
  CREDIT_POWER_ICON_COLOR,
  CREDIT_POWER_ICON_PATH,
  CREDIT_POWER_ICON_VIEWBOX,
} from "./creditPowerIconSvg"

const props = withDefaults(
  defineProps<{
    class?: string
    size?: number | string
  }>(),
  {},
)

const style = computed(() => {
  if (props.size == null || props.size === "") return undefined
  const px = typeof props.size === "number" ? `${props.size}px` : props.size
  return { width: px, height: px }
})
</script>

<template>
  <svg
    xmlns="http://www.w3.org/2000/svg"
    :viewBox="CREDIT_POWER_ICON_VIEWBOX"
    fill="none"
    preserveAspectRatio="xMidYMid meet"
    class="credit-power-icon inline-block shrink-0"
    :class="props.class"
    :style="style"
    aria-hidden="true"
  >
    <path
      :fill="CREDIT_POWER_ICON_COLOR"
      fill-rule="evenodd"
      clip-rule="evenodd"
      :d="CREDIT_POWER_ICON_PATH"
    />
  </svg>
</template>

<style scoped>
.credit-power-icon {
  color: transparent;
}
</style>
