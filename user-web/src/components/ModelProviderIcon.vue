<script setup lang="ts">
import { computed, ref, watch } from "vue"

const props = withDefaults(
  defineProps<{
    iconUrl?: string | null
    mark: string
    label?: string
    tintClass?: string
  }>(),
  {
    iconUrl: null,
    label: "",
    tintClass: "",
  },
)

const imageFailed = ref(false)

watch(
  () => props.iconUrl,
  () => {
    imageFailed.value = false
  },
)

const showImage = computed(() => Boolean(props.iconUrl) && !imageFailed.value)
</script>

<template>
  <span class="model-provider-icon" :class="tintClass">
    <img
      v-if="showImage"
      :src="iconUrl!"
      :alt="label || mark"
      class="model-provider-icon-img"
      loading="lazy"
      decoding="async"
      @error="imageFailed = true"
    />
    <span v-else class="model-provider-icon-fallback" :title="label || mark">{{ mark }}</span>
  </span>
</template>
