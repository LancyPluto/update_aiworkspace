<script setup lang="ts">
import { computed } from "vue"

import { CREDIT_POWER_ICON_URL } from "@/constants/publicAssets"

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
  <img
    :src="CREDIT_POWER_ICON_URL"
    alt=""
    aria-hidden="true"
    class="inline-block shrink-0 object-contain"
    :class="props.class"
    :style="style"
  />
</template>
