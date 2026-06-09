import { type Ref, onMounted, onUnmounted, ref, watch } from "vue"

export type MasonryLayoutOptions = {
  minColumnWidth?: number
  minColumnWidthMobile?: number
  gap?: number
  mobileBreakpoint?: number
}

export function computeMasonryColumnCount(
  containerWidth: number,
  options: MasonryLayoutOptions = {},
): number {
  if (containerWidth <= 0) return 1

  const {
    minColumnWidth = 280,
    minColumnWidthMobile = 160,
    gap = 20,
    mobileBreakpoint = 767,
  } = options

  const minCol = containerWidth <= mobileBreakpoint ? minColumnWidthMobile : minColumnWidth
  return Math.max(1, Math.floor((containerWidth + gap) / (minCol + gap)))
}

export function distributeMasonryItems<T>(
  items: T[],
  columnCount: number,
  getHeight: (item: T, index: number) => number,
  gap: number,
): T[][] {
  const count = Math.max(1, columnCount)
  const columns = Array.from({ length: count }, () => ({
    items: [] as T[],
    height: 0,
  }))

  items.forEach((item, index) => {
    const itemHeight = Math.max(getHeight(item, index), 1)
    let target = 0
    for (let columnIndex = 1; columnIndex < count; columnIndex += 1) {
      if (columns[columnIndex].height < columns[target].height) {
        target = columnIndex
      }
    }
    columns[target].items.push(item)
    columns[target].height += itemHeight + gap
  })

  return columns.map((column) => column.items)
}

type UseMasonryLayoutParams<T> = {
  items: Ref<T[]>
  getKey: (item: T) => string | number
  estimateHeight?: (item: T, index: number) => number
  options?: MasonryLayoutOptions
}

export function useMasonryLayout<T>({
  items,
  getKey,
  estimateHeight,
  options = {},
}: UseMasonryLayoutParams<T>) {
  const containerRef = ref<HTMLElement | null>(null)
  const columnCount = ref(1)
  const columnItems = ref<T[][]>([[]])
  const measuredHeights = ref(new Map<string | number, number>())

  const gap = options.gap ?? 20
  let containerObserver: ResizeObserver | null = null
  const itemObservers = new Map<string | number, ResizeObserver>()
  let relayoutFrame = 0

  function getItemHeight(item: T, index: number) {
    const key = getKey(item)
    return measuredHeights.value.get(key) ?? estimateHeight?.(item, index) ?? 280
  }

  function relayout() {
    columnItems.value = distributeMasonryItems(items.value, columnCount.value, getItemHeight, gap)
  }

  function scheduleRelayout() {
    if (relayoutFrame) cancelAnimationFrame(relayoutFrame)
    relayoutFrame = requestAnimationFrame(() => {
      relayoutFrame = 0
      relayout()
    })
  }

  function updateColumnCount(width: number) {
    const nextCount = computeMasonryColumnCount(width, { ...options, gap })
    if (nextCount !== columnCount.value) {
      columnCount.value = nextCount
      scheduleRelayout()
    }
  }

  function unobserveItem(key: string | number) {
    const observer = itemObservers.get(key)
    if (!observer) return
    observer.disconnect()
    itemObservers.delete(key)
  }

  function observeItem(element: Element | null, key: string | number) {
    unobserveItem(key)
    if (!element || !(element instanceof HTMLElement)) return

    const observer = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (!entry) return
      const nextHeight = Math.ceil(entry.contentRect.height)
      const prevHeight = measuredHeights.value.get(key)
      if (prevHeight === nextHeight) return
      measuredHeights.value.set(key, nextHeight)
      scheduleRelayout()
    })

    observer.observe(element)
    itemObservers.set(key, observer)
  }

  function observeContainer() {
    containerObserver?.disconnect()
    if (!containerRef.value) return

    containerObserver = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (!entry) return
      updateColumnCount(entry.contentRect.width)
    })
    containerObserver.observe(containerRef.value)
    updateColumnCount(containerRef.value.clientWidth)
  }

  onMounted(() => {
    observeContainer()
    relayout()
  })

  onUnmounted(() => {
    if (relayoutFrame) cancelAnimationFrame(relayoutFrame)
    containerObserver?.disconnect()
    itemObservers.forEach((observer) => observer.disconnect())
    itemObservers.clear()
  })

  watch(
    items,
    (nextItems) => {
      const activeKeys = new Set(nextItems.map((item) => getKey(item)))
      for (const key of measuredHeights.value.keys()) {
        if (!activeKeys.has(key)) {
          measuredHeights.value.delete(key)
          unobserveItem(key)
        }
      }
      scheduleRelayout()
    },
    { deep: true },
  )

  return {
    containerRef,
    columnCount,
    columnItems,
    gap,
    observeItem,
    scheduleRelayout,
  }
}
