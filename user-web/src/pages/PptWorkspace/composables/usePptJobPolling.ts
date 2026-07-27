import { onBeforeUnmount, ref } from "vue"
import { pptApi, type PptJob } from "@/api/pptApi"

const ACTIVE_STATUSES = new Set([
  "CREATED", "CREDIT_RESERVED", "SUBMITTED", "QUEUED", "RUNNING", "RECONCILING",
])

export function usePptJobPolling(onUpdate: (job: PptJob) => void, onSettled?: () => void | Promise<void>) {
  const polling = ref(false)
  let timer: ReturnType<typeof setTimeout> | null = null
  let generation = 0

  function stop() {
    generation += 1
    polling.value = false
    if (timer) clearTimeout(timer)
    timer = null
  }

  async function poll(jobId: number) {
    stop()
    const currentGeneration = generation
    polling.value = true

    const tick = async () => {
      if (currentGeneration !== generation) return
      try {
        const job = await pptApi.job(jobId)
        if (currentGeneration !== generation) return
        onUpdate(job)
        if (!ACTIVE_STATUSES.has(job.status)) {
          polling.value = false
          await onSettled?.()
          return
        }
      } catch {
        // Transient network failures should not strand a recoverable backend job.
      }
      if (currentGeneration === generation) timer = setTimeout(tick, 1800)
    }

    await tick()
  }

  onBeforeUnmount(stop)
  return { polling, poll, stop }
}
