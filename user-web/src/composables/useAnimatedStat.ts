import { onUnmounted, ref } from "vue"
import { useReducedMotion } from "@/composables/useReducedMotion"

export function useAnimatedStat(sourceValue: () => number | null | undefined) {
  const { reducedMotion } = useReducedMotion()
  const displayValue = ref<number | string>("--")
  let frame = 0

  function cancelAnim() {
    if (frame) cancelAnimationFrame(frame)
    frame = 0
  }

  function setStatic() {
    const v = sourceValue()
    displayValue.value = v ?? "--"
  }

  function animateFromHover() {
    if (reducedMotion.value) {
      setStatic()
      return
    }
    const target = sourceValue()
    if (target == null || Number.isNaN(target)) {
      displayValue.value = "--"
      return
    }
    const start = Math.max(0, target - Math.min(5, Math.ceil(target * 0.08)))
    const duration = 420
    const t0 = performance.now()
    cancelAnim()

    function step(now: number) {
      const p = Math.min(1, (now - t0) / duration)
      const eased = 1 - (1 - p) ** 3
      displayValue.value = Math.round(start + (target - start) * eased)
      if (p < 1) frame = requestAnimationFrame(step)
      else frame = 0
    }
    frame = requestAnimationFrame(step)
  }

  onUnmounted(cancelAnim)

  return {
    displayValue,
    setStatic,
    animateFromHover,
  }
}
