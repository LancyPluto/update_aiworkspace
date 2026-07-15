<script setup lang="ts">
import { computed, ref, watch } from "vue"
import { ImageOff } from "lucide-vue-next"
import {
  buildImageCandidateChain,
  mediaDeliveryOptimizationEnabled,
  type ImageDeliveryPreset,
} from "@/utils/mediaDelivery"
import { recordMediaDeliveryEvent } from "@/utils/mediaTelemetry"

const props = withDefaults(defineProps<{
  src?: string | null
  alt?: string
  preset?: Exclude<ImageDeliveryPreset, "lqip">
  loading?: "lazy" | "eager"
  fetchpriority?: "high" | "low" | "auto"
}>(), {
  alt: "",
  preset: "card",
  loading: "lazy",
  fetchpriority: "auto",
})

const emit = defineEmits<{ loaded: []; failed: [] }>()
const candidateIndex = ref(0)
const failed = ref(false)
const candidates = computed(() => buildImageCandidateChain(
  props.src,
  props.preset,
  mediaDeliveryOptimizationEnabled(),
))
const currentUrl = computed(() => candidates.value[candidateIndex.value] || "")

watch(() => props.src, () => {
  candidateIndex.value = 0
  failed.value = false
})

function onLoad() {
  recordMediaDeliveryEvent("image", candidateIndex.value === 0 && candidates.value.length > 1 ? "derivative" : "original", "loaded")
  emit("loaded")
}

function onError() {
  if (candidateIndex.value + 1 < candidates.value.length) {
    candidateIndex.value += 1
    recordMediaDeliveryEvent("image", "derivative", "fallback")
    return
  }
  failed.value = true
  recordMediaDeliveryEvent("image", "original", "failed")
  emit("failed")
}
</script>

<template>
  <img
    v-if="currentUrl && !failed"
    :src="currentUrl"
    :alt="alt"
    :loading="loading"
    :fetchpriority="fetchpriority"
    decoding="async"
    @load="onLoad"
    @error="onError"
  />
  <span v-else class="flex h-full w-full items-center justify-center bg-secondary/40 text-muted-foreground" role="img" :aria-label="alt || '图片加载失败'">
    <ImageOff class="h-6 w-6" />
  </span>
</template>
