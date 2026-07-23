<script setup lang="ts">
import { computed, ref, watch } from "vue"
import { getApiOrigin } from "@/api/client"

const DEFAULT_AVATAR_URL = "/assets/default-user-avatar.svg"

const props = withDefaults(
  defineProps<{
    src?: string | null
    name?: string | null
    size?: "sm" | "md" | "lg" | "xl"
  }>(),
  {
    src: null,
    name: "",
    size: "md",
  },
)

const failed = ref(false)

watch(
  () => props.src,
  () => {
    failed.value = false
  },
)

const sizeClass = computed(() => {
  if (props.size === "sm") return "h-8 w-8 text-xs"
  if (props.size === "lg") return "h-16 w-16 text-xl"
  if (props.size === "xl") return "h-24 w-24 text-3xl"
  return "h-10 w-10 text-sm"
})

const avatarSrc = computed(() => {
  if (!props.src || failed.value) return DEFAULT_AVATAR_URL
  if (/^https?:\/\//i.test(props.src)) return props.src
  if (props.src.startsWith("/assets/")) return props.src
  const origin = getApiOrigin()
  if (origin && props.src.startsWith("/")) return `${origin}${props.src}`
  return props.src
})

</script>

<template>
  <span
    :class="[
      'inline-flex shrink-0 items-center justify-center overflow-hidden rounded-full border border-white/10 bg-gradient-to-br from-primary/25 via-white/8 to-cyan-300/10 font-semibold text-white shadow-[inset_0_1px_0_rgb(255_255_255_/_0.12)]',
      sizeClass,
    ]"
  >
    <img
      v-if="avatarSrc"
      :src="avatarSrc"
      :alt="name || '用户头像'"
      class="h-full w-full object-cover"
      @error="failed = true"
    />
  </span>
</template>
