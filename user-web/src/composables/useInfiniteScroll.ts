import { nextTick, onUnmounted, watch, type Ref } from "vue"

export function useInfiniteScroll(options: {
  sentinelRef: Ref<HTMLElement | null>
  scrollRootRef: Ref<HTMLElement | null>
  enabled: () => boolean
  hasMore: () => boolean
  loading: () => boolean
  loadingMore: () => boolean
  onLoadMore: () => void | Promise<void>
}) {
  let observer: IntersectionObserver | null = null

  function disconnect() {
    observer?.disconnect()
    observer = null
  }

  function setup() {
    disconnect()
    if (!options.enabled() || !options.sentinelRef.value) return
    observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return
        if (!options.enabled() || !options.hasMore() || options.loading() || options.loadingMore()) return
        void options.onLoadMore()
      },
      {
        root: options.scrollRootRef.value,
        rootMargin: "0px 0px 200px 0px",
      },
    )
    observer.observe(options.sentinelRef.value)
  }

  watch(
    [options.sentinelRef, options.scrollRootRef],
    async () => {
      await nextTick()
      setup()
    },
    { flush: "post" },
  )

  watch(
    () => [options.enabled(), options.hasMore(), options.loading(), options.loadingMore()],
    async () => {
      await nextTick()
      setup()
    },
    { flush: "post" },
  )

  onUnmounted(disconnect)

  return { setup, disconnect }
}
