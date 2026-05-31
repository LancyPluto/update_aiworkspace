import { onMounted, onUnmounted, ref } from "vue"
import { useReducedMotion } from "@/composables/useReducedMotion"

export function useProfileParallax(containerRef?: { value: HTMLElement | null }) {
  const { reducedMotion } = useReducedMotion()
  const offsetX = ref(0)
  const offsetY = ref(0)
  let raf = 0
  let targetX = 0
  let targetY = 0

  function onMouseMove(event: MouseEvent) {
    if (reducedMotion.value) return
    const el = containerRef?.value ?? document.documentElement
    const rect = el.getBoundingClientRect()
    const nx = ((event.clientX - rect.left) / rect.width - 0.5) * 2
    const ny = ((event.clientY - rect.top) / rect.height - 0.5) * 2
    targetX = nx * 8
    targetY = ny * 6
  }

  function tick() {
    offsetX.value += (targetX - offsetX.value) * 0.08
    offsetY.value += (targetY - offsetY.value) * 0.08
    raf = requestAnimationFrame(tick)
  }

  onMounted(() => {
    window.addEventListener("mousemove", onMouseMove, { passive: true })
    raf = requestAnimationFrame(tick)
  })

  onUnmounted(() => {
    window.removeEventListener("mousemove", onMouseMove)
    cancelAnimationFrame(raf)
  })

  return { offsetX, offsetY, reducedMotion }
}
