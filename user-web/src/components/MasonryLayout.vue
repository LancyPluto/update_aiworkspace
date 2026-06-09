<script setup lang="ts" generic="T">
import { toRef } from "vue"
import { useMasonryLayout } from "@/composables/useMasonryLayout"

const props = withDefaults(
  defineProps<{
    items: T[]
    itemKey: keyof T | ((item: T) => string | number)
    minColumnWidth?: number
    minColumnWidthMobile?: number
    gap?: number
    mobileBreakpoint?: number
    estimateHeight?: (item: T, index: number) => number
    ariaBusy?: boolean
    ariaLabel?: string
  }>(),
  {
    minColumnWidth: 280,
    minColumnWidthMobile: 160,
    gap: 20,
    mobileBreakpoint: 767,
    ariaBusy: undefined,
    ariaLabel: undefined,
  },
)

function resolveKey(item: T) {
  if (typeof props.itemKey === "function") return props.itemKey(item)
  return item[props.itemKey] as string | number
}

const { containerRef, columnItems, gap, observeItem } = useMasonryLayout({
  items: toRef(props, "items"),
  getKey: resolveKey,
  estimateHeight: props.estimateHeight,
  options: {
    minColumnWidth: props.minColumnWidth,
    minColumnWidthMobile: props.minColumnWidthMobile,
    gap: props.gap,
    mobileBreakpoint: props.mobileBreakpoint,
  },
})
</script>

<template>
  <div
    ref="containerRef"
    class="masonry-layout"
    :style="{ gap: `${gap}px` }"
    :aria-busy="ariaBusy"
    :aria-label="ariaLabel"
  >
    <div
      v-for="(column, columnIndex) in columnItems"
      :key="columnIndex"
      class="masonry-column"
      :style="{ gap: `${gap}px` }"
    >
      <div
        v-for="item in column"
        :key="resolveKey(item)"
        :ref="(element) => observeItem(element as Element | null, resolveKey(item))"
        class="masonry-item"
      >
        <slot :item="item" />
      </div>
    </div>
  </div>
</template>
