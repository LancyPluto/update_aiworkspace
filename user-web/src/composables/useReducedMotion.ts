import { onMounted, onUnmounted, ref } from "vue"

export function useReducedMotion() {
  const reducedMotion = ref(false)
  let mq: MediaQueryList | null = null

  function sync() {
    reducedMotion.value =
      typeof window !== "undefined" &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches
  }

  onMounted(() => {
    if (typeof window === "undefined") return
    mq = window.matchMedia("(prefers-reduced-motion: reduce)")
    sync()
    mq.addEventListener("change", sync)
  })

  onUnmounted(() => {
    mq?.removeEventListener("change", sync)
  })

  return { reducedMotion }
}
