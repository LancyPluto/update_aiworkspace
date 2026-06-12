<script setup lang="ts">
import { computed } from "vue"
import { getApiOrigin } from "@/api/client"

/**
 * 多图预览：微信朋友圈式九宫格图集。
 * 1 张铺满；2/4 张两列；3 张及 5 张以上三列方格；最多显示 9 格，
 * 超出部分在最后一格用 +N 提示。角标展示真实图片总数。
 */
const props = withDefaults(
  defineProps<{
    images: string[]
    alt?: string
    fit?: "cover" | "contain"
    showCount?: boolean
    maxCells?: number
  }>(),
  {
    alt: "图片预览",
    fit: "cover",
    showCount: true,
    maxCells: 9,
  },
)

const normalizedImages = computed(() => {
  const seen = new Set<string>()
  return props.images
    .map((url) => normalizeMediaUrl(url))
    .filter((url) => {
      if (!url || seen.has(url)) return false
      seen.add(url)
      return true
    })
})

const total = computed(() => normalizedImages.value.length)
const isGrid = computed(() => total.value > 1)
const visibleImages = computed(() => normalizedImages.value.slice(0, props.maxCells))
const hiddenCount = computed(() => Math.max(0, total.value - visibleImages.value.length))

/** 微信样式列数：2/4 张两列，其余多图三列 */
const columns = computed(() => {
  if (total.value <= 1) return 1
  if (total.value === 2 || total.value === 4) return 2
  return 3
})

function normalizeMediaUrl(value?: string | null) {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const apiOrigin = getApiOrigin()
  return apiOrigin ? `${apiOrigin}${path}` : path
}
</script>

<template>
  <div
    class="image-grid-preview"
    :class="{ grid: isGrid, [`fit-${fit}`]: true }"
    :style="isGrid ? { '--grid-columns': columns } : undefined"
  >
    <template v-if="isGrid">
      <div
        v-for="(url, index) in visibleImages"
        :key="`${url}-${index}`"
        class="image-grid-cell"
      >
        <img :src="url" :alt="`${alt} ${index + 1}`" loading="lazy" decoding="async" />
        <span
          v-if="hiddenCount > 0 && index === visibleImages.length - 1"
          class="image-grid-more"
        >
          +{{ hiddenCount }}
        </span>
      </div>
      <span v-if="showCount" class="image-grid-count">共 {{ total }} 张</span>
    </template>
    <template v-else>
      <img
        v-if="normalizedImages[0]"
        :src="normalizedImages[0]"
        :alt="alt"
        class="image-grid-single"
        loading="lazy"
        decoding="async"
      />
    </template>
  </div>
</template>

<style scoped>
.image-grid-preview {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  background: rgb(16 16 20);
}

.image-grid-preview.grid {
  display: grid;
  grid-template-columns: repeat(var(--grid-columns, 3), minmax(0, 1fr));
  gap: 3px;
  height: auto;
  padding: 0;
}

.image-grid-cell {
  position: relative;
  overflow: hidden;
  aspect-ratio: 1 / 1;
  background: rgb(0 0 0 / 0.35);
}

.image-grid-cell img,
.image-grid-single {
  display: block;
  width: 100%;
  height: 100%;
}

.fit-cover .image-grid-cell img,
.fit-cover .image-grid-single {
  object-fit: cover;
}

.fit-contain .image-grid-cell img,
.fit-contain .image-grid-single {
  object-fit: contain;
  background: rgb(5 5 7);
}

.image-grid-single {
  width: 100%;
  height: 100%;
}

.image-grid-more {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgb(0 0 0 / 0.55);
  color: #fff;
  font-size: 20px;
  font-weight: 700;
}

.image-grid-count {
  position: absolute;
  right: 8px;
  top: 8px;
  z-index: 3;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.68);
  color: #fff;
  padding: 4px 8px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  backdrop-filter: blur(10px);
}
</style>
