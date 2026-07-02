<script setup lang="ts">
import { computed } from "vue"

const iconUrl = `${import.meta.env.BASE_URL}assets/credit-power-icon.png`

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
    :src="iconUrl"
    alt=""
    aria-hidden="true"
    class="inline-block shrink-0 object-contain"
    :class="props.class"
    :style="style"
  />
</template>
